package com.legendblack2.app

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.util.Locale

enum class ChipProfile(val id: String, val displayName: String) {
    AUTO("auto", "Tự động theo chip"),
    DIMENSITY("dimensity", "MediaTek / Dimensity (Khóa 60Hz)"),
    SNAPDRAGON("snapdragon", "Snapdragon (Chống crash Adreno)"),
    STANDARD("standard", "Tiêu chuẩn (60Hz Software)")
}

data class HardwareInfo(
    val brand: String,
    val model: String,
    val socName: String,
    val detectedProfile: ChipProfile,
    val isLtpo120Hz: Boolean,
    val currentMaxHz: Float
)

object ChipOptimizer {

    private const val TAG = "ChipOptimizer"

    fun detectHardware(activity: Activity): HardwareInfo {
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.ROOT)
        val board = (Build.BOARD ?: "").lowercase(Locale.ROOT)
        val socRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (Build.SOC_MODEL ?: "").trim()
        } else ""
        val socLower = socRaw.lowercase(Locale.ROOT)
        val cpuInfo = readCpuInfoSummary().lowercase(Locale.ROOT)
        val combined = "$hardware $board $socLower $cpuInfo"

        val isMediaTek = combined.contains("mt6") ||
            combined.contains("mt8") ||
            combined.contains("dimensity") ||
            combined.contains("helio") ||
            combined.contains("mediatek") ||
            combined.contains("immortalis") ||
            combined.contains("mali")

        val isSnapdragon = combined.contains("qcom") ||
            combined.contains("qualcomm") ||
            combined.contains("snapdragon") ||
            combined.contains("sm8") ||
            combined.contains("sm7") ||
            combined.contains("sm6") ||
            combined.contains("adreno")

        val detected = when {
            isMediaTek -> ChipProfile.DIMENSITY
            isSnapdragon -> ChipProfile.SNAPDRAGON
            else -> ChipProfile.STANDARD
        }

        val prettySoc = when {
            socRaw.isNotEmpty() && !socRaw.equals("unknown", ignoreCase = true) -> socRaw
            isMediaTek -> "MediaTek ($hardware)"
            isSnapdragon -> "Snapdragon ($hardware)"
            else -> hardware.ifEmpty { board }.ifEmpty { "ARM64" }
        }

        var maxHz = 60f
        try {
            val disp = getDisplayCompat(activity)
            if (disp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (m in disp.supportedModes) {
                    if (m.refreshRate > maxHz) maxHz = m.refreshRate
                }
            }
        } catch (_: Exception) {
        }

        return HardwareInfo(
            brand = (Build.BRAND ?: "").replaceFirstChar { it.uppercase(Locale.ROOT) },
            model = Build.MODEL ?: "Android",
            socName = prettySoc,
            detectedProfile = detected,
            isLtpo120Hz = maxHz >= 89f,
            currentMaxHz = maxHz
        )
    }

    fun resolveEffectiveProfile(selected: ChipProfile, hw: HardwareInfo): ChipProfile {
        return if (selected == ChipProfile.AUTO) hw.detectedProfile else selected
    }

    fun getHeaderBadgeLabel(selected: ChipProfile, hw: HardwareInfo): String {
        val effective = resolveEffectiveProfile(selected, hw)
        return when (effective) {
            ChipProfile.DIMENSITY -> "DIMENSITY • 60HZ"
            ChipProfile.SNAPDRAGON -> "SNAPDRAGON • SAFE"
            ChipProfile.STANDARD -> "60HZ • SOFTWARE"
            ChipProfile.AUTO -> "60HZ • AUTO"
        }
    }

    fun getOptimizationTips(selected: ChipProfile, hw: HardwareInfo): String {
        val effective = resolveEffectiveProfile(selected, hw)
        return when (effective) {
            ChipProfile.DIMENSITY -> {
                "Đã kích hoạt khóa 60.0Hz cố định cho màn hình LTPO/120Hz (chống tụt xung MediaTek CorePilot 100→30 FPS).\n\n" +
                    "Trong cài đặt Video của melonDS, hãy bật:\n" +
                    "• 3D Renderer: Software (CPU Cortex-X gánh mượt 60 FPS)\n" +
                    "• Threaded 3D Rendering: Tắt\n" +
                    "• Audio Latency: Medium hoặc High"
            }
            ChipProfile.SNAPDRAGON -> {
                "Đã kích hoạt profile bảo vệ Adreno + khóa 60.0Hz ổn định.\n\n" +
                    "Trong cài đặt Video của melonDS, hãy bật:\n" +
                    "• 3D Renderer: Software (triệt tiêu lỗi crash văng game trên driver Adreno tại Gym 1 & trận 3D)\n" +
                    "• Threaded 3D Rendering: Tắt\n" +
                    "• Audio Sync / Resampling: Bật"
            }
            else -> {
                "Đã kích hoạt khóa 60.0Hz chống giật khung hình.\n\n" +
                    "Trong cài đặt Video của melonDS, hãy bật:\n" +
                    "• 3D Renderer: Software\n" +
                    "• Threaded 3D Rendering: Tắt\n" +
                    "• Audio Latency: Medium"
            }
        }
    }

    /**
     * Locks display refresh rate to 60.0Hz on LTPO/120Hz panels (Dimensity ColorOS / HyperOS / OneUI)
     * to prevent VRR oscillation (120Hz <-> 30Hz) and audio buffer underrun.
     */
    fun apply60HzFramePacing(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val disp = getDisplayCompat(activity)
                if (disp != null) {
                    val curMode = disp.mode
                    val modes = disp.supportedModes
                    val best60HzMode = modes
                        .filter {
                            it.physicalWidth == curMode.physicalWidth &&
                                it.physicalHeight == curMode.physicalHeight &&
                                it.refreshRate in 58.5f..61.5f
                        }
                        .minByOrNull { kotlin.math.abs(it.refreshRate - 60.0f) }
                        ?: modes.filter { it.refreshRate in 58.5f..61.5f }
                            .minByOrNull { kotlin.math.abs(it.refreshRate - 60.0f) }

                    if (best60HzMode != null) {
                        val params: WindowManager.LayoutParams = activity.window.attributes
                        params.preferredDisplayModeId = best60HzMode.modeId
                        activity.window.attributes = params
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val compat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                } else {
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                }
                activity.window.decorView.post {
                    try {
                        activity.window.decorView.holder?.surface?.setFrameRate(60.0f, compat)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "apply60HzFramePacing warning: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayCompat(activity: Activity): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }
    }

    private fun readCpuInfoSummary(): String {
        return try {
            val f = File("/proc/cpuinfo")
            if (!f.exists()) return ""
            f.readLines()
                .filter {
                    it.startsWith("Hardware", ignoreCase = true) ||
                        it.startsWith("Processor", ignoreCase = true) ||
                        it.startsWith("CPU implementer", ignoreCase = true) ||
                        it.startsWith("CPU part", ignoreCase = true)
                }
                .take(12)
                .joinToString(" ")
        } catch (_: Exception) {
            ""
        }
    }
}
