package dev.jellyflix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyflix.R
import dev.jellyflix.data.SessionManager
import dev.jellyflix.ui.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.discovery.RecommendedServerInfo

data class LoginState(
    val server: RecommendedServerInfo? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val quickConnectCode: String? = null,
)

class LoginViewModel(private val sessions: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state
    private var quickJob: Job? = null

    fun connect(address: String) = launchBusy {
        sessions.probeServer(address)
            .onSuccess { s -> _state.update { it.copy(server = s) } }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Server not reachable") } }
    }

    fun signIn(user: String, password: String) = launchBusy {
        val server = _state.value.server ?: return@launchBusy
        sessions.signIn(server, user.trim(), password)
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Sign in failed") } }
    }

    fun quickConnect() {
        val server = _state.value.server ?: return
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            val (code, secret) = sessions.startQuickConnect(server).getOrElse {
                _state.update { s -> s.copy(error = "Quick Connect unavailable on this server") }; return@launch
            }
            _state.update { it.copy(quickConnectCode = code, error = null) }
            while (true) {
                delay(2000)
                if (sessions.pollQuickConnect(server, secret)) {
                    sessions.completeQuickConnect(server, secret).onFailure { e -> _state.update { it.copy(error = e.message) } }
                    return@launch
                }
            }
        }
    }

    fun back() { quickJob?.cancel(); _state.value = LoginState() }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try { block() } finally { _state.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() { quickJob?.cancel() }
}

@Composable
fun LoginScreen() {
    val vm = appViewModel { LoginViewModel(it.session) }
    val state by vm.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            if (state.server == null) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) }, placeholder = { Text("jellyfin.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Button({ vm.connect(address) }, enabled = address.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connect))
                }
            } else {
                Text(state.server?.systemInfo?.getOrNull()?.serverName ?: state.server!!.address, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.username)) })
                OutlinedTextField(
                    password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button({ vm.signIn(user, password) }, enabled = user.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sign_in))
                }
                if (state.quickConnectCode == null) {
                    OutlinedButton({ vm.quickConnect() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.quick_connect)) }
                } else {
                    Text(stringResource(R.string.quick_connect_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(state.quickConnectCode!!, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton({ vm.back() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
            }
            if (state.busy) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
