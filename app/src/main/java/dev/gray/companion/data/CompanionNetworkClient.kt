package dev.gray.companion.data

import android.content.Context
import io.ktor.http.HttpHeaders
import java.net.Authenticator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import okhttp3.OkHttpClient

/** Original transport preferences, without the original chat service's request hooks. */
fun createCompanionNetworkClient(context: Context, settingsStore: SettingsStore): OkHttpClient {
    val acceptLanguage = AcceptLanguageBuilder.fromAndroid(context).build()
    Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
    val initial = settingsStore.settingsFlow.value.networkSetting
    val appliedProxy = AtomicReference(Triple(initial.proxyUrl, initial.proxyUsername, initial.proxyPassword))
    lateinit var client: OkHttpClient
    client = OkHttpClient.Builder()
        .proxySelector(SettingsProxySelector(settingsStore))
        .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val network = settingsStore.settingsFlow.value.networkSetting
            val currentProxy = Triple(network.proxyUrl, network.proxyUsername, network.proxyPassword)
            if (appliedProxy.getAndSet(currentProxy) != currentProxy) client.connectionPool.evictAll()

            val original = chain.request()
            val request = original.newBuilder().addHeader(HttpHeaders.AcceptLanguage, acceptLanguage)
            if (original.header(HttpHeaders.UserAgent) == null) {
                request.addHeader(
                    HttpHeaders.UserAgent,
                    network.userAgent.trim().ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" },
                )
            }
            chain.proceed(request.build())
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val contentType = request.header(HttpHeaders.ContentType)
            if (contentType != null && contentType.contains(';') &&
                contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) {
                chain.proceed(request.newBuilder()
                    .header(HttpHeaders.ContentType, contentType.substringBefore(';').trim())
                    .build())
            } else chain.proceed(request)
        }
        .build()
    return client
}
