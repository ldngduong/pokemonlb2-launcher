package me.magnum.melonds.legend

import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteReleaseInfo(
    val version: String,
    val romAssetName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val changelogVi: String
)

/**
 * HTTP client for querying the latest Pokémon Legend Black 2 release metadata
 * and streaming the verified NDS ROM asset with progress reporting.
 */
object ReleaseClient {

    const val RELEASES_REPO = "ldngduong/pokemon-legend-black2"
    const val RELEASES_API_LATEST = "https://api.github.com/repos/$RELEASES_REPO/releases/latest"
    const val RELEASES_WEB_PAGE = "https://github.com/$RELEASES_REPO/releases"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 LegendLauncher-MelonDS-Android/1.0"

    fun fetchLatestRelease(timeoutMs: Int = 10000): RemoteReleaseInfo {
        val conn = openWithRedirects(
            RELEASES_API_LATEST,
            timeoutMs,
            mapOf("Accept" to "application/vnd.github.v3+json")
        )
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Máy chủ phản hồi mã lỗi $code")
            }
            val bodyText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(bodyText)

            val tagName = json.optString("tag_name", "").trim()
            val remoteVersion = if (tagName.startsWith("v", ignoreCase = true)) {
                "Beta ${tagName.substring(1)}"
            } else {
                tagName
            }

            val assets = json.optJSONArray("assets")
            var ndsUrl = ""
            var ndsName = "Pokemon Legend Black 2 ($remoteVersion).nds"
            var ndsSize = 362560104L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.lowercase().endsWith(".nds")) {
                        ndsName = name
                        ndsUrl = asset.optString("browser_download_url", "")
                        ndsSize = asset.optLong("size", 362560104L)
                        break
                    }
                }
            }

            if (ndsUrl.isEmpty()) {
                throw IllegalStateException(
                    "Không tìm thấy file ROM (.nds) trong bản phát hành $remoteVersion trên máy chủ."
                )
            }

            val rawBody = json.optString("body", "")
            val changelogVi = extractVietnameseChangelog(rawBody)

            return RemoteReleaseInfo(
                version = remoteVersion,
                romAssetName = ndsName,
                downloadUrl = ndsUrl,
                fileSize = ndsSize,
                changelogVi = changelogVi
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractVietnameseChangelog(body: String): String {
        if (body.isBlank()) return "Cập nhật tính năng và sửa lỗi."
        val parsed = if (body.contains("### Tiếng Việt")) {
            body.substringAfter("### Tiếng Việt").substringBefore("### English").trim()
        } else {
            body.trim()
        }
        return parsed.ifBlank { "Cập nhật tính năng và sửa lỗi." }
    }

    fun downloadFile(
        urlStr: String,
        destPartFile: File,
        expectedSize: Long,
        cancelled: AtomicBoolean,
        onProgress: (downloaded: Long, total: Long, pct: Int, speedMbps: Double) -> Unit
    ) {
        val conn = openWithRedirects(urlStr, 25000)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Máy chủ trả về lỗi HTTP $code")
            }

            val contentLen = conn.contentLengthLongCompat()
            val total = if (contentLen > 0L) contentLen else if (expectedSize > 0L) expectedSize else 362560104L
            val buffer = ByteArray(128 * 1024)
            var downloaded = 0L
            val startMs = System.currentTimeMillis()
            var lastUiUpdateMs = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destPartFile).use { output ->
                    while (!cancelled.get()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateMs >= 80 || downloaded >= total) {
                            lastUiUpdateMs = now
                            val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000.0
                            val speedMbps = (downloaded / (1024.0 * 1024.0)) / elapsedSec
                            val pct = ((downloaded.toDouble() / total.toDouble()) * 100.0)
                                .toInt()
                                .coerceIn(0, 100)
                            onProgress(downloaded, total, pct, speedMbps)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun openWithRedirects(
        initialUrl: String,
        timeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 8) {
            require(URL(currentUrl).protocol == "https") { "Cần kết nối HTTPS." }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) {
                    throw IllegalStateException("Liên kết chuyển hướng từ máy chủ bị rỗng.")
                }
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Quá nhiều lần chuyển hướng từ máy chủ.")
    }

    private fun HttpURLConnection.contentLengthLongCompat(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                contentLengthLong
            } else {
                contentLength.toLong()
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
