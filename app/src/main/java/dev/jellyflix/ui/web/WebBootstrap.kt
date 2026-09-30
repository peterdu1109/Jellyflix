package dev.jellyflix.ui.web

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Glue between the native session and the Jellyfin web client that the server itself serves.
 *
 * The web client keeps its session in `localStorage["jellyfin_credentials"]` (jellyfin-apiclient), its device id in
 * `_deviceId2` and its layout in `layout`. Writing them before the page scripts run signs the user in and picks the
 * TV layout, so the server's own interface (theme, custom CSS, plugins such as Media Bar) shows up untouched.
 */
object WebBootstrap {
    /** jellyfin-apiclient's ConnectionMode.Manual: "use the address stored in ManualAddress". */
    private const val MODE_MANUAL = 2

    fun startUrl(serverUrl: String): String = serverUrl.trimEnd('/') + "/web/index.html"

    /** `scheme://host[:port]`, the unit localStorage and the document-start-script rules are scoped to. */
    fun origin(serverUrl: String): String {
        val u = URI(serverUrl.trim())
        val port = if (u.port == -1) "" else ":${u.port}"
        return "${u.scheme.lowercase()}://${u.host.lowercase()}$port"
    }

    /** True when [url] belongs to the same server: those stay in the WebView, anything else opens the browser. */
    fun isSameOrigin(url: String, serverUrl: String): Boolean = runCatching {
        val a = URI(url.trim()); val b = URI(serverUrl.trim())
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    }.getOrDefault(false)

    private fun effectivePort(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    private fun js(value: String): String = Json.encodeToString(String.serializer(), value)

    /** Script run at document start on the server's origin. Every value is JSON-encoded, so nothing can break out of it. */
    fun script(serverUrl: String, serverId: String, serverName: String, userId: String, token: String, deviceId: String, tv: Boolean): String = """
        (function () {
          try {
            var KEY = 'jellyfin_credentials';
            var entry = {
              Id: ${js(serverId)}, Name: ${js(serverName)}, ManualAddress: ${js(serverUrl.trimEnd('/'))},
              LastConnectionMode: $MODE_MANUAL, UserId: ${js(userId)}, AccessToken: ${js(token)}, DateLastAccessed: Date.now()
            };
            var cur = null;
            try { cur = JSON.parse(localStorage.getItem(KEY)); } catch (e) {}
            if (!cur || !Array.isArray(cur.Servers)) cur = { Servers: [] };
            var found = false;
            cur.Servers = cur.Servers.map(function (s) {
              if (s.Id === entry.Id) { found = true; return Object.assign({}, s, entry); }
              return s;
            });
            if (!found) cur.Servers.push(entry);
            localStorage.setItem(KEY, JSON.stringify(cur));
            localStorage.setItem('_deviceId2', ${js(deviceId)});
            ${if (tv) "if (!localStorage.getItem('layout')) localStorage.setItem('layout', 'tv');" else ""}
          } catch (e) {}
        })();
    """.trimIndent()

    /** `window.__JELLYFLIX__` for assets/web/jellyflix-web.js. [deviceProfile] is the DeviceProfile as API JSON, or null. */
    fun config(
        tv: Boolean, accent: Int?, nativePlayer: Boolean, deviceProfile: JsonElement?,
        deviceId: String, deviceName: String, appVersion: String,
    ): String = buildJsonObject {
        put("tv", tv)
        // The server theme's accent when it defines one, otherwise Jellyfin blue.
        put("accent", "#%06X".format((accent ?: 0x00A4DC) and 0xFFFFFF))
        put("nativePlayer", nativePlayer)
        if (nativePlayer && deviceProfile != null) put("deviceProfile", deviceProfile)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("appVersion", appVersion)
    }.toString()

    /** What runs at document start: the signed-in session, then the configuration, then the Jellyflix additions. */
    fun fullScript(bootstrap: String, configJson: String, asset: String): String =
        bootstrap + "\n" + "window.__JELLYFLIX__ = " + configJson + ";\n" + asset
}
