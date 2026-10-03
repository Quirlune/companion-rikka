package me.rerere.rikkahub.companion

import android.app.Application
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import dev.gray.companion.data.createCompanionNetworkClient
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesVM
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** The copied frontend retains its services; this entry only initializes its active presentation needs. */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@CompanionApplication)
            modules(module {
                single { AppScope() }
                single<Json> { JsonInstant }
                single { SettingsStore(get(), get()) }
                single { PebbleEngine.Builder().autoEscaping(false).build() }
                single<OkHttpClient> { createCompanionNetworkClient(get(), get()) }
                single { AppDatabaseFactory.create(get()) }
                single { FilesRepository(get<AppDatabase>().managedFileDao()) }
                single { FilesManager(get(), get(), get()) }
                viewModelOf(::SettingVM)
                viewModelOf(::QuickMessagesVM)
            })
        }
    }
}
