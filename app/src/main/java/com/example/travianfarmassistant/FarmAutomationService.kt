package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.random.Random

class FarmAutomationService : Service() {
    companion object {
        private var instanceRef: WeakReference<FarmAutomationService>? = null
        private var visibleWebViewRef: WeakReference<WebView>? = null

        fun attachVisibleWebView(view: WebView) {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER attachVisibleWebView")
            visibleWebViewRef = WeakReference(view)
        }

        fun detachVisibleWebView(view: WebView) {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER detachVisibleWebView")
            if (visibleWebViewRef?.get() === view) visibleWebViewRef = null
        }

        fun isRunningFromService(): Boolean {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER isRunningFromService")
            return instanceRef?.get()?.running == true
        }

        fun forwardPageFinished(url: String) {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER forwardPageFinished")
            instanceRef?.get()?.handleVisiblePageFinished(url)
        }

        fun forwardLoginResult(result: String) {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER forwardLoginResult")
            instanceRef?.get()?.handleLoginResultFromVisibleWebView(result)
        }

        fun forwardVillageListResult(result: String) {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER forwardVillageListResult")
            instanceRef?.get()?.handleVillageListResult(result)
        }

        fun onVisibleWebViewDetached() {
            android.util.Log.d("TravianFarmAssistant", "[DEBUG] ENTER onVisibleWebViewDetached")
            instanceRef?.get()?.onVisibleWebViewDetachedInternal()
        }

        const val ACTION_START = "com.example.travianfarmassistant.START"
        const val ACTION_STOP = "com.example.travianfarmassistant.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_MINUTES_MIN = "minutes_min"
        const val EXTRA_MINUTES_MAX = "minutes_max"
        const val EXTRA_RESOURCE_BUILDER = "resource_builder"
        const val EXTRA_FARM_LIST_ENABLED = "farm_list_enabled"
        const val EXTRA_SELECTED_VILLAGES = "selected_villages"
        const val EXTRA_SELECTED_VILLAGES_JSON = "selected_villages_json"
        const val EXTRA_SELECTION_CONFIGURED = "selection_configured"

        private const val CHANNEL_ID = "farm_automation"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "config"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val PASSWORD_KEY_ALIAS = "TravianFarmAssistantPassword"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var running = false
    private var pendingStartAll = false
    private var loginInProgress = false
    private var reloginRequested = false
    private var loginRetryCount = 0
    private var startAllAttempt = 0
    private var raidCountBeforeStartAll = 0
    private var raidVerificationAttempt = 0
    private var farmListBeforeReady = 0
    private var farmListProgressObserved = false
    private var farmListLastState = ""
    private var farmListStableChecks = 0
    private var fallbackFarmListMode = false
    private var consentAttempt = 0
    private var server = ""
    private var username = ""
    private var password = ""
    private var minMinutes = 1L
    private var maxMinutes = 1L
    private var nextAt = 0L
    private val delayedVillageRefreshRunnable: Runnable = Runnable {
        if (!running) return@Runnable
        if (pendingStartAll || builderInProgress || loginInProgress || reloginRequested) {
            // Jangan mengganggu Farm List / Resource Builder yang sedang memakai WebView.
            // Coba lagi setelah fase aktif selesai.
            logEvent("AUTO REFRESH VILLAGE: WebView sedang dipakai; refresh ditunda 10 detik")
            handler.postDelayed(delayedVillageRefreshRunnable, 10_000L)
            return@Runnable
        }
        val requested = MainActivity.requestVillageRefreshFromService()
        if (requested) {
            logEvent("AUTO REFRESH VILLAGE: dijalankan 1 menit setelah Next Run")
        } else {
            logEvent("AUTO REFRESH VILLAGE: MainActivity belum aktif; refresh akan dicoba lagi 10 detik")
            handler.postDelayed(delayedVillageRefreshRunnable, 10_000L)
        }
    }
    private val cycleWatchdogRunnable: Runnable = Runnable {
        if (!running) return@Runnable
        val now = System.currentTimeMillis()
        persistActiveCycleDuration(now)
        logEvent("WATCHDOG: fase siklus berjalan >5 menit — proses aktif diakhiri agar scheduler tidak stuck")
        pendingStartAll = false
        builderInProgress = false
        loginInProgress = false
        reloginRequested = false
        pendingUpgradeUrl = ""
        pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)
        try { automationWebView()?.stopLoading() } catch (_: Exception) {}
        scheduleNextRandomRun()
        updateNotification("Siklus dihentikan oleh watchdog 5 menit")
    }
    private data class VillageDataRecord(
        val isChecklist: Boolean,
        val namaVillage: String,
        val id: String,
        val linkVillage: String,
        val linkResource: String,
        val minLvl: Int
    )

    private fun loadVillageDataRecordsFromPrefs(): List<VillageDataRecord> {
        debugTrace("ENTER loadVillageDataRecordsFromPrefs")
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("village_data_json", "[]").orEmpty()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<VillageDataRecord>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("Id").trim()
            if (id.isBlank() || !seen.add(id)) continue
            out.add(
                VillageDataRecord(
                    isChecklist = item.optBoolean("IsChecklist", false),
                    namaVillage = item.optString("NamaVillage").trim().ifBlank { "Village $id" },
                    id = id,
                    linkVillage = item.optString("LinkVillage").trim(),
                    linkResource = item.optString("LinkResource").trim(),
                    minLvl = item.optInt("MinLvl", -1)
                )
            )
        }
        return out
    }

    private fun loadBuilderStateFromVillageData(): Boolean {
        debugTrace("ENTER loadBuilderStateFromVillageData")
        val records = loadVillageDataRecordsFromPrefs()
        builderVillages.clear()
        builderVillageLinks.clear()
        builderResourceLinks.clear()
        builderResourceLevels.clear()

        val selected = records.filter { it.isChecklist }
        for (record in selected) {
            builderVillages.add(record.id to record.namaVillage)
            if (record.linkVillage.isNotBlank()) builderVillageLinks[record.id] = record.linkVillage
            if (record.linkResource.isNotBlank()) builderResourceLinks[record.id] = record.linkResource
            if (record.minLvl >= 0) builderResourceLevels[record.id] = record.minLvl
        }

        logEvent(
            "Resource Builder: database village dimuat — total=${records.size}, " +
                "checklist=${selected.size}, resourceLink=${builderResourceLinks.size}"
        )
        selected.forEach { record ->
            logEvent(
                "Resource Builder DB: ${record.namaVillage} [${record.id}] " +
                    "check=${record.isChecklist}; village=${record.linkVillage.ifBlank { "-" }}; " +
                    "resource=${record.linkResource.ifBlank { "-" }}; min=L${record.minLvl}"
            )
        }
        return selected.isNotEmpty()
    }

    private var resourceBuilderEnabled = true
    private var farmListEnabled = true
    private var builderSelectionConfigured = false
    private var selectedBuilderVillageIds = emptySet<String>()
    private var selectedBuilderVillagesJson = "[]"
    private var builderInProgress = false
    private var builderVillages = mutableListOf<Pair<String, String>>()
    private var builderVillageIndex = 0
    private var builderAttempt = 0

    // Target resource disimpan saat scanner UI mencari level terendah.
    // Resource Builder tidak lagi menebak field dari halaman village ketika eksekusi;
    // ia memakai href yang sudah disimpan untuk village tersebut.
    private val builderResourceLinks = linkedMapOf<String, String>()
    private val builderVillageLinks = linkedMapOf<String, String>()
    private val builderResourceLevels = linkedMapOf<String, Int>()
    private var pendingBuilderResourceHref = ""
    private var builderVillageClickInProgress = false
    private var builderDiscoverInFlight = false
    // State machine agar callback onPageFinished tidak menjalankan Builder
    // berulang-ulang pada dorf1.php atau salah mengklik tombol di halaman lain.
    private var builderStage = "IDLE"

    private var pendingUpgradeUrl = ""
    private var pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)
    private var inventoryUseAttempt = 0
    private var cycleNumber = 0
    private var farmListCycleStartedAt = 0L
    private var resourceBuilderCycleStartedAt = 0L
    private var recoveringService = false
    private var webViewRecoveryInProgress = false
    private var lastAutomationUrl = ""

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L

    override fun onCreate() {
        debugTrace("ENTER onCreate")
        super.onCreate()
        instanceRef = WeakReference(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Farm Assistant aktif"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugTrace("ENTER onStartCommand")
        when (intent?.action) {
            ACTION_STOP -> stopAutomation()
            ACTION_START -> {
                server = normalizeServer(intent.getStringExtra(EXTRA_SERVER).orEmpty())
                username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim()
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                minMinutes = intent.getLongExtra(EXTRA_MINUTES_MIN, 1L).coerceAtLeast(1L)
                maxMinutes = intent.getLongExtra(EXTRA_MINUTES_MAX, minMinutes).coerceAtLeast(minMinutes)
                resourceBuilderEnabled = intent.getBooleanExtra(EXTRA_RESOURCE_BUILDER, true)
                farmListEnabled = intent.getBooleanExtra(EXTRA_FARM_LIST_ENABLED, true)
                builderSelectionConfigured = intent.getBooleanExtra(
                    EXTRA_SELECTION_CONFIGURED,
                    getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("resource_builder_selection_configured", false)
                )
                selectedBuilderVillageIds = intent.getStringArrayExtra(EXTRA_SELECTED_VILLAGES)?.toSet()
                    ?: (getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("resource_builder_selected_villages", emptySet()) ?: emptySet())
                selectedBuilderVillagesJson = intent.getStringExtra(EXTRA_SELECTED_VILLAGES_JSON)
                    ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString("resource_builder_villages_json", "[]").orEmpty()
                loadSavedBuilderResourceTargets()
                loadSavedBuilderVillageLinks()
                startAutomation()
            }
            null -> recoverAfterProcessRecreation()
        }
        return START_STICKY
    }

    private fun recoverAfterProcessRecreation() {
        debugTrace("ENTER recoverAfterProcessRecreation")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("service_running", false)) return
        if (recoveringService) return
        recoveringService = true

        server = normalizeServer(prefs.getString("server", "").orEmpty())
        username = prefs.getString("username", "").orEmpty().trim()
        password = decryptPassword(prefs.getString("password_secure", "").orEmpty())
        minMinutes = prefs.getLong("interval_min_minutes", 1L).coerceAtLeast(1L)
        maxMinutes = prefs.getLong("interval_max_minutes", minMinutes).coerceAtLeast(minMinutes)
        farmListEnabled = prefs.getBoolean("farm_list_enabled", true)
        resourceBuilderEnabled = prefs.getBoolean("resource_builder_enabled", true)
        builderSelectionConfigured = prefs.getBoolean("resource_builder_selection_configured", false)
        selectedBuilderVillageIds = prefs.getStringSet("resource_builder_selected_villages", emptySet()) ?: emptySet()
        selectedBuilderVillagesJson = prefs.getString("resource_builder_villages_json", "[]").orEmpty()
        loadSavedBuilderResourceTargets(prefs)
        loadSavedBuilderVillageLinks()
        cycleNumber = prefs.getInt("current_cycle_number", 0)
        farmListCycleStartedAt = prefs.getLong("farm_cycle_started_at", 0L)
        resourceBuilderCycleStartedAt = prefs.getLong("resource_cycle_started_at", 0L)

        if (username.isBlank()) {
            logEvent("RECOVERY: service ditandai aktif tetapi username kosong; recovery dibatalkan")
            recoveringService = false
            return
        }

        running = true
        updateNotification("Farm Assistant — memulihkan service")
        logEvent("RECOVERY: proses Android dibuat ulang; memulihkan konfigurasi, WebView, dan scheduler")
        ensureServiceWebView()

        val cycleActive = prefs.getBoolean("cycle_active", false)
        val savedNextAt = prefs.getLong("next_run_at", 0L)
        val delay = savedNextAt - System.currentTimeMillis()

        handler.postDelayed({
            if (!running) return@postDelayed
            recoveringService = false
            if (cycleActive || delay <= 0L) {
                logEvent("RECOVERY: siklus terakhir belum selesai/interval sudah lewat; memulai ulang siklus")
                triggerScheduledCycle()
            } else {
                logEvent("RECOVERY: scheduler dipulihkan; run berikutnya dalam ${((delay + 999L) / 1000L)} detik")
                handler.removeCallbacks(nextRunRunnable)
                nextAt = savedNextAt
                handler.postDelayed(nextRunRunnable, delay)
                updateNextRun(delay)
            }
        }, 800L)
    }

    private fun automationWebView(): WebView? {
        debugTrace("ENTER automationWebView")
        return visibleWebViewRef?.get() ?: webView
    }

    private fun handleVisiblePageFinished(url: String) {
        debugTrace("ENTER handleVisiblePageFinished")
        if (!running) return
        lastAutomationUrl = url
        val lower = url.lowercase(Locale.US)
        handlePageAfterConsent(url, lower, 0)
    }

    private fun handleLoginResultFromVisibleWebView(result: String) {
        debugTrace("ENTER handleLoginResultFromVisibleWebView")
        if (!running) return
        handler.post {
            if (!running) return@post
            when (result) {
                "submitting" -> updateNotification("Farm Assistant — mengirim login")
                "no_login_form" -> {
                    loginInProgress = false
                    reloginRequested = false
                    loginRetryCount = 0
                    logEvent("Session aktif terdeteksi; membuka Farm List")
                    handler.postDelayed({ triggerStartAllFarmLists() }, 250)
                }
                "no_username_field", "no_form" -> {
                    if (loginRetryCount < 20) handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                    else {
                        loginInProgress = false
                        reloginRequested = false
                        logEvent("Form login Travian tidak dikenali")
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureServiceWebView() {
        debugTrace("ENTER ensureServiceWebView")
        if (webView != null) return
        webView = WebView(this@FarmAutomationService).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(FarmBridge(), "AndroidFarm")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    debugTrace("ENTER onPageFinished")
                    super.onPageFinished(view, url)
                    if (url == null || !running) return
                    lastAutomationUrl = url
                    handlePageAfterConsent(url, url.lowercase(Locale.US), 0)
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    debugTrace("ENTER onRenderProcessGone")
                    logEvent("RECOVERY: WebView renderer mati; membuat WebView baru")
                    if (view === webView) {
                        webView = null
                    }
                    webViewRecoveryInProgress = false
                    if (running) {
                        handler.postDelayed({ recoverWebView() }, 500L)
                    }
                    return true
                }
            }
        }
    }

    private fun onVisibleWebViewDetachedInternal() {
        debugTrace("ENTER onVisibleWebViewDetachedInternal")
        if (running && webView == null) ensureServiceWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startAutomation() {
        debugTrace("ENTER startAutomation")
        running = true
        cycleNumber = 0
        pendingStartAll = false
        loginInProgress = false
        reloginRequested = false
        loginRetryCount = 0
        startAllAttempt = 0
        consentAttempt = 0

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", username)
            .putString("password_secure", encryptPassword(password))
            .putLong("interval_min_minutes", minMinutes)
            .putLong("interval_max_minutes", maxMinutes)
            .putBoolean("farm_list_enabled", farmListEnabled)
            .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
            .putBoolean("service_running", true)
            .apply()

        logEvent("Background service dimulai. Range interval=${minMinutes}-${maxMinutes} menit; Farm List=${if (farmListEnabled) "ON" else "OFF"}; Resource Builder=${if (resourceBuilderEnabled) "ON" else "OFF"}; Village terpilih=${if (builderSelectionConfigured) selectedBuilderVillageIds.size else "SEMUA"}")
        updateNextRun(0L)
        updateNotification("Farm Assistant aktif — menyiapkan siklus")

        if (username.isBlank() || password.isBlank()) {
            logEvent("Background service gagal: username/password kosong")
            stopAutomation()
            return
        }

        if (webView == null && visibleWebViewRef?.get() == null) {
            ensureServiceWebView()
        }

        triggerScheduledCycle()
    }

    private fun triggerScheduledCycle() {
        debugTrace("ENTER triggerScheduledCycle")
        if (!running) return
        val now = timeFormat.format(Date())
        cycleNumber += 1
        farmListCycleStartedAt = if (farmListEnabled) System.currentTimeMillis() else 0L
        resourceBuilderCycleStartedAt = 0L
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("last_run", now)
            .putInt("current_cycle_number", cycleNumber)
            .putBoolean("cycle_active", true)
            .putLong("farm_cycle_started_at", farmListCycleStartedAt)
            .putLong("resource_cycle_started_at", 0L)
            .apply()
        logEvent("Siklus dimulai pada $now")
        handler.removeCallbacks(cycleWatchdogRunnable)
        handler.removeCallbacks(delayedVillageRefreshRunnable)
        // Farm List + verifikasi dapat membutuhkan >60 detik untuk banyak village.
        // Watchdog 5 menit mencegah false timeout sebelum Resource Builder sempat jalan.
        handler.postDelayed(cycleWatchdogRunnable, 5 * 60_000L)
        handler.postDelayed(delayedVillageRefreshRunnable, 60_000L)
        if (farmListEnabled) {
            triggerStartAllFarmLists()
        } else if (resourceBuilderEnabled) {
            logEvent("Farm List OFF — langsung menjalankan Resource Builder")
            startResourceBuilderCycle()
        } else {
            logEvent("Farm List OFF dan Resource Builder OFF — tidak ada aksi pada siklus ini")
            scheduleNextRandomRun()
        }
    }

    private fun triggerStartAllFarmLists() {
        debugTrace("ENTER triggerStartAllFarmLists")
        if (!running || !farmListEnabled) return
        pendingStartAll = true
        startAllAttempt = 0
        consentAttempt = 0
        updateNotification("Farm Assistant aktif — membuka Farm List")
        logEvent("Memulai siklus Start All Farm Lists")
        automationWebView()?.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    private fun handlePageAfterConsent(url: String, lower: String, attempt: Int): Unit {
        debugTrace("ENTER handlePageAfterConsent")
        if (!running) return
        acceptCookiesIfPresent { result ->
            if (!running) return@acceptCookiesIfPresent
            val consentStillVisible = result.contains("visible") || result.contains("clicked")
            if (consentStillVisible && attempt < 8) {
                consentAttempt = attempt + 1
                CookieManager.getInstance().flush()
                handler.postDelayed({ handlePageAfterConsent(url, lower, attempt + 1) }, 700)
                return@acceptCookiesIfPresent
            }

            if (lower.contains("gid=16") && lower.contains("tt=99")) {
                loginInProgress = false
                reloginRequested = false
                loginRetryCount = 0
                if (pendingStartAll) {
                    startAllAttempt = 0
                    handler.postDelayed({ clickStartAllFarmLists() }, 1200)
                }
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("dorf1.php")) {
                val expectedId = builderVillages.getOrNull(builderVillageIndex)?.first.orEmpty()
                val currentId = Regex("[?&]newdid=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(lower)?.groupValues?.getOrNull(1).orEmpty()

                if (builderVillages.isEmpty()) {
                    builderStage = "DISCOVER"
                    handler.postDelayed({ discoverVillagesForBuilder() }, 700)
                } else if (expectedId.isNotBlank() && currentId == expectedId &&
                    pendingBuilderResourceHref.isNotBlank()) {
                    // Village yang benar sudah aktif. SEKARANG baru buka href
                    // resource yang telah disimpan oleh scanner.
                    if (builderStage != "OPEN_RESOURCE" && builderStage != "WAIT_UPGRADE" &&
                        builderStage != "ADVANCING") {
                        builderStage = "OPEN_RESOURCE"
                        handler.postDelayed({ openSavedBuilderResource() }, 500)
                    }
                } else if (builderStage == "LOAD_DORF" || builderStage == "CLICK_VILLAGE") {
                    // Masih berada di daftar village: klik village yang dipilih.
                    builderStage = "CLICK_VILLAGE"
                    handler.postDelayed({ clickBuilderVillageFromDorf() }, 400)
                } else {
                    // Jangan pernah memanggil processResourceBuilderVillage() dari
                    // onPageFinished dorf1.php secara membabi buta. Ini sebelumnya
                    // menyebabkan loop dan bahkan bisa mengklik tombol Rally Point.
                    logEvent("Resource Builder: dorf1 menunggu village target; stage=$builderStage current=$currentId expected=$expectedId")
                }
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("build.php") && !lower.contains("gid=16")) {
                builderStage = "INSPECT_UPGRADE"
                handler.postDelayed({ inspectUpgradeResources() }, 700)
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("hero/inventory")) {
                handler.postDelayed({ useHeroInventoryForPendingUpgrade() }, 700)
                return@acceptCookiesIfPresent
            }

            if (isLikelyLoginPage(lower)) {
                if (username.isNotBlank() && password.isNotBlank()) {
                    loginInProgress = true
                    reloginRequested = true
                    loginRetryCount = 0
                    updateNotification("Farm Assistant — auto re-login")
                    logEvent("Session Travian habis; memulai auto re-login")
                    handler.postDelayed({ autoLoginIfNeeded() }, 500)
                } else {
                    logEvent("Session habis tetapi password tidak tersedia di RAM")
                }
                return@acceptCookiesIfPresent
            }

            if (loginInProgress) {
                handler.postDelayed({ autoLoginIfNeeded() }, 500)
            } else if (pendingStartAll) {
                detectLoginFormForScheduler()
            }
        }
    }

    private fun isLikelyLoginPage(url: String): Boolean {
        debugTrace("ENTER isLikelyLoginPage")
        return url.contains("login") || url.contains("logout") ||
            url.contains("anmelden") || url.contains("signin")
    }

    private fun autoLoginIfNeeded() {
        debugTrace("ENTER autoLoginIfNeeded")
        if (!running || !loginInProgress) return
        if (username.isBlank() || password.isBlank()) return
        loginRetryCount++
        if (loginRetryCount > 20) {
            loginInProgress = false
            reloginRequested = false
            logEvent("Auto re-login gagal setelah 20 percobaan")
            return
        }

        val usernameJson = JSONObject.quote(username)
        val passwordJson = JSONObject.quote(password)
        val js = """
            (() => {
                const username = $usernameJson;
                const password = $passwordJson;
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el);
                    return s.display !== 'none' && s.visibility !== 'hidden' && el.offsetParent !== null;
                };
                const inputs = [...document.querySelectorAll('input')].filter(visible);
                const passwordInput = inputs.find(x =>
                    (x.type || '').toLowerCase() === 'password' || /pass|password/i.test(x.name || '') || /pass|password/i.test(x.id || '')
                );
                if (!passwordInput) { AndroidFarm.onLoginResult('no_login_form'); return; }
                const userInput = inputs.find(x =>
                    /user|username|email|login|name/i.test(x.name || '') || /user|username|email|login|name/i.test(x.id || '') || (x.type || '').toLowerCase() === 'email'
                );
                if (!userInput) { AndroidFarm.onLoginResult('no_username_field'); return; }
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };
                setValue(userInput, username);
                setValue(passwordInput, password);
                const form = passwordInput.closest('form') || userInput.closest('form');
                if (!form) { AndroidFarm.onLoginResult('no_form'); return; }
                const buttons = [...form.querySelectorAll('button,input[type=submit],input[type=button],a')].filter(visible);
                const submitButton = buttons.find(x => /login|log in|sign in|anmelden|connexion|entrar|acceder/i.test((x.innerText || x.value || x.title || '').trim()));
                AndroidFarm.onLoginResult('submitting');
                if (submitButton) submitButton.click();
                else if (typeof form.requestSubmit === 'function') form.requestSubmit();
                else form.submit();
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js, null)
    }

    private fun detectLoginFormForScheduler() {
        debugTrace("ENTER detectLoginFormForScheduler")
        automationWebView()?.evaluateJavascript("""
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                return [...document.querySelectorAll('input[type=password]')].some(visible) ? 'login_form' : 'not_login';
            })();
        """.trimIndent()) { raw ->
            if (raw.orEmpty().contains("login_form") && running) {
                loginInProgress = true
                reloginRequested = true
                loginRetryCount = 0
                logEvent("Form login terdeteksi saat scheduler berjalan")
                handler.postDelayed({ autoLoginIfNeeded() }, 250)
            }
        }
    }

    private fun clickStartAllFarmLists(): Unit {
        debugTrace("ENTER clickStartAllFarmLists")
        if (!running || !pendingStartAll) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                const statusCount = () => {
                    let total = 0;
                    for (const el of document.querySelectorAll('#rallyPointFarmList .farmListStatus, .farmListStatus')) {
                        const m = norm(el.textContent).match(/(\d+)\s*\/\s*(\d+)/);
                        if (m) total += parseInt(m[1], 10);
                    }
                    return total;
                };
                const readyCount = () => {
                    let ready = 0;
                    for (const b of document.querySelectorAll('#rallyPointFarmList .farmListWrapper button.startFarmList, button.startFarmList')) {
                        if (visible(b) && !b.disabled && b.getAttribute('disabled') === null && b.getAttribute('aria-disabled') !== 'true') ready++;
                    }
                    return ready;
                };
                const dispatch = btn => {
                    btn.scrollIntoView({block:'center'});
                    // Gunakan native HTMLElement.click() terlebih dahulu. Beberapa
                    // handler Travian/jQuery tidak bereaksi terhadap MouseEvent buatan.
                    try { btn.click(); } catch (_) {}
                    // Event fallback untuk markup/handler lama.
                    try {
                        btn.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
                        btn.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
                    } catch (_) {}
                };
                const selectors = [
                    '#rallyPointFarmList button.startAllFarmLists',
                    'button.startAllFarmLists',
                    '.startAllFarmLists button',
                    '.startAllFarmLists'
                ];
                for (const selector of selectors) {
                    let nodes = [];
                    try { nodes = [...document.querySelectorAll(selector)]; } catch (_) {}
                    const btn = nodes.find(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                    if (btn) {
                        const before = statusCount();
                        const beforeReady = readyCount();
                        dispatch(btn);
                        return JSON.stringify({state:'clicked', before, beforeReady, selector});
                    }
                }
                const candidates = [...document.querySelectorAll('button,input[type=button],input[type=submit],a,[role=button]')];
                const textBtn = candidates.find(el => {
                    if (!visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
                    const t = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    return /^(start all|start all farm lists?|start all farmlists?|send all)$/.test(t) || /start all.*farm/i.test(t);
                });
                if (textBtn) {
                    const before = statusCount();
                    const beforeReady = readyCount();
                    dispatch(textBtn);
                    return JSON.stringify({state:'clicked', before, beforeReady, selector:'text'});
                }
                return JSON.stringify({state:'not-found', before:statusCount(), beforeReady:readyCount()});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            if (result.contains("\"state\":\"clicked\"")) {
                pendingStartAll = false
                startAllAttempt = 0
                raidVerificationAttempt = 0
                farmListProgressObserved = false
                farmListLastState = ""
                farmListStableChecks = 0
                fallbackFarmListMode = false
                raidCountBeforeStartAll = Regex("\"before\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                farmListBeforeReady = Regex("\"readyBefore\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val now = timeFormat.format(Date())
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_run", now).apply()
                logEvent("Send All Farm Lists diklik; raid aktif sebelum klik=$raidCountBeforeStartAll; tombol Farm List siap sebelum klik=$farmListBeforeReady")
                updateNotification("Farm List — menunggu 1 menit agar semua raid terkirim")
                logEvent("Farm List: Send All berhasil; menunggu 60 detik sebelum Resource Builder")
                // Send All adalah satu aksi dispatch. Tidak perlu polling status tombol
                // berulang-ulang karena tombol bisa tetap aktif walaupun semua request
                // sudah masuk. Beri Travian 60 detik untuk menyelesaikan seluruh dispatch,
                // lalu lanjut ke Resource Builder.
                handler.postDelayed({ finishFarmListAfterOneMinute() }, 60_000L)
            } else if (startAllAttempt < 10) {
                startAllAttempt++
                handler.postDelayed({ clickStartAllFarmLists() }, 1000)
            } else {
                pendingStartAll = false
                logEvent("Send All gagal: tombol tidak ditemukan setelah 10 percobaan; Farm List belum dianggap selesai")
                fallbackSequentialFarmListSend()
            }
        }
    }

    private fun finishFarmListAfterOneMinute() {
        debugTrace("ENTER finishFarmListAfterOneMinute")
        if (!running) return
        pendingStartAll = false
        fallbackFarmListMode = false
        val now = System.currentTimeMillis()
        if (farmListCycleStartedAt > 0L) {
            val farmDuration = (now - farmListCycleStartedAt).coerceAtLeast(0L)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("farm_cycle_duration_ms", farmDuration)
                .putLong("farm_cycle_started_at", 0L)
                .apply()
            logEvent("Farm List: waktu proses ${formatDuration(farmDuration)}; jeda dispatch 60 detik selesai")
            farmListCycleStartedAt = 0L
        } else {
            logEvent("Farm List: jeda dispatch 60 detik selesai")
        }

        updateNotification("Farm List selesai — Resource Builder dimulai")
        if (resourceBuilderEnabled) {
            startResourceBuilderCycle()
        } else {
            scheduleNextRandomRun()
        }
    }

    private fun verifyRaidDispatch(): Unit {
        debugTrace("ENTER verifyRaidDispatch")
        if (!running) return

        // Farm List adalah aksi dispatch, bukan proses yang harus ditunggu sampai
        // semua tombol Start menjadi disabled. Pada Travian tombol Start sering tetap
        // aktif walaupun request raid sudah berhasil dikirim. Verifikasi lama bisa
        // polling 20x + fallback + reload sampai watchdog 5 menit dan membuat
        // Resource Builder tidak pernah kebagian waktu.
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                let total = 0, wrappers = 0, ready = 0;
                for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                    wrappers++;
                    const status = wrapper.querySelector('.farmListStatus');
                    const m = norm(status?.textContent || '').match(/(\d+)\s*\/\s*(\d+)/);
                    if (m) total += parseInt(m[1], 10);
                    const btn = wrapper.querySelector('button.startFarmList');
                    if (btn && visible(btn) && !btn.disabled && btn.getAttribute('disabled') === null && btn.getAttribute('aria-disabled') !== 'true') ready++;
                }
                const allText = norm(document.querySelector('#rallyPointFarmList')?.innerText || '');
                const busy = /sending|loading|processing|mengirim|memproses/.test(allText);
                return JSON.stringify({total, wrappers, ready, busy});
            })();
        """.trimIndent()

        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val current = Regex("\"total\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ready = Regex("\"ready\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val busy = Regex("\"busy\":(true|false)").find(result)?.groupValues?.get(1) == "true"
            val wrappers = Regex("\"wrappers\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (current > raidCountBeforeStartAll || ready < farmListBeforeReady || busy) {
                farmListProgressObserved = true
            }

            // Beri AJAX Travian waktu singkat untuk mulai, tetapi jangan pernah
            // menahan siklus sampai menit ke-5 hanya karena tombol Start tetap aktif.
            val elapsedChecks = raidVerificationAttempt
            val dispatchSettled = wrappers > 0 && !busy &&
                (farmListProgressObserved || elapsedChecks >= 4)

            if (dispatchSettled || elapsedChecks >= 6) {
                val sent = (current - raidCountBeforeStartAll).coerceAtLeast(0)
                logEvent(
                    "Farm List selesai dispatch: $sent raid terdeteksi; " +
                        "tombol Start aktif=$ready; verifikasi=${elapsedChecks + 1}x — lanjut Resource Builder"
                )
                updateNotification("Farm List selesai — Resource Builder dimulai")
                pendingStartAll = false
                fallbackFarmListMode = false
                if (resourceBuilderEnabled) startResourceBuilderCycle() else scheduleNextRandomRun()
            } else {
                raidVerificationAttempt++
                logEvent(
                    "Farm List verifikasi ${raidVerificationAttempt}/6; raid=$current; " +
                        "tombol Start aktif=$ready; busy=$busy; progress=${if (farmListProgressObserved) "YA" else "BELUM"}"
                )
                updateNotification("Farm List — dispatch ${raidVerificationAttempt}/6")
                handler.postDelayed({ verifyRaidDispatch() }, 1000)
            }
        }
    }

    private fun fallbackSequentialFarmListSend() {
        debugTrace("ENTER fallbackSequentialFarmListSend")
        if (!running) return
        updateNotification("Farm List — fallback, menyelesaikan pengiriman")
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const statusCount = () => {
                    let total = 0;
                    for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                        const m = (wrapper.querySelector('.farmListStatus')?.textContent || '').replace(/\s+/g,' ').match(/(\d+)\s*\/\s*(\d+)/);
                        if (m) total += parseInt(m[1],10);
                    }
                    return total;
                };
                const buttons = [...document.querySelectorAll('#rallyPointFarmList .farmListWrapper button.startFarmList, button.startFarmList')]
                    .filter(b => visible(b) && !b.disabled && b.getAttribute('disabled') === null && b.getAttribute('aria-disabled') !== 'true');
                const readyBefore = buttons.length;
                const totalBefore = statusCount();
                for (const btn of buttons) {
                    btn.scrollIntoView({block:'center'});
                    try { btn.click(); } catch (_) {}
                    try {
                        btn.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
                        btn.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
                    } catch (_) {}
                }
                return JSON.stringify({clicked:buttons.length, readyBefore, totalBefore});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val clicked = Regex("\"clicked\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                farmListBeforeReady = Regex("\"readyBefore\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            farmListProgressObserved = false
            farmListLastState = ""
            farmListStableChecks = 0
            fallbackFarmListMode = true
            logEvent("Fallback Start per Farm List: $clicked tombol diklik; sebelum=$farmListBeforeReady tombol siap; menunggu 60 detik")
            raidVerificationAttempt = 0
            updateNotification("Farm List — fallback, menunggu 1 menit")
            handler.postDelayed({ finishFarmListAfterOneMinute() }, 60_000L)
        }
    }

    private fun verifyFallbackRaidCompletion(): Unit {
        debugTrace("ENTER verifyFallbackRaidCompletion")
        if (!running) return

        // Fallback hanya memastikan request sudah diberi kesempatan diproses.
        // Jangan reload Farm List berulang-ulang: reload + polling lama dapat
        // menghabiskan seluruh watchdog dan mencegah Resource Builder berjalan.
        val js = """
            (() => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                let total = 0, wrappers = 0;
                for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                    wrappers++;
                    const m = norm(wrapper.querySelector('.farmListStatus')?.textContent || '').match(/(\d+)\s*\/\s*(\d+)/);
                    if (m) total += parseInt(m[1], 10);
                }
                const allText = norm(document.querySelector('#rallyPointFarmList')?.innerText || '');
                const busy = /sending|loading|processing|mengirim|memproses/.test(allText);
                return JSON.stringify({total, wrappers, busy});
            })();
        """.trimIndent()

        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val current = Regex("\"total\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val wrappers = Regex("\"wrappers\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val busy = Regex("\"busy\":(true|false)").find(result)?.groupValues?.get(1) == "true"

            raidVerificationAttempt++
            if (busy) farmListProgressObserved = true
            val sent = (current - raidCountBeforeStartAll).coerceAtLeast(0)

            // Maksimal 6 detik. Jika status raid tidak berubah, tetap lanjut karena
            // tujuan fallback adalah dispatch, bukan menunggu counter berubah.
            if (!busy && (farmListProgressObserved || raidVerificationAttempt >= 3) || raidVerificationAttempt >= 6) {
                logEvent(
                    "Fallback Farm List selesai dispatch: $sent raid terdeteksi; " +
                        "wrappers=$wrappers; lanjut Resource Builder"
                )
                updateNotification("Farm List selesai — Resource Builder dimulai")
                fallbackFarmListMode = false
                pendingStartAll = false
                if (resourceBuilderEnabled) startResourceBuilderCycle() else scheduleNextRandomRun()
            } else {
                logEvent("Fallback Farm List menunggu dispatch ${raidVerificationAttempt}/6; raid=$current; busy=$busy")
                updateNotification("Farm List — fallback ${raidVerificationAttempt}/6")
                handler.postDelayed({ verifyFallbackRaidCompletion() }, 1000)
            }
        }
    }

    /**
     * Resource Builder: setelah raid berhasil dijalankan, kunjungi setiap village
     * dan upgrade satu resource field dengan level terendah yang tersedia.
     * Strategi ini sengaja hanya melakukan satu upgrade per village per siklus.
     */
    private fun startResourceBuilderCycle() {
        debugTrace("ENTER startResourceBuilderCycle")
        if (!running) return
        builderInProgress = true
        builderVillages.clear()
        builderVillageIndex = 0
        builderAttempt = 0
        pendingBuilderResourceHref = ""
        builderVillageClickInProgress = false
        builderDiscoverInFlight = false
        // Selection adalah snapshot saat BOT dinyalakan. Selama BOT aktif
        // jangan membaca ulang SharedPreferences agar checklist tidak berubah di tengah siklus.
        loadSavedBuilderResourceTargets()
        loadSavedBuilderVillageLinks()
        val now = System.currentTimeMillis()
        if (farmListCycleStartedAt > 0L) {
            val farmDuration = (now - farmListCycleStartedAt).coerceAtLeast(0L)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("farm_cycle_duration_ms", farmDuration)
                .putLong("farm_cycle_started_at", 0L)
                .apply()
            logEvent("Farm List: waktu proses ${formatDuration(farmDuration)}")
            farmListCycleStartedAt = 0L
        }
        resourceBuilderCycleStartedAt = now
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("resource_cycle_started_at", now)
            .apply()
        // JANGAN discover/scan ulang village di sini. Daftar village + link village
        // sudah disimpan saat REFRESH VILLAGE dan selection sudah menjadi snapshot
        // ketika BOT dinyalakan. Discovery ulang sebelumnya menyebabkan builder
        // menunggu retry berkali-kali (bahkan sampai 10x) karena sidebar tidak selalu
        // merender semua village.
        val hasVillageData = loadBuilderStateFromVillageData()
        if (!hasVillageData) {
            logEvent("Resource Builder: database village tidak memiliki checklist aktif; siklus dilewati")
            finishResourceBuilderCycle()
            return
        }

        val missingTargets = builderVillages.filter { builderResourceLinks[it.first].isNullOrBlank() }
        if (missingTargets.isNotEmpty()) {
            logEvent(
                "Resource Builder: target resource belum tersimpan untuk " +
                    missingTargets.joinToString(" | ") { "${it.second} [${it.first}]" } +
                    "; village tersebut dilewati"
            )
            builderVillages = builderVillages.filter { builderResourceLinks[it.first].orEmpty().isNotBlank() }.toMutableList()
        }

        if (builderVillages.isEmpty()) {
            finishResourceBuilderCycle()
            return
        }

        builderStage = "LOAD_DORF"
        updateNotification("Farm Assistant — Resource Builder menyiapkan village")
        logEvent(
            "Resource Builder: ${builderVillages.size} village siap; " +
                "menggunakan link village + target resource yang sudah tersimpan"
        )
        // Reset watchdog saat masuk fase Builder agar timeout Farm List tidak
        // mematikan Builder yang memang membutuhkan waktu lebih dari 1 menit.
        handler.removeCallbacks(cycleWatchdogRunnable)
        handler.postDelayed(cycleWatchdogRunnable, 5 * 60_000L)
        automationWebView()?.loadUrl("$server/dorf1.php")
    }

    private fun processResourceBuilderVillage() {
        debugTrace("ENTER processResourceBuilderVillage")
        if (!running || !builderInProgress) return

        if (builderVillageIndex >= builderVillages.size) {
            finishResourceBuilderCycle()
            return
        }

        val (villageId, villageName) = builderVillages[builderVillageIndex]
        val resourceHref = builderResourceLinks[villageId].orEmpty()
        if (resourceHref.isBlank()) {
            logEvent("Resource Builder: target resource belum tersimpan untuk $villageName (ID $villageId); village dilewati. Jalankan REFRESH VILLAGE terlebih dahulu.")
            goToNextBuilderVillage()
            return
        }

        builderAttempt = 0
        pendingBuilderResourceHref = resourceHref
        builderVillageClickInProgress = true
        builderStage = "LOAD_DORF"
        val savedLevel = builderResourceLevels[villageId]
        logEvent(
            "Resource Builder: village ${builderVillageIndex + 1}/${builderVillages.size} — $villageName (ID $villageId); " +
                "target=${resourceHref}${savedLevel?.let { "; level=L$it" } ?: ""}"
        )
        updateNotification("Resource Builder — ${builderVillageIndex + 1}/${builderVillages.size}: $villageName")

        // Selalu kembali ke dorf1.php. Dari sana bot benar-benar klik village yang
        // dicentang, baru setelah halaman village terbuka mengikuti href resource
        // yang sudah disimpan saat pencarian level terendah.
        automationWebView()?.loadUrl("$server/dorf1.php")
    }

    private fun openSavedBuilderResource(): Unit {
        debugTrace("ENTER openSavedBuilderResource")
        if (!running || !builderInProgress || pendingBuilderResourceHref.isBlank()) return
        val (villageId, villageName) = builderVillages.getOrNull(builderVillageIndex)
            ?: return
        val expectedId = villageId
        val href = absoluteBuilderHref(pendingBuilderResourceHref)
        val hrefJson = JSONObject.quote(href)
        builderStage = "OPEN_RESOURCE"
        val idJson = JSONObject.quote(expectedId)

        val js = """
            (() => {
                const id = $idJson;
                const targetHref = $hrefJson;
                const current = location.href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                if (current !== id) return JSON.stringify({state:'wrong_village', current, expected:id});

                // Pastikan target href yang tersimpan memang berasal dari village ini.
                const anchors = [...document.querySelectorAll('#resourceFieldContainer a[href*="build.php?id="], a[href*="build.php?id="]')];
                const wanted = targetHref.split('#')[0];
                const found = anchors.find(a => {
                    const raw = a.getAttribute('href') || '';
                    return raw === targetHref || raw === wanted || a.href === targetHref || a.href === wanted;
                });

                if (found) {
                    found.scrollIntoView({block:'center'});
                    found.click();
                    return JSON.stringify({state:'clicked', href:found.getAttribute('href') || found.href});
                }

                // Jika skin Travian tidak merender anchor resource di DOM, gunakan
                // href tersimpan secara langsung. Ini tetap menuju field yang sama.
                location.href = targetHref;
                return JSON.stringify({state:'load_saved_href', href:targetHref});
            })();
        """.trimIndent()

        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result.contains("wrong_village") -> {
                    logEvent("Resource Builder: village aktif salah saat membuka target; expected=$expectedId; retry")
                    if (builderAttempt < 4) {
                        builderAttempt++
                        automationWebView()?.loadUrl("$server/dorf1.php")
                    } else {
                        builderVillageClickInProgress = false
                        pendingBuilderResourceHref = ""
                        goToNextBuilderVillage()
                    }
                }
                result.contains("clicked") || result.contains("load_saved_href") -> {
                    builderVillageClickInProgress = false
                    logEvent("Resource Builder: $villageName — membuka target resource tersimpan $href")
                }
                else -> {
                    if (builderAttempt < 4) {
                        builderAttempt++
                        handler.postDelayed({ openSavedBuilderResource() }, 700)
                    } else {
                        builderVillageClickInProgress = false
                        pendingBuilderResourceHref = ""
                        logEvent("Resource Builder: gagal membuka target resource tersimpan untuk $villageName")
                        goToNextBuilderVillage()
                    }
                }
            }
        }
    }

    private fun saveDebugResourceBuilderVillageLink(villageId: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val targetUrl = "$server/dorf1.php?newdid=$villageId"
        prefs.edit()
            .putString("debug_last_resource_builder_village_link", targetUrl)
            .apply()
    }

    private fun clickBuilderVillageFromDorf(): Unit {
        debugTrace("ENTER clickBuilderVillageFromDorf")
        if (!running || !builderInProgress || !builderVillageClickInProgress) return
        val village = builderVillages.getOrNull(builderVillageIndex) ?: return
        saveDebugResourceBuilderVillageLink(village.first)
        val savedVillageHref = builderVillageLinks[village.first].orEmpty().trim()
        val idJson = JSONObject.quote(village.first)
        val nameJson = JSONObject.quote(village.second)
        val hrefJson = JSONObject.quote(savedVillageHref)
        val js = """
            (() => {
                const id = $idJson;
                const name = $nameJson;
                const savedHref = $hrefJson;
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const sameHref = (a, b) => {
                    if (!a || !b || b === '#') return false;
                    const absA = new URL(a, location.href).href.split('#')[0];
                    const absB = new URL(b, location.href).href.split('#')[0];
                    return absA === absB;
                };
                const anchors = [...document.querySelectorAll('a[href], [data-did]')];
                let anchor = null;

                // Prioritas 1: link village yang memang sudah disimpan saat scan.
                if (savedHref && savedHref !== '#') {
                    anchor = anchors.find(a => {
                        const h = a.getAttribute('href') || '';
                        return sameHref(h, savedHref);
                    }) || null;
                }

                // Prioritas 2: data-did / newdid dari village yang sama.
                if (!anchor) {
                    anchor = anchors.find(a => {
                        const entry = a.closest('.listEntry, .dropContainer, li');
                        const dataDid = a.getAttribute('data-did') || entry?.getAttribute('data-did') || '';
                        const href = a.getAttribute('href') || '';
                        const hrefDid = href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                        return dataDid === id || hrefDid === id;
                    }) || null;
                }

                if (anchor) {
                    anchor.scrollIntoView({block:'center', inline:'nearest'});
                    anchor.click();
                    // Link village Travian sering membuka build.php?id=39 (Rally Point)
                    // sambil mengganti context village. Builder harus kembali ke dorf1.php
                    // agar tahap berikutnya bisa memverifikasi village lalu membuka target
                    // resource tersimpan. Ini mengikuti mekanisme Refresh Village.
                    setTimeout(() => {
                        const current = location.href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                        const active = document.querySelector(
                            '#sidebarBoxVillagelist .listEntry.active, .villageList .listEntry.active'
                        )?.getAttribute('data-did') || '';
                        AndroidFarm.onLiveClickResult(JSON.stringify({
                            kind:'AUTO_VILLAGE_CLICK_VERIFY', id, current, active, pageUrl:location.href
                        }));
                        location.href = '/dorf1.php?newdid=' + encodeURIComponent(id);
                    }, 2200);
                    return 'clicked';
                }

                // Last resort: jika Travian tidak merender anchor village tetapi
                // snapshot memiliki href village yang valid, buka link tersimpan.
                if (savedHref && savedHref !== '#' && /newdid=\d+/i.test(savedHref)) {
                    location.href = new URL(savedHref, location.href).href;
                    return 'loaded_saved_village';
                }
                return 'not-found';
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"')
            if (result == "clicked" || result == "loaded_saved_village") {
                builderStage = "WAIT_VILLAGE"
                logEvent(
                    "Resource Builder: ${if (result == "clicked") "klik" else "buka link tersimpan"} " +
                        "village ${village.second} (ID ${village.first}) dari dorf1.php"
                )
            } else if (builderAttempt < 3) {
                builderAttempt++
                handler.postDelayed({ clickBuilderVillageFromDorf() }, 350)
            } else {
                logEvent("Resource Builder: link village ${village.second} (ID ${village.first}) tidak ditemukan di dorf1.php")
                builderVillageClickInProgress = false
                pendingBuilderResourceHref = ""
                goToNextBuilderVillage()
            }
        }
    }

    private fun inspectResourceVillage(): Unit {
        debugTrace("ENTER inspectResourceVillage")
        if (!running || !builderInProgress) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim();
                const container = document.querySelector('#resourceFieldContainer');
                if (!container) return JSON.stringify({state:'no_container'});

                const queue = document.querySelector('.boxes.buildingList, .buildingList');
                if (queue && queue.querySelector('li')) {
                    return JSON.stringify({state:'queue_busy'});
                }

                const fields = [];
                // Travian's resource field map normally contains 18 direct anchors.
                // Avoid depending on a skin-specific wrapper/class.
                const anchors = [...container.querySelectorAll('a[href*="build.php?id="]')];
                const seen = new Set();
                for (const a of anchors) {
                    const href = a.getAttribute('href') || '';
                    const m = href.match(/[?&]id=(\d+)/);
                    if (!m) continue;
                    const idNum = parseInt(m[1], 10);
                    if (idNum < 1 || idNum > 18 || seen.has(m[1])) continue;
                    seen.add(m[1]);
                    let level = 0;
                    let node = a;
                    for (let i=0; i<7 && node; i++, node=node.parentElement) {
                        const text = norm(node.innerText || node.textContent || '');
                        const attrs = [node.getAttribute('class') || '', node.getAttribute('title') || '', node.getAttribute('aria-label') || ''].join(' ');
                        const lm = text.match(/(?:level|lvl)\s*(\d+)/i) || attrs.match(/level\s*(\d+)/i);
                        if (lm) { level = parseInt(lm[1],10); break; }
                    }
                    const disabled = a.classList.contains('disabled') || !!a.closest('.disabled') || a.getAttribute('aria-disabled') === 'true' || a.getAttribute('data-disabled') === 'true';
                    fields.push({id:m[1], level, disabled:!!disabled, name:norm(a.getAttribute('title') || a.getAttribute('aria-label') || a.innerText || '')});
                }
                if (!fields.length) return JSON.stringify({state:'no_fields'});
                fields.sort((a,b) => a.level-b.level || parseInt(a.id)-parseInt(b.id));
                const candidate = fields.find(f => !f.disabled && f.level < 10);
                if (!candidate) return JSON.stringify({state:'none_available', fields});
                return JSON.stringify({state:'candidate', id:candidate.id, level:candidate.level, name:candidate.name});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result.contains("queue_busy") -> {
                    logEvent("Resource Builder: village ${builderVillageIndex + 1} dilewati, construction queue sedang terisi")
                    goToNextBuilderVillage()
                }
                result.contains("no_container") || result.contains("no_fields") -> {
                    if (builderAttempt < 3) {
                        builderAttempt++
                        handler.postDelayed({ inspectResourceVillage() }, 800)
                    } else {
                        logEvent("Resource Builder: struktur resource field tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                result.contains("none_available") -> {
                    logEvent("Resource Builder: tidak ada field yang bisa di-upgrade di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                    goToNextBuilderVillage()
                }
                result.contains("candidate") -> {
                    val idMatch = Regex("\\\"id\\\":\\\"?(\\d+)").find(result)
                    val levelMatch = Regex("\\\"level\\\":(\\d+)").find(result)
                    val id = idMatch?.groupValues?.get(1)
                    val level = levelMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (id == null) {
                        goToNextBuilderVillage()
                    } else {
                        logEvent("Resource Builder: memilih field id=$id level=$level di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        updateNotification("Resource Builder — field level $level → ${level + 1}")
                        automationWebView()?.loadUrl("$server/build.php?id=$id")
                    }
                }
                else -> goToNextBuilderVillage()
            }
        }
    }

    private fun inspectUpgradeResources(): Unit {
        debugTrace("ENTER inspectUpgradeResources")
        if (!running || !builderInProgress) return

        // Guard penting: jangan pernah mencari tombol "Upgrade/Build" di dorf1.php.
        // Sebelumnya halaman Rally Point di dorf1 dapat dianggap sebagai target
        // dan Builder lalu melaporkan "upgrade berhasil" padahal resource field
        // tidak pernah dibuka.
        val currentUrl = automationWebView()?.url.orEmpty()
        if (!currentUrl.contains("build.php", ignoreCase = true) ||
            currentUrl.contains("gid=16", ignoreCase = true)) {
            logEvent("Resource Builder: halaman target bukan build.php; URL=$currentUrl; membuka ulang target tersimpan")
            builderStage = "OPEN_RESOURCE"
            handler.postDelayed({ openSavedBuilderResource() }, 400)
            return
        }

        builderStage = "INSPECT_UPGRADE"
        val js = """
            (() => {
                const num = s => {
                    const m = String(s || '').replace(/[^0-9.,-]/g, '').replace(/,/g, '');
                    const n = parseInt(m, 10);
                    return Number.isFinite(n) ? n : 0;
                };
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim().toLowerCase();
                const root = document.querySelector('#build, #villageContent, #content') || document.body;
                const current = [
                    '#l1', '#l2', '#l3', '#l4'
                ].map(sel => {
                    const el = document.querySelector(sel);
                    const text = el ? (el.innerText || el.textContent || el.getAttribute('title') || '') : '';
                    return num(text.split('/')[0]);
                });

                const costs = [0,0,0,0];
                const contract = document.querySelector('#contract, .buildCosts, .costs, .buildingCosts, .resourceCosts') || root;
                for (let i=1;i<=4;i++) {
                    let value = 0;
                    const nodes = [...contract.querySelectorAll('img.r' + i + ', .r' + i + ', [class~="r' + i + '"]')];
                    for (const node of nodes) {
                        // Legacy/current Travian markup commonly places the cost directly
                        // after the r1/r2/r3/r4 icon as a text node.
                        let text = '';
                        let sibling = node.nextSibling;
                        for (let j=0; j<4 && sibling; j++, sibling=sibling.nextSibling) {
                            text += ' ' + (sibling.textContent || '');
                            if (/\d/.test(text)) break;
                        }
                        let matches = text.match(/\d[\d.,]*/g) || [];
                        if (!matches.length && node.parentElement) {
                            text = node.parentElement.innerText || node.parentElement.textContent || '';
                            matches = text.match(/\d[\d.,]*/g) || [];
                        }
                        if (matches.length) {
                            const candidate = num(matches[0]);
                            if (candidate > value) value = candidate;
                        }
                    }
                    costs[i-1] = value;
                }

                // Modern Travian can expose the costs through data attributes.
                if (costs.some(x => x > 0) === false) {
                    const text = norm(contract.innerText || contract.textContent || '');
                    const all = text.match(/(?:lumber|clay|iron|crop)[^0-9]{0,40}(\d[\d.,]*)/gi) || [];
                    const names = ['lumber','clay','iron','crop'];
                    for (let i=0;i<4;i++) {
                        const hit = all.find(x => x.toLowerCase().startsWith(names[i]));
                        if (hit) costs[i] = num(hit);
                    }
                }

                const all = [...root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')];
                const candidates = all.filter(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                const btn = candidates.find(el => {
                    const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    const cls = (el.className || '').toString().toLowerCase();
                    const href = (el.getAttribute('href') || '').toLowerCase();
                    if (/cancel|demolish|destroy|remove/.test(text + ' ' + cls)) return false;
                    return /upgrade|upgrade to level|build/.test(text) ||
                           /(?:^|\s)(green|build|upgrade)(?:\s|$)/.test(cls) ||
                           /build\.php/.test(href);
                }) || candidates.find(el => {
                    const cls = (el.className || '').toString().toLowerCase();
                    return /green/.test(cls) && /build|upgrade/.test(cls);
                });

                // Jika tombol Upgrade sudah enabled, Travian sendiri sudah menyatakan
                // bahwa resource cukup. Jangan menggagalkan upgrade hanya karena parser
                // biaya gagal membaca markup versi tertentu.
                if (btn && costs.some(x => x <= 0)) {
                    return JSON.stringify({state:'ready_button', current, costs, deficit:[0,0,0,0]});
                }

                if (costs.some(x => x <= 0)) return JSON.stringify({state:'costs_unknown', current, costs});
                if (!btn) return JSON.stringify({state:'not_found', current, costs});
                const deficit = costs.map((c,i) => Math.max(0, c - current[i]));
                return JSON.stringify({state:'ready', current, costs, deficit});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val deficitMatch = Regex("\\\"deficit\\\":\\[(.*?)\\]").find(result)
            val deficit = deficitMatch?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            val costMatch = Regex("\\\"costs\\\":\\[(.*?)\\]").find(result)
            val costs = costMatch?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            when {
                result.contains("ready_button") -> {
                    logEvent("Resource Builder: tombol upgrade tersedia, langsung menjalankan upgrade di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                    clickResourceUpgrade()
                }
                result.contains("costs_unknown") -> {
                    if (builderAttempt < 5) {
                        builderAttempt++
                        handler.postDelayed({ inspectUpgradeResources() }, 900)
                    } else {
                        logEvent("Resource Builder: biaya upgrade tidak terbaca di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                result.contains("not_found") -> {
                    if (builderAttempt < 5) {
                        builderAttempt++
                        handler.postDelayed({ inspectUpgradeResources() }, 900)
                    } else {
                        logEvent("Resource Builder: tombol upgrade tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                deficit.isEmpty() || deficit.all { it <= 0L } -> clickResourceUpgrade()
                costs.size >= 4 && deficit.size >= 4 -> {
                    pendingUpgradeCosts = LongArray(4) { deficit[it].coerceAtLeast(0L).let { v -> if (v == 0L) 0L else ((v + 99L) / 100L) * 100L } }
                    pendingUpgradeUrl = webView?.url.orEmpty().ifBlank { "$server/build.php" }
                    val total = pendingUpgradeCosts.sum()
                    logEvent("Resource Builder: resource village kurang; kebutuhan inventory=${pendingUpgradeCosts.joinToString(",")}, total=$total")
                    inventoryUseAttempt = 0
                    updateNotification("Resource Builder — mengambil resource Hero")
                    automationWebView()?.loadUrl("$server/hero/inventory")
                }
                else -> {
                    logEvent("Resource Builder: biaya upgrade tidak terbaca; village dilewati")
                    goToNextBuilderVillage()
                }
            }
        }
    }

    private fun useHeroInventoryForPendingUpgrade(): Unit {
        debugTrace("ENTER useHeroInventoryForPendingUpgrade")
        if (!running || !builderInProgress || pendingUpgradeUrl.isBlank()) return
        inventoryUseAttempt++
        if (inventoryUseAttempt > 5) {
            logEvent("Resource Builder: gagal menggunakan resource Hero setelah 5 percobaan")
            pendingUpgradeUrl = ""
            goToNextBuilderVillage()
            return
        }

        val needed = pendingUpgradeCosts.joinToString(",")
        val js = """
            (() => {
                const needed = [$needed];
                const names = ['lumber','clay','iron','crop'];
                const patterns = [
                    /lumber|wood/i,
                    /clay/i,
                    /iron/i,
                    /crop/i
                ];
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, String(value)); else el.value = String(value);
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };
                const inventoryRoot = document.querySelector('#heroInventory, .heroInventory, .inventory, [class*="inventory"]') || document.body;
                const elements = [...inventoryRoot.querySelectorAll('*')].filter(visible);
                const metadata = el => [
                    el.getAttribute('title'), el.getAttribute('alt'), el.getAttribute('aria-label'),
                    el.getAttribute('data-item'), el.getAttribute('data-item-type'), el.getAttribute('data-type'),
                    (el.className || '').toString(), el.id || ''
                ].filter(Boolean).join(' ');

                let used = false;
                const missing = [];
                for (let i=0;i<4;i++) {
                    if (needed[i] <= 0) continue;
                    const candidate = elements.find(el => {
                        const meta = metadata(el);
                        return patterns[i].test(meta) && (
                            /item|resource|inventory|slot/i.test(meta) || el.tagName === 'IMG'
                        );
                    });
                    if (!candidate) { missing.push(names[i]); continue; }
                    const slot = candidate.closest('[data-item-id],[data-slot],.item,.slot,[class*="item"],[class*="slot"]') || candidate.parentElement || candidate;
                    slot.scrollIntoView({block:'center'});
                    candidate.click();
                    used = true;
                    break;
                }
                if (!used) return JSON.stringify({state:'no_item', missing});
                return JSON.stringify({state:'item_clicked'});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result.contains("no_item") -> {
                    logEvent("Resource Builder: item resource Hero tidak ditemukan untuk kebutuhan ${pendingUpgradeCosts.joinToString(",")}")
                    pendingUpgradeUrl = ""
                    goToNextBuilderVillage()
                }
                result.contains("item_clicked") -> {
                    handler.postDelayed({ fillHeroResourceDialog() }, 600)
                }
                else -> handler.postDelayed({ useHeroInventoryForPendingUpgrade() }, 700)
            }
        }
    }

    private fun fillHeroResourceDialog(): Unit {
        debugTrace("ENTER fillHeroResourceDialog")
        if (!running || !builderInProgress || pendingUpgradeUrl.isBlank()) return
        val needed = pendingUpgradeCosts.joinToString(",")
        val js = """
            (() => {
                const needed = [$needed];
                const names = ['lumber','clay','iron','crop'];
                const inputs = [
                    document.querySelector('input[name="lumber"]'),
                    document.querySelector('input[name="clay"]'),
                    document.querySelector('input[name="iron"]'),
                    document.querySelector('input[name="crop"]')
                ];
                const setValue = (el, value) => {
                    if (!el) return false;
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, String(value)); else el.value = String(value);
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return true;
                };
                let found = 0;
                for (let i=0;i<4;i++) if (needed[i] > 0 && setValue(inputs[i], needed[i])) found++;
                if (!found) {
                    const all = [...document.querySelectorAll('input[type=number], input[type=text]')];
                    const candidates = all.filter(x => x.offsetParent !== null && !x.disabled);
                    for (let i=0;i<4 && i<candidates.length;i++) if (needed[i] > 0) { setValue(candidates[i], needed[i]); found++; }
                }
                if (!found) return 'no_amount_inputs';
                const buttons = [...document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')]
                    .filter(x => x.offsetParent !== null && !x.disabled);
                const norm = x => (x || '').replace(/\s+/g,' ').trim().toLowerCase();
                const confirm = buttons.find(x => /confirm|use|transfer|send|ok|done|accept/.test(norm(x.innerText || x.textContent || x.value || x.title || x.getAttribute('aria-label'))));
                if (!confirm) return 'no_confirm';
                confirm.click();
                return 'confirmed';
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result == "confirmed" -> {
                    logEvent("Resource Builder: resource Hero digunakan (${pendingUpgradeCosts.joinToString(",")})")
                    handler.postDelayed({
                        automationWebView()?.loadUrl(pendingUpgradeUrl)
                    }, 900)
                }
                result == "no_amount_inputs" || result == "no_confirm" -> {
                    if (inventoryUseAttempt < 5) handler.postDelayed({ fillHeroResourceDialog() }, 800)
                    else {
                        logEvent("Resource Builder: dialog penggunaan resource Hero tidak dikenali")
                        pendingUpgradeUrl = ""
                        goToNextBuilderVillage()
                    }
                }
                else -> handler.postDelayed({ fillHeroResourceDialog() }, 800)
            }
        }
    }

    private fun clickResourceUpgrade() {
        debugTrace("ENTER clickResourceUpgrade")
        if (!running || !builderInProgress) return

        val currentUrl = automationWebView()?.url.orEmpty()
        if (!currentUrl.contains("build.php", ignoreCase = true) ||
            currentUrl.contains("gid=16", ignoreCase = true)) {
            logEvent("Resource Builder: batal klik Upgrade karena bukan halaman resource build.php; URL=$currentUrl")
            builderStage = "OPEN_RESOURCE"
            handler.postDelayed({ openSavedBuilderResource() }, 400)
            return
        }

        builderStage = "WAIT_UPGRADE"
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim().toLowerCase();
                const root = document.querySelector('#build, #villageContent') || document.body;
                const all = [...root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')];
                const candidates = all.filter(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                const btn = candidates.find(el => {
                    const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    const cls = (el.className || '').toString().toLowerCase();
                    const href = (el.getAttribute('href') || '').toLowerCase();
                    if (/cancel|demolish|destroy|remove/.test(text + ' ' + cls)) return false;
                    return /upgrade|upgrade to level|build/.test(text) ||
                           /(?:^|\s)(green|build|upgrade)(?:\s|$)/.test(cls) ||
                           /build\.php/.test(href);
                }) || candidates.find(el => {
                    const cls = (el.className || '').toString().toLowerCase();
                    return /green/.test(cls) && /build|upgrade/.test(cls);
                });
                if (!btn) return 'not-found';
                btn.scrollIntoView({block:'center'});
                const href = btn.getAttribute('href') || '';
                if (href && /build\.php/i.test(href)) {
                    window.location.href = href;
                    return 'navigated:' + href;
                }
                btn.click();
                return 'clicked:' + (btn.innerText || btn.value || 'upgrade');
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            if (result.startsWith("clicked") || result.startsWith("navigated")) {
                pendingUpgradeUrl = ""
                pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)
                logEvent("Resource Builder: upgrade berhasil diklik di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                handler.postDelayed({ goToNextBuilderVillage() }, 1200)
            } else if (builderAttempt < 5) {
                builderAttempt++
                handler.postDelayed({ inspectUpgradeResources() }, 900)
            } else {
                pendingUpgradeUrl = ""
                logEvent("Resource Builder: tombol upgrade tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                goToNextBuilderVillage()
            }
        }
    }

    private fun goToNextBuilderVillage() {
        debugTrace("ENTER goToNextBuilderVillage")
        if (!builderInProgress) return

        // Cegah callback ganda menaikkan index dua kali.
        if (builderStage == "ADVANCING") return
        builderStage = "ADVANCING"

        pendingBuilderResourceHref = ""
        builderVillageClickInProgress = false
        pendingUpgradeUrl = ""
        pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)

        builderVillageIndex++
        handler.postDelayed({
            if (!running || !builderInProgress) return@postDelayed
            builderStage = "LOAD_DORF"
            processResourceBuilderVillage()
        }, 700)
    }

    private fun finishResourceBuilderCycle() {
        debugTrace("ENTER finishResourceBuilderCycle")
        val now = System.currentTimeMillis()
        if (resourceBuilderCycleStartedAt > 0L) {
            val duration = (now - resourceBuilderCycleStartedAt).coerceAtLeast(0L)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("resource_cycle_duration_ms", duration)
                .putLong("resource_cycle_started_at", 0L)
                .apply()
            logEvent("Resource Builder: waktu proses ${formatDuration(duration)}")
            resourceBuilderCycleStartedAt = 0L
        }
        builderInProgress = false
        builderVillages.clear()
        builderVillageIndex = 0
        pendingBuilderResourceHref = ""
        builderVillageClickInProgress = false
        builderStage = "IDLE"
        builderStage = "IDLE"
        logEvent("Resource Builder: siklus selesai")
        scheduleNextRandomRun()
        updateNotification("Next Run ${timeFormat.format(Date(nextAt))} | dalam ${formatDuration((nextAt - System.currentTimeMillis()).coerceAtLeast(0L))}")
    }

    private fun loadBuilderVillagesFromSnapshot(): MutableList<Pair<String, String>> {
        debugTrace("ENTER loadBuilderVillagesFromSnapshot")
        val array = runCatching { org.json.JSONArray(selectedBuilderVillagesJson) }.getOrNull()
            ?: return mutableListOf()
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().ifBlank { "Village $id" }
            if (id.isBlank() || !selectedBuilderVillageIds.contains(id) || !seen.add(id)) continue
            out.add(id to name)
        }
        // Jika selection tidak dikonfigurasi, gunakan semua village yang tersimpan
        // pada snapshot. Tidak ada discovery ulang dari sidebar.
        if (!builderSelectionConfigured && out.isEmpty()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { "Village $id" }
                if (id.isNotBlank() && seen.add(id)) out.add(id to name)
            }
        }
        return out
    }

    private fun loadSavedBuilderVillageLinks() {
        debugTrace("ENTER loadSavedBuilderVillageLinks")
        builderVillageLinks.clear()
        val array = runCatching { org.json.JSONArray(selectedBuilderVillagesJson) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val href = item.optString("href").trim()
            if (id.isNotBlank() && href.isNotBlank()) builderVillageLinks[id] = href
        }
        logEvent("Resource Builder: ${builderVillageLinks.size} link village tersimpan dimuat")
    }

    private fun refreshBuilderSelectionFromPrefs() {
        debugTrace("ENTER refreshBuilderSelectionFromPrefs")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        builderSelectionConfigured = prefs.getBoolean("resource_builder_selection_configured", builderSelectionConfigured)
        selectedBuilderVillageIds = prefs.getStringSet(
            "resource_builder_selected_villages",
            selectedBuilderVillageIds
        )?.toSet() ?: emptySet()
        selectedBuilderVillagesJson = prefs.getString(
            "resource_builder_villages_json",
            selectedBuilderVillagesJson
        ).orEmpty()
        logEvent(
            "Resource Builder: selection terbaru dimuat — " +
                if (builderSelectionConfigured) {
                    if (selectedBuilderVillageIds.isEmpty()) "tidak ada village"
                    else selectedBuilderVillageIds.joinToString(", ")
                } else "SEMUA village"
        )
    }

    private fun loadSavedBuilderResourceTargets(overridePrefs: android.content.SharedPreferences? = null) {
        debugTrace("ENTER loadSavedBuilderResourceTargets")
        val prefs = overridePrefs ?: getSharedPreferences(PREFS, MODE_PRIVATE)
        builderResourceLinks.clear()
        builderResourceLevels.clear()
        val raw = prefs.getString("resource_builder_targets_json", "{}").orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        for (key in obj.keys()) {
            val item = obj.optJSONObject(key) ?: continue
            val href = item.optString("href").trim()
            if (href.isBlank()) continue
            builderResourceLinks[key] = href
            builderResourceLevels[key] = item.optInt("level", -1).takeIf { it >= 0 } ?: -1
        }
        logEvent("Resource Builder: ${builderResourceLinks.size} target resource tersimpan dimuat")
    }

    private fun absoluteBuilderHref(href: String): String {
        val clean = href.trim()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        return when {
            clean.startsWith("/") -> server + clean
            clean.startsWith("./") -> "$server/${clean.removePrefix("./")}"
            else -> "$server/$clean"
        }
    }

    private fun discoverVillagesForBuilder() {
        debugTrace("ENTER discoverVillagesForBuilder")
        if (!running || !builderInProgress) return
        if (builderVillages.isNotEmpty() || builderStage != "DISCOVER" || builderDiscoverInFlight) return
        builderDiscoverInFlight = true
        val js = """
            (async () => {
                const villages = [];
                const seen = new Set();
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const add = (value, nameHint='') => {
                    const text = String(value || '');
                    const patterns = [
                        /[?&]newdid=(\d+)/i,
                        /(?:newdid|did|villageId|village_id)[=:'" ]+(\d+)/i
                    ];
                    let id = null;
                    if (/^\d+$/.test(text.trim())) {
                        // data-did contains the numeric ID directly.
                        id = text.trim();
                    } else {
                        for (const re of patterns) {
                            const m = text.match(re);
                            if (m) { id=m[1]; break; }
                        }
                    }
                    if (id && !seen.has(id)) {
                        seen.add(id);
                        villages.push({id, name: clean(nameHint) || ('Village ' + id)});
                    }
                };
                const scan = root => {
                    if (!root) return;

                    // Travian T4/T5: village entries are .listEntry and the
                    // village ID is stored in data-did. Do not depend only on
                    // href containing newdid because newer village-list markup
                    // can use data-did on the entry itself.
                    const entries = root.querySelectorAll('.listEntry');
                    for (const entry of entries) {
                        const id = entry.getAttribute('data-did') ||
                                   entry.dataset.did ||
                                   entry.getAttribute('data-village-id') ||
                                   entry.getAttribute('data-villageid');
                        const nameEl = entry.querySelector('.name');
                        const link = entry.querySelector('a[href]');
                        const name = clean(
                            nameEl ? (nameEl.textContent || '') :
                            (entry.getAttribute('data-name') || '')
                        );
                        if (id) add(id, name);
                        if (link) {
                            add(link.getAttribute('href'), name);
                            add(link.outerHTML, name);
                        }
                    }

                    // Older Travian markup / profile village list.
                    for (const a of root.querySelectorAll('a[href*="newdid="], a[title][href]')) {
                        const name = clean(
                            a.querySelector('.name')?.textContent ||
                            a.getAttribute('title') ||
                            a.textContent ||
                            a.getAttribute('aria-label') || ''
                        );
                        add(a.getAttribute('href'), name);
                    }

                    // Last-resort data attributes used by some layouts.
                    for (const el of root.querySelectorAll('[data-did],[data-village-id],[data-villageid],[data-newdid]')) {
                        const id = el.getAttribute('data-did') ||
                                   el.getAttribute('data-village-id') ||
                                   el.getAttribute('data-villageid') ||
                                   el.getAttribute('data-newdid');
                        const nameEl = el.querySelector?.('.name');
                        const name = clean(
                            nameEl ? nameEl.textContent :
                            el.getAttribute('title') ||
                            el.textContent || ''
                        );
                        add(id, name);
                    }
                };

                [document.querySelector('#sidebarBoxVillagelist'),
                 document.querySelector('#villageList'),
                 document.querySelector('#villageList .list'),
                 document.querySelector('#side_info'),
                 document.body].filter(Boolean).forEach(scan);

                const current = location.search.match(/[?&]newdid=(\d+)/i);
                if (current && !seen.has(current[1])) {
                    seen.add(current[1]);
                    villages.unshift({id: current[1], name: 'Village ' + current[1]});
                }

                const bodyText = (document.body.innerText || '').replace(/\s+/g, ' ');
                let expected = 0;
                const countMatch = bodyText.match(/VILLAGES\s+(\d+)\s*\/\s*\d+/i) ||
                                   bodyText.match(/VILLAGES\s*\(?\s*(\d+)\s*\/\s*\d+\)?/i);
                if (countMatch) expected = parseInt(countMatch[1], 10) || 0;

                if (expected > 0 && villages.length < expected) {
                    try {
                        let uid = null;
                        const profileLink = [...document.querySelectorAll('a[href]')]
                            .map(a => a.getAttribute('href') || '')
                            .find(h => /spieler\.php\?uid=\d+/i.test(h));
                        if (profileLink) {
                            const m = profileLink.match(/[?&]uid=(\d+)/i);
                            if (m) uid = m[1];
                        }
                        if (uid) {
                            const response = await fetch('spieler.php?uid=' + uid, {
                                credentials: 'include', cache: 'no-store'
                            });
                            const html = await response.text();
                            const doc = new DOMParser().parseFromString(html, 'text/html');
                            for (const root of [doc.querySelector('#villageList'),
                                                doc.querySelector('#sidebarBoxVillagelist'),
                                                doc.body].filter(Boolean)) scan(root);

                            // Profile pages have a dedicated village list. Parse each
                            // item explicitly so grouped/hidden sidebar villages are
                            // not lost.
                            for (const a of doc.querySelectorAll('#villageList .list li a[title][href], #villageList li a[href*="newdid="]')) {
                                const name = clean(a.getAttribute('title') || a.textContent || '');
                                add(a.getAttribute('href'), name);
                            }
                        }
                    } catch (e) {}
                }

                AndroidFarm.onVillageListResult(JSON.stringify({villages, expected}));
            })().catch(e => AndroidFarm.onVillageListResult(JSON.stringify({villages:[],expected:0,error:String(e)})));
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js, null)
    }

    private fun handleVillageListResult(rawJson: String) {
        debugTrace("ENTER handleVillageListResult")
        builderDiscoverInFlight = false
        if (!running || !builderInProgress) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val villages = mutableListOf<Pair<String, String>>()
        val villageArray = json?.optJSONArray("villages")
        if (villageArray != null) {
            for (i in 0 until villageArray.length()) {
                val item = villageArray.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { "Village $id" }
                if (id.isNotBlank()) villages.add(id to name)
            }
        }

        val uniqueVillages = villages.distinctBy { it.first }
        val expected = json?.optInt("expected", 0) ?: 0
        var selectedVillages = if (builderSelectionConfigured) {
            uniqueVillages.filter { selectedBuilderVillageIds.contains(it.first) }
        } else uniqueVillages

        // Gunakan daftar tersimpan dari UI bila halaman aktif tidak merender sidebar lengkap.
        if (builderSelectionConfigured && selectedVillages.size < selectedBuilderVillageIds.size) {
            val savedArray = runCatching { org.json.JSONArray(selectedBuilderVillagesJson) }.getOrNull()
            if (savedArray != null) {
                val savedVillages = mutableListOf<Pair<String, String>>()
                for (i in 0 until savedArray.length()) {
                    val item = savedArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim().ifBlank { "Village $id" }
                    if (id.isNotBlank() && selectedBuilderVillageIds.contains(id)) {
                        savedVillages.add(id to name)
                    }
                }
                if (savedVillages.size >= selectedBuilderVillageIds.size) selectedVillages = savedVillages
            }
        }

        if (builderSelectionConfigured && selectedBuilderVillageIds.isEmpty()) {
            builderVillages = mutableListOf()
            logEvent("Resource Builder: tidak ada village yang dicentang; Builder dilewati pada siklus ini")
            finishResourceBuilderCycle()
            return
        }

        val completeSelectedList = builderSelectionConfigured &&
            selectedBuilderVillageIds.isNotEmpty() &&
            selectedVillages.size >= selectedBuilderVillageIds.size

        if (uniqueVillages.isEmpty() || ((expected > 0 && uniqueVillages.size < expected) && !completeSelectedList)) {
            if (builderAttempt < 10) {
                builderAttempt++
                logEvent("Resource Builder: village terdeteksi ${uniqueVillages.size}${if (expected > 0) "/$expected" else ""}; retry ${builderAttempt}/10")
                handler.postDelayed({ discoverVillagesForBuilder() }, 1800)
            } else {
                logEvent("Resource Builder: daftar village belum lengkap setelah 10 retry (${uniqueVillages.size}${if (expected > 0) "/$expected" else ""}); siklus dibatalkan agar tidak memproses sebagian village")
                finishResourceBuilderCycle()
            }
            return
        }

        builderVillages = selectedVillages.toMutableList()
        builderAttempt = 0
        builderStage = "LOAD_DORF"
        logEvent("Resource Builder: ${uniqueVillages.size} village ditemukan; ${selectedVillages.size} village dipilih: ${selectedVillages.joinToString(" | ") { "${it.second} [${it.first}]" }}")
        processResourceBuilderVillage()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun persistActiveCycleDuration(now: Long = System.currentTimeMillis()) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val edit = prefs.edit()
        if (farmListCycleStartedAt > 0L) {
            edit.putLong("farm_cycle_duration_ms", (now - farmListCycleStartedAt).coerceAtLeast(0L))
            edit.putLong("farm_cycle_started_at", 0L)
            farmListCycleStartedAt = 0L
        }
        if (resourceBuilderCycleStartedAt > 0L) {
            edit.putLong("resource_cycle_duration_ms", (now - resourceBuilderCycleStartedAt).coerceAtLeast(0L))
            edit.putLong("resource_cycle_started_at", 0L)
            resourceBuilderCycleStartedAt = 0L
        }
        edit.apply()
    }

    private fun scheduleNextRandomRun() {
        debugTrace("ENTER scheduleNextRandomRun")
        if (!running) return
        persistActiveCycleDuration()
        handler.removeCallbacks(cycleWatchdogRunnable)
        val chosenMinutes = if (maxMinutes <= minMinutes) minMinutes
        else Random.nextLong(minMinutes, maxMinutes + 1)
        val delay = chosenMinutes * 60_000L
        nextAt = System.currentTimeMillis() + delay
        updateNextRun(delay)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("cycle_active", false).apply()
        handler.removeCallbacks(nextRunRunnable)
        handler.postDelayed(nextRunRunnable, delay)
        logEvent("Interval berikutnya dipilih acak: $chosenMinutes menit; Next Run=${timeFormat.format(Date(nextAt))}")
        updateNotification("Next Run ${timeFormat.format(Date(nextAt))} | dalam ${formatDuration(delay)}")
    }

    private val nextRunRunnable = Runnable {
        if (running) triggerScheduledCycle()
    }

    private fun updateNextRun(delayMs: Long) {
        debugTrace("ENTER updateNextRun")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("next_run_at", if (delayMs == 0L) 0L else nextAt)
            .apply()
    }

    private fun acceptCookiesIfPresent(done: (String) -> Unit) {
        debugTrace("ENTER acceptCookiesIfPresent")
        val js = """
            (() => {
              const visible = el => {
                if (!el) return false; const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
              const roots = [document];
              for (let i=0;i<roots.length;i++) {
                let els=[]; try { els=[...roots[i].querySelectorAll('*')]; } catch(_) {}
                for (const el of els) if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
              }
              const selectors=['#cmpwelcomebtnyes a','#cmpwelcomebtnyes','.cmpboxbtnyes','#cmpbntyestxt','[class*="cmpboxbtnyes"]','[id*="cmpwelcomebtnyes"]'];
              let bannerVisible=false;
              for (const root of roots) {
                try { const box=root.querySelector('#cmpbox,#cmpbox2,.cmpbox,.cmpmore'); if(box&&visible(box)) bannerVisible=true; } catch(_){}
                for(const sel of selectors){ let el=null; try{el=root.querySelector(sel);}catch(_){} if(el&&visible(el)){try{el.click();return 'clicked';}catch(_){} } }
                let candidates=[]; try{candidates=[...root.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')];}catch(_){}
                const accept=candidates.find(el=>visible(el)&&/^(accept all|accept all cookies|allow all|agree all|alle akzeptieren|tout accepter|aceptar todo)$/.test(norm(el.innerText||el.textContent||el.value||el.title||el.getAttribute('aria-label'))));
                if(accept){try{accept.click();return 'clicked';}catch(_){} }
              }
              const host=document.querySelector('#cmpwrapper'); if(host&&visible(host)) bannerVisible=true;
              return bannerVisible?'visible':'absent';
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw -> done(raw.orEmpty().trim('"').lowercase(Locale.US)) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun recoverWebView() {
        debugTrace("ENTER recoverWebView")
        if (!running || webViewRecoveryInProgress) return
        webViewRecoveryInProgress = true
        try {
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        ensureServiceWebView()
        webViewRecoveryInProgress = false

        val resumeUrl = when {
            loginInProgress || isLikelyLoginPage(lastAutomationUrl.lowercase(Locale.US)) -> server
            builderInProgress && lastAutomationUrl.contains("dorf1.php", ignoreCase = true) -> lastAutomationUrl
            pendingStartAll -> "$server/build.php?id=39&gid=16&tt=99"
            farmListEnabled -> "$server/build.php?id=39&gid=16&tt=99"
            else -> "$server/dorf1.php"
        }
        logEvent("RECOVERY: WebView baru siap; melanjutkan dari $resumeUrl")
        handler.postDelayed({
            if (running) automationWebView()?.loadUrl(resumeUrl)
        }, 250L)
    }

    private fun encryptPassword(value: String): String {
        debugTrace("ENTER encryptPassword")
        if (value.isEmpty()) return ""
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = if (keyStore.containsAlias(PASSWORD_KEY_ALIAS)) {
                (keyStore.getKey(PASSWORD_KEY_ALIAS, null) as javax.crypto.SecretKey)
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        PASSWORD_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            logEvent("RECOVERY: gagal mengenkripsi password (${e.javaClass.simpleName})")
            ""
        }
    }

    private fun decryptPassword(encoded: String): String {
        debugTrace("ENTER decryptPassword")
        if (encoded.isEmpty()) return ""
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(PASSWORD_KEY_ALIAS)) return ""
            val key = keyStore.getKey(PASSWORD_KEY_ALIAS, null) as javax.crypto.SecretKey
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            if (packed.size <= 12) return ""
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            logEvent("RECOVERY: gagal mendekripsi password (${e.javaClass.simpleName})")
            ""
        }
    }

    private fun stopAutomation() {
        debugTrace("ENTER stopAutomation")
        persistActiveCycleDuration()
        running = false
        handler.removeCallbacks(cycleWatchdogRunnable)
        handler.removeCallbacks(delayedVillageRefreshRunnable)
        pendingStartAll = false
        handler.removeCallbacksAndMessages(null)
        if (webView != null) {
            webView?.destroy()
            webView = null
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("service_running", false)
            .putLong("next_run_at", 0L)
            .putBoolean("cycle_active", false)
            .putBoolean("farm_list_enabled", farmListEnabled)
            .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
            .apply()
        logEvent("Background service dihentikan")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        debugTrace("ENTER updateNotification")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        debugTrace("ENTER buildNotification")
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Travian Farm Assistant — AKTIF")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Travian Farm Assistant — AKTIF")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending)
                .build()
        }
    }

    private fun createNotificationChannel() {
        debugTrace("ENTER createNotificationChannel")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Farm Assistant Background", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun normalizeServer(value: String): String {
        debugTrace("ENTER normalizeServer")
        var s = value.trim()
        if (s.isBlank()) s = "https://ts20.x2.europe.travian.com"
        if (!s.startsWith("http")) s = "https://$s"
        return s.trimEnd('/')
    }

    /** Verbose diagnostics: every function entry is sent to Logcat and, except high-frequency UI helpers, to the app log. */
    private fun debugTrace(message: String) {
        android.util.Log.d("TravianFarmAssistant", "[DEBUG] $message")
        val quiet = message.removePrefix("ENTER ").substringBefore("(")
        if (quiet !in setOf("updateCountdown", "refreshRecentLogs", "pruneLogs", "showLogs")) {
            logEvent("[DEBUG] $message")
        }
    }

    private fun logEvent(message: String) {
        val cycleTag = if (cycleNumber > 0) "[CYCLE $cycleNumber]" else "[SYSTEM]"
        val line = "${logTimeFormat.format(Date())} | $cycleTag $message"
        try {
            openFileOutput(logFileName, MODE_APPEND).bufferedWriter().use { it.appendLine(line) }
            pruneLogs()
        } catch (_: Exception) {}
    }

    private fun pruneLogs() {
        debugTrace("ENTER pruneLogs")
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try { logTimeFormat.parse(line.substringBefore(" | "))?.time ?: 0L >= cutoff }
                catch (_: Exception) { false }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {}
    }

    inner class FarmBridge {
        @JavascriptInterface
        fun onLoginResult(result: String) {
            debugTrace("ENTER onLoginResult")
            handler.post {
                if (!running) return@post
                when (result) {
                    "submitting" -> updateNotification("Farm Assistant — mengirim login")
                    "no_login_form" -> {
                        loginInProgress = false
                        reloginRequested = false
                        loginRetryCount = 0
                        logEvent("Session aktif terdeteksi; membuka Farm List")
                        handler.postDelayed({ triggerStartAllFarmLists() }, 250)
                    }
                    "no_username_field", "no_form" -> {
                        if (loginRetryCount < 20 && running) handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                        else {
                            loginInProgress = false
                            reloginRequested = false
                            logEvent("Form login Travian tidak dikenali")
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onVillageListResult(result: String) {
            debugTrace("ENTER onVillageListResult")
            handler.post {
                handleVillageListResult(result)
            }
        }
    }

    override fun onDestroy() {
        debugTrace("ENTER onDestroy")
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        instanceRef = null
        visibleWebViewRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        debugTrace("ENTER onBind")
        return null
    }
}
