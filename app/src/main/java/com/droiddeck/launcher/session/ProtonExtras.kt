package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONObject

/** Immediate download and installation of the optional ARM64 Proton builds. */
object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val PROTON_SEED_URL =
        "https://raw.githubusercontent.com/mihsian77/DroidDeck-zh/main/proton-seed.json"
    @Volatile
    var installInProgress = false
        private set

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    /** [sha256] from GitHub's asset digest; [sha512] the URL of a checksum file published beside it. */
    private data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null)

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")

    /** The installed build's directory name (e.g. GE-Proton11-7), or null. */
    fun installed(context: Context, tool: Tool): String? =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.maxByOrNull { it.name }?.name

    /** A request left by an earlier app version, still consumed by the session shim. */
    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Deletes the installed build; Steam discovers its absence on its next start. */
    fun remove(context: Context, tool: Tool) {
        installed(context, tool)?.let { File(toolsDir(context), it).deleteRecursively() }
    }

    /** Downloads, verifies and registers the latest build without waiting for another session. */
    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return context.getString(R.string.pextra_busy)
        installInProgress = true
        return try { installNow(context, tool, onProgress) } finally { installInProgress = false }
    }

    /**
     * Installs a local Proton archive the user picked (import path). The file is copied into the
     * download directory so the install logic always reads from the same place, then verified and
     * registered exactly like a downloaded build. The caller owns the picked file's lifetime.
     */
    @Synchronized
    fun importArchive(context: Context, tool: Tool, file: File, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return context.getString(R.string.pextra_busy)
        installInProgress = true
        try {
            if (SessionState.running) return context.getString(R.string.pextra_stop_session)
            if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)
            val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
            val archive = File(downloads, "${tool.id}-local${file.name.substringBeforeLast('.')}.tar.gz")
            onProgress(context.getString(R.string.pextra_copying, tool.name), 0)
            file.copyTo(archive, overwrite = true)
            return installArchive(context, tool, archive, onProgress)
        } finally {
            installInProgress = false
        }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return context.getString(R.string.pextra_stop_session)
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)

        val asset = findLatestAsset(tool) ?: return context.getString(R.string.pextra_no_release, tool.name)
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return context.getString(R.string.pextra_no_space, tool.name)
        }

        val downloading = context.getString(R.string.pextra_downloading, tool.name, asset.tag)
        onProgress(downloading, 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true) { fraction ->
                onProgress(downloading, if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress(context.getString(R.string.pextra_retrying, attempt + 2, 5), -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return context.getString(R.string.pextra_interrupted)
                }
            }
        }
        if (!downloaded) return context.getString(R.string.pextra_download_failed)
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return context.getString(R.string.pextra_too_big)
            }
            return context.getString(R.string.pextra_incomplete)
        }

        // One checksum has to be found and has to match: GitHub's sha256 digest, else the
        // release's own sha512 file. A download neither vouches for is not installed.
        onProgress(context.getString(R.string.pextra_verifying), -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadString(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return context.getString(R.string.pextra_checksum_unreadable)
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            else -> {
                archive.delete()
                return context.getString(R.string.pextra_no_checksum, tool.name, asset.tag)
            }
        }
        if (!verified) {
            archive.delete()
            return context.getString(R.string.pextra_checksum_mismatch)
        }
        return installArchive(context, tool, archive, onProgress)
    }

    /**
     * Verifies (where possible) and registers one Proton archive: stage the session files, then
     * ask the runtime's droiddeck-proton-extra shim to unpack it into compatibilitytools.d. The
     * archive is deleted on success; on a checksum failure it is deleted before install too.
     * Imported archives arrive here unverified (no release to vouch for them) and are installed
     * as-is - the shim refuses an archive that does not look like a Proton build.
     */
    private fun installArchive(context: Context, tool: Tool, archive: File, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return context.getString(R.string.pextra_session_started)
        val installing = context.getString(R.string.pextra_installing, tool.name)
        onProgress(installing, -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/droiddeck-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress(installing, -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) context.getString(R.string.pextra_install_failed, tool.name, status)
            else {
                archive.delete()
                unqueue(context, tool)
                if (EsyncPacks.enabled(context)) {
                    onProgress(context.getString(R.string.esync_fetching), -1)
                    try {
                        EsyncPacks.fetchWanted(context, root, onProgress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync pack for ${tool.name}", t)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: context.getString(R.string.pextra_could_not_install, tool.name)
        }
    }

    /**
     * The latest build comes from this repo's proton-seed.json, not api.github.com: the API is
     * blocked or throttled for users on CN networks and the mirror nodes cannot proxy it. A raw
     * GitHub URL is served by the same MirrorHub sources as every other fetch.
     */
    private fun findLatestAsset(tool: Tool): Asset? {
        val json = Downloader.downloadString(PROTON_SEED_URL) ?: return null
        return try {
            val entry = JSONObject(json).optJSONObject("tools")?.optJSONObject(tool.id) ?: return null
            Asset(
                entry.optString("tag"), entry.optString("name"),
                entry.optString("url").takeIf { it.startsWith("http") } ?: return null,
                entry.optString("sha512").takeIf { it.startsWith("http") },
                entry.optLong("size", 0L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "proton seed for ${tool.id}", e)
            null
        }
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
