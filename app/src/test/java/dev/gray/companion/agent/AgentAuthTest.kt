package dev.gray.companion.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

class AgentAuthTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Test fun callbackRequiresExactLoopbackAndState() {
        val good = "http://127.0.0.1:1455/auth/callback?code=abc&state=original&client_id=oaiapp_test"
        assertEquals("abc", AgentOAuth.callback(good, 1455, "original")?.get("code"))
        assertNull(AgentOAuth.callback(good, 1455, "other"))
        assertNull(AgentOAuth.callback(good.replace("127.0.0.1", "localhost"), 1455, "original"))
        assertNull(AgentOAuth.callback(good.replace("/auth/callback", "/callback"), 1455, "original"))
        assertNull(AgentOAuth.callback("$good&state=original", 1455, "original"))
    }

    @Test fun identityRequiresValidSignatureAudienceNonceAndExpiry() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val jwks = """{"keys":[{"kty":"RSA","kid":"test","alg":"RS256","use":"sig","n":"${b64(public.modulus.toByteArray().unsigned())}","e":"${b64(public.publicExponent.toByteArray().unsigned())}"}]}"""
        val now = Instant.now().epochSecond
        fun jwt(exp: Long, audience: String = "oaiapp_test", nonce: String = "once"): String {
            val header = b64("""{"alg":"RS256","kid":"test"}""".toByteArray())
            val payload = b64("""{"iss":"https://auth.openai.com","aud":"$audience","sub":"subject-1","email":"a@example.com","iat":$now,"exp":$exp,"nonce":"$nonce"}""".toByteArray())
            val input = "$header.$payload"
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(pair.private)
            signer.update(input.toByteArray(Charsets.US_ASCII))
            return "$input.${b64(signer.sign())}"
        }
        val token = jwt(now + 3_600)
        assertEquals("subject-1", AgentOAuth.verifyIdToken(token, jwks, "oaiapp_test", "once", now).subject)
        assertThrows(IllegalArgumentException::class.java) {
            AgentOAuth.verifyIdToken(token, jwks, "oaiapp_other", "once", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentOAuth.verifyIdToken(token, jwks, "oaiapp_test", "wrong", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentOAuth.verifyIdToken(jwt(now - 120), jwks, "oaiapp_test", "once", now)
        }
        val pieces = token.split('.')
        val tampered = "${pieces[0]}.${b64("""{"iss":"https://auth.openai.com","aud":"oaiapp_test","sub":"attacker","iat":$now,"exp":${now + 3600},"nonce":"once"}""".toByteArray())}.${pieces[2]}"
        assertThrows(IllegalArgumentException::class.java) {
            AgentOAuth.verifyIdToken(tampered, jwks, "oaiapp_test", "once", now)
        }
    }

    private fun b64(bytes: ByteArray) = b64.encodeToString(bytes)
    private fun ByteArray.unsigned(): ByteArray = if (size > 1 && first() == 0.toByte()) copyOfRange(1, size) else this
}
