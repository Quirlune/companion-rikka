package dev.gray.companion.core

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Public v2 host contract. A declaration is never a device grant. */
object PluginCapabilities {
    const val NETWORK_HTTP = "network.http"
    const val RUNTIME_SLEEP = "runtime.sleep"
    const val CAPABILITIES_DESCRIBE = "capabilities.describe"
    const val MAX_HTTP_REQUESTS = 64
    const val MAX_TOTAL_RUNTIME_MS = 180_000L
    const val DEFAULT_HTTP_TIMEOUT_MS = 30_000L
    const val MAX_HTTP_TIMEOUT_MS = 60_000L
    const val DEFAULT_RESPONSE_BYTES = 262_144
    const val MAX_RESPONSE_BYTES = 1_048_576
    const val MAX_REQUEST_BYTES = 262_144
    const val MAX_SLEEP_MS = 10_000L

    val httpMethods: Set<String> = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    private val aliasPattern = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")

    fun validAlias(alias: String): Boolean = aliasPattern.matches(alias)

    /** Null means the string is already a canonical HTTPS origin. */
    fun originIssue(origin: String): String? {
        val uri = try { URI(origin) } catch (_: Exception) { return "须为规范 HTTPS origin" }
        val host = uri.host ?: return "须为规范 HTTPS origin"
        if (uri.scheme != "https" || uri.isOpaque || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
            host.endsWith('.') || uri.port !in -1..65535 || uri.port == 0) return "须为规范 HTTPS origin"
        val canonical = try { URI("https", null, host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString() }
            catch (_: Exception) { return "须为规范 HTTPS origin" }
        return if (origin == canonical) null else "须为规范 HTTPS origin"
    }

    fun validate(permissions: List<PluginPermission>, path: String = "plugin.permissions"): List<Issue> = buildList {
        permissions.forEachIndexed { index, rule ->
            val item = "$path[$index]"
            if (rule.capability != NETWORK_HTTP) {
                add(Issue("$item.capability", "未知或无需声明的插件能力：${rule.capability}"))
                return@forEachIndexed
            }
            if (rule.origins.isEmpty()) add(Issue("$item.origins", "网络能力须声明至少一个 HTTPS origin"))
            rule.origins.forEachIndexed { i, origin -> originIssue(origin)?.let { add(Issue("$item.origins[$i]", it)) } }
            if (rule.origins.distinct().size != rule.origins.size) add(Issue("$item.origins", "HTTPS origin 不能重复"))
            if (rule.methods.isEmpty()) add(Issue("$item.methods", "网络能力须声明至少一个 HTTP 方法"))
            rule.methods.forEachIndexed { i, method ->
                if (method !in httpMethods) add(Issue("$item.methods[$i]", "不支持的 HTTP 方法：$method"))
            }
            if (rule.methods.distinct().size != rule.methods.size) add(Issue("$item.methods", "HTTP 方法不能重复"))
            rule.credentials.forEachIndexed { i, alias ->
                if (!validAlias(alias)) add(Issue("$item.credentials[$i]", "凭据别名须以英文字母开头，且不超过 64 字符"))
            }
            if (rule.credentials.distinct().size != rule.credentials.size) add(Issue("$item.credentials", "凭据别名不能重复"))
        }
    }

    /** Includes every code and permission field; stable across process restarts. */
    fun fingerprint(code: PluginCode): String = MessageDigest.getInstance("SHA-256")
        .digest(LibraryFormat.json.encodeToString(PluginCode.serializer(), code).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Machine-readable authoring description; values here never contain a credential secret. */
    fun manifest(): JsonObject = LibraryFormat.json.parseToJsonElement("""
        {
          "apiVersion": 2,
          "entrypoint": "async function execute(context, host) { return JSON.stringify(await host.invoke('capabilities.describe', {})); }",
          "returnValue": "text string or {text:string}; host.invoke returns an envelope that must be converted to text before returning",
          "invocation": "await host.invoke(capability, arguments)",
          "permissions": {
            "network.http": {"capability":"network.http","origins":["https://api.example.com"],"methods":["GET","POST"],"credentials":["serviceKey"]},
            "note": "Declarations are not device grants; grants bind plugin ID and code fingerprint. runtime.sleep and capabilities.describe need no declaration."
          },
          "capabilities": [
            {
              "name":"network.http","permission":"network.http",
              "description":"One direct request to a declared and device-granted public HTTPS origin. Redirects and retries are disabled; URL alone grants nothing.",
              "argumentsSchema":{
                "type":"object","additionalProperties":false,"required":["url"],
                "properties":{
                  "url":{"type":"string","format":"uri","pattern":"^https://","description":"Absolute HTTPS URL; no userinfo or fragment. Runtime validates the canonical origin, destination and grant."},
                  "method":{"type":"string","enum":["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"],"default":"GET"},
                  "headers":{"type":"object","additionalProperties":{"type":"string"},"default":{}},
                  "body":{"type":["string","null"],"default":null},
                  "credential":{"type":["object","null"],"additionalProperties":false,"required":["alias"],"properties":{
                    "alias":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,63}$","description":"Declared credential alias; never a raw secret."},
                    "header":{"type":"string","default":"Authorization"},"prefix":{"type":"string","default":"Bearer "}
                  },"default":null},
                  "timeoutMs":{"type":"integer","minimum":1,"maximum":60000,"default":30000},
                  "maxResponseBytes":{"type":"integer","minimum":1,"maximum":1048576,"default":262144}
                },
                "allOf":[{"if":{"properties":{"method":{"enum":["GET","HEAD"]}}},"then":{"properties":{"body":{"type":"null"}}}}]
              },
              "resultSchema":{"oneOf":[{"type":"object","additionalProperties":false,"required":["ok","data"],"properties":{
                "ok":{"const":true},"data":{"type":"object","additionalProperties":false,"required":["status","headers","body"],"properties":{
                  "status":{"type":"integer"},"headers":{"type":"object","additionalProperties":{"type":"string"}},"body":{"type":"string"}
                }}
              }},{"${'$'}ref":"#/failureSchema"}]},
              "example":{"url":"https://api.example.com/items","method":"GET","credential":{"alias":"serviceKey"}}
            },
            {
              "name":"runtime.sleep","permission":null,"description":"Cancellable wait within the remaining 180-second run budget.",
              "argumentsSchema":{"type":"object","additionalProperties":false,"required":["milliseconds"],"properties":{"milliseconds":{"type":"integer","minimum":0,"maximum":10000}}},
              "resultSchema":{"oneOf":[{"type":"object","additionalProperties":false,"required":["ok","data"],"properties":{"ok":{"const":true},"data":{"type":"object","additionalProperties":false,"maxProperties":0}}},{"${'$'}ref":"#/failureSchema"}]},
              "example":{"milliseconds":1000}
            },
            {
              "name":"capabilities.describe","permission":null,"description":"Return this machine-readable manifest.",
              "argumentsSchema":{"type":"object","additionalProperties":false,"maxProperties":0},
              "resultSchema":{"oneOf":[{"type":"object","additionalProperties":false,"required":["ok","data"],"properties":{"ok":{"const":true},"data":{"type":"object"}}},{"${'$'}ref":"#/failureSchema"}]},
              "example":{}
            }
          ],
          "failure":{"ok":false,"error":{"code":"stable machine code","message":"human-readable, no secrets","retryable":false,"delivery":"not_sent|unknown|received"}},
          "failureSchema":{"type":"object","additionalProperties":false,"required":["ok","error"],"properties":{
            "ok":{"const":false},"error":{"type":"object","additionalProperties":false,"required":["code","message","retryable","delivery"],"properties":{
              "code":{"type":"string","enum":["UNKNOWN_CAPABILITY","RUN_TIMEOUT","INVALID_ARGUMENT","INVALID_URL","REQUEST_LIMIT","REQUEST_TOO_LARGE","PERMISSION_DENIED","INVALID_HEADER","CREDENTIAL_UNAVAILABLE","DESTINATION_DENIED","NETWORK_ERROR","RESPONSE_TOO_LARGE","HOST_ERROR"]},
              "message":{"type":"string"},"retryable":{"type":"boolean"},"delivery":{"type":"string","enum":["not_sent","unknown","received"]}
            }}
          }},
          "errorCodes":["UNKNOWN_CAPABILITY","RUN_TIMEOUT","INVALID_ARGUMENT","INVALID_URL","REQUEST_LIMIT","REQUEST_TOO_LARGE","PERMISSION_DENIED","INVALID_HEADER","CREDENTIAL_UNAVAILABLE","DESTINATION_DENIED","NETWORK_ERROR","RESPONSE_TOO_LARGE","HOST_ERROR"],
          "networkPolicy":{"schemes":["https"],"destinations":"public IP addresses only; DNS checked at connection","redirects":false,"retries":false,"appProxy":false,"requestAndResponseLogs":false,"rawCredentialAccess":false},
          "limits":{"requestsPerRun":64,"runMs":180000,"requestBytes":262144,"defaultResponseBytes":262144,"maxResponseBytes":1048576,"defaultHttpTimeoutMs":30000,"maxHttpTimeoutMs":60000},
          "example":"async function execute(context, host) { const response = await host.invoke('network.http', {url:'https://api.example.com/items', method:'GET', credential:{alias:'serviceKey'}}); if (!response.ok) throw new Error(response.error.code); return response.data.body; }"
        }
    """.trimIndent()).jsonObject
}
