package dev.jellyflix

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.jellyflix.data.AppSettings
import dev.jellyflix.data.AuthState
import dev.jellyflix.data.InterfaceMode
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.jellyflix.ui.JellyflixNav
import dev.jellyflix.ui.components.LoadingView
import dev.jellyflix.ui.screens.LoginScreen
import dev.jellyflix.ui.theme.JellyflixTheme
import dev.jellyflix.ui.theme.LocalIsTv

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as JellyflixApp).container
        val isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        setContent {
            val loaded by container.settings.settings.collectAsState(initial = null)
            val settings = loaded ?: AppSettings()
            val auth by container.session.state.collectAsState()
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalIsTv provides isTv) {
                JellyflixTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // Wait for the saved settings too, so the wrong interface never flashes on screen.
                        if (loaded == null || auth is AuthState.Loading) LoadingView()
                        else when (val a = auth) {
                            AuthState.SignedOut -> LoginScreen()
                            is AuthState.SignedIn -> androidx.compose.runtime.key(a.session.account.key) {
                                // Re-read the server theme for every account/session so admin changes show up on next launch.
                                androidx.compose.runtime.LaunchedEffect(a.session.account.key) {
                                    container.serverTheme.sync(container.settings)
                                    container.downloads.syncPositions()
                                }
                                if (settings.interfaceMode == InterfaceMode.Server) {
                                    dev.jellyflix.ui.web.ServerWebScreen(
                                        session = a.session, isTv = isTv,
                                        nativePlayer = settings.nativePlayer,
                                        accent = settings.serverTheme.accent,
                                        onToggleNativePlayer = { scope.launch { container.settings.update { it.nativePlayer(!settings.nativePlayer) } } },
                                        onUseNative = { scope.launch { container.settings.update { it.interfaceMode(InterfaceMode.Native) } } },
                                        onSignOut = { scope.launch { container.session.signOut() } },
                                        onQuit = { finish() },
                                    )
                                } else JellyflixNav(settings, container.plugins)
                            }
                            AuthState.Loading -> LoadingView()
                        }
                    }
                }
            }
        }
    }
}
