package dev.jellyflix.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.jellyflix.R
import dev.jellyflix.data.Session
import dev.jellyflix.player.DeviceProfiles
import dev.jellyflix.player.ExternalStream
import dev.jellyflix.player.PlayerEvents
import dev.jellyflix.player.PlayerViewModel
import dev.jellyflix.ui.screens.ExternalPlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.jellyfin.sdk.model.api.DeviceProfile
import dev.jellyflix.ui.components.ErrorView
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadingView
import org.jellyfin.sdk.api.client.extensions.systemApi

/** WebView that hands the remote's Menu key to the app instead of the page. */
private class ServerWebView(context: android.content.Context) : WebView(context) {
    var onMenuKey: (() -> Unit)? = null
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { onMenuKey?.invoke(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/** Removes the web client's stored session and cache, so nothing of a signed-out account survives in the WebView. */
fun clearWebData() {
    runCatching { WebStorage.getInstance().deleteAllData() }
    runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
}

/**
 * The server's own web interface, signed in with the app's session. Because it *is* the server's UI, the theme,
 * custom CSS and plugins (Media Bar, Home Screen Sections, JavaScript Injector…) appear exactly as in a browser,
 * and on Android TV the web client's TV layout is used with the remote's D-pad.
 */
@Composable
fun ServerWebScreen(
    session: Session,
    isTv: Boolean,
    nativePlayer: Boolean,
    accent: Int?,
    onToggleNativePlayer: () -> Unit,
    onUseNative: () -> Unit,
    onSignOut: () -> Unit,
    onQuit: () -> Unit,
) {
    var reloadKey by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // The web client identifies the server by its id; the public endpoint needs no authentication.
    var server by remember { mutableStateOf<Load<Pair<String, String>>>(Load.Loading) }
    LaunchedEffect(session.account.key, reloadKey) {
        server = Load.Loading
        server = try {
            val info = session.api.systemApi.getPublicSystemInfo().content
            Load.Ready((info.id ?: error("Server did not report its id")) to (info.serverName ?: session.account.serverName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message)
        }
    }

    when (val s = server) {
        Load.Loading -> LoadingView()
        is Load.Failed -> ErrorView(s.message, { reloadKey++ })
        is Load.Ready -> WebHost(session, s.data.first, s.data.second, session.api.deviceInfo.id, isTv, nativePlayer, accent, reloadKey, onMenu = { showMenu = true })
    }
    // Also reachable from the error screen: a server that can't be reached must not trap the user in web mode.
    BackHandler(enabled = server is Load.Failed) { showMenu = true }

    if (showMenu) AlertDialog(
        onDismissRequest = { showMenu = false },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                TextButton({ showMenu = false; reloadKey++ }) { Text(stringResource(R.string.web_reload)) }
                TextButton({ showMenu = false; onToggleNativePlayer(); reloadKey++ }) {
                    Text(stringResource(if (nativePlayer) R.string.web_player_use_browser else R.string.web_player_use_native))
                }
                TextButton({ showMenu = false; showDiagnostics = true }) { Text(stringResource(R.string.diagnostics_title)) }
                TextButton({ showMenu = false; onUseNative() }) { Text(stringResource(R.string.web_use_native)) }
                TextButton({ showMenu = false; onSignOut() }) { Text(stringResource(R.string.sign_out)) }
                TextButton({ showMenu = false; onQuit() }) { Text(stringResource(R.string.web_quit)) }
            }
        },
        confirmButton = { TextButton({ showMenu = false }) { Text(stringResource(R.string.back)) } },
    )

    if (showDiagnostics) dev.jellyflix.ui.components.DiagnosticsDialog { showDiagnostics = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebHost(
    session: Session, serverId: String, serverName: String, deviceId: String, isTv: Boolean,
    nativePlayer: Boolean, accent: Int?, reloadKey: Int, onMenu: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val serverUrl = session.account.serverUrl
    var loadError by remember(reloadKey) { mutableStateOf<String?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val holder = remember(session.account.key, reloadKey) {
        val root = FrameLayout(ctx)
        val web = ServerWebView(ctx)
        val overlay = FrameLayout(ctx).apply { visibility = View.GONE; setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        Triple(root, web, overlay)
    }
    val (root, web, overlay) = holder

    // ---- the app's hardware player on top of the web interface ----
    val scope = rememberCoroutineScope()
    var nativeStream by remember(holder) { mutableStateOf<ExternalStream?>(null) }
    var serial by remember(holder) { mutableIntStateOf(0) }
    var playerVm by remember(holder) { mutableStateOf<PlayerViewModel?>(null) }
    val send = remember(holder) { { e: kotlinx.serialization.json.JsonObject -> web.post { web.evaluateJavascript(WebPlayerBridge.eventScript(e), null) }; Unit } }
    val events = remember(holder) {
        object : PlayerEvents {
            override fun started() = send(WebPlayerBridge.event("started"))
            override fun time(positionMs: Long, durationMs: Long, paused: Boolean) =
                send(WebPlayerBridge.event("time") { put("ms", positionMs); put("dur", durationMs); put("paused", paused) })
            override fun ended() {
                send(WebPlayerBridge.event("ended"))
                // The web client may start the next episode right away; if it doesn't, give the screen back.
                val endedSerial = nativeStream?.serial
                scope.launch { delay(2500); if (nativeStream?.serial == endedSerial) nativeStream = null }
            }
            override fun userStop(positionMs: Long) = send(WebPlayerBridge.event("userstop") { put("ms", positionMs) })
            override fun error(type: String) = send(WebPlayerBridge.event("error") { put("errorType", type) })
            override fun selectAudio(index: Int) = send(WebPlayerBridge.event("audio") { put("index", index) })
            override fun selectSubtitle(index: Int) = send(WebPlayerBridge.event("subtitle") { put("index", index) })
            override fun selectBitrate(bitrate: Int?) = send(WebPlayerBridge.event("bitrate") { put("bitrate", bitrate) })
        }
    }
    val assetJs = remember { runCatching { ctx.assets.open("web/jellyflix-web.js").bufferedReader().use { it.readText() } }.getOrDefault("") }
    val profile = remember(nativePlayer) {
        if (!nativePlayer) null
        else runCatching { Json { encodeDefaults = true; explicitNulls = false }.encodeToJsonElement(DeviceProfile.serializer(), DeviceProfiles.build(null)) }.getOrNull()
    }

    DisposableEffect(holder) {
        val origin = WebBootstrap.origin(serverUrl)
        val documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        // The player needs both a document-start script (to register before the web client boots) and the message
        // channel. The channel is restricted to the server's origin; any failure means the web player is simply kept.
        var bridgeReady = false
        if (nativePlayer && documentStartScript && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bridgeReady = runCatching {
                WebViewCompat.addWebMessageListener(web, "JellyflixNative", setOf(origin)) { _, message, _, _, _ ->
                    when (val cmd = message.data?.let { WebPlayerBridge.parse(it, serial + 1) }) {
                        is BridgeCommand.Play -> { serial = cmd.stream.serial; nativeStream = cmd.stream }
                        BridgeCommand.Stop -> nativeStream = null
                        BridgeCommand.Pause -> playerVm?.setPaused(true)
                        BridgeCommand.Unpause -> playerVm?.setPaused(false)
                        is BridgeCommand.Seek -> playerVm?.seekToMs(cmd.ms)
                        is BridgeCommand.Audio -> playerVm?.applyAudio(cmd.index)
                        is BridgeCommand.Subtitle -> playerVm?.applySubtitle(cmd.index)
                        BridgeCommand.Quit -> activity?.finish()
                        null -> Unit
                    }
                }
            }.isSuccess
        }
        val appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"
        val config = WebBootstrap.config(isTv, accent, bridgeReady, profile, deviceId, android.os.Build.MODEL ?: "Android", appVersion)
        val script = WebBootstrap.fullScript(
            WebBootstrap.script(serverUrl, serverId, serverName, session.account.userId, session.account.token, deviceId, isTv), config, assetJs,
        )

        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.onMenuKey = onMenu
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            // The web client switches to its TV layout when the user agent says "TV" (browser.js).
            if (isTv) userAgentString = "$userAgentString Jellyflix AndroidTV"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            // Same-server pages stay here, anything else (links in plugin content, etc.) goes to the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (WebBootstrap.isSameOrigin(url, serverUrl)) return false
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Fallback for WebViews without document-start scripts: later than ideal, but the web client reads
                // its credentials lazily, and a reload picks them up.
                if (!documentStartScript && url != null && WebBootstrap.isSameOrigin(url, serverUrl)) view.evaluateJavascript(script, null)
            }
            override fun onPageFinished(view: WebView, url: String?) { loadError = null }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = error.description?.toString() ?: "Error"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view; fullscreenCallback = callback
                overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
                overlay.visibility = View.VISIBLE
                activity?.window?.let { w ->
                    WindowCompat.setDecorFitsSystemWindows(w, false)
                    WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            override fun onHideCustomView() {
                overlay.removeAllViews(); overlay.visibility = View.GONE
                fullscreenView = null; fullscreenCallback = null
                activity?.window?.let { w -> WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }

        if (documentStartScript) WebViewCompat.addDocumentStartJavaScript(web, script, setOf(origin))
        web.loadUrl(WebBootstrap.startUrl(serverUrl))
        web.requestFocus()

        onDispose {
            fullscreenCallback?.onCustomViewHidden()
            root.removeAllViews()
            web.stopLoading(); web.destroy()
        }
    }

    // Pause page timers and media when the app is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) web.onPause() else if (e == Lifecycle.Event.ON_RESUME) web.onResume()
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }

    // Back: leave fullscreen video, then walk the page history, and only at the root offer the app menu.
    BackHandler {
        when {
            fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
            web.canGoBack() -> web.goBack()
            else -> onMenu()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (loadError != null) ErrorView(loadError, { web.reload() }, Modifier.fillMaxSize())
        else AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())
        nativeStream?.let { stream ->
            ExternalPlayerScreen(stream, events, onViewModel = { playerVm = it }, onBack = { nativeStream = null })
        }
    }
    // The D-pad goes back to the web page once the player is gone.
    LaunchedEffect(nativeStream == null) { if (nativeStream == null) web.requestFocus() }
}
