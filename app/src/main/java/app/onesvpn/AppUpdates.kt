package app.onesvpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Download streams to private storage; checksum, package, version and signing identity are checked. */
object AppUpdates {
    internal const val API = "https://api.github.com/repos/salfetka1222/ones-vpn-public/releases"
    internal const val ASSET_PREFIX = "https://github.com/salfetka1222/ones-vpn-public/releases/download/"
    private const val LIMIT = 180_000_000L
    data class Release(
        val version: Int,
        val title: String,
        val notes: String,
        val apk: String,
        val checksum: String,
        val assetName: String = "ONES-VPN-alpha.apk"
    )
    class UpdateException(message: String) : Exception(message)

    private fun open(source: String): HttpURLConnection {
        var url = URL(source)
        repeat(6) {
            require(url.protocol == "https") { "UPDATE_HTTPS_REQUIRED" }
            require(url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "UPDATE_HOST_REJECTED" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ONES-VPN-Android")
            }
            try {
                when (connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: error("UPDATE_REDIRECT_MISSING")
                        url = URL(url, location)
                    }
                    401, 403, 404 -> throw UpdateException("Релизы ONES VPN сейчас недоступны. Проверьте интернет и повторите попытку.")
                    else -> throw UpdateException("Сервер обновлений недоступен. Повторите позже.")
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        throw UpdateException("Слишком много перенаправлений загрузки")
    }

    private fun download(url: String, limit: Long, output: java.io.OutputStream) {
        val connection = open(url)
        try {
            require(connection.contentLengthLong <= limit) { "UPDATE_TOO_LARGE" }
            connection.inputStream.use { input ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "UPDATE_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
            }
        } finally { connection.disconnect() }
    }
    private fun bytes(url: String, limit: Long): ByteArray = java.io.ByteArrayOutputStream().use {
        download(url, limit, it)
        it.toByteArray()
    }
    fun check(current: Int, currentVersionName: String): Release? = parseReleases(
        JSONArray(String(bytes("$API?per_page=100", 2_000_000), Charsets.UTF_8)),
        current, allowLegacyAlpha = !ReleaseChannelPolicy.isStable(currentVersionName))

    internal fun parseReleases(
        releases: JSONArray, current: Int, allowLegacyAlpha: Boolean = true
    ): Release? {
        val candidates = mutableListOf<Release>()
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            // Legacy alpha releases remain supported for installed users.
            // Public Stable uses an explicitly different tag and APK asset.
            // Never offer an RC as Stable or trust GitHub's release "latest".
            val stableVersion = Regex("^ones-vpn-stable-(\\d+)$")
                .matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
            val alphaVersion = if (allowLegacyAlpha) Regex("^ones-vpn-alpha-(\\d+)$")
                .matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() else null
            val version = stableVersion ?: alphaVersion ?: continue
            if (version <= current) continue
            if (stableVersion != null && release.optBoolean("prerelease")) continue
            val assetName = if (stableVersion != null) "ONES-VPN.apk" else "ONES-VPN-alpha.apk"
            val assets = release.optJSONArray("assets") ?: continue
            val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                .associate { it.optString("name") to it.optString("browser_download_url") }
            val apk = urls[assetName] ?: continue
            val sum = urls["SHA256SUMS.txt"] ?: continue
            if (apk != ASSET_PREFIX + tag + "/" + assetName ||
                sum != ASSET_PREFIX + tag + "/SHA256SUMS.txt") continue
            candidates += Release(
                version, release.optString("name"), release.optString("body").take(3000),
                apk, sum, assetName
            )
        }
        // Prefer Stable over legacy alpha when both carry an identical code.
        return candidates.maxWithOrNull(
            compareBy<Release> { it.version }.thenBy { if (it.assetName == "ONES-VPN.apk") 1 else 0 }
        )
    }

    /** Parse only the exact, pinned APK filename. Reject duplicate hash entries. */
    internal fun expectedSha256(sumText: String, assetName: String): String? {
        if (assetName !in setOf("ONES-VPN-alpha.apk", "ONES-VPN.apk")) return null
        val sums = Regex("(?im)^([a-f0-9]{64})[ \\t]+\\*?" +
            Regex.escape(assetName) + "\\r?$").findAll(sumText).toList()
        return sums.singleOrNull()?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Return false before downloading when Android has not granted permission.
     * The Activity opens Android's permission screen on its main thread.
     */
    fun downloadAndInstall(context: Context, release: Release): Boolean {
        val manager = context.packageManager
        return UpdateInstallGate.runWhenPermitted(manager.canRequestPackageInstalls()) {
            downloadVerifiedAndInstall(context, release, manager)
        } ?: false
    }

    private fun downloadVerifiedAndInstall(
        context: Context, release: Release, manager: PackageManager
    ): Boolean {
        require(release.assetName in setOf("ONES-VPN-alpha.apk", "ONES-VPN.apk"))
        val tag = if (release.assetName == "ONES-VPN.apk")
            "ones-vpn-stable-" + release.version else "ones-vpn-alpha-" + release.version
        require(release.apk == ASSET_PREFIX + tag + "/" + release.assetName)
        require(release.checksum == ASSET_PREFIX + tag + "/SHA256SUMS.txt")
        val sumText = String(bytes(release.checksum, 2048), Charsets.UTF_8)
        val expected = expectedSha256(sumText, release.assetName)
            ?: throw UpdateException("UPDATE_CHECKSUM_INVALID")
        val dir = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        val pending = File.createTempFile("download-", ".apk", dir)
        try {
            pending.outputStream().use { download(release.apk, LIMIT, it) }
            val digest = MessageDigest.getInstance("SHA-256")
            pending.inputStream().use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().hex().equals(expected, true)) { "UPDATE_CHECKSUM_MISMATCH" }
            val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val archive = manager.getPackageArchiveInfo(pending.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw UpdateException("Не удалось проверить APK")
            fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
            try {
                UpdateVerifier.requireCompatible(context.packageName, installed.longVersionCode, release.version,
                    archive.packageName, archive.longVersionCode, signers(installed), signers(archive))
            } catch (_: IllegalArgumentException) {
                throw UpdateException("APK несовместим с установленной версией или подписью. Не удаляйте приложение: это удалит профили.")
            }
            // Permission can be revoked while a large APK is downloading.
            // Never open an installer for an app without current permission.
            if (!manager.canRequestPackageInstalls()) return false
            val file = File(dir, release.assetName)
            check(pending.renameTo(file)) { "UPDATE_SAVE_FAILED" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return true
        } finally { pending.delete() }
    }
    /** Called from the UI thread, never the background download worker. */
    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
