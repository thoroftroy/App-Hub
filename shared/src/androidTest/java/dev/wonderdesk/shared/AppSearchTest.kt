package dev.wonderdesk.shared

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSearchTest {
    @Test fun titlePackageAndAliasesMatchWithoutSurroundingWhitespace() {
        val app = LaunchableApp(ComponentName("dev.wonderdesk.abacus", "MainActivity"), "Abacus", null, "1", listOf("calculator"))
        assertTrue(app.matches(" ABA "))
        assertTrue(app.matches("WONDERDESK"))
        assertTrue(app.matches(" calculator "))
        assertFalse(app.matches("no such app"))
        assertTrue(app.matches("   "))
    }
    @Test fun suiteFilterKeepsWonderdeskAndInstalledMusicApp() {
        fun app(packageName: String) = LaunchableApp(ComponentName(packageName, "MainActivity"), packageName, null, "1")
        val mine = app("dev.wonderdesk.abacus")
        val music = app("com.blissplayer.music.debug")
        val other = app("com.android.chrome")
        assertEquals(listOf(mine, music), filterManageApps(listOf(mine, other, music), "", true))
        assertEquals(listOf(mine, other, music), filterManageApps(listOf(mine, other, music), "", false))
        assertTrue(filterManageApps(listOf(other), "chrome", true).isEmpty())
    }
}
