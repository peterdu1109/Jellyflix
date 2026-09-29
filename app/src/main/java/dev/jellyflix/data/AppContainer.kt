package dev.jellyflix.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.jellyflix.plugin.BuiltInPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** Hand-rolled DI: a single graph created once by the Application. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val plugins = BuiltInPlugins.create()

    val jellyfin: Jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Jellyflix", version = "0.2.0")
        deviceInfo = DeviceInfo(
            id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "jellyflix-device",
            name = Build.MODEL ?: "Android",
        )
        this.context = context
    }

    val session = SessionManager(jellyfin, settings, appScope)
    val repository = MediaRepository(session)
    val downloads = dev.jellyflix.download.DownloadRepository(context.applicationContext, session, settings, appScope)
    val serverTheme = ServerThemeRepository(session)
}
