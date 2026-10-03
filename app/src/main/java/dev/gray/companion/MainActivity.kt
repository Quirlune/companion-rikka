package dev.gray.companion

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.gray.companion.ui.OriginalFrontendHost
import dev.gray.companion.data.PreviewImportCommand
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.WindowManager
import android.view.KeyEvent
import me.rerere.rikkahub.ui.context.VolumeKeyScrollOwner
import me.rerere.rikkahub.ui.context.consumeVolumeKey

class MainActivity : ComponentActivity(), VolumeKeyScrollOwner {
    override val volumeKeyListeners = mutableListOf<(Boolean) -> Boolean>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (consumeVolumeKey(event)) true else super.dispatchKeyEvent(event)
    private val controller: AppController by viewModels { AppController.factory(applicationContext) }
    private var fileError by mutableStateOf<String?>(null)

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val json = controller.export()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: error("无法写入文件")
                }
                fileError = "导出完成"
            } catch (e: Exception) { fileError = e.message ?: "导出失败" }
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                }
                val issues = controller.importBundle(json)
                fileError = if (issues.any { it.error }) issues.filter { it.error }.take(3).joinToString("；") { it.message }
                else "导入完成" + issues.take(3).joinToString(separator = "；", prefix = if (issues.isEmpty()) "" else "；") { it.message }
            } catch (e: Exception) { fileError = e.message ?: "导入失败" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context).crossfade(true).components {
                        add(OkHttpNetworkFetcherFactory(
                            callFactory = { org.koin.core.context.GlobalContext.get().get<okhttp3.OkHttpClient>() },
                            cacheStrategy = { CacheControlCacheStrategy() },
                        ))
                        if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                        add(SvgDecoder.Factory(scaleToDensity = true))
                    }.build()
                }
                OriginalFrontendHost(controller, onExport = { fileError = null; exportFile.launch("companion-resources.json") }, onImport = { fileError = null; importFile.launch(arrayOf("application/json", "text/plain")) }, externalError = fileError)
            }
        }
        handlePreviewImport(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreviewImport(intent)
    }

    private fun handlePreviewImport(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.action == PreviewImportCommand.ACTION) {
            // Consumed once: activity recreation must not import another copy.
            setIntent(Intent(intent).setAction(null))
            lifecycleScope.launch { PreviewImportCommand.run(applicationContext, controller) }
        }
    }
}
