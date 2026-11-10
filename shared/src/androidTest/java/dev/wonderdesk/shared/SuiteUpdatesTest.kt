package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuiteUpdatesTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(compareVersions("0.2.10", "0.2.9") > 0)
        assertTrue(compareVersions("0.2.9", "0.2.10") < 0)
        assertEquals(0, compareVersions("0.2.9", "0.2.9"))
        assertTrue(compareVersions("0.3.8", "0.2.11") > 0)
        assertEquals(0, compareVersions("0.2", "0.2.0"))
    }

    @Test fun atomFeedParsesFirstRelease() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Release notes from Abacus</title>
  <entry>
    <id>tag:github.com,2008:Repository/1450978959/v0.2.10</id>
    <link rel="alternate" type="text/html" href="https://github.com/thoroftroy/Abacus/releases/tag/v0.2.10"/>
    <title>Abacus v0.2.10</title>
  </entry>
  <entry>
    <id>tag:github.com,2008:Repository/1450978959/v0.2.9</id>
    <title>Abacus v0.2.9</title>
  </entry>
</feed>"""
        val rel = parseAtomFeed(xml, "Abacus")
        assertNotNull(rel)
        assertEquals("v0.2.10", rel!!.tag)
        assertEquals("0.2.10", rel.version)
        assertEquals("Wonderdesk-Abacus-v0.2.10-debug.apk", rel.apkName)
        assertTrue(rel.apkUrl.contains("/Abacus/releases/download/v0.2.10/"))
    }

    @Test fun atomFeedUsesBlissPlayerNaming() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>tag:github.com,2008:Repository/999/v0.14.50</id>
    <title>Bliss Player v0.14.50</title>
  </entry>
</feed>"""
        val rel = parseAtomFeed(xml, "BlissPlayer")
        assertNotNull(rel)
        assertEquals("BlissPlayer-v0.14.50-debug.apk", rel!!.apkName)
        assertTrue(rel.apkUrl.contains("/BlissPlayer/releases/download/v0.14.50/"))
    }

    @Test fun atomFeedDerivesMultiwordRepoNames() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>tag:github.com,2008:Repository/1450980908/v0.2.12</id>
    <title>Control Room v0.2.12</title>
  </entry>
</feed>"""
        val rel = parseAtomFeed(xml, "Control-Room")
        assertNotNull(rel)
        assertEquals("Wonderdesk-Control-Room-v0.2.12-debug.apk", rel!!.apkName)
        assertTrue(rel.apkUrl.contains("/Control-Room/releases/download/v0.2.12/"))
    }

    @Test fun atomFeedWithNoEntriesIsNull() {
        assertNull(parseAtomFeed("""<feed><title>Release notes from Empty</title></feed>""", "Empty"))
        assertNull(parseAtomFeed("", "Empty"))
    }

    @Test fun suiteCatalogCoversAllSuitePackages() {
        val pkgs = SUITE_ENTRIES.map { it.pkg }.toSet()
        assertEquals(17, pkgs.size)
        assertTrue(pkgs.all { it.startsWith("dev.wonderdesk.") || it == "com.blissplayer.music.debug" })
        assertTrue(pkgs.contains("dev.wonderdesk.apphub"))
        assertTrue(pkgs.contains("dev.wonderdesk.launcher"))
        assertTrue(pkgs.contains("com.blissplayer.music.debug"))
        // Shutter and Spyglass are gone: their functionality merged into Memories.
        assertFalse(pkgs.contains("dev.wonderdesk.shutter"))
        assertFalse(pkgs.contains("dev.wonderdesk.spyglass"))
    }

    @Test fun missingEntriesAreQueryableAndExcludeInstalled() {
        val installed = setOf("dev.wonderdesk.abacus", "dev.wonderdesk.apphub")
        val missing = missingSuiteEntries(installed, "")
        assertEquals(15, missing.size)
        assertTrue(missing.none { it.pkg in installed })
        assertEquals(listOf("Beacon"), missingSuiteEntries(installed, "bea").map { it.name })
        assertTrue(missingSuiteEntries(installed, "no such app").isEmpty())
        assertEquals(17, missingSuiteEntries(emptySet(), "").size)
    }
}
