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
}
