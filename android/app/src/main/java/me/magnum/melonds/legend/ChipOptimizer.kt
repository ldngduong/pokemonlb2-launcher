package me.magnum.melonds.legend

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import me.magnum.melonds.MelonEmulator
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
    val maxHz: Float
)

object ChipOptimizer {

    private const val PREF_CHIP_PROFILE = "legend_chip_profile"
    private const val PREF_CORE_CONFIG_APPLIED = "legend_chip_core_config_v1"

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
            maxHz = maxHz
        )
    }

    fun getSavedProfile(context: Context): ChipProfile {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val id = prefs.getString(PREF_CHIP_PROFILE, ChipProfile.AUTO.id)
        return ChipProfile.entries.firstOrNull { it.id == id } ?: ChipProfile.AUTO
    }

    fun setSavedProfile(context: Context, profile: ChipProfile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putString(PREF_CHIP_PROFILE, profile.id)
        }
    }

    fun resolveEffectiveProfile(selected: ChipProfile, hw: HardwareInfo): ChipProfile {
        return if (selected == ChipProfile.AUTO) hw.detectedProfile else selected
    }

    fun getHeaderBadgeLabel(selected: ChipProfile, hw: HardwareInfo): String {
        return when (resolveEffectiveProfile(selected, hw)) {
            ChipProfile.DIMENSITY -> "DIMENSITY • 60HZ"
            ChipProfile.SNAPDRAGON -> "SNAPDRAGON • SAFE"
            ChipProfile.STANDARD -> "60HZ • SOFTWARE"
            ChipProfile.AUTO -> "60HZ • AUTO"
        }
    }

    fun getOptimizationSummary(selected: ChipProfile, hw: HardwareInfo): String {
        return when (resolveEffectiveProfile(selected, hw)) {
            ChipProfile.DIMENSITY ->
                "Đã tự động tối ưu nhân giả lập cho MediaTek / Dimensity:\n" +
                    "• Tắt ADPF PerformanceHintSession (chống tụt xung CorePilot 100→30 FPS)\n" +
                    "• Bộ điều tốc C++ Fixed-Step + khóa màn hình 60.0Hz cố định\n" +
                    "• 3D Renderer: Software · Threaded 3D: Tắt · Audio Latency: Medium"
            ChipProfile.SNAPDRAGON ->
                "Đã tự động tối ưu nhân giả lập cho Qualcomm Snapdragon:\n" +
                    "• Ép 3D Renderer = Software & tắt Threaded 3D (chống crash OpenGL Adreno tại Gym 1 & trận 3D)\n" +
                    "• Bộ điều tốc C++ Fixed-Step + khóa màn hình 60.0Hz cố định\n" +
                    "• Audio Latency: Medium"
            else ->
                "Đã tự động tối ưu nhân giả lập chế độ chuẩn:\n" +
                    "• Bộ điều tốc C++ Fixed-Step + khóa màn hình 60.0Hz\n" +
                    "• 3D Renderer: Software · Threaded 3D: Tắt\n" +
                    "• Audio Latency: Medium"
        }
    }

    /**
     * Applies optimal C++ JNI flags, melonDS SharedPreferences core defaults, and 60Hz Window pacing.
     */
    fun applyCoreOptimizations(activity: Activity, forceWritePrefs: Boolean = false) {
        val hw = detectHardware(activity)
        val selected = getSavedProfile(activity)
        val effective = resolveEffectiveProfile(selected, hw)

        // 1. Apply C++ JNI optimization flags directly inside melonDS native core
        val disableAdpf = (effective == ChipProfile.DIMENSITY)
        val smoothPacing = true
        try {
            MelonEmulator.setChipOptimizationFlags(disableAdpf, smoothPacing)
        } catch (_: Throwable) {
        }

        // 2. Apply optimal melonDS core preferences automatically on first run or profile change
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (forceWritePrefs || !prefs.getBoolean(PREF_CORE_CONFIG_APPLIED, false)) {
            prefs.edit {
                putString("video_renderer", "software")
                putBoolean("enable_threaded_rendering", false)
                putString("audio_latency", "medium")
                putBoolean("use_rom_dir", true)
                putBoolean("sustained_performance_mode", effective != ChipProfile.DIMENSITY)
                putBoolean(PREF_CORE_CONFIG_APPLIED, true)
            }
        }

        // 3. Lock display refresh rate to 60.0Hz on LTPO/120Hz panels
        apply60HzDisplayLock(activity)
    }

    fun apply60HzDisplayLock(activity: Activity) {
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
                        @Suppress("DEPRECATION")
                        params.preferredRefreshRate = best60HzMode.refreshRate
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
                val rootGroup = activity.window.decorView as? android.view.ViewGroup
                if (rootGroup != null && rootGroup.findViewWithTag<android.view.SurfaceView>("fps_lock_sv") == null) {
                    val sv = android.view.SurfaceView(activity).apply {
                        tag = "fps_lock_sv"
                        setWillNotDraw(true)
                        holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
                        layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                    }
                    sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            try {
                                holder.surface.setFrameRate(60.0f, compat)
                            } catch (_: Exception) {
                            }
                        }
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                    })
                    rootGroup.addView(sv, 0)
                }
            }
        } catch (_: Exception) {
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
