package dev.jellyflix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerThemeParserTest {
    @Test fun readsAccentAndBackgroundFromRootVariables() {
        val t = ServerThemeParser.fromCss(":root { --accent: #FF5722; --background: rgb(16, 16, 32); }")
        assertEquals(0xFFFF5722.toInt(), t.accent)
        assertEquals(0xFF101020.toInt(), t.background)
        assertEquals(true, t.dark)
    }

    @Test fun expandsShortHexAndDropsAlpha() {
        assertEquals(0xFFAABBCC.toInt(), ServerThemeParser.parseColor("#abc"))
        assertEquals(0xFF112233.toInt(), ServerThemeParser.parseColor("#11223380"))
    }

    @Test fun lightBackgroundIsNotDark() {
        assertEquals(false, ServerThemeParser.fromCss("--theme-body-bg: #fafafa;").dark)
    }

    @Test fun emptyOrUnrelatedCssGivesNothing() {
        assertNull(ServerThemeParser.fromCss("body { color: red }").accent)
        assertNull(ServerThemeParser.fromCss("").dark)
    }

    @Test fun resolvesImports() {
        val css = "@import url('https://cdn.example/theme.css'); @import \"/web/custom.css\";"
        assertEquals(listOf("https://cdn.example/theme.css", "https://srv.tld/web/custom.css"), ServerThemeParser.imports(css, "https://srv.tld/"))
    }

    @Test fun mapsKnownWebThemes() {
        assertTrue(ServerThemeParser.fromWebTheme("purplehaze")!!.dark == true)
        assertNull(ServerThemeParser.fromWebTheme("custom-unknown"))
    }
}
