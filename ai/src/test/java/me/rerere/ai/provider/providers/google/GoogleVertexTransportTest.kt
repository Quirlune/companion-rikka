package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class GoogleVertexTransportTest {
    private fun account(location: String = "us-central1") = ProviderSetting.Google(
        vertexAI = true, useServiceAccount = true, projectId = "test-project", location = location,
        serviceAccountEmail = "test@test-project.iam.gserviceaccount.com", privateKey = pem)

    @Test fun separateSystemMessagesAreAllSentAsInstructionParts() {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody",
            List::class.java, TextGenerationParams::class.java).apply { isAccessible = true }
        val params = TextGenerationParams(model = Model(modelId = "gemini-2.5-flash"))
        fun body(messages: List<UIMessage>) = method.invoke(provider, messages, params) as JsonObject
        val one = body(listOf(UIMessage.system("first"), UIMessage.user("hello")))
        assertEquals(listOf("first"), one["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.map {
            it.jsonObject["text"]!!.jsonPrimitive.content
        })
        val two = body(listOf(UIMessage.system("first"), UIMessage.system("second"), UIMessage.user("hello")))
        assertEquals(listOf("first", "second"), two["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.map {
            it.jsonObject["text"]!!.jsonPrimitive.content
        })
        assertEquals(1, two["contents"]!!.jsonArray.size)
    }

    @Test fun regionalAndGlobalGenerationUseMatchingHosts() {
        val provider = GoogleProvider(OkHttpClient())
        for (method in listOf("generateContent", "streamGenerateContent")) {
            val path = "publishers/google/models/gemini-test:$method"
            assertEquals("https://us-central1-aiplatform.googleapis.com/v1/projects/test-project/locations/us-central1/$path",
                provider.buildUrl(account(), path).toString())
            assertEquals("https://aiplatform.googleapis.com/v1/projects/test-project/locations/global/$path",
                provider.buildUrl(account("global"), path).toString())
        }
    }

    @Test fun serviceAccountCatalogUsesPublisherSchemaAndPagination() = runBlocking {
        var tokenRequests = 0
        var pages = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "oauth2.googleapis.com") {
                tokenRequests++
                """{"access_token":"offline-token","expires_in":3600}"""
            } else {
                pages++
                assertEquals("us-central1-aiplatform.googleapis.com", request.url.host)
                assertEquals("/v1beta1/publishers/google/models", request.url.encodedPath)
                assertEquals("Bearer offline-token", request.header("Authorization"))
                assertNull(request.url.queryParameter("key"))
                if (pages == 1) {
                    assertNull(request.url.queryParameter("pageToken"))
                    """{"publisherModels":[{"name":"publishers/google/models/gemini-test","displayName":"Gemini Test"},{"name":"publishers/google/models/imagen-test"}],"nextPageToken":"next+page"}"""
                } else {
                    assertEquals("next+page", request.url.queryParameter("pageToken"))
                    """{"publisherModels":[{"name":"publishers/google/models/gemini-second"}]}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val models = GoogleProvider(client).listModels(account())
        assertEquals(listOf("gemini-test", "gemini-second"), models.map { it.modelId })
        assertEquals("gemini-second", models.last().displayName)
        assertEquals(1, tokenRequests)
        assertEquals(2, pages)
    }

    @Test fun failedCatalogIsAnErrorRatherThanAnEmptyList() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("secret-server-detail".toResponseBody()).build()
        }.build()
        try {
            GoogleProvider(client).listModels(ProviderSetting.Google(apiKey = "offline-key"))
            fail("Expected HTTP failure")
        } catch (error: HttpException) {
            assertEquals(403, error.statusCode)
            assertFalse(error.message.orEmpty().contains("secret-server-detail"))
        }
    }

    @Test fun developerApiKeepsItsOwnEndpointAndMethodFilter() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1beta/models", chain.request().url.encodedPath)
            assertEquals("offline-key", chain.request().header("x-goog-api-key"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]},{"name":"models/unsupported"}]}""".toResponseBody()).build()
        }.build()
        assertEquals(listOf("gemini-test"), GoogleProvider(client)
            .listModels(ProviderSetting.Google(apiKey = "offline-key")).map { it.modelId })
    }

    companion object {
        private val pem by lazy {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----"
        }
    }
}
