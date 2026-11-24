package com.legendblack2.app

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var btnPlay: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvChipHeaderBadge: TextView

    // Overlays
    private lateinit var modalBackdrop: FrameLayout
    private lateinit var cardMissingRom: LinearLayout
    private lateinit var cardSettings: LinearLayout
    private lateinit var cardDownload: LinearLayout
    private lateinit var cardChipProfile: LinearLayout
    private lateinit var cardConfirm: LinearLayout

    // Missing ROM modal views
    private lateinit var btnMissingCloseX: TextView
    private lateinit var btnMissingDownload: Button
    private lateinit var btnMissingPickFile: Button
    private lateinit var btnMissingOpenFolder: Button
    private lateinit var btnMissingClose: Button

    // Settings modal views
    private lateinit var btnSettingsCloseX: TextView
    private lateinit var tvSettingsSubtitle: TextView
    private lateinit var btnSetOpenFolder: Button
    private lateinit var btnSetSyncSave: Button
    private lateinit var btnSetPickSave: Button
    private lateinit var btnSetChipProfile: Button
    private lateinit var btnSetRedownload: Button
    private lateinit var btnSetOpenServer: Button

    // Download modal views
    private lateinit var tvDlTitle: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var tvDlStats: TextView
    private lateinit var btnDlCancel: Button

    // Chip profile modal views
    private lateinit var btnChipCloseX: TextView
    private lateinit var tvChipInfoDetail: TextView
    private lateinit var btnProfileAuto: Button
    private lateinit var btnProfileDimensity: Button
    private lateinit var btnProfileSnapdragon: Button
    private lateinit var tvChipMelondsTips: TextView
    private lateinit var btnChipClose: Button

    // Confirm / Alert modal views
    private lateinit var tvConfirmTitle: TextView
    private lateinit var tvConfirmBody: TextView
    private lateinit var btnConfirmPrimary: Button
    private lateinit var btnConfirmSecondary: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dlCancelled = AtomicBoolean(false)
    private var isOverlayOpen = false
    private var hasCheckedStartupUpdate = false
    private var launchedGameInSession = false

    private lateinit var hwInfo: HardwareInfo
    private var selectedChipProfile = ChipProfile.AUTO
    private var currentVersion = ""
    private var currentRomFileName = ""
    private var optimizationTipShown = false

    private data class SaveInfo(val name: String, val sizeKb: Long)
    private var currentSaveInfo: SaveInfo? = null

    private val baseDir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PokemonLegendBlack2")
    }
    private val romDir: File by lazy { File(baseDir, "rom") }
    private val backupDir: File by lazy { File(baseDir, "backups") }
    private val oldSaveDir: File by lazy { File(baseDir, "saves") }
    private val versionFile: File by lazy { File(baseDir, "version.json") }

    private val pickRomLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRomFromUri(uri)
    }

    private val pickSaveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSaveFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        applyResponsiveModalWidths()

        hwInfo = ChipOptimizer.detectHardware(this)
        initEnvironment()
        loadLocalVersion()
        ChipOptimizer.apply60HzFramePacing(this)
        updateChipHeaderBadge()

        setupListeners()
        refreshAllStatus()
        ensureStoragePermissions()

        mainHandler.postDelayed({
            triggerStartupUpdateCheck()
        }, 650L)
    }

    override fun onResume() {
        super.onResume()
        ChipOptimizer.apply60HzFramePacing(this)
        if (launchedGameInSession) {
            backupActiveSaveSilent()
            launchedGameInSession = false
        }
        if (!isOverlayOpen) {
            refreshAllStatus()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(R.layout.activity_main)
        bindViews()
        applyWindowInsets()
        applyResponsiveModalWidths()
        updateChipHeaderBadge()
        setupListeners()
        refreshAllStatus()
        closeOverlay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isOverlayOpen) {
            if (cardDownload.visibility == View.VISIBLE) {
                return
            }
            closeOverlay()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun bindViews() {
        btnPlay = findViewById(R.id.btnPlay)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatus = findViewById(R.id.tvStatus)
        tvChipHeaderBadge = findViewById(R.id.tvChipHeaderBadge)

        modalBackdrop = findViewById(R.id.modalBackdrop)
        cardMissingRom = findViewById(R.id.cardMissingRom)
        cardSettings = findViewById(R.id.cardSettings)
        cardDownload = findViewById(R.id.cardDownload)
        cardChipProfile = findViewById(R.id.cardChipProfile)
        cardConfirm = findViewById(R.id.cardConfirm)

        btnMissingCloseX = findViewById(R.id.btnMissingCloseX)
        btnMissingDownload = findViewById(R.id.btnMissingDownload)
        btnMissingPickFile = findViewById(R.id.btnMissingPickFile)
        btnMissingOpenFolder = findViewById(R.id.btnMissingOpenFolder)
        btnMissingClose = findViewById(R.id.btnMissingClose)

        btnSettingsCloseX = findViewById(R.id.btnSettingsCloseX)
        tvSettingsSubtitle = findViewById(R.id.tvSettingsSubtitle)
        btnSetOpenFolder = findViewById(R.id.btnSetOpenFolder)
        btnSetSyncSave = findViewById(R.id.btnSetSyncSave)
        btnSetPickSave = findViewById(R.id.btnSetPickSave)
        btnSetChipProfile = findViewById(R.id.btnSetChipProfile)
        btnSetRedownload = findViewById(R.id.btnSetRedownload)
        btnSetOpenServer = findViewById(R.id.btnSetOpenServer)

        tvDlTitle = findViewById(R.id.tvDlTitle)
        pbDownload = findViewById(R.id.pbDownload)
        tvDlStats = findViewById(R.id.tvDlStats)
        btnDlCancel = findViewById(R.id.btnDlCancel)

        btnChipCloseX = findViewById(R.id.btnChipCloseX)
        tvChipInfoDetail = findViewById(R.id.tvChipInfoDetail)
        btnProfileAuto = findViewById(R.id.btnProfileAuto)
        btnProfileDimensity = findViewById(R.id.btnProfileDimensity)
        btnProfileSnapdragon = findViewById(R.id.btnProfileSnapdragon)
        tvChipMelondsTips = findViewById(R.id.tvChipMelondsTips)
        btnChipClose = findViewById(R.id.btnChipClose)

        tvConfirmTitle = findViewById(R.id.tvConfirmTitle)
        tvConfirmBody = findViewById(R.id.tvConfirmBody)
        btnConfirmPrimary = findViewById(R.id.btnConfirmPrimary)
        btnConfirmSecondary = findViewById(R.id.btnConfirmSecondary)
    }

    private fun applyWindowInsets() {
        val topGlassBar = findViewById<View>(R.id.topGlassBar)
        val bottomForeground = findViewById<View>(R.id.bottomForeground)
        val density = resources.displayMetrics.density

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padTopExtra = (10 * density).toInt()
            val padBottomExtra = (24 * density).toInt()
            val padSide = topGlassBar.paddingLeft
            topGlassBar.setPadding(padSide, sysBars.top + padTopExtra, padSide, (10 * density).toInt())

            val bLeft = bottomForeground.paddingLeft
            val bRight = bottomForeground.paddingRight
            val bTop = bottomForeground.paddingTop
            bottomForeground.setPadding(bLeft, bTop, bRight, sysBars.bottom + padBottomExtra)
            insets
        }
    }

    private fun applyResponsiveModalWidths() {
        val dm = resources.displayMetrics
        val maxCardPx = (460 * dm.density).toInt()
        val marginPx = (40 * dm.density).toInt()
        val targetWidth = min(dm.widthPixels - marginPx, maxCardPx).coerceAtLeast((280 * dm.density).toInt())

        listOf(cardMissingRom, cardSettings, cardDownload, cardChipProfile, cardConfirm).forEach { card ->
            val lp = card.layoutParams as FrameLayout.LayoutParams
            lp.width = targetWidth
            card.layoutParams = lp
        }
    }

    private fun setupListeners() {
        btnPlay.setOnClickListener { onClickPlay() }
        btnUpdate.setOnClickListener { checkAndUpdate() }
        btnSettings.setOnClickListener { showSettingsOverlay() }
        tvChipHeaderBadge.setOnClickListener { showChipProfileOverlay() }

        modalBackdrop.setOnClickListener {
            if (cardDownload.visibility != View.VISIBLE) {
                closeOverlay()
            }
        }

        // Missing ROM overlay
        btnMissingCloseX.setOnClickListener { closeOverlay() }
        btnMissingClose.setOnClickListener { closeOverlay() }
        btnMissingDownload.setOnClickListener { triggerFullDownload() }
        btnMissingPickFile.setOnClickListener { pickRomLauncher.launch(arrayOf("*/*")) }
        btnMissingOpenFolder.setOnClickListener { openGameFolder() }

        // Settings overlay
        btnSettingsCloseX.setOnClickListener { closeOverlay() }
        btnSetOpenFolder.setOnClickListener { openGameFolder() }
        btnSetSyncSave.setOnClickListener { onClickSyncSave() }
        btnSetPickSave.setOnClickListener { pickSaveLauncher.launch(arrayOf("*/*")) }
        btnSetChipProfile.setOnClickListener { showChipProfileOverlay() }
        btnSetRedownload.setOnClickListener { triggerFullDownload() }
        btnSetOpenServer.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseClient.RELEASES_WEB_PAGE)))
            } catch (_: Exception) {
                Toast.makeText(this, "Không thể mở trình duyệt", Toast.LENGTH_SHORT).show()
            }
        }

        // Chip profile overlay
        btnChipCloseX.setOnClickListener { closeOverlay() }
        btnChipClose.setOnClickListener { closeOverlay() }
        btnProfileAuto.setOnClickListener { selectChipProfile(ChipProfile.AUTO) }
        btnProfileDimensity.setOnClickListener { selectChipProfile(ChipProfile.DIMENSITY) }
        btnProfileSnapdragon.setOnClickListener { selectChipProfile(ChipProfile.SNAPDRAGON) }
    }

    private fun initEnvironment() {
        try {
            listOf(baseDir, romDir, backupDir).forEach { dir ->
                if (!dir.exists()) dir.mkdirs()
            }
        } catch (_: Exception) {
        }
    }

    private fun extractVersionFromRomDir(): Pair<String, String>? {
        if (!romDir.exists()) return null
        val ndsFiles = romDir.listFiles()?.filter {
            it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".nds") &&
                !it.name.endsWith(".part") && !it.name.endsWith(".tmp")
        }?.sortedByDescending { it.name } ?: return null

        if (ndsFiles.isEmpty()) return null
        val picked = ndsFiles.first().name
        val match = Regex("""Beta\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(picked)
        val ver = if (match != null) "Beta ${match.groupValues[1]}" else ""
        return picked to ver
    }

    private fun loadLocalVersion() {
        var loadedVer = ""
        var loadedRom = ""
        if (versionFile.exists()) {
            try {
                val json = JSONObject(versionFile.readText(Charsets.UTF_8))
                loadedVer = json.optString("version", "").trim()
                loadedRom = json.optString("rom_file", "").trim()
                val profileId = json.optString("chip_profile", "auto")
                selectedChipProfile = ChipProfile.values().firstOrNull { it.id == profileId } ?: ChipProfile.AUTO
                optimizationTipShown = json.optBoolean("optimization_tip_shown", false)
            } catch (_: Exception) {
            }
        }

        val detected = extractVersionFromRomDir()
        if (detected != null) {
            currentRomFileName = if (loadedRom.isNotEmpty() && File(romDir, loadedRom).exists()) loadedRom else detected.first
            currentVersion = loadedVer.ifEmpty { detected.second }
        } else {
            currentRomFileName = ""
            currentVersion = ""
        }
    }

    private fun saveLocalVersion() {
        try {
            initEnvironment()
            val json = JSONObject().apply {
                put("version", currentVersion)
                put("rom_file", currentRomFileName)
                put("chip_profile", selectedChipProfile.id)
                put("optimization_tip_shown", optimizationTipShown)
                put("last_updated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            versionFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun getCurrentRomInfo(): Pair<File?, String> {
        if (!romDir.exists()) return null to ""

        if (currentRomFileName.isNotEmpty()) {
            val f = File(romDir, currentRomFileName)
            if (f.exists()) {
                return f to currentVersion
            }
        }

        val detected = extractVersionFromRomDir() ?: return null to ""
        return File(romDir, detected.first) to detected.second
    }

    private fun findNewestSave(): File? {
        val candidates = mutableListOf<File>()
        listOf(romDir, oldSaveDir, baseDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter {
                    it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".sav")
                }?.let { candidates.addAll(it) }
            }
        }
        return candidates.maxByOrNull { it.lastModified() }
    }

    private fun detectAndSyncSave(romFileOverride: File? = null): File? {
        if (!romDir.exists()) {
            currentSaveInfo = null
            return null
        }

        val romFile = romFileOverride ?: getCurrentRomInfo().first
        val targetSav = if (romFile != null) {
            File(romDir, "${romFile.nameWithoutExtension}.sav")
        } else {
            File(romDir, "Pokemon Legend Black 2.sav")
        }

        // Migrate any leftover .sav from legacy saves/ folder into rom/
        if (oldSaveDir.exists()) {
            oldSaveDir.listFiles()?.filter { it.name.lowercase(Locale.ROOT).endsWith(".sav") }?.forEach { oldSav ->
                val dest = File(romDir, oldSav.name)
                if (!dest.exists()) {
                    try {
                        oldSav.copyTo(dest, overwrite = false)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val romSavFiles = romDir.listFiles()?.filter {
            it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".sav")
        } ?: emptyList()

        if (romSavFiles.isEmpty()) {
            val newestLegacy = findNewestSave()
            if (newestLegacy != null) {
                try {
                    newestLegacy.copyTo(targetSav, overwrite = true)
                } catch (_: Exception) {
                }
            } else {
                currentSaveInfo = null
                return null
            }
        } else if (!targetSav.exists()) {
            val newest = findNewestSave()
            if (newest != null && newest.absolutePath != targetSav.absolutePath) {
                try {
                    newest.copyTo(targetSav, overwrite = true)
                } catch (_: Exception) {
                }
            }
        }

        if (targetSav.exists()) {
            currentSaveInfo = SaveInfo(targetSav.name, targetSav.length() / 1024L)
            return targetSav
        }

        currentSaveInfo = null
        return null
    }

    private fun refreshAllStatus() {
        loadLocalVersion()
        val (romFile, ver) = getCurrentRomInfo()
        detectAndSyncSave(romFile)

        val hasRom = romFile != null && romFile.exists()
        val romTxt = if (hasRom) "ROM: Sẵn sàng" else "ROM: Chưa có"
        val saveTxt = if (currentSaveInfo != null) "Save: Sẵn sàng" else "Save: Chưa có"
        val verDisplay = ver.ifEmpty { "Chưa có ROM" }

        tvStatus.text = "Phiên bản: $verDisplay   •   $romTxt   •   $saveTxt"
        tvStatus.setTextColor(
            Color.parseColor(if (hasRom) "#94A3B8" else "#F87171")
        )
    }

    private fun updateChipHeaderBadge() {
        tvChipHeaderBadge.text = ChipOptimizer.getHeaderBadgeLabel(selectedChipProfile, hwInfo)
    }

    private fun closeOverlay() {
        modalBackdrop.visibility = View.GONE
        cardMissingRom.visibility = View.GONE
        cardSettings.visibility = View.GONE
        cardDownload.visibility = View.GONE
        cardChipProfile.visibility = View.GONE
        cardConfirm.visibility = View.GONE
        isOverlayOpen = false
        refreshAllStatus()
    }

    private fun openModalCard(targetCard: View) {
        cardMissingRom.visibility = View.GONE
        cardSettings.visibility = View.GONE
        cardDownload.visibility = View.GONE
        cardChipProfile.visibility = View.GONE
        cardConfirm.visibility = View.GONE

        targetCard.visibility = View.VISIBLE
        modalBackdrop.visibility = View.VISIBLE
        isOverlayOpen = true
    }

    private fun showMissingRomOverlay() {
        openModalCard(cardMissingRom)
    }

    private fun showSettingsOverlay() {
        val (_, ver) = getCurrentRomInfo()
        val verDisplay = ver.ifEmpty { "Chưa có ROM" }
        val saveTxt = currentSaveInfo?.let { "${it.name} (${it.sizeKb} KB)" }
            ?: "Chưa có file save trong thư mục Game"

        tvSettingsSubtitle.text = "Phiên bản: $verDisplay   •   Save: $saveTxt"
        val profileShort = ChipOptimizer.getHeaderBadgeLabel(selectedChipProfile, hwInfo)
        btnSetChipProfile.text = "TỐI ƯU CHIP & GIẢ LẬP ($profileShort)"
        openModalCard(cardSettings)
    }

    private fun showChipProfileOverlay() {
        val hzStr = if (hwInfo.isLtpo120Hz) "120Hz LTPO → Khóa cứng 60.0Hz" else "60Hz"
        tvChipInfoDetail.text =
            "Thiết bị: ${hwInfo.brand} ${hwInfo.model}\n" +
                "Vi xử lý: ${hwInfo.socName}\n" +
                "Màn hình: $hzStr"

        refreshChipProfileButtons()
        tvChipMelondsTips.text = ChipOptimizer.getOptimizationTips(selectedChipProfile, hwInfo)
        openModalCard(cardChipProfile)
    }

    private fun selectChipProfile(profile: ChipProfile) {
        selectedChipProfile = profile
        saveLocalVersion()
        ChipOptimizer.apply60HzFramePacing(this)
        updateChipHeaderBadge()
        refreshChipProfileButtons()
        tvChipMelondsTips.text = ChipOptimizer.getOptimizationTips(selectedChipProfile, hwInfo)
        Toast.makeText(this, "Đã áp dụng profile: ${profile.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshChipProfileButtons() {
        val pairs = listOf(
            btnProfileAuto to ChipProfile.AUTO,
            btnProfileDimensity to ChipProfile.DIMENSITY,
            btnProfileSnapdragon to ChipProfile.SNAPDRAGON
        )
        pairs.forEach { (btn, prof) ->
            val active = selectedChipProfile == prof
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_white else R.drawable.bg_pill_dark)
            btn.setTextColor(if (active) Color.BLACK else Color.WHITE)
        }
    }

    private fun showDarkDialog(
        title: String,
        body: String,
        primaryText: String,
        secondaryText: String? = "ĐÓNG",
        titleColorHex: String = "#FFFFFF",
        onPrimary: (() -> Unit)? = null
    ) {
        tvConfirmTitle.text = title
        tvConfirmTitle.setTextColor(Color.parseColor(titleColorHex))
        tvConfirmBody.text = body
        btnConfirmPrimary.text = primaryText
        btnConfirmPrimary.setOnClickListener {
            closeOverlay()
            onPrimary?.invoke()
        }
        if (secondaryText != null) {
            btnConfirmSecondary.visibility = View.VISIBLE
            btnConfirmSecondary.text = secondaryText
            btnConfirmSecondary.setOnClickListener { closeOverlay() }
        } else {
            btnConfirmSecondary.visibility = View.GONE
        }
        openModalCard(cardConfirm)
    }

    private fun onClickPlay() {
        if (isOverlayOpen) return
        val (romFile, _) = getCurrentRomInfo()
        if (romFile == null || !romFile.exists()) {
            showMissingRomOverlay()
            return
        }

        detectAndSyncSave(romFile)
        val emuPkg = findInstalledMelonDsPackage()
        if (emuPkg == null) {
            showDarkDialog(
                title = "CHƯA CÀI GIẢ LẬP MELONDS",
                body = "Thiết bị chưa cài đặt melonDS.\n\nKhuyên dùng melonDS để tối ưu ổn định 60 FPS theo chip và hỗ trợ đầy đủ tính năng Online (Battle / Trade).",
                primaryText = "CÀI MELONDS TRÊN GOOGLE PLAY",
                secondaryText = "ĐÓNG",
                titleColorHex = "#FBBF24"
            ) {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=me.magnum.melonds")
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Vui lòng tìm melonDS trên Google Play", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        if (!optimizationTipShown) {
            optimizationTipShown = true
            saveLocalVersion()
            val chipTips = ChipOptimizer.getOptimizationTips(selectedChipProfile, hwInfo)
            showDarkDialog(
                title = "TỐI ƯU CHIP TRƯỚC KHI CHƠI",
                body = "$chipTips\n\n(Bạn có thể mở lại mục này trong Cài đặt → Tối ưu chip)",
                primaryText = "VÀO GAME NGAY",
                secondaryText = "ĐỂ SAU",
                titleColorHex = "#38BDF8"
            ) {
                launchMelonDsWithRom(romFile, emuPkg)
            }
            return
        }

        launchMelonDsWithRom(romFile, emuPkg)
    }

    private fun findInstalledMelonDsPackage(): String? {
        val candidates = listOf("me.magnum.melonds", "me.magnum.melonds.nightly")
        for (pkg in candidates) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(pkg, 0)
                }
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun launchMelonDsWithRom(romFile: File, emuPkg: String) {
        ChipOptimizer.apply60HzFramePacing(this)
        backupActiveSaveSilent()
        launchedGameInSession = true

        val contentUri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", romFile)
        } catch (_: Exception) {
            null
        }

        // Primary attempt: launch melonDS directly with FileProvider URI
        if (contentUri != null) {
            try {
                val launchIntent = Intent("me.magnum.melonds.LAUNCH_ROM").apply {
                    `package` = emuPkg
                    data = contentUri
                    putExtra("PATH", romFile.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(launchIntent)
                return
            } catch (_: Exception) {
            }

            try {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    `package` = emuPkg
                    setDataAndType(contentUri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(viewIntent)
                return
            } catch (_: Exception) {
            }
        }

        // Fallback: open melonDS main activity cleanly & notify user where ROM resides
        try {
            val appIntent = packageManager.getLaunchIntentForPackage(emuPkg)
            if (appIntent != null) {
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Toast.makeText(
                    this,
                    "Mở thư mục Download/PokemonLegendBlack2/rom trong melonDS để vào game!",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(appIntent)
                return
            }
        } catch (_: Exception) {
        }

        Toast.makeText(this, "Không thể khởi chạy melonDS!", Toast.LENGTH_LONG).show()
    }

    private fun isEmulatorRunning(): Boolean {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            @Suppress("DEPRECATION")
            val runningProcs = am?.runningAppProcesses ?: emptyList()
            for (proc in runningProcs) {
                val name = proc.processName.lowercase(Locale.ROOT)
                if (name.contains("melonds") || name.contains("desmume")) {
                    return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun showEmulatorRunningWarning() {
        showDarkDialog(
            title = "TRÒ CHƠI ĐANG CHẠY",
            body = "Trò chơi hoặc giả lập melonDS đang mở!\n\nVui lòng lưu game (In-game Save) và đóng hẳn giả lập trước khi tiến hành cập nhật để tránh mất dữ liệu save.",
            primaryText = "ĐÃ HIỂU",
            secondaryText = null,
            titleColorHex = "#FBBF24"
        )
    }

    private fun triggerStartupUpdateCheck() {
        if (hasCheckedStartupUpdate) return
        hasCheckedStartupUpdate = true

        // Không kiểm tra cập nhật tự động khi chưa có ROM
        val (romFile, localVer) = getCurrentRomInfo()
        if (romFile == null || !romFile.exists()) return

        thread {
            try {
                val remote = ReleaseClient.fetchLatestRelease(timeoutMs = 7000)
                if (remote.version.isNotEmpty() && localVer.isNotEmpty() && remote.version != localVer) {
                    mainHandler.post {
                        if (isFinishing || isDestroyed || isOverlayOpen || isEmulatorRunning()) return@post
                        showDarkDialog(
                            title = "PHÁT HIỆN BẢN CẬP NHẬT MỚI!",
                            body = "Đã có phiên bản mới: ${remote.version}\n" +
                                "(Phiên bản hiện tại: $localVer)\n\n" +
                                "Nội dung cập nhật:\n${remote.changelogVi}\n\n" +
                                "Nâng cấp ngay? (Tiến trình save được tự động chuyển sang bản mới 100%)",
                            primaryText = "NÂNG CẤP NGAY",
                            secondaryText = "ĐỂ SAU",
                            titleColorHex = "#38BDF8"
                        ) {
                            startUnifiedDownload(remote)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkAndUpdate() {
        if (isOverlayOpen) return
        if (isEmulatorRunning()) {
            showEmulatorRunningWarning()
            return
        }

        tvStatus.text = "Đang kiểm tra bản cập nhật từ máy chủ..."
        tvStatus.setTextColor(Color.parseColor("#FBBF24"))

        thread {
            try {
                val remote = ReleaseClient.fetchLatestRelease(timeoutMs = 10000)
                val (localRom, localVer) = getCurrentRomInfo()

                mainHandler.post {
                    if (localRom != null && localRom.exists() && localVer == remote.version) {
                        refreshAllStatus()
                        showDarkDialog(
                            title = "BẢN MỚI NHẤT",
                            body = "Bạn đang chơi phiên bản mới nhất ($localVer)!\nKhông có bản cập nhật mới.",
                            primaryText = "ĐÓNG",
                            secondaryText = null,
                            titleColorHex = "#34D399"
                        )
                        return@post
                    }

                    val curDisplay = localVer.ifEmpty { "Chưa có" }
                    showDarkDialog(
                        title = "BẢN CẬP NHẬT MỚI!",
                        body = "Phiên bản hiện tại: $curDisplay\n" +
                            "Phiên bản mới: ${remote.version}\n\n" +
                            "Nội dung cập nhật:\n${remote.changelogVi}\n\n" +
                            "Nâng cấp ngay? (Tiến trình save được tự động chuyển sang bản mới 100%)",
                        primaryText = "NÂNG CẤP NGAY",
                        secondaryText = "BỎ QUA",
                        titleColorHex = "#38BDF8"
                    ) {
                        startUnifiedDownload(remote)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    refreshAllStatus()
                    showDarkDialog(
                        title = "LỖI KẾT NỐI",
                        body = "Không thể kết nối đến máy chủ cập nhật:\n${e.localizedMessage}",
                        primaryText = "ĐÓNG",
                        secondaryText = null,
                        titleColorHex = "#EF4444"
                    )
                }
            }
        }
    }

    private fun triggerFullDownload() {
        closeOverlay()
        if (isEmulatorRunning()) {
            showEmulatorRunningWarning()
            return
        }

        tvStatus.text = "Đang lấy thông tin bản mới nhất từ server..."
        tvStatus.setTextColor(Color.parseColor("#FBBF24"))

        thread {
            try {
                val remote = ReleaseClient.fetchLatestRelease(timeoutMs = 12000)
                mainHandler.post {
                    startUnifiedDownload(remote)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    refreshAllStatus()
                    showDarkDialog(
                        title = "LỖI KẾT NỐI",
                        body = "Không thể lấy thông tin tải từ máy chủ:\n${e.localizedMessage}",
                        primaryText = "ĐÓNG",
                        secondaryText = null,
                        titleColorHex = "#EF4444"
                    )
                }
            }
        }
    }

    private fun startUnifiedDownload(remote: RemoteReleaseInfo) {
        if (isEmulatorRunning()) {
            showEmulatorRunningWarning()
            return
        }

        dlCancelled.set(false)
        tvDlTitle.text = "ĐANG TẢI ROM ${remote.version}..."
        pbDownload.progress = 0
        tvDlStats.text = "Đang kết nối đến máy chủ cập nhật..."
        btnDlCancel.setOnClickListener {
            dlCancelled.set(true)
            closeOverlay()
        }
        openModalCard(cardDownload)

        val targetRomName = "Pokemon Legend Black 2 (${remote.version}).nds"
        val targetRomFile = File(romDir, targetRomName)
        val tempPartFile = File(romDir, "$targetRomName.part")

        thread {
            try {
                initEnvironment()
                if (tempPartFile.exists()) tempPartFile.delete()

                ReleaseClient.downloadFile(
                    urlStr = remote.downloadUrl,
                    destPartFile = tempPartFile,
                    expectedSize = remote.fileSize,
                    cancelled = dlCancelled
                ) { downloaded, total, pct, speedMbps ->
                    mainHandler.post {
                        if (isOverlayOpen && cardDownload.visibility == View.VISIBLE) {
                            pbDownload.progress = pct
                            tvDlStats.text = String.format(
                                Locale.US,
                                "Đã tải: %.1f MB / %.1f MB (%d%%)   •   %.2f MB/s",
                                downloaded / (1024.0 * 1024.0),
                                total / (1024.0 * 1024.0),
                                pct,
                                speedMbps
                            )
                        }
                    }
                }

                if (dlCancelled.get()) {
                    if (tempPartFile.exists()) tempPartFile.delete()
                    return@thread
                }

                // STEP A: Backup & Migrate existing save seamlessly
                val existingSave = findNewestSave()
                if (existingSave != null && existingSave.exists()) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val bakFile = File(backupDir, "save_pre_${remote.version}_$ts.bak")
                    try {
                        existingSave.copyTo(bakFile, overwrite = true)
                    } catch (_: Exception) {
                    }

                    val newSavFile = File(romDir, "Pokemon Legend Black 2 (${remote.version}).sav")
                    if (existingSave.absolutePath != newSavFile.absolutePath) {
                        try {
                            existingSave.copyTo(newSavFile, overwrite = true)
                        } catch (_: Exception) {
                        }
                    }
                }

                // STEP B: Finalize ROM & Remove obsolete .nds files
                romDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".nds") &&
                        f.name != targetRomName && !f.name.endsWith(".part")
                    ) {
                        try {
                            f.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (targetRomFile.exists()) targetRomFile.delete()
                if (!tempPartFile.renameTo(targetRomFile)) {
                    tempPartFile.copyTo(targetRomFile, overwrite = true)
                    tempPartFile.delete()
                }

                // STEP C: Save version.json
                currentVersion = remote.version
                currentRomFileName = targetRomName
                saveLocalVersion()

                mainHandler.post {
                    closeOverlay()
                    refreshAllStatus()
                    showDarkDialog(
                        title = "THÀNH CÔNG",
                        body = "Đã cập nhật lên ${remote.version} thành công!\n" +
                            "Save của bạn đã được chuyển sang bản mới và bảo toàn 100%.\n\n" +
                            "Bạn có thể bấm [VÀO GAME] ngay.",
                        primaryText = "VÀO GAME NGAY",
                        secondaryText = "ĐÓNG",
                        titleColorHex = "#34D399"
                    ) {
                        onClickPlay()
                    }
                }
            } catch (e: Exception) {
                if (tempPartFile.exists()) tempPartFile.delete()
                mainHandler.post {
                    closeOverlay()
                    refreshAllStatus()
                    showDarkDialog(
                        title = "LỖI TẢI ROM",
                        body = "Không thể tải file ROM từ máy chủ:\n${e.localizedMessage}",
                        primaryText = "ĐÓNG",
                        secondaryText = null,
                        titleColorHex = "#EF4444"
                    )
                }
            }
        }
    }

    private fun openGameFolder() {
        initEnvironment()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ROM Folder", romDir.absolutePath))

        try {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/PokemonLegendBlack2/rom"
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Thư mục game: Download/PokemonLegendBlack2/rom", Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) {
        }

        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(romDir.absolutePath), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Đã sao chép đường dẫn thư mục Game:\n${romDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onClickSyncSave() {
        val synced = detectAndSyncSave()
        refreshAllStatus()
        if (synced != null && currentSaveInfo != null) {
            showDarkDialog(
                title = "ĐỒNG BỘ SAVE THÀNH CÔNG",
                body = "Đã quét và chuẩn hóa file Save khớp với phiên bản hiện tại!\n\n" +
                    "File save: ${currentSaveInfo!!.name}\n" +
                    "Dung lượng: ${currentSaveInfo!!.sizeKb} KB",
                primaryText = "VỀ CÀI ĐẶT",
                secondaryText = null,
                titleColorHex = "#34D399"
            ) {
                showSettingsOverlay()
            }
        } else {
            showDarkDialog(
                title = "CHƯA CÓ FILE SAVE",
                body = "Chưa phát hiện file save (.sav) nào trong thư mục Game.\n\n" +
                    "Bạn có thể bấm [NHẬP FILE SAVE] hoặc chép file .sav vào thư mục Download/PokemonLegendBlack2/rom/ rồi quét lại.",
                primaryText = "NHẬP FILE SAVE",
                secondaryText = "VỀ CÀI ĐẶT",
                titleColorHex = "#FBBF24"
            ) {
                pickSaveLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    private fun importRomFromUri(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "Pokemon Legend Black 2.nds"
        if (!fileName.lowercase(Locale.ROOT).endsWith(".nds")) {
            Toast.makeText(this, "Vui lòng chọn đúng file ROM đuôi .nds!", Toast.LENGTH_LONG).show()
            return
        }
        try {
            initEnvironment()
            val dest = File(romDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            val match = Regex("""Beta\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(fileName)
            currentVersion = if (match != null) "Beta ${match.groupValues[1]}" else currentVersion
            currentRomFileName = fileName
            saveLocalVersion()
            closeOverlay()
            refreshAllStatus()
            Toast.makeText(this, "Đã nạp ROM: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi nhập file ROM: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSaveFromUri(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "imported.sav"
        val lower = fileName.lowercase(Locale.ROOT)
        if (!lower.endsWith(".sav") && !lower.endsWith(".dsv")) {
            Toast.makeText(this, "Vui lòng chọn file save đuôi .sav hoặc .dsv!", Toast.LENGTH_LONG).show()
            return
        }
        try {
            initEnvironment()
            var bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Không thể đọc file save")

            // If DeSmuME .dsv (512KB + footer), extract clean 512KB raw battery save for melonDS
            if (lower.endsWith(".dsv") && bytes.size > 524288) {
                bytes = bytes.copyOf(524288)
            }

            val (romFile, _) = getCurrentRomInfo()
            val targetName = if (romFile != null) "${romFile.nameWithoutExtension}.sav" else "Pokemon Legend Black 2.sav"
            val dest = File(romDir, targetName)
            if (dest.exists()) {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                dest.copyTo(File(backupDir, "save_pre_import_$ts.bak"), overwrite = true)
            }
            dest.writeBytes(bytes)
            detectAndSyncSave(romFile)
            refreshAllStatus()
            Toast.makeText(this, "Đã nhập và chuẩn hóa save: $targetName", Toast.LENGTH_LONG).show()
            showSettingsOverlay()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi nhập file save: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun backupActiveSaveSilent() {
        try {
            val (romFile, ver) = getCurrentRomInfo()
            val sav = detectAndSyncSave(romFile) ?: return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tag = ver.ifEmpty { "game" }
            sav.copyTo(File(backupDir, "save_${tag}_$ts.bak"), overwrite = true)
        } catch (_: Exception) {
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun ensureStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showDarkDialog(
                    title = "CẤP QUYỀN THƯ MỤC GAME",
                    body = "Trên Android 11+, Launcher cần quyền truy cập tệp để đồng bộ trực tiếp file ROM & Save trong thư mục Download/PokemonLegendBlack2/rom với giả lập melonDS.",
                    primaryText = "CẤP QUYỀN NGAY",
                    secondaryText = "ĐỂ SAU",
                    titleColorHex = "#38BDF8"
                ) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
        } else {
            val needWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needWrite) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    101
                )
            }
        }
    }
}
