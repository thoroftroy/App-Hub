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

    @Test fun parsesApkAssetFromLatestRelease() {
        val json = """{"tag_name":"v0.2.11","assets":[{"name":"README.md","browser_download_url":"https://example.com/r"},{"name":"Wonderdesk-App-Hub-v0.2.11-debug.apk","browser_download_url":"https://github.com/x/y.apk"}]}"""
        val rel = parseLatestRelease(json)
        assertNotNull(rel)
        assertEquals("v0.2.11", rel!!.tag)
        assertEquals("0.2.11", rel.version)
        assertEquals("Wonderdesk-App-Hub-v0.2.11-debug.apk", rel.apkName)
    }

    @Test fun noApkAssetParsesToNull() {
        assertNull(parseLatestRelease("""{"tag_name":"v0.2.11","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n"}]}"""))
        assertNull(parseLatestRelease("""{"tag_name":"","assets":[]}"""))
    }

    @Test fun suiteCatalogCoversAllSuitePackages() {
        val pkgs = SUITE_ENTRIES.map { it.pkg }.toSet()
        assertEquals(18, pkgs.size)
        assertTrue(pkgs.all { it.startsWith("dev.wonderdesk.") })
        assertTrue(pkgs.contains("dev.wonderdesk.apphub"))
        assertTrue(pkgs.contains("dev.wonderdesk.launcher"))
    }

    @Test fun missingEntriesAreQueryableAndExcludeInstalled() {
        val installed = setOf("dev.wonderdesk.abacus", "dev.wonderdesk.apphub")
        val missing = missingSuiteEntries(installed, "")
        assertEquals(16, missing.size)
        assertTrue(missing.none { it.pkg in installed })
        assertEquals(listOf("Beacon"), missingSuiteEntries(installed, "bea").map { it.name })
        assertTrue(missingSuiteEntries(installed, "no such app").isEmpty())
        assertEquals(18, missingSuiteEntries(emptySet(), "").size)
    }
}
