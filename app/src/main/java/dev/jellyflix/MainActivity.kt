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
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            val auth by container.session.state.collectAsState()
            CompositionLocalProvider(LocalIsTv provides isTv) {
                JellyflixTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (val a = auth) {
                            AuthState.Loading -> LoadingView()
                            AuthState.SignedOut -> LoginScreen()
                            // Keyed on the account so all screen state resets when switching users.
                            is AuthState.SignedIn -> androidx.compose.runtime.key(a.session.account.key) { JellyflixNav(settings, container.plugins) }
                        }
                    }
                }
            }
        }
    }
}
