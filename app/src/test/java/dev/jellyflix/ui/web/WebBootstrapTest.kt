package dev.jellyflix.ui.web

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBootstrapTest {
    @Test fun startUrlKeepsReverseProxySubPath() {
        assertEquals("https://h.tld/jellyfin/web/index.html", WebBootstrap.startUrl("https://h.tld/jellyfin/"))
    }

    @Test fun originDropsPathAndNormalisesCase() {
        assertEquals("https://h.tld", WebBootstrap.origin("HTTPS://H.tld/jellyfin"))
        assertEquals("http://192.168.1.5:8096", WebBootstrap.origin("http://192.168.1.5:8096/"))
    }

    @Test fun sameOriginTreatsDefaultPortsAsEqual() {
        assertTrue(WebBootstrap.isSameOrigin("https://h.tld:443/web/index.html", "https://h.tld"))
        assertTrue(WebBootstrap.isSameOrigin("http://10.0.0.2:8096/x", "http://10.0.0.2:8096"))
        assertFalse(WebBootstrap.isSameOrigin("https://evil.tld/", "https://h.tld"))
        assertFalse(WebBootstrap.isSameOrigin("http://h.tld/", "https://h.tld"))
        assertFalse(WebBootstrap.isSameOrigin("not a url", "https://h.tld"))
    }

    @Test fun scriptCarriesTheSessionAndEscapesHostileValues() {
        val s = WebBootstrap.script("https://h.tld/", "srv1", "My \"Server\"", "user1", "tok'\"</script>", "dev1", tv = false)
        assertTrue(s.contains("jellyfin_credentials"))
        assertTrue(s.contains("LastConnectionMode: 2"))
        assertTrue(s.contains("\"srv1\""))
        assertTrue(s.contains("\"https://h.tld\""))          // trailing slash removed
        assertTrue(s.contains("My \\\"Server\\\""))          // quote escaped, cannot end the string literal
        assertTrue(s.contains("tok'\\\"</script>"))          // token stays inside its JSON string
        assertFalse(s.contains("'layout'"))                  // phones keep the web client's automatic layout
    }

    @Test fun tvForcesTheTvLayoutOnlyWhenTheUserHasNoChoice() {
        val s = WebBootstrap.script("https://h.tld", "srv1", "n", "u", "t", "d", tv = true)
        assertTrue(s.contains("if (!localStorage.getItem('layout')) localStorage.setItem('layout', 'tv');"))
    }

    @Test fun configCarriesTheAccentAndOnlySendsTheProfileWhenTheNativePlayerIsOn() {
        val profile = kotlinx.serialization.json.Json.parseToJsonElement("""{"Name":"p"}""")
        val on = kotlinx.serialization.json.Json.parseToJsonElement(WebBootstrap.config(true, 0xFFAA5CC3.toInt(), true, profile, "d", "TV \"1\"", "0.5")).jsonObject
        assertEquals("#AA5CC3", on["accent"]!!.jsonPrimitive.content)
        assertEquals("p", on["deviceProfile"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals("TV \"1\"", on["deviceName"]!!.jsonPrimitive.content)
        val off = kotlinx.serialization.json.Json.parseToJsonElement(WebBootstrap.config(false, null, false, profile, "d", "n", "v")).jsonObject
        assertEquals("#00A4DC", off["accent"]!!.jsonPrimitive.content)
        assertFalse("deviceProfile" in off)
    }

    @Test fun fullScriptKeepsTheOrderSessionThenConfigThenAssets() {
        val full = WebBootstrap.fullScript("A();", "{\"tv\":true}", "B();")
        assertTrue(full.indexOf("A();") < full.indexOf("window.__JELLYFLIX__ = {\"tv\":true};") && full.indexOf("__JELLYFLIX__") < full.indexOf("B();"))
    }
}

