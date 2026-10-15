package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Carries out a requested Steam logout on the next session.
 *
 * The Steam home writes a flag file when the user asks to sign out; the flag is consumed here,
 * once, at the start of a Steam (or desktop) session, before the client boots. Signing out is
 * wiping the credentials the guest knows: loginusers.vdf is the account database the client reads
 * to skip the sign-in screen, and AutoLoginUser in config.vdf is the account it would sign in as
 * without asking. Both are removed, then the flag goes with them, so a fresh client start is
 * exactly that - a fresh sign-in screen.
 *
 * Failures are logged and the session still starts: a refused logout must not take the client
 * down with it, and the flag is only deleted once the credentials are gone.
 */
object SteamLogout {
    private const val TAG = "SteamLogout"
    internal const val FLAG = ".droiddeck-steam-logout"
    private val AUTOLOGIN = Regex("""^\s*"AutoLoginUser"\s*"([^"]*)"\s*$""")

    private fun loginUsers(root: File) = File(root, "root/.local/share/Steam/config/loginusers.vdf")
    private fun configVdf(root: File) = File(root, "root/.local/share/Steam/config/config.vdf")
    private fun steamHome(root: File) = File(root, "root/.local/share/Steam")

    /** True when the Steam home asked for a logout. */
    fun requested(context: Context): Boolean = File(context.filesDir, FLAG).isFile

    /** Wipes the guest's Steam credentials; returns what was cleared, for the session log. */
    fun apply(context: Context, root: File): String {
        val done = mutableListOf<String>()
        val users = loginUsers(root)
        if (users.isFile) {
            users.delete()
            done.add("loginusers.vdf")
        }
        val config = configVdf(root)
        if (config.isFile) {
            val rewritten = clearAutoLogin(config)
            if (rewritten != null) {
                val staged = File(config.parentFile, "config.vdf.staged")
                staged.writeText(rewritten)
                if (staged.renameTo(config)) done.add("config.vdf") else staged.delete()
            }
        }
        // P0-8: the client also keeps login traces outside config/: ssfn* credential caches,
        // the htmlcache web session, and per-account localconfig.vdf. A full logout removes them.
        val home = steamHome(root)
        home.listFiles { f -> f.isFile && f.name.startsWith("ssfn") }
            ?.forEach { it.delete(); done.add(it.name) }
        val htmlcache = File(home, "htmlcache")
        if (htmlcache.isDirectory) {
            htmlcache.deleteRecursively()
            done.add("htmlcache")
        }
        val userdata = File(home, "userdata")
        userdata.listFiles()?.forEach { account ->
            if (!account.isDirectory) return@forEach
            val local = File(File(account, "config"), "localconfig.vdf")
            if (local.isFile) {
                local.delete()
                done.add("userdata/" + account.name + "/config/localconfig.vdf")
            }
        }
        return if (done.isEmpty()) "logout: nothing to clear" else "logout: cleared " + done.joinToString(", ")
    }

    /** Drops the AutoLoginUser line (the account the client signs in as itself). */
    private fun clearAutoLogin(file: File): String? {
        val lines = file.readLines()
        val out = lines.filterNot { AUTOLOGIN.matches(it) }
        return if (out.size == lines.size) null else out.joinToString("\n", postfix = "\n")
    }
}
