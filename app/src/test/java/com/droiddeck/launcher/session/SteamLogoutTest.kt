package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The sign-out flag and the credential wipe. The order against killStragglers() lives in
 * SessionService and cannot be unit-tested here; these cover what apply() itself promises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamLogoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val flag = File(context.filesDir, SteamLogout.FLAG)

    private fun steamRoot(): File {
        val root = File(context.cacheDir, "steamroot-${System.nanoTime()}").apply { mkdirs() }
        File(root, "root/.local/share/Steam/config").mkdirs()
        return root
    }

    private fun loginUsers(root: File) =
        File(root, "root/.local/share/Steam/config/loginusers.vdf")

    private fun configVdf(root: File) =
        File(root, "root/.local/share/Steam/config/config.vdf")

    @Test
    fun clearsCredentialsAndTheFlag() {
        val root = steamRoot()
        loginUsers(root).writeText("\"users\"\n{\n}\n")
        configVdf(root).writeText("\"InstallConfigStore\"\n{\n\t\"AutoLoginUser\"\t\t\"someone\"\n}\n")
        flag.parentFile?.mkdirs()
        flag.createNewFile()
        assertTrue(SteamLogout.requested(context))

        SteamLogout.apply(context, root)

        assertFalse(loginUsers(root).exists())
        assertFalse(configVdf(root).readText().contains("AutoLoginUser"))
        assertFalse(flag.exists())
    }

    @Test
    fun keepsTheFlagWhenThereWasNothingToClear() {
        val root = steamRoot()
        flag.createNewFile()

        SteamLogout.apply(context, root)

        assertTrue(flag.exists())
    }

    @Test
    fun leavesUnrelatedConfigLinesAlone() {
        val root = steamRoot()
        configVdf(root).writeText(
            "\"InstallConfigStore\"\n{\n\t\"AutoLoginUser\"\t\t\"someone\"\n\t\"Rate\"\t\t\"250\"\n}\n",
        )
        flag.createNewFile()

        SteamLogout.apply(context, root)

        val text = configVdf(root).readText()
        assertTrue(text.contains("\"Rate\""))
        assertFalse(text.contains("AutoLoginUser"))
    }

    @Test
    fun aNormalStartWithoutTheFlagLeavesCredentialsAlone() {
        val root = steamRoot()
        loginUsers(root).writeText("\"users\"\n{\n}\n")

        assertFalse(SteamLogout.requested(context))
        assertTrue(loginUsers(root).exists())
    }
}
