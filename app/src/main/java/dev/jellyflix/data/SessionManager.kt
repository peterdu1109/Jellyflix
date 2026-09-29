package dev.jellyflix.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult

class Session(val account: Account, val api: ApiClient) {
    val userId get() = java.util.UUID.fromString(account.userId)
}

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: Session) : AuthState
}

class SessionManager(
    private val jellyfin: Jellyfin,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    val accounts: StateFlow<List<Account>> = settings.accounts.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            runCatching { settings.migrateLegacyAccounts() }
            val key = settings.currentAccountKey.first()
            val account = settings.accounts.first().firstOrNull { it.key == key }
            _state.value = account?.let { AuthState.SignedIn(open(it)) } ?: AuthState.SignedOut
        }
    }

    val current: Session? get() = (state.value as? AuthState.SignedIn)?.session

    private fun open(account: Account): Session =
        Session(account, jellyfin.createApi(baseUrl = account.serverUrl, accessToken = account.token))

    /** Finds the best working URL for what the user typed (adds scheme/port, prefers https, follows redirects). */
    suspend fun probeServer(address: String): Result<RecommendedServerInfo> = runCatching {
        val found = jellyfin.discovery.getRecommendedServers(address.trim(), RecommendedServerInfoScore.OK)
        // Enum order is GREAT, GOOD, OK, BAD: the smallest ordinal is the best server.
        found.minByOrNull { it.score.ordinal } ?: error("Server not reachable")
    }

    suspend fun signIn(server: RecommendedServerInfo, user: String, password: String): Result<Unit> = runCatching {
        val api = jellyfin.createApi(baseUrl = server.address)
        finish(server, api.userApi.authenticateUserByName(user, password).content)
    }

    /** Quick Connect: returns code to show; [awaitApproval] suspends until the user approves on another device. */
    suspend fun startQuickConnect(server: RecommendedServerInfo): Result<Pair<String, String>> = runCatching {
        val api = jellyfin.createApi(baseUrl = server.address)
        val r = api.quickConnectApi.initiateQuickConnect().content
        r.code to r.secret
    }

    suspend fun pollQuickConnect(server: RecommendedServerInfo, secret: String): Boolean {
        val api = jellyfin.createApi(baseUrl = server.address)
        return runCatching { api.quickConnectApi.getQuickConnectState(secret).content.authenticated }.getOrDefault(false)
    }

    suspend fun completeQuickConnect(server: RecommendedServerInfo, secret: String): Result<Unit> = runCatching {
        val api = jellyfin.createApi(baseUrl = server.address)
        finish(server, api.userApi.authenticateWithQuickConnect(secret).content)
    }

    private suspend fun finish(server: RecommendedServerInfo, result: AuthenticationResult) {
        val token = result.accessToken ?: error("No access token")
        val user = result.user ?: error("No user")
        val name = server.systemInfo.getOrNull()?.serverName ?: server.address
        val account = Account(server.address, name, user.id.toString(), user.name ?: "", token)
        settings.saveAccount(account)
        _state.value = AuthState.SignedIn(open(account))
    }

    suspend fun switchTo(account: Account) {
        settings.saveAccount(account)
        _state.value = AuthState.SignedIn(open(account))
    }

    suspend fun signOut() {
        val s = current ?: return
        runCatching { s.api.userApi.let { } }
        settings.removeAccount(s.account.key)
        val next = settings.accounts.first().firstOrNull()
        _state.value = next?.let { settings.saveAccount(it); AuthState.SignedIn(open(it)) } ?: AuthState.SignedOut
    }
}
