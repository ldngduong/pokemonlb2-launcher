package me.magnum.melonds.legend

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class LauncherUpdates(private val activity: LegendLauncherActivity) {
    private data class Update(val version: String, val url: String, val size: Long, val sha256: String)

    private val inProgress = AtomicBoolean()
    private val directory by lazy { File(activity.cacheDir, "launcher-update") }
    private val apk by lazy { File(directory, "launcher.apk") }
    private val permission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (activity.packageManager.canRequestPackageInstalls() && apk.isFile) {
            runCatching { install() }.onFailure { showError(it) }
        }
    }

    fun check() {
        if (!inProgress.compareAndSet(false, true)) return
        thread(name = "launcher-update-check") {
            val result = runCatching { latestUpdate() }
            activity.runOnUiThread {
                inProgress.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { update ->
                    if (update == null) {
                        AlertDialog.Builder(activity).setMessage("Launcher đã ở phiên bản mới nhất.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        AlertDialog.Builder(activity).setTitle("Launcher ${update.version}")
                            .setMessage("Tải bản cập nhật và mở trình cài đặt Android. ROM và save vẫn dùng được sau khi cập nhật.")
                            .setNegativeButton("Để sau", null)
                            .setPositiveButton("Cập nhật") { _, _ -> download(update) }.show()
                    }
                }.onFailure { showError(it) }
            }
        }
    }

    private fun latestUpdate(): Update? {
        val connection = ReleaseClient.openWithRedirects(
            "https://api.github.com/repos/ldngduong/pokemonlb2-launcher/releases/latest", 10000
        )
        try {
            check(connection.responseCode == 200) { "GitHub trả về HTTP ${connection.responseCode}." }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val tag = release.getString("tag_name").removePrefix("v")
            val current = installed.versionName.orEmpty().substringBefore(' ')
            if (!isNewer(tag, current)) return null
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .single { it.getString("name") == "PokemonLegendBlack2-Android.apk" }
            val hash = asset.getString("digest").removePrefix("sha256:")
            val size = asset.getLong("size")
            val url = asset.getString("browser_download_url")
            require(hash.matches(Regex("[a-fA-F0-9]{64}")) && size in 1L..104857600L)
            require(url.startsWith("https://github.com/ldngduong/pokemonlb2-launcher/releases/download/"))
            return Update(tag, url, size, hash)
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(value: String): List<Int> {
            require(value.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+"))) { "Phiên bản launcher không hợp lệ." }
            return value.split('.').map(String::toInt)
        }
        return parts(remote).zip(parts(current)).firstOrNull { (a, b) -> a != b }
            ?.let { (a, b) -> a > b } ?: false
    }

    private fun download(update: Update) {
        if (!inProgress.compareAndSet(false, true)) return
        val cancelled = AtomicBoolean()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        val dialog = AlertDialog.Builder(activity).setTitle("Đang tải launcher ${update.version}")
            .setView(bar).setCancelable(false)
            .setNegativeButton("Hủy") { _, _ -> cancelled.set(true) }.show()
        thread(name = "launcher-update-download") {
            val part = File(directory, "launcher-part.apk")
            val result = runCatching {
                check(directory.isDirectory || directory.mkdirs())
                ReleaseClient.downloadFile(update.url, part, update.size, cancelled) { _, _, percent, _ ->
                    activity.runOnUiThread { bar.progress = percent }
                }
                if (cancelled.get()) return@runCatching
                check(part.length() == update.size && sha256(part).equals(update.sha256, ignoreCase = true)) {
                    "Tệp tải về không khớp mã kiểm tra."
                }
                verifyPackage(part)
                check(part.renameTo(apk)) { "Không thể lưu gói cài đặt." }
            }
            part.delete()
            activity.runOnUiThread {
                inProgress.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                dialog.dismiss()
                if (!cancelled.get()) {
                    result.onSuccess { runCatching { install() }.onFailure { showError(it) } }
                        .onFailure { showError(it) }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val target = checkNotNull(pm.getPackageArchiveInfo(file.path, flags)) { "APK không hợp lệ." }
        fun signers(info: PackageInfo) = (
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        )?.toSet().orEmpty()
        val trusted = signers(installed)
        check(target.packageName == activity.packageName && trusted.isNotEmpty() && signers(target) == trusted &&
            PackageInfoCompat.getLongVersionCode(target) > PackageInfoCompat.getLongVersionCode(installed)) {
            "APK không cùng chữ ký hoặc phiên bản không mới hơn bản đã cài."
        }
    }

    private fun install() {
        verifyPackage(apk)
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("Cho phép cài bản cập nhật")
                .setMessage("Android yêu cầu cho phép launcher mở trình cài đặt ứng dụng.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }.show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.launcherupdates", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun showError(error: Throwable) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            AlertDialog.Builder(activity).setTitle("Không thể cập nhật launcher")
                .setMessage(error.message ?: "Vui lòng thử lại.").setPositiveButton("OK", null).show()
        }
    }
}
