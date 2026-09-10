package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import android.widget.*
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun requestVillageRefreshFromService(): Boolean {
            val activity = instanceRef?.get() ?: return false
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    activity.logEvent("AUTO: REFRESH VILLAGE dijalankan 1 menit setelah Next Run")
                    activity.villageScanActive = false
                    activity.villageScanTargets.clear()
                    activity.villageScanResults.clear()
                    activity.villageScanIndex = 0
                    activity.villageScanExpected = 0
                    activity.villageScanRetry = 0
                    activity.villageScanPageRetry = 0
                    activity.villageScanDataRetry = 0
                    activity.refreshVillagesForUi()
                }
            }
            return true
        }
    }
    private lateinit var webView: WebView
    private lateinit var farmStatus: TextView
    private lateinit var status: TextView
    private lateinit var lastRun: TextView
    private lateinit var nextRun: TextView
    private lateinit var farmCycleTime: TextView
    private lateinit var resourceCycleTime: TextView
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var refreshVillageLinkPreview: TextView
    private lateinit var resourceBuilderVillageLinkPreview: TextView
    private lateinit var villageChecklist: LinearLayout
    private var loadedVillages = linkedMapOf<String, String>()

    private data class ResourceSnapshot(
        val villageId: String,
        val villageName: String,
        val wood: Int = -1,
        val woodCap: Int = -1,
        val clay: Int = -1,
        val clayCap: Int = -1,
        val iron: Int = -1,
        val ironCap: Int = -1,
        val crop: Int = -1,
        val cropCap: Int = -1,
        val woodProd: Int = -1,
        val clayProd: Int = -1,
        val ironProd: Int = -1,
        val cropProd: Int = -1,
        val updatedAt: String = "--"
    )

    private val resourceSnapshots = linkedMapOf<String, ResourceSnapshot>()
    private val recentLogRefreshRunnable = object : Runnable {
        override fun run() {
            debugTrace("ENTER run")
            if (!isFinishing) {
                refreshRecentLogs()
                handler.postDelayed(this, 1000L)
            }
        }
    }
    // Semua pembacaan file log dilakukan di thread background agar pindah tab
    // tidak memblokir UI/WebView.
    private val logIoExecutor = Executors.newSingleThreadExecutor()
    private var logOverviewRequestId = 0L
    private var recentLogRefreshScheduled = false

    private lateinit var farmTab: View
    private lateinit var capacityTab: View
    private lateinit var logTab: View
    private lateinit var farmTabButton: Button
    private lateinit var capacityTabButton: Button
    private lateinit var logTabButton: Button
    private lateinit var capacityOverview: LinearLayout
    private lateinit var capacityStatus: TextView
    private lateinit var logOverview: TextView
    private lateinit var recentLogs: TextView
    private lateinit var botToggle: Switch
    private var selectionControlsLocked = false

    // Scanner village UI: setelah daftar link ditemukan, WebView benar-benar
    // berpindah ke village satu per satu agar nama + resource dibaca dari halaman
    // village yang sebenarnya. Ini sengaja dibuat terlihat di Live WebView.
    private var villageScanActive = false
    private var villageScanTargets = mutableListOf<Pair<String, String>>()
    private var villageScanIndex = 0
    private var villageScanResults = mutableListOf<Pair<String, String>>()
    private val villageMinLevels = mutableMapOf<String, Int>()
    private val villageMinResourceDetails = mutableMapOf<String, Pair<String, String>>()
    private var villageScanExpected = 0
    private var villageScanRetry = 0
    private var villageScanPageRetry = 0
    private var villageScanDataRetry = 0
    private var villageScanScrollPass = 0
    private val villageScanCollectedTargets = linkedMapOf<String, String>()
    // Link village disimpan saat discovery agar Builder dapat mengikuti link village yang sama.
    private val villageScanCollectedLinks = linkedMapOf<String, String>()
    private var villageScanCollectInFlight = false

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextAt = 0L
    private lateinit var minIntervalInput: EditText
    private lateinit var maxIntervalInput: EditText
    private var loginInProgress = false
    private var loginRetryCount = 0
    private var reloginRequested = false
    private var farmListRequested = false
    private var pendingStartAll = false
    private var startAllAttempt = 0
    private var pendingUsername = ""
    private var pendingPassword = ""
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L
    private var lastLogPruneAt = 0L
    private val passwordKeyStore = "AndroidKeyStore"
    private val passwordKeyAlias = "TravianFarmAssistantPassword"
    private val logCleanup = object : Runnable {
        override fun run() {
            debugTrace("ENTER run")
            pruneLogs()
            handler.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    private val countdownUpdater = object : Runnable {
        override fun run() {
            debugTrace("ENTER run")
            updateCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        debugTrace("ENTER onCreate")
        super.onCreate(savedInstanceState)
        instanceRef = java.lang.ref.WeakReference(this)
        setContentView(R.layout.activity_main)

        farmTab = findViewById(R.id.farmTab)
        capacityTab = findViewById(R.id.capacityTab)
        logTab = findViewById(R.id.logTab)
        farmTabButton = findViewById(R.id.farmTabButton)
        capacityTabButton = findViewById(R.id.capacityTabButton)
        logTabButton = findViewById(R.id.logTabButton)
        capacityOverview = findViewById(R.id.capacityOverview)
        capacityStatus = findViewById(R.id.capacityStatus)
        logOverview = findViewById(R.id.logOverview)
        recentLogs = findViewById(R.id.recentLogs)
        botToggle = findViewById(R.id.botToggle)
        setupTabs()
        renderCapacityOverview()
        refreshLogOverview()

        serverInput = findViewById(R.id.server)
        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        farmStatus = findViewById(R.id.farmListStatus)
        refreshVillageLinkPreview = findViewById(R.id.refreshVillageLinkPreview)
        resourceBuilderVillageLinkPreview = findViewById(R.id.resourceBuilderVillageLinkPreview)
        updateVillageLinkPreviews()
        status = findViewById(R.id.status)
        lastRun = findViewById(R.id.lastRun)
        nextRun = findViewById(R.id.nextRun)
        farmCycleTime = findViewById(R.id.farmCycleTime)
        resourceCycleTime = findViewById(R.id.resourceCycleTime)
        webView = findViewById(R.id.webView)
        pruneLogs()
        handler.postDelayed(logCleanup, 60 * 60 * 1000L)
        handler.post(countdownUpdater)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        restoreResourceSnapshots(prefs)
        logEvent("Aplikasi v4.14 dimulai")
        serverInput.setText(prefs.getString("server", "https://ts20.x2.europe.travian.com"))
        usernameInput.setText(prefs.getString("username", ""))
        passwordInput.setText(decryptSavedPassword(prefs.getString("password_secure", "").orEmpty()))

        minIntervalInput = findViewById(R.id.intervalMin)
        maxIntervalInput = findViewById(R.id.intervalMax)
        val savedMin = prefs.getLong("interval_min_minutes", 5L)
        val savedMax = prefs.getLong("interval_max_minutes", savedMin)
        minIntervalInput.setText(savedMin.toString())
        maxIntervalInput.setText(savedMax.toString())

        val farmListEnabledCheck = findViewById<CheckBox>(R.id.farmListEnabled)
        val resourceBuilderCheck = findViewById<CheckBox>(R.id.resourceBuilder)
        farmListEnabledCheck.isChecked = prefs.getBoolean("farm_list_enabled", true)
        resourceBuilderCheck.isChecked = prefs.getBoolean("resource_builder_enabled", true)

        villageChecklist = findViewById(R.id.villageChecklist)
        restoreVillageSelection(prefs)

        findViewById<Button>(R.id.refreshVillages).setOnClickListener {
            // Klik baru = scan baru. Retry internal tidak mereset counter.
            villageScanActive = false
            villageScanTargets.clear()
            villageScanResults.clear()
            villageScanIndex = 0
            villageScanExpected = 0
            villageScanRetry = 0
            villageScanPageRetry = 0
            refreshVillagesForUi()
        }

        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // Live WebView selalu memakai User-Agent desktop + wide viewport supaya
        // halaman Travian dirender seperti desktop dan area Farm List lebih lengkap.
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.textZoom = 100
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(FarmBridge(), "AndroidFarm")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                debugTrace("ENTER onPageFinished")
                super.onPageFinished(view, url)
                if (url == null) return

                // DEBUG LIVE: pasang listener klik setiap kali halaman selesai dimuat.
                // Ini sengaja dipasang sebelum scanner/service supaya klik manual di Live
                // tetap tercatat walaupun halaman sedang dipakai automation.
                installLiveClickLogger(url)
                logEvent("LIVE PAGE FINISHED: $url")

                // Saat background service aktif, WebView Live adalah milik automation.
                // Forward event halaman terlebih dahulu agar Resource Builder tidak
                // tertahan oleh scanner UI.
                if (FarmAutomationService.isRunningFromService()) {
                    FarmAutomationService.forwardPageFinished(url)
                    return
                }

                // LOAD VILLAGE hanya berjalan ketika background service tidak aktif.
                if (villageScanActive) {
                    // Satu onPageFinished bisa terpanggil beberapa kali (redirect/hash/consent).
                    // Jangan menembakkan evaluateJavascript scan berulang secara bersamaan.
                    handler.postDelayed({
                        if (!villageScanActive) return@postDelayed
                        val target = villageScanTargets.getOrNull(villageScanIndex)
                        if (target != null) {
                            if (!villageScanCollectInFlight) collectCurrentVillageData()
                        } else {
                            collectVillageTargetsFromSidebar()
                        }
                    }, 900)
                    return
                }

                val lower = url.lowercase(Locale.US)

                // ConsentManager yang dipakai situs dapat berada di Shadow DOM.
                // Jangan lanjut login / parsing Farm List sampai layer consent benar-benar hilang.
                handlePageAfterConsent(url, lower, 0)
            }
        }

        FarmAutomationService.attachVisibleWebView(webView)

        findViewById<Button>(R.id.loginTravian).setOnClickListener {
            logEvent("Tombol LOGIN ditekan — setelah login scanner village otomatis dijalankan")
            startAutomaticLogin()
        }

        val serviceRunning = getSharedPreferences("config", MODE_PRIVATE)
            .getBoolean("service_running", false)
        running = serviceRunning
        botToggle.setOnCheckedChangeListener(null)
        botToggle.isChecked = serviceRunning
        updateBotToggleVisual(serviceRunning)
        botToggle.setOnCheckedChangeListener(this@MainActivity::handleBotToggle)
        setSelectionControlsLocked(serviceRunning)

        createNotificationChannel()
    }

    /**
     * Login sekarang benar-benar otomatis:
     * 1. Ambil username/password dari form.
     * 2. Buka server.
     * 3. Jika session masih aktif -> langsung mulai refresh/scan village.
     * 4. Jika belum login -> cari form login dan submit dari WebView.
     * 5. Setelah login/redirect sukses -> otomatis refresh/scan seluruh village.
     *
     * Password hanya disimpan di RAM selama aplikasi hidup dan tidak ditulis
     * ke SharedPreferences. Ini memungkinkan auto re-login ketika session
     * Travian expired, selama proses aplikasi masih berjalan.
     */
    private fun setSelectionControlsLocked(locked: Boolean) {
        debugTrace("ENTER setSelectionControlsLocked")
        selectionControlsLocked = locked
        val farmListCheck = findViewById<CheckBox>(R.id.farmListEnabled)
        val resourceBuilderCheck = findViewById<CheckBox>(R.id.resourceBuilder)
        farmListCheck.isEnabled = !locked
        resourceBuilderCheck.isEnabled = !locked
        findViewById<Button>(R.id.refreshVillages).isEnabled = !locked
        if (::villageChecklist.isInitialized) {
            for (i in 0 until villageChecklist.childCount) {
                (villageChecklist.getChildAt(i) as? CheckBox)?.isEnabled = !locked
            }
        }
    }

    private fun handleBotToggle(button: CompoundButton, checked: Boolean) {
        debugTrace("ENTER handleBotToggle")
        if (checked) {
            if (!startSchedulerFromToggle()) {
                botToggle.setOnCheckedChangeListener(null)
                botToggle.isChecked = false
                setSelectionControlsLocked(false)
                updateBotToggleVisual(false)
                botToggle.setOnCheckedChangeListener(this@MainActivity::handleBotToggle)
            }
        } else {
            stopScheduler()
        }
    }

    private fun updateBotToggleVisual(enabled: Boolean) {
        debugTrace("ENTER updateBotToggleVisual")
        botToggle.text = if (enabled) "BOT AKTIF" else "BOT MATI"
        botToggle.textOn = "BOT AKTIF"
        botToggle.textOff = "BOT MATI"
        status.text = if (enabled) "Status: RUNNING — BACKGROUND" else "Status: STOPPED"
    }

    private fun startSchedulerFromToggle(): Boolean {
        debugTrace("ENTER startSchedulerFromToggle")
        val minMinutes = minIntervalInput.text.toString().trim().toLongOrNull()
        val maxMinutes = maxIntervalInput.text.toString().trim().toLongOrNull()
        if (minMinutes == null || minMinutes < 1) {
            minIntervalInput.error = "Minimum 1 menit"
            minIntervalInput.requestFocus()
            farmStatus.text = "Interval minimum tidak valid."
            logEvent("Background service gagal dimulai: minimum interval tidak valid")
            return false
        }
        if (maxMinutes == null || maxMinutes < minMinutes) {
            maxIntervalInput.error = "Harus >= minimum"
            maxIntervalInput.requestFocus()
            farmStatus.text = "Interval maksimum harus >= minimum."
            logEvent("Background service gagal dimulai: maksimum interval tidak valid")
            return false
        }

        val server = normalizeServer(serverInput.text.toString())
        val farmListEnabled = findViewById<CheckBox>(R.id.farmListEnabled).isChecked
        val resourceBuilderEnabled = findViewById<CheckBox>(R.id.resourceBuilder).isChecked
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            farmStatus.text = "Username dan password harus diisi sebelum BOT AKTIF."
            logEvent("Background service gagal dimulai: username/password kosong")
            return false
        }

        getSharedPreferences("config", MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", user)
            .putLong("interval_min_minutes", minMinutes)
            .putLong("interval_max_minutes", maxMinutes)
            .putBoolean("farm_list_enabled", farmListEnabled)
            .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
            .putBoolean("resource_builder_selection_configured", loadedVillages.isNotEmpty())
            .putStringSet("resource_builder_selected_villages", selectedVillageIds())
            .putString("resource_builder_villages_json", villageSelectionJson())
            .apply()

        pendingUsername = user
        pendingPassword = pass
        running = true
        setSelectionControlsLocked(true)
        updateBotToggleVisual(true)
        farmStatus.text = "Background automation sedang dimulai..."
        logEvent("Memulai background automation. Range=${minMinutes}-${maxMinutes} menit; Farm List=${if (farmListEnabled) "ON" else "OFF"}; Resource Builder=${if (resourceBuilderEnabled) "ON" else "OFF"}")

        val intent = android.content.Intent(this, FarmAutomationService::class.java).apply {
            action = FarmAutomationService.ACTION_START
            putExtra(FarmAutomationService.EXTRA_SERVER, server)
            putExtra(FarmAutomationService.EXTRA_USERNAME, user)
            putExtra(FarmAutomationService.EXTRA_PASSWORD, pass)
            putExtra(FarmAutomationService.EXTRA_MINUTES_MIN, minMinutes)
            putExtra(FarmAutomationService.EXTRA_MINUTES_MAX, maxMinutes)
            putExtra(FarmAutomationService.EXTRA_FARM_LIST_ENABLED, farmListEnabled)
            putExtra(FarmAutomationService.EXTRA_RESOURCE_BUILDER, resourceBuilderEnabled)
            putExtra(FarmAutomationService.EXTRA_SELECTED_VILLAGES, selectedVillageIds().toTypedArray())
            putExtra(FarmAutomationService.EXTRA_SELECTED_VILLAGES_JSON, villageSelectionJson())
            putExtra(FarmAutomationService.EXTRA_SELECTION_CONFIGURED, loadedVillages.isNotEmpty())
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        return true
    }

    private fun startAutomaticLogin() {
        debugTrace("ENTER startAutomaticLogin")
        val server = normalizeServer(serverInput.text.toString())
        pendingUsername = usernameInput.text.toString().trim()
        pendingPassword = passwordInput.text.toString()

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            farmStatus.text = "Username dan password harus diisi."
            logEvent("Login gagal: username/password kosong")
            return
        }

        getSharedPreferences("config", MODE_PRIVATE)
            .edit()
            .putString("server", server)
            .putString("username", pendingUsername)
            .apply()

        loginInProgress = true
        reloginRequested = false
        loginRetryCount = 0
        farmListRequested = false
        farmStatus.text = "Menghubungkan ke Travian..."
        logEvent("Memulai login ke $server sebagai $pendingUsername")

        webView.loadUrl(server)
    }

    private fun isLikelyLoginPage(url: String): Boolean {
        debugTrace("ENTER isLikelyLoginPage")
        return url.contains("login") ||
            url.contains("logout") ||
            url.contains("anmelden") ||
            url.contains("signin")
    }

    private fun autoLoginIfNeeded() {
        debugTrace("ENTER autoLoginIfNeeded")
        if (!loginInProgress) return

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            loginInProgress = false
            farmStatus.text = "Tidak bisa auto re-login: username/password tidak tersedia."
            return
        }

        loginRetryCount++
        if (loginRetryCount > 20) {
            loginInProgress = false
            reloginRequested = false
            farmStatus.text = "Auto re-login gagal setelah beberapa percobaan. Silakan login manual."
            logEvent("Auto re-login gagal setelah 20 percobaan")
            return
        }

        val usernameJson = JSONObject.quote(pendingUsername)
        val passwordJson = JSONObject.quote(pendingPassword)

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
                    (x.type || '').toLowerCase() === 'password' ||
                    /pass|password/i.test(x.name || '') ||
                    /pass|password/i.test(x.id || '')
                );

                if (!passwordInput) {
                    AndroidFarm.onLoginResult('no_login_form');
                    return;
                }

                const userInput = inputs.find(x =>
                    /user|username|email|login|name/i.test(x.name || '') ||
                    /user|username|email|login|name/i.test(x.id || '') ||
                    (x.type || '').toLowerCase() === 'email'
                );

                if (!userInput) {
                    AndroidFarm.onLoginResult('no_username_field');
                    return;
                }

                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(
                        Object.getPrototypeOf(el), 'value'
                    )?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };

                setValue(userInput, username);
                setValue(passwordInput, password);

                const form = passwordInput.closest('form') || userInput.closest('form');
                if (!form) {
                    AndroidFarm.onLoginResult('no_form');
                    return;
                }

                const buttons = [...form.querySelectorAll('button,input[type=submit],input[type=button],a')]
                    .filter(visible);

                const submitButton = buttons.find(x =>
                    /login|log in|sign in|anmelden|connexion|entrar|acceder/i.test(
                        (x.innerText || x.value || x.title || '').trim()
                    )
                );

                AndroidFarm.onLoginResult('submitting');

                if (submitButton) {
                    submitButton.click();
                } else if (typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                } else {
                    form.submit();
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun villageSelectionJson(): String {
        debugTrace("ENTER villageSelectionJson")
        val selected = selectedVillageIds()
        val array = org.json.JSONArray()
        loadedVillages.forEach { (id, name) ->
            if (selected.contains(id)) {
                array.put(JSONObject().apply { put("id", id); put("name", name)
                    put("href", villageScanCollectedLinks[id].orEmpty())
                })
            }
        }
        return array.toString()
    }

    private fun selectedVillageIds(): Set<String> {
        debugTrace("ENTER selectedVillageIds")
        if (!::villageChecklist.isInitialized) return emptySet()
        return (0 until villageChecklist.childCount)
            .mapNotNull { villageChecklist.getChildAt(it) as? CheckBox }
            .filter { it.isChecked }
            .mapNotNull { it.tag?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .toSet()
    }

    private fun restoreVillageSelection(prefs: android.content.SharedPreferences) {
        debugTrace("ENTER restoreVillageSelection")
        // UI akan diisi ulang ketika daftar village berhasil dibaca.
        // Selection lama dipakai sebagai acuan centang berdasarkan ID village.
        loadedVillages.clear()
    }

    private fun renderVillageChecklist(villages: List<Pair<String, String>>) {
        debugTrace("ENTER renderVillageChecklist")
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val saved = prefs.getStringSet("resource_builder_selected_villages", emptySet()) ?: emptySet()
        val configured = prefs.getBoolean("resource_builder_selection_configured", false)

        loadedVillages.clear()
        villages.distinctBy { it.first }.forEach { (id, name) ->
            if (id.isNotBlank()) {
                val villageName = name.ifBlank { "Village $id" }
                val minLevel = villageMinLevels[id]
                val resourceDetail = villageMinResourceDetails[id]
                loadedVillages[id] = if (minLevel != null && minLevel >= 0) {
                    if (resourceDetail != null) {
                        "$villageName - Lvl $minLevel ${resourceDetail.first} ${resourceDetail.second}"
                    } else {
                        "$villageName - Lvl $minLevel"
                    }
                } else {
                    "$villageName - Lvl ?"
                }
            }
        }

        villageChecklist.removeAllViews()
        if (loadedVillages.isEmpty()) {
            villageChecklist.addView(TextView(this).apply {
                text = "Village belum ditemukan. Buka Travian lalu tekan LOAD VILLAGE."
                setPadding(8, 8, 8, 8)
            })
            return
        }

        val selectAll = CheckBox(this).apply {
            text = "PILIH SEMUA VILLAGE"
            isChecked = if (configured) loadedVillages.keys.all { saved.contains(it) } else true
            setOnCheckedChangeListener { _, checked ->
                for (i in 1 until villageChecklist.childCount) {
                    (villageChecklist.getChildAt(i) as? CheckBox)?.isChecked = checked
                }
            }
        }
        selectAll.isEnabled = !selectionControlsLocked
        villageChecklist.addView(selectAll)

        loadedVillages.forEach { (id, name) ->
            villageChecklist.addView(CheckBox(this).apply {
                text = name
                tag = id
                isEnabled = !selectionControlsLocked
                isChecked = if (configured) saved.contains(id) else true
                setOnCheckedChangeListener { _, _ ->
                    // Simpan segera agar pilihan tidak hilang ketika Activity ditutup.
                    getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putBoolean("resource_builder_selection_configured", true)
                        .putStringSet("resource_builder_selected_villages", selectedVillageIds())
                        .putString("resource_builder_villages_json", villageSelectionJson())
                        .apply()
                }
            })
        }

        logEvent("UI: ${loadedVillages.size} village dimuat: ${loadedVillages.values.joinToString(" | ")}")
    }

    /**
     * LOAD VILLAGE sekarang tidak hanya membaca sidebar lalu selesai.
     *
     * Tahap 1: ambil semua link village dari daftar Travian.
     * Tahap 2: WebView terlihat berpindah ke village 1, 2, 3, dst.
     * Tahap 3: pada setiap halaman, baca nama village + resource bar + level field.
     * Tahap 4: setelah semua selesai, baru render checklist Resource Builder.
     */
    /**
     * LOAD VILLAGE:
     * 1) selalu mulai dari dorf1.php
     * 2) baca seluruh village dari sidebar
     * 3) pindah dengan loadUrl(dorf1.php?newdid=...)
     * 4) setelah halaman target selesai, scan nama + resource fields
     * 5) lanjut langsung ke village berikutnya
     */
    /**
     * DEBUG LIVE PAGE
     * Mencatat setiap tap/click yang dilakukan user di WebView.
     * Khusus link village, log juga data-did, href, nama, class, tag dan URL halaman.
     * Listener dipasang ulang setelah setiap page load, tetapi tidak didaftarkan dua kali
     * pada halaman yang sama.
     */
    private fun installLiveClickLogger(pageUrl: String) {
        debugTrace("ENTER installLiveClickLogger")
        val pageUrlJson = JSONObject.quote(pageUrl)
        val js = """
            (() => {
                try {
                    if (window.__farmLiveClickLoggerInstalled) return;
                    window.__farmLiveClickLoggerInstalled = true;

                    const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
                    const shortText = s => clean(s).slice(0, 120);
                    const safe = v => String(v == null ? '' : v);

                    document.addEventListener('click', function(event) {
                        try {
                            let node = event.target;
                            if (node && node.nodeType !== 1) node = node.parentElement;

                            const clickable = node?.closest?.('a,button,[role="button"],input,select,summary') || node;
                            if (!clickable) return;

                            const entry = clickable.closest?.('.listEntry, .dropContainer, li');
                            const anchor = clickable.matches?.('a[href]')
                                ? clickable
                                : clickable.closest?.('a[href]');
                            const href = anchor?.getAttribute('href') || clickable.getAttribute?.('data-href') || '';
                            const dataDid = clickable.getAttribute?.('data-did') ||
                                entry?.getAttribute?.('data-did') || '';
                            const hrefDid = safe(href).match(/[?&]newdid=(\d+)/i)?.[1] || '';
                            const villageId = dataDid || hrefDid;
                            const name = clean(
                                entry?.querySelector?.('.name')?.textContent ||
                                clickable.querySelector?.('.name')?.textContent ||
                                clickable.getAttribute?.('title') ||
                                clickable.getAttribute?.('aria-label') ||
                                clickable.textContent || ''
                            );
                            const payload = {
                                kind: villageId ? 'VILLAGE_CLICK' : 'CLICK',
                                tag: clickable.tagName || '',
                                id: clickable.id || '',
                                idAttr: clickable.id || '',
                                className: shortText(clickable.className || ''),
                                text: shortText(name),
                                dataDid: safe(villageId),
                                href: safe(href),
                                pageUrl: location.href,
                                x: event.clientX,
                                y: event.clientY,
                                button: event.button,
                                defaultPrevented: event.defaultPrevented
                            };
                            AndroidFarm.onLiveClickResult(JSON.stringify(payload));
                        } catch (e) {
                            try {
                                AndroidFarm.onLiveClickResult(JSON.stringify({
                                    kind:'CLICK_ERROR',
                                    error:String(e),
                                    pageUrl:location.href
                                }));
                            } catch (_) {}
                        }
                    }, true);

                    const active = document.querySelector(
                        '#sidebarBoxVillagelist .listEntry.active, ' +
                        '#sidebarBoxVillagelist .listEntry[data-did].active'
                    );
                    const activeId = active?.getAttribute('data-did') || '';
                    const activeName = clean(active?.querySelector('.name')?.textContent || '');
                    const villageLinks = [...document.querySelectorAll('a[href*="newdid="]')]
                        .slice(0, 20)
                        .map(a => ({
                            href:a.getAttribute('href') || '',
                            text:shortText(a.textContent || ''),
                            id:(a.getAttribute('href') || '').match(/[?&]newdid=(\d+)/i)?.[1] || ''
                        }));

                    AndroidFarm.onLiveClickResult(JSON.stringify({
                        kind:'CLICK_LOGGER_READY',
                        pageUrl:$pageUrlJson,
                        domReady:document.readyState,
                        title:document.title || '',
                        root:!!document.querySelector('#sidebarBoxVillagelist'),
                        villageLinks:villageLinks.length,
                        activeId,
                        activeName,
                        villageLinkSample:villageLinks
                    }));
                } catch (e) {
                    AndroidFarm.onLiveClickResult(JSON.stringify({
                        kind:'CLICK_LOGGER_ERROR',
                        error:String(e),
                        pageUrl:location.href
                    }));
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        logEvent("LIVE DEBUG: memasang logger klik; page=$pageUrl")
    }

    private fun clearSavedResourceBuilderTargets() {
        debugTrace("ENTER clearSavedResourceBuilderTargets")
        getSharedPreferences("config", MODE_PRIVATE).edit()
            .putString("resource_builder_targets_json", "{}")
            .apply()
        logEvent("UI: target resource tersimpan direset; scanner akan mencari ulang level terendah")
    }

    private fun saveResourceBuilderTarget(
        villageId: String,
        villageName: String,
        level: Int,
        fieldId: String,
        href: String
    ) {
        debugTrace("ENTER saveResourceBuilderTarget")
        val cleanHref = href.trim()
        if (villageId.isBlank() || cleanHref.isBlank()) return
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val obj = runCatching { JSONObject(prefs.getString("resource_builder_targets_json", "{}").orEmpty()) }
            .getOrElse { JSONObject() }
        obj.put(villageId, JSONObject().apply {
            put("id", villageId)
            put("name", villageName)
            put("level", level)
            put("fieldId", fieldId)
            put("href", cleanHref)
            put("savedAt", System.currentTimeMillis())
        })
        prefs.edit().putString("resource_builder_targets_json", obj.toString()).apply()
        logEvent(
            "UI: TARGET RESOURCE tersimpan — $villageName [${villageId}] " +
                "L$level field=$fieldId href=$cleanHref"
        )
    }

    private fun refreshVillagesForUi() {
        debugTrace("ENTER refreshVillagesForUi")
        villageScanActive = true
        villageScanTargets.clear()
        villageScanResults.clear()
        villageMinLevels.clear()
        villageMinResourceDetails.clear()
        villageScanIndex = 0
        villageScanExpected = 0
        villageScanRetry = 0
        villageScanPageRetry = 0
        villageScanDataRetry = 0
        villageScanScrollPass = 0
        villageScanCollectedTargets.clear()
        villageScanCollectedLinks.clear()
        villageScanCollectInFlight = false
        clearSavedResourceBuilderTargets()

        farmStatus.text = "Refresh village: membaca daftar village..."
        logEvent("UI: REFRESH VILLAGE → membuka dorf1.php")
        val server = normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/dorf1.php")
    }

    private fun collectVillageTargetsFromSidebar() {
        debugTrace("ENTER collectVillageTargetsFromSidebar")
        if (!villageScanActive) return

        val js = """
            (() => {
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const out = [];
                const seen = new Set();
                const root = document.querySelector('#sidebarBoxVillagelist');
                const scrollPass = $villageScanScrollPass;

                // Beberapa halaman Travian merender list village secara lazy/virtual.
                // Geser container daftar ke beberapa posisi; hasil tiap pass digabung
                // di Kotlin sehingga village yang belum ada di DOM pada pass pertama
                // tetap akan ditemukan pada pass berikutnya.
                try {
                    const candidates = [];
                    if (root) candidates.push(root);
                    if (root) candidates.push(...root.querySelectorAll('*'));
                    const scrollable = candidates.find(el =>
                        el && el.scrollHeight > el.clientHeight + 8 &&
                        getComputedStyle(el).overflowY !== 'hidden'
                    );
                    if (scrollable) {
                        const max = Math.max(0, scrollable.scrollHeight - scrollable.clientHeight);
                        const positions = [0, 0.5, 1, 0.25, 0.75, 0];
                        const ratio = positions[scrollPass % positions.length];
                        scrollable.scrollTop = Math.round(max * ratio);
                    }
                } catch (_) {}

                // Travian Legends biasanya menyimpan SEMUA village di:
                // #sidebarBoxVillagelist > div.content > div.villageList > div.dropContainer
                // lalu ID ada di .listEntry[data-did]. Jangan bergantung pada href karena
                // beberapa anchor village memakai href="#".
                const directEntries = root
                    ? [...root.querySelectorAll('div.content div.villageList div.dropContainer')]
                    : [];
                for (const container of directEntries) {
                    const entry = container.querySelector('.listEntry') || container;
                    const anchor = entry.querySelector('a');
                    const dataDid = entry.getAttribute('data-did') ||
                        container.getAttribute('data-did') ||
                        anchor?.getAttribute('data-did') || '';
                    const href = anchor?.getAttribute('href') || '';
                    const hrefDid = href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                    const id = /^\d+$/.test(dataDid) ? dataDid : hrefDid;
                    if (!/^\d+$/.test(id) || seen.has(id)) continue;
                    let name = clean(
                        entry.querySelector('.name')?.textContent ||
                        anchor?.getAttribute('title') ||
                        anchor?.getAttribute('aria-label') ||
                        entry.textContent || ''
                    );
                    name = name.replace(/\(\s*[−-]?\d+\s*\|\s*[−-]?\d+\s*\)/g, '').trim();
                    if (!name) name = 'Village ' + id;
                    seen.add(id);
                    out.push({ id, name, href, tag:anchor?.tagName || entry.tagName || '',
                        idAttr:anchor?.id || entry.id || '',
                        className:clean(entry.className || '').slice(0,100),
                        matchSource:'sidebar-dropContainer' });
                }

                // Fallback untuk layout/tema Travian yang berbeda atau DOM yang berubah.
                const candidates = [...document.querySelectorAll(
                    'a, [data-did], [role="button"], .listEntry, .dropContainer, li'
                )];
                for (const anchor of candidates) {
                    const entry = anchor.matches('.listEntry, .dropContainer, li')
                        ? anchor
                        : anchor.closest('.listEntry, .dropContainer, li');
                    const href = anchor.getAttribute('href') || '';
                    const dataDid = anchor.getAttribute('data-did') ||
                        entry?.getAttribute('data-did') || '';
                    const hrefDid = href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                    if (/^\s*\/?build\.php(?:[?&]|$)/i.test(href)) continue;
                    const id = /^\d+$/.test(dataDid) ? dataDid : hrefDid;
                    if (!/^\d+$/.test(id) || seen.has(id)) continue;
                    let name = clean(
                        entry?.querySelector('.name')?.textContent ||
                        anchor.getAttribute('title') || anchor.getAttribute('aria-label') ||
                        anchor.textContent || ''
                    );
                    name = name.replace(/\(\s*[−-]?\d+\s*\|\s*[−-]?\d+\s*\)/g, '').trim();
                    if (!name) name = 'Village ' + id;
                    seen.add(id);
                    out.push({id, name, href, tag:anchor.tagName || '', idAttr:anchor.id || '',
                        className:clean(anchor.className || '').slice(0,100), matchSource:dataDid ? 'data-did' : 'href'});
                }

                const bodyText = clean(document.body?.innerText || '');
                const m = bodyText.match(/VILLAGES\s+(\d+)\s*\/\s*\d+/i);
                const expected = m ? parseInt(m[1], 10) || 0 : out.length;

                AndroidFarm.onVillageTargetsResult(JSON.stringify({
                    villages: out,
                    expected,
                    rootFound: !!root,
                    anchorCount: candidates.length,
                    villageCount: out.length,
                    scrollPass,
                    globalNewdidLinks: document.querySelectorAll('a[href*="newdid="]').length,
                    globalDataDid: document.querySelectorAll('[data-did]').length,
                    url: location.href
                }));
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleVillageTargetsResult(rawJson: String) {
        debugTrace("ENTER handleVillageTargetsResult")
        if (!villageScanActive) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val targets = mutableListOf<Pair<String, String>>()
        val array = json?.optJSONArray("villages")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { "Village $id" }
                val href = item.optString("href").trim()
                if (id.isNotBlank()) {
                    targets.add(id to name)
                    if (href.isNotBlank()) villageScanCollectedLinks[id] = href
                }
            }
        }

        val expected = json?.optInt("expected", 0) ?: 0
        if (expected > 0) villageScanExpected = maxOf(villageScanExpected, expected)

        // Jangan mengganti hasil scan setiap retry. Beberapa layout Travian merender
        // daftar village secara virtual/lazy; setiap scroll bisa hanya menampilkan
        // sebagian item. Semua bagian digabung sampai jumlahnya lengkap.
        for ((id, name) in targets) {
            if (id.isNotBlank()) villageScanCollectedTargets[id] = name
        }

        val discovered = villageScanCollectedTargets.entries.map { it.key to it.value }
        val details = discovered.joinToString(" | ") { (id, name) -> "$name(ID=$id)" }
        if (discovered.isNotEmpty()) {
            logEvent(
                "UI: TARGET DEBUG: ${discovered.size}/${villageScanExpected}; " +
                    "pass=$villageScanScrollPass; $details"
            )
        }

        val complete = villageScanExpected <= 0 || discovered.size >= villageScanExpected
        if (!complete && villageScanScrollPass < 12) {
            villageScanRetry++
            villageScanScrollPass++
            val rootFound = json?.optBoolean("rootFound", false) == true
            val anchorCount = json?.optInt("anchorCount", 0) ?: 0
            val globalNewdidLinks = json?.optInt("globalNewdidLinks", 0) ?: 0
            val globalDataDid = json?.optInt("globalDataDid", 0) ?: 0
            logEvent(
                "UI: daftar village belum lengkap ${discovered.size}/${villageScanExpected}; " +
                    "scroll pass=$villageScanScrollPass root=$rootFound anchors=$anchorCount " +
                    "newdid=$globalNewdidLinks data-did=$globalDataDid"
            )
            handler.postDelayed({
                if (villageScanActive) collectVillageTargetsFromSidebar()
            }, 650)
            return
        }

        if (discovered.isEmpty()) {
            villageScanRetry++
            if (villageScanRetry <= 15) {
                handler.postDelayed({ if (villageScanActive) collectVillageTargetsFromSidebar() }, 700)
            } else {
                villageScanActive = false
                farmStatus.text = "Daftar village tidak tersedia."
                logEvent("UI: sidebar village tidak muncul setelah 15 percobaan; REFRESH VILLAGE dihentikan")
            }
            return
        }

        villageScanTargets = discovered.toMutableList()
        villageScanResults.clear()
        villageMinLevels.clear()
        villageMinResourceDetails.clear()
        villageScanIndex = 0
        villageScanRetry = 0
        villageScanDataRetry = 0
        villageScanScrollPass = 0
        villageScanCollectInFlight = false

        logEvent(
            "UI: ${villageScanTargets.size}/${villageScanExpected} village ditemukan; " +
                "mulai scan berurutan"
        )
        visitNextVillageForScan()
    }

    private fun visitNextVillageForScan() {
        debugTrace("ENTER visitNextVillageForScan")
        if (!villageScanActive) return

        if (villageScanIndex >= villageScanTargets.size) {
            finishVillageScan()
            return
        }

        val (id, name) = villageScanTargets[villageScanIndex]
        val progress = "${villageScanIndex + 1}/${villageScanTargets.size}"

        // Simpan URL tujuan pindah village untuk ditampilkan tepat di bawah tombol LOGIN.
        val refreshVillageTargetUrl =
            "${normalizeServer(serverInput.text.toString())}/dorf1.php?newdid=$id"
        getSharedPreferences("config", MODE_PRIVATE).edit()
            .putString("debug_last_refresh_village_link", refreshVillageTargetUrl)
            .apply()
        updateVillageLinkPreviews()

        farmStatus.text = "Village $progress — $name"
        logEvent("UI: [$progress] target village: $name (ID $id)")
        villageScanPageRetry = 0

        // Ikuti mekanisme klik village yang benar-benar dipakai Travian.
        // Dari log Live terlihat link village mempunyai href="#", sementara handler
        // Travian sendiri yang kemudian mengubah URL menjadi dorf1.php?newdid=....
        // Karena itu scanner sekarang mencoba klik elemen village berdasarkan data-did,
        // bukan langsung loadUrl(). Jika elemen tidak ditemukan, baru gunakan fallback URL.
        val idJson = JSONObject.quote(id)
        val js = """
            (() => {
                const id = $idJson;
                const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
                const anchors = [...document.querySelectorAll('a')];
                let anchor = null;
                let entry = null;
                let matchSource = '';

                // Gunakan aturan yang sama dengan Live Click Logger:
                // data-did bisa berada pada anchor atau ancestor entry.
                for (const candidate of anchors) {
                    const candidateEntry = candidate.closest('.listEntry, .dropContainer, li');
                    const href = candidate.getAttribute('href') || '';
                    const dataDid = candidate.getAttribute('data-did') ||
                        candidateEntry?.getAttribute('data-did') || '';
                    const hrefDid = href.match(/[?&]newdid=(\d+)/i)?.[1] || '';

                    if (/^\s*\/?build\.php(?:[?&]|$)/i.test(href)) continue;
                    const villageId = /^\d+$/.test(dataDid) ? dataDid : hrefDid;
                    if (villageId !== id) continue;

                    anchor = candidate;
                    entry = candidateEntry;
                    matchSource = dataDid ? 'data-did' : 'href';
                    break;
                }

                const name = clean(
                    entry?.querySelector('.name')?.textContent ||
                    anchor?.querySelector?.('.name')?.textContent ||
                    anchor?.getAttribute('title') ||
                    anchor?.getAttribute('aria-label') ||
                    anchor?.textContent || ''
                ).replace(/\(\s*[−-]?\d+\s*\|\s*[−-]?\d+\s*\)/g, '').trim();
                const href = anchor?.getAttribute('href') || '';

                AndroidFarm.onLiveClickResult(JSON.stringify({
                    kind:'AUTO_VILLAGE_CLICK_ATTEMPT',
                    id, name, href, found:!!anchor, matchSource,
                    entryTag:entry?.tagName || '',
                    entryClass:clean(entry?.className || '').slice(0,100),
                    anchorId:anchor?.id || '',
                    anchorClass:clean(anchor?.className || '').slice(0,100),
                    pageUrl:location.href
                }));

                if (anchor) {
                    try {
                        anchor.scrollIntoView({block:'center', inline:'nearest'});
                        window.__farmAutoVillageClickSent = id;
                        anchor.click();
                        AndroidFarm.onLiveClickResult(JSON.stringify({
                            kind:'AUTO_VILLAGE_CLICK_SENT', id, name, href,
                            matchSource, pageUrl:location.href
                        }));
                        // Beberapa entry terakhir (termasuk village baru) terlihat di DOM
                        // tetapi handler kliknya tidak selalu aktif. Verifikasi setelah 2,2 detik;
                        // bila URL/active village masih bukan target, pakai URL dorf1 langsung.
                        setTimeout(() => {
                            const current = location.href.match(/[?&]newdid=(\d+)/i)?.[1] || '';
                            const active = document.querySelector(
                                '#sidebarBoxVillagelist .listEntry.active, .villageList .listEntry.active'
                            )?.getAttribute('data-did') || '';
                            if (current !== id && active !== id) {
                                location.href = '/dorf1.php?newdid=' + encodeURIComponent(id);
                            }
                        }, 2200);
                        return true;
                    } catch (e) {
                        AndroidFarm.onLiveClickResult(JSON.stringify({
                            kind:'AUTO_VILLAGE_CLICK_ERROR', id, name, href,
                            matchSource, error:String(e), pageUrl:location.href
                        }));
                    }
                }

                AndroidFarm.onLiveClickResult(JSON.stringify({
                    kind:'AUTO_VILLAGE_CLICK_FALLBACK', id, name,
                    matchSource, pageUrl:location.href
                }));
                return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            debugTrace("AUTO village click callback result=$result")
            if (result == "false") {
                logEvent("UI: [$progress] target $name (ID $id) tidak bisa diklik; fallback loadUrl")
                val server = normalizeServer(serverInput.text.toString())
                webView.loadUrl("$server/dorf1.php?newdid=$id")
            } else {
                logEvent("UI: [$progress] click target $name (ID $id) berhasil dikirim ke Travian")
            }
        }
    }

    private fun collectCurrentVillageData() {
        debugTrace("ENTER collectCurrentVillageData")
        if (!villageScanActive || villageScanCollectInFlight) return

        if (villageScanTargets.isEmpty()) {
            collectVillageTargetsFromSidebar()
            return
        }

        val target = villageScanTargets.getOrNull(villageScanIndex) ?: return
        val expectedId = target.first
        val expectedIdJson = JSONObject.quote(expectedId)
        villageScanCollectInFlight = true

        val js = """
            (() => {
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const expectedId = $expectedIdJson;
                const url = location.href;
                const match = url.match(/[?&]newdid=(\d+)/i);
                let currentId = match ? match[1] : '';

                const activeCandidates = [
                    '#sidebarBoxVillagelist .listEntry.active',
                    '#sidebarBoxVillagelist .listEntry.selected',
                    '.villageList .listEntry.active',
                    '.villageList .listEntry.selected',
                    '[data-did].active'
                ];
                let active = null;
                for (const selector of activeCandidates) {
                    try { active = document.querySelector(selector); if (active) break; } catch (_) {}
                }
                const activeId = active?.getAttribute('data-did') || '';
                const activeName = clean(active?.querySelector('.name')?.textContent || '');
                if (!currentId && /^\d+$/.test(activeId)) currentId = activeId;

                if (currentId !== expectedId) {
                    AndroidFarm.onVillageScanResult(JSON.stringify({
                        notReady:true, reason:'WRONG_VILLAGE', id:currentId, expectedId,
                        url, activeId, activeName, pageTitle:document.title || '',
                        readyState:document.readyState
                    }));
                    return;
                }

                // RESOURCE FIELDS: jangan bergantung pada satu class. Travian dapat
                // menaruh level pada class level10, data-level, title, aria-label,
                // atau elemen anak dari field. Karena container khusus resource field,
                // kita scan seluruh turunannya dan deduplicate level yang ditemukan.
                const container = document.querySelector('#resourceFieldContainer');
                const fields = [];
                const debugFields = [];
                const fieldNodes = container ? [...container.querySelectorAll('*')] : [];
                for (const node of fieldNodes) {
                    const candidates = [
                        node.getAttribute?.('data-level') || '',
                        node.getAttribute?.('title') || '',
                        node.getAttribute?.('aria-label') || '',
                        String(node.className || ''),
                        clean(node.textContent || '')
                    ];
                    let level = -1;
                    for (const text of candidates) {
                        const m = String(text).match(/(?:^|[\s_-])level\s*(\d+)\b/i) ||
                                  String(text).match(/\blevel(\d+)\b/i);
                        if (m) { level = parseInt(m[1],10); break; }
                    }
                    if (level >= 0) {
                        fields.push(level);
                        if (debugFields.length < 30) {
                            debugFields.push({level, tag:node.tagName || '', className:String(node.className || '').slice(0,140), text:clean(node.textContent || '').slice(0,60)});
                        }
                    }
                }
                const levels = fields.filter(n => Number.isFinite(n));
                const uniqueLevels = levels.slice(0, 18);

                // Simpan LINK field dengan level terendah. Ini menjadi target yang
                // dipakai Resource Builder saat eksekusi berikutnya. Link diambil
                // langsung dari anchor field agar id/link tetap spesifik ke village.
                const resourceAnchors = container
                    ? [...container.querySelectorAll('a[href*="build.php?id="]')]
                    : [];
                const resourceCandidates = [];
                const seenResourceIds = new Set();
                for (const a of resourceAnchors) {
                    const href = a.getAttribute('href') || '';
                    const m = href.match(/[?&]id=(\d+)/i);
                    if (!m || seenResourceIds.has(m[1])) continue;
                    const fieldId = parseInt(m[1], 10);
                    if (!Number.isFinite(fieldId) || fieldId < 1 || fieldId > 18) continue;
                    seenResourceIds.add(m[1]);
                    let level = -1;
                    let node = a;
                    for (let depth = 0; depth < 8 && node; depth++, node = node.parentElement) {
                        const text = clean(node.innerText || node.textContent || '');
                        const attrs = [
                            node.getAttribute?.('data-level') || '',
                            node.getAttribute?.('title') || '',
                            node.getAttribute?.('aria-label') || '',
                            String(node.className || '')
                        ].join(' ');
                        const lm = text.match(/(?:level|lvl)\s*(\d+)/i) || attrs.match(/level\s*(\d+)/i);
                        if (lm) { level = parseInt(lm[1], 10); break; }
                    }
                    const disabled = a.classList.contains('disabled') || !!a.closest('.disabled') ||
                        a.getAttribute('aria-disabled') === 'true' || a.getAttribute('data-disabled') === 'true';
                    resourceCandidates.push({fieldId, level, href, disabled});
                }
                resourceCandidates.sort((a,b) => a.level - b.level || a.fieldId - b.fieldId);
                const lowestResource = resourceCandidates.find(x => !x.disabled && x.level >= 0 && x.level < 10) || null;

                // RESOURCE BAR: Travian Legends memakai l1=wood, l2=clay,
                // l3=iron, l4=crop. Sumber paling stabil adalah object window.resources
                // yang dipakai UI Travian sendiri; fallback membaca #stockBar.
                const resourcesObject = (typeof window.resources === 'object' && window.resources) ? window.resources : null;
                const storage = resourcesObject?.storage || {};
                const maxStorage = resourcesObject?.maxStorage || {};
                const production = resourcesObject?.production || {};
                const resources = {};

                const numberFromText = value => {
                    const m = String(value || '').replace(/\u00a0/g,' ').match(/-?\d[\d\s.,]*/);
                    return m ? parseInt(m[0].replace(/[^0-9-]/g,''), 10) : NaN;
                };

                const readResource = rid => {
                    const el = document.getElementById(rid);
                    const direct = storage[rid];
                    const directMax = maxStorage[rid];
                    const directProduction = production[rid];
                    let current = Number.isFinite(Number(direct)) ? Number(direct) : NaN;
                    let capacity = Number.isFinite(Number(directMax)) ? Number(directMax) : NaN;

                    if (el) {
                        const source = [
                            el.textContent || '',
                            el.getAttribute('title') || '',
                            el.getAttribute('data-value') || '',
                            el.getAttribute('data-current') || '',
                            el.getAttribute('data-max') || '',
                            el.getAttribute('data-capacity') || '',
                            el.parentElement?.textContent || ''
                        ].join(' ');
                        const pair = source.match(/(-?\d[\d\s.,]*)\s*\/\s*(\d[\d\s.,]*)/);
                        if (!Number.isFinite(current)) current = pair ? numberFromText(pair[1]) : numberFromText(source);
                        if (!Number.isFinite(capacity)) capacity = pair ? numberFromText(pair[2]) : NaN;
                    }

                    // Current Travian UI also exposes warehouse/granary capacity in #stockBar.
                    if (!Number.isFinite(capacity)) {
                        const isCrop = rid === 'l4';
                        const capNode = document.querySelector(
                            isCrop ? '#stockBar > div.granary > div > div' : '#stockBar > div.warehouse > div > div'
                        );
                        capacity = numberFromText(capNode?.textContent || '');
                    }
                    return {
                        text: el?.textContent || '',
                        current: Number.isFinite(current) ? String(Math.trunc(current)) : '',
                        capacity: Number.isFinite(capacity) ? String(Math.trunc(capacity)) : '',
                        production: Number.isFinite(Number(directProduction)) ? String(Math.trunc(Number(directProduction))) : ''
                    };
                };

                let resourceComplete = true;
                for (const rid of ['l1','l2','l3','l4']) {
                    resources[rid] = readResource(rid);
                    if (!resources[rid].current || !resources[rid].capacity) resourceComplete = false;
                }

                // Level village dan snapshot resource dipisahkan: jangan membuang village
                // hanya karena resource bar terlambat dirender. Nama + level tetap diterima
                // jika 18 field sudah tersedia; resource akan disimpan bila lengkap.
                if (!container || uniqueLevels.length < 18) {
                    AndroidFarm.onVillageScanResult(JSON.stringify({
                        notReady:true, reason:'FIELDS_NOT_READY', id:currentId, expectedId,
                        url, activeId, activeName, fieldCount:uniqueLevels.length,
                        resourceComplete, resourceContainer:!!container, resources,
                        lowestResource, debugFields:debugFields.slice(0,12)
                    }));
                    return;
                }

                const entry = [...document.querySelectorAll('[data-did]')]
                    .find(e => String(e.getAttribute('data-did') || '') === expectedId);
                const pageName = clean(
                    entry?.querySelector('.name')?.textContent ||
                    entry?.querySelector('[class*="name"]')?.textContent || activeName || ''
                );

                AndroidFarm.onVillageScanResult(JSON.stringify({
                    id:expectedId, name:pageName, minLevel:Math.min(...uniqueLevels),
                    fields:uniqueLevels, fieldNodeCount:fieldNodes.length,
                    debugFieldCount:debugFields.length, debugFields,
                    resourceContainer:true, activeId, activeName, url, resources, lowestResource
                }));
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleVillageScanResult(rawJson: String) {
        debugTrace("ENTER handleVillageScanResult")
        if (!villageScanActive) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()

        if (json?.optBoolean("notReady", false) == true) {
            villageScanCollectInFlight = false
            villageScanDataRetry++
            villageScanPageRetry++

            if (villageScanDataRetry <= 14) {
                if (villageScanPageRetry == 1 || villageScanPageRetry == 10) {
                    logEvent(
                        "UI: [${villageScanIndex + 1}/${villageScanTargets.size}] " +
                            "data belum siap reason=${json.optString("reason").ifBlank { "UNKNOWN" }}; " +
                            "target=${json.optString("expectedId")}; " +
                            "current=${json.optString("id").ifBlank { "-" }}; " +
                            "fields=${json.optInt("fieldCount", 0)}; " +
                            "resourceComplete=${json.optBoolean("resourceComplete", false)}; " +
                            "ready=${json.optString("readyState").ifBlank { "-" }}; " +
                            "url=${json.optString("url").ifBlank { "-" }}"
                    )
                }
                handler.postDelayed({ if (villageScanActive) collectCurrentVillageData() }, 700)
            } else {
                // Satu village gagal tidak boleh mengunci seluruh scanner.
                val (_, name) = villageScanTargets[villageScanIndex]
                logEvent(
                    "UI: [${villageScanIndex + 1}/${villageScanTargets.size}] " +
                        "$name timeout; village dilewati"
                )
                villageScanIndex++
                villageScanDataRetry = 0
                villageScanCollectInFlight = false
                handler.postDelayed({ visitNextVillageForScan() }, 500)
            }
            return
        }

        villageScanCollectInFlight = false
        villageScanPageRetry = 0
        villageScanDataRetry = 0

        val target = villageScanTargets.getOrNull(villageScanIndex) ?: return
        val id = json?.optString("id").orEmpty().ifBlank { target.first }
        val name = json?.optString("name").orEmpty().trim().ifBlank { target.second }
        val minLevel = json?.optInt("minLevel", -1) ?: -1
        val activeId = json?.optString("activeId", "").orEmpty()
        val activeName = json?.optString("activeName", "").orEmpty()
        val fieldNodeCount = json?.optInt("fieldNodeCount", 0) ?: 0
        val resourceContainer = json?.optBoolean("resourceContainer", false) == true
        val lowestResource = json?.optJSONObject("lowestResource")
        val lowestResourceLevel = lowestResource?.optInt("level", -1) ?: -1
        val lowestResourceId = lowestResource?.optString("fieldId", "").orEmpty()
        val lowestResourceHref = lowestResource?.optString("href", "").orEmpty()

        villageMinLevels[id] = minLevel
        if (lowestResourceLevel >= 0 && lowestResourceId.isNotBlank()) {
            val resourceType = when (lowestResourceId.toIntOrNull()) {
                in 1..4 -> "Wood"
                in 5..8 -> "Clay"
                in 9..12 -> "Iron"
                in 13..18 -> "Crop"
                else -> "Resource"
            }
            villageMinResourceDetails[id] = resourceType to "id$lowestResourceId"
        } else {
            villageMinResourceDetails.remove(id)
        }

        val progress = "${villageScanIndex + 1}/${villageScanTargets.size}"

        val resources = json?.optJSONObject("resources")
        val resourceText = resources?.let {
            listOf("l1","l2","l3","l4").mapNotNull { key ->
                val item = it.optJSONObject(key)
                if (item != null) {
                    val txt = item.optString("text", "").trim()
                    if (txt.isNotBlank()) "$key=$txt" else null
                } else {
                    val v = it.optString(key, "").trim()
                    if (v.isNotBlank()) "$key=$v" else null
                }
            }.joinToString(", ")
        }.orEmpty()

        fun pair(key: String): Pair<Int, Int> {
            debugTrace("ENTER pair")
            val item = resources?.optJSONObject(key) ?: return -1 to -1
            val current = item.optString("current", "").replace(".", "").replace(",", "").toIntOrNull() ?: -1
            val capacity = item.optString("capacity", "").replace(".", "").replace(",", "").toIntOrNull() ?: -1
            return current to capacity
        }
        // Travian Legends: l1=wood, l2=clay, l3=iron, l4=crop.
        val wood = pair("l1")
        val clay = pair("l2")
        val iron = pair("l3")
        val crop = pair("l4")
        fun production(key: String): Int {
            debugTrace("ENTER production")
            val item = resources?.optJSONObject(key) ?: return -1
            return item.optString("production", "").replace(".", "").replace(",", "").toIntOrNull() ?: -1
        }
        val woodProd = production("l1")
        val clayProd = production("l2")
        val ironProd = production("l3")
        val cropProd = production("l4")
        resourceSnapshots[id] = ResourceSnapshot(
            villageId = id, villageName = name,
            wood = wood.first, woodCap = wood.second,
            clay = clay.first, clayCap = clay.second,
            iron = iron.first, ironCap = iron.second,
            crop = crop.first, cropCap = crop.second,
            woodProd = woodProd, clayProd = clayProd,
            ironProd = ironProd, cropProd = cropProd,
            updatedAt = timeFormat.format(Date())
        )
        saveResourceSnapshots()
        if (lowestResource != null && lowestResourceHref.isNotBlank() && lowestResourceLevel >= 0) {
            saveResourceBuilderTarget(id, name, lowestResourceLevel, lowestResourceId, lowestResourceHref)
        } else {
            logEvent("UI: [$progress] target resource level terendah tidak ditemukan untuk $name")
        }
        if (wood.first < 0 || clay.first < 0 || iron.first < 0 || crop.first < 0) {
            logEvent("UI: [$progress] resource belum lengkap — wood=$wood; clay=$clay; iron=$iron; crop=$crop")
        }
        if (capacityTab.visibility == View.VISIBLE) renderCapacityOverview()

        val existing = villageScanResults.indexOfFirst { it.first == id }
        if (existing >= 0) villageScanResults[existing] = id to name
        else villageScanResults.add(id to name)

        val debugFields = json?.optJSONArray("debugFields")
        if (debugFields != null && debugFields.length() > 0) {
            for (i in 0 until minOf(debugFields.length(), 18)) {
                val f = debugFields.optJSONObject(i) ?: continue
                logEvent(
                    "UI FIELD[$i]: level=${f.optInt("level", -1)}; " +
                        "tag=${f.optString("tag")}; class=${f.optString("className")}; " +
                        "href=${f.optString("href")}; text=${f.optString("text").ifBlank { "-" }}"
                )
            }
        }

        logEvent(
            "UI: [$progress] $name selesai — min=L$minLevel; " +
                "active=$activeName(ID=${activeId.ifBlank { "-" }}); " +
                "fieldNodes=$fieldNodeCount; resourceContainer=$resourceContainer" +
                if (resourceText.isNotBlank()) "; $resourceText" else ""
        )

        villageScanIndex++
        handler.postDelayed({ visitNextVillageForScan() }, 250)
    }

    private fun finishVillageScan() {
        debugTrace("ENTER finishVillageScan")
        villageScanActive = false

        // Semua target tetap masuk ke checklist. Jika pembacaan detail satu village
        // gagal, namanya tetap ditampilkan dan level akan memakai data lama bila ada.
        val resultById = villageScanResults.toMap()
        val merged = villageScanTargets.map { (id, targetName) ->
            id to (resultById[id] ?: targetName)
        }.distinctBy { it.first }
        val processedCount = villageScanResults.distinctBy { it.first }.size
        farmStatus.text = "${merged.size} village ditemukan; detail berhasil ${processedCount}/${merged.size}."
        logEvent(
            "UI: scan selesai — ${processedCount}/${merged.size} detail berhasil; " +
                "${merged.size} village tetap ditampilkan"
        )

        renderVillageChecklist(merged)
        villageScanTargets.clear()
    }

    private fun handleVillageListResult(rawJson: String) {
        debugTrace("ENTER handleVillageListResult")
        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val villages = mutableListOf<Pair<String,String>>()
        val array = json?.optJSONArray("villages")
        if (array != null) for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().ifBlank { "Village $id" }
            if (id.isNotBlank()) villages.add(id to name)
        }
        val unique = villages.distinctBy { it.first }
        val expected = json?.optInt("expected", 0) ?: 0
        if (unique.isEmpty()) {
            farmStatus.text = "Village belum terlihat. Pastikan sudah login di Live WebView."
            logEvent("UI: daftar village tidak ditemukan dari halaman aktif")
        } else {
            renderVillageChecklist(unique)
            farmStatus.text = if (expected > 0 && unique.size < expected) {
                "${unique.size}/$expected village terbaca; masih belum lengkap."
            } else {
                "${unique.size} village siap dipilih untuk Resource Builder."
            }
        }
    }

    private fun openFarmList() {
        debugTrace("ENTER openFarmList")
        val server = normalizeServer(serverInput.text.toString())
        farmStatus.text = "Membuka halaman Farm List..."
        farmListRequested = true
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    /**
     * ConsentManager (consentmanager.net) sering memasang UI di #cmpwrapper.shadowRoot.
     * querySelector biasa dari document tidak akan melihat tombol di dalam Shadow DOM.
     * Karena itu kita cari di document + semua open shadowRoot dan baru lanjut setelah
     * banner tidak terlihat lagi.
     */
    private fun handlePageAfterConsent(url: String, lower: String, attempt: Int) {
        debugTrace("ENTER handlePageAfterConsent")
        acceptCookiesIfPresent { result ->
            val consentStillVisible = result.contains("visible") || result.contains("clicked")

            if (consentStillVisible && attempt < 8) {
                farmStatus.text = if (result.contains("clicked")) {
                    "Cookie consent ditemukan. Menerima cookies..."
                } else {
                    "Menunggu cookie consent ditutup..."
                }
                CookieManager.getInstance().flush()
                handler.postDelayed({ handlePageAfterConsent(url, lower, attempt + 1) }, 700)
                return@acceptCookiesIfPresent
            }

            if (lower.contains("gid=16") && lower.contains("tt=99")) {
                loginInProgress = false
                reloginRequested = false
                loginRetryCount = 0
                farmListRequested = true
                farmStatus.text = "Halaman Farm List siap. Tidak perlu membaca daftar Farm List."
                logEvent("Farm List siap; tidak melakukan parsing daftar")
                if (pendingStartAll) {
                    startAllAttempt = 0
                    handler.postDelayed({ clickStartAllFarmLists() }, 1200)
                }
                return@acceptCookiesIfPresent
            }

            // Jika scheduler aktif dan Travian mengarahkan kembali ke halaman login,
            // lakukan re-login otomatis menggunakan kredensial yang masih tersedia di RAM.
            if (isLikelyLoginPage(lower)) {
                if (pendingUsername.isNotBlank() && pendingPassword.isNotBlank()) {
                    if (!loginInProgress || reloginRequested) {
                        loginInProgress = true
                        reloginRequested = true
                        loginRetryCount = 0
                        farmStatus.text = "Session Travian habis. Melakukan auto re-login..."
                    logEvent("Session Travian habis; memulai auto re-login")
                    }
                    handler.postDelayed({ autoLoginIfNeeded() }, 500)
                } else {
                    loginInProgress = false
                    reloginRequested = false
                    farmStatus.text = "Session habis. Password tidak tersedia untuk auto re-login."
                    logEvent("Session habis tetapi password tidak tersedia di RAM")
                }
                return@acceptCookiesIfPresent
            }

            if (loginInProgress) {
                handler.postDelayed({ autoLoginIfNeeded() }, 500)
            } else if (running && pendingStartAll) {
                // Session Travian kadang expired tanpa mengubah URL menjadi login.php.
                // Cek DOM untuk form password agar auto re-login tetap terpicu.
                detectLoginFormForScheduler()
            }
        }
    }

    private fun detectLoginFormForScheduler() {
        debugTrace("ENTER detectLoginFormForScheduler")
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' &&
                           r.width > 0 && r.height > 0;
                };
                const hasPassword = [...document.querySelectorAll('input[type=password]')]
                    .some(visible);
                return hasPassword ? 'login_form' : 'not_login';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            if (raw.orEmpty().contains("login_form")) {
                loginInProgress = true
                reloginRequested = true
                loginRetryCount = 0
                farmStatus.text = "Session Travian habis. Melakukan auto re-login..."
                handler.postDelayed({ autoLoginIfNeeded() }, 250)
            }
        }
    }

    /** Klik Accept All, termasuk jika tombol berada di Shadow DOM ConsentManager. */
    private fun acceptCookiesIfPresent(done: (String) -> Unit) {
        debugTrace("ENTER acceptCookiesIfPresent")
        val js = """
            (() => {
              const visible = el => {
                if (!el) return false;
                const s = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

              // Ambil semua root yang dapat diakses: document + open Shadow DOM bertingkat.
              const roots = [document];
              for (let i = 0; i < roots.length; i++) {
                const root = roots[i];
                let els = [];
                try { els = [...root.querySelectorAll('*')]; } catch (_) {}
                for (const el of els) {
                  if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
                }
              }

              // ConsentManager dikenal memakai #cmpwrapper dengan shadowRoot dan
              // #cmpwelcomebtnyes / .cmpboxbtnyes untuk tombol opt-in.
              const selectors = [
                '#cmpwelcomebtnyes a',
                '#cmpwelcomebtnyes',
                '.cmpboxbtnyes',
                '#cmpbntyestxt',
                '[class*="cmpboxbtnyes"]',
                '[id*="cmpwelcomebtnyes"]'
              ];

              let bannerVisible = false;
              for (const root of roots) {
                try {
                  const box = root.querySelector('#cmpbox, #cmpbox2, .cmpbox, .cmpmore');
                  if (box && visible(box)) bannerVisible = true;
                } catch (_) {}

                for (const sel of selectors) {
                  let el = null;
                  try { el = root.querySelector(sel); } catch (_) {}
                  if (el && visible(el)) {
                    try {
                      el.click();
                      return 'clicked';
                    } catch (_) {}
                  }
                }

                // Fallback berbasis teks untuk varian markup lain.
                let candidates = [];
                try {
                  candidates = [...root.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')];
                } catch (_) {}
                const accept = candidates.find(el => {
                  if (!visible(el)) return false;
                  const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                  return /^(accept all|accept all cookies|allow all|agree all|alle akzeptieren|tout accepter|aceptar todo)$/.test(text);
                });
                if (accept) {
                  try {
                    accept.click();
                    return 'clicked';
                  } catch (_) {}
                }
              }

              // #cmpwrapper sendiri mungkin host Shadow DOM, jadi kehadirannya juga dicek.
              const host = document.querySelector('#cmpwrapper');
              if (host && visible(host)) bannerVisible = true;

              return bannerVisible ? 'visible' : 'absent';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').lowercase(Locale.US)
            done(result)
        }
    }

    /**
     * Setiap periode kita tidak membaca nama/ID Farm List sama sekali.
     * App selalu membuka halaman Farm List, lalu menekan tombol "Start all"
     * begitu React selesai merender tombolnya.
     */
    private fun triggerStartAllFarmLists() {
        debugTrace("ENTER triggerStartAllFarmLists")
        if (!running) return

        pendingStartAll = true
        startAllAttempt = 0
        val server = normalizeServer(serverInput.text.toString())

        if (pendingUsername.isBlank()) pendingUsername = usernameInput.text.toString().trim()
        if (pendingPassword.isBlank()) pendingPassword = passwordInput.text.toString()

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            pendingStartAll = false
            farmStatus.text = "Username dan password diperlukan untuk Start All otomatis."
            status.text = "Status: RUNNING — menunggu login"
            return
        }
        farmStatus.text = "Menyiapkan Start All Farm Lists..."
        logEvent("Memulai siklus Start All Farm Lists")

        // Selalu kembali ke Farm List agar tetap bekerja walaupun pengguna
        // sebelumnya membuka halaman Travian lain di WebView.
        farmListRequested = true
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    private fun clickStartAllFarmLists() {
        debugTrace("ENTER clickStartAllFarmLists")
        if (!running || !pendingStartAll) return

        val js = """
            (() => {
              const visible = el => {
                if (!el) return false;
                const s = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' &&
                       r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

              const selectors = [
                'button.startAllFarmLists',
                '.startAllFarmLists button',
                '.startAllFarmLists',
                'button[class*="startAllFarm"]',
                '[class*="startAllFarmLists"]'
              ];

              for (const selector of selectors) {
                let nodes = [];
                try { nodes = [...document.querySelectorAll(selector)]; } catch (_) {}
                const btn = nodes.find(el => visible(el) && !el.disabled &&
                  el.getAttribute('aria-disabled') !== 'true');
                if (btn) {
                  btn.scrollIntoView({ block: 'center' });
                  btn.click();
                  return 'clicked-selector:' + selector;
                }
              }

              // Fallback jika class Travian berubah lagi: cari berdasarkan teks tombol.
              const candidates = [...document.querySelectorAll(
                'button, input[type=button], input[type=submit], a, [role=button]'
              )];
              const textBtn = candidates.find(el => {
                if (!visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
                const t = norm(el.innerText || el.textContent || el.value || el.title ||
                               el.getAttribute('aria-label'));
                return /^(start all|start all farm lists?|start all farmlists?|send all)$/.test(t) ||
                       /start all.*farm/i.test(t);
              });

              if (textBtn) {
                textBtn.scrollIntoView({ block: 'center' });
                textBtn.click();
                return 'clicked-text';
              }

              return 'not-found';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"')
            if (result.startsWith("clicked")) {
                pendingStartAll = false
                startAllAttempt = 0
                val now = timeFormat.format(Date())
                lastRun.text = "Last run: $now"
                farmStatus.text = "Start All Farm Lists ditekan pada $now"
                logEvent("Start All Farm Lists berhasil ditekan")
                status.text = "Status: RUNNING — Start All berhasil"
            } else if (startAllAttempt < 10) {
                startAllAttempt++
                farmStatus.text = "Menunggu tombol Start All Farm Lists... (${startAllAttempt}/10)"
                handler.postDelayed({ clickStartAllFarmLists() }, 1000)
            } else {
                pendingStartAll = false
                lastRun.text = "Last run: ${timeFormat.format(Date())}"
                farmStatus.text = "Tombol Start All Farm Lists tidak ditemukan setelah menunggu halaman selesai dimuat."
                logEvent("Start All gagal: tombol tidak ditemukan setelah 10 percobaan")
                status.text = "Status: RUNNING — Start All gagal"
            }
        }
    }

    private fun updateNextRun() {
        debugTrace("ENTER updateNextRun")
        nextRun.text = "Next run: ${timeFormat.format(Date(nextAt))}"
    }

    private fun stopScheduler() {
        debugTrace("ENTER stopScheduler")
        running = false
        setSelectionControlsLocked(false)
        pendingStartAll = false
        val intent = android.content.Intent(this, FarmAutomationService::class.java).apply {
            action = FarmAutomationService.ACTION_STOP
        }
        startService(intent)
        updateBotToggleVisual(false)
        status.text = "Status: STOPPED"
        logEvent("Status STOPPED")
        nextRun.text = "Next run: --"
    }

    private fun saveCredentialsSecure() {
        debugTrace("ENTER saveCredentialsSecure")
        if (pendingUsername.isBlank() || pendingPassword.isBlank()) return
        val encrypted = encryptPasswordSecure(pendingPassword)
        if (encrypted.isBlank()) {
            logEvent("Login berhasil tetapi password aman gagal disimpan")
            return
        }
        getSharedPreferences("config", MODE_PRIVATE).edit()
            .putString("username", pendingUsername)
            .putString("password_secure", encrypted)
            .apply()
    }

    private fun encryptPasswordSecure(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val keyStore = KeyStore.getInstance(passwordKeyStore).apply { load(null) }
            val key = if (keyStore.containsAlias(passwordKeyAlias)) {
                keyStore.getKey(passwordKeyAlias, null) as javax.crypto.SecretKey
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, passwordKeyStore)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        passwordKeyAlias,
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
            val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("TravianFarmAssistant", "Gagal menyimpan password aman", e)
            ""
        }
    }

    private fun decryptSavedPassword(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val keyStore = KeyStore.getInstance(passwordKeyStore).apply { load(null) }
            if (!keyStore.containsAlias(passwordKeyAlias)) return ""
            val key = keyStore.getKey(passwordKeyAlias, null) as javax.crypto.SecretKey
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            if (packed.size <= 12) return ""
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("TravianFarmAssistant", "Password tersimpan tidak dapat dibaca", e)
            ""
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
        // Helper UI/log ini dapat dipanggil dari logEvent(). Jangan persist debugTrace
        // mereka, karena refreshLogOverview() -> debugTrace() -> logEvent() akan
        // membuat rekursi tak berujung dan menyebabkan ANR saat tab Log dibuka.
        if (quiet !in setOf(
                "updateCountdown",
                "run",
                "refreshRecentLogs",
                "pruneLogs",
                "refreshLogOverview",
                "buildColoredLog"
            )) {
            logEvent("[DEBUG] $message")
        }
    }

    private fun logEvent(message: String) {
        val line = "${logTimeFormat.format(Date())} | $message"
        try {
            openFileOutput(logFileName, MODE_APPEND).bufferedWriter().use { it.appendLine(line) }

            // Jangan prune/read seluruh file pada setiap log. Dengan verbose debug hal ini
            // membuat UI macet dan tab LOG dapat ANR. Cleanup cukup berkala.
            val now = System.currentTimeMillis()
            if (now - lastLogPruneAt >= 60 * 60 * 1000L) {
                lastLogPruneAt = now
                handler.post { pruneLogs() }
            }

            // Jangan membaca file log synchronously untuk setiap baris debug.
            // Dengan verbose logging, ini sebelumnya membuat main thread sibuk terus
            // dan tab dapat terlihat seperti crash/ANR.
            scheduleRecentLogRefresh()
        } catch (_: Exception) {
            // Logging must never interrupt the automation.
        }
    }

    private fun readLastLogLines(maxLines: Int, maxBytes: Int = 262144): List<String> {
        val file = getFileStreamPath(logFileName)
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = maxOf(0L, length - maxBytes.toLong())
                raf.seek(start)
                if (start > 0) raf.readLine() // buang partial line
                val out = ArrayList<String>(maxLines)
                while (true) {
                    val raw = raf.readLine() ?: break
                    if (raw.isNotBlank()) {
                        val decoded = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                        out.add(decoded)
                        if (out.size > maxLines) out.removeAt(0)
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pruneLogs() {
        debugTrace("ENTER pruneLogs")
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try {
                    val stamp = line.substringBefore(" | ")
                    val time = logTimeFormat.parse(stamp)?.time ?: return@filter false
                    time >= cutoff
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {
            // Ignore cleanup errors; automation continues normally.
        }
    }

    private fun createNotificationChannel() {
        debugTrace("ENTER createNotificationChannel")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("farm", "Farm reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    inner class FarmBridge {
        @JavascriptInterface
        fun onLoginResult(result: String) {
            debugTrace("ENTER onLoginResult")
            if (FarmAutomationService.isRunningFromService()) {
                FarmAutomationService.forwardLoginResult(result)
                return
            }
            runOnUiThread {
                when (result) {
                    "submitting" -> {
                        farmStatus.text = "Mengirim login ke Travian..."
                    }
                    "no_login_form" -> {
                        loginInProgress = false
                        reloginRequested = false
                        loginRetryCount = 0
                        saveCredentialsSecure()
                        farmStatus.text = "Login berhasil. User/password tersimpan. Mengambil data village..."
                        logEvent("Login berhasil/session aktif — user + password tersimpan aman; otomatis menjalankan refresh village")
                        handler.postDelayed({ refreshVillagesForUi() }, 350)
                    }
                    "no_username_field", "no_form" -> {
                        if (loginRetryCount < 20 && (running || reloginRequested)) {
                            farmStatus.text = "Menunggu form login Travian... ($loginRetryCount/20)"
                            handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                        } else {
                            farmStatus.text = "Form login Travian tidak dikenali. Silakan cek halaman login."
                            loginInProgress = false
                            reloginRequested = false
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onLiveClickResult(result: String) {
            debugTrace("ENTER onLiveClickResult")
            runOnUiThread { handleLiveClickResult(result) }
        }

        @JavascriptInterface
        fun onVillageListResult(result: String) {
            debugTrace("ENTER onVillageListResult")
            if (FarmAutomationService.isRunningFromService()) {
                FarmAutomationService.forwardVillageListResult(result)
            }
        }

        @JavascriptInterface
        fun onVillageTargetsResult(result: String) {
            debugTrace("ENTER onVillageTargetsResult")
            runOnUiThread { handleVillageTargetsResult(result) }
        }

        @JavascriptInterface
        fun onVillageScanResult(result: String) {
            debugTrace("ENTER onVillageScanResult")
            runOnUiThread { handleVillageScanResult(result) }
        }
    }

    private fun handleLiveClickResult(rawJson: String) {
        debugTrace("ENTER handleLiveClickResult")
        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        if (json == null) {
            logEvent("LIVE CLICK: JSON tidak valid: ${rawJson.take(300)}")
            return
        }

        when (json.optString("kind")) {
            "CLICK_LOGGER_READY" -> {
                logEvent(
                    "LIVE DEBUG: logger siap; dom=${json.optString("domReady")}; " +
                        "root=${json.optBoolean("root")}; " +
                        "villageLinks=${json.optInt("villageLinks")}; " +
                        "active=${json.optString("activeName").ifBlank { "-" }}(ID=${json.optString("activeId").ifBlank { "-" }}); " +
                        "title=${json.optString("title").take(80)}; " +
                        "url=${json.optString("pageUrl")}" 
                )
                val sample = json.optJSONArray("villageLinkSample")
                if (sample != null && sample.length() > 0) {
                    for (i in 0 until minOf(sample.length(), 20)) {
                        val item = sample.optJSONObject(i) ?: continue
                        logEvent(
                            "LIVE VILLAGE LINK[$i]: id=${item.optString("id", "-")}; " +
                                "text=${item.optString("text").ifBlank { "-" }}; " +
                                "href=${item.optString("href").ifBlank { "-" }}"
                        )
                    }
                }
            }
            "AUTO_VILLAGE_CLICK_ATTEMPT" -> {
                logEvent(
                    "LIVE AUTO VILLAGE: cari target id=${json.optString("id")}; " +
                        "name=${json.optString("name").ifBlank { "-" }}; " +
                        "found=${json.optBoolean("found")}; href=${json.optString("href").ifBlank { "-" }}; " +
                        "source=${json.optString("matchSource").ifBlank { "-" }}; " +
                        "entry=${json.optString("entryTag").ifBlank { "-" }}; " +
                        "page=${json.optString("pageUrl").ifBlank { "-" }}"
                )
            }
            "AUTO_VILLAGE_CLICK_SENT" -> {
                logEvent(
                    "LIVE AUTO VILLAGE: click dikirim id=${json.optString("id")}; " +
                        "name=${json.optString("name").ifBlank { "-" }}; " +
                        "href=${json.optString("href").ifBlank { "-" }}"
                )
            }
            "AUTO_VILLAGE_CLICK_FALLBACK" -> {
                logEvent(
                    "LIVE AUTO VILLAGE: elemen target tidak ditemukan; akan fallback loadUrl id=${json.optString("id")}"
                )
            }
            "AUTO_VILLAGE_CLICK_ERROR" -> {
                logEvent(
                    "LIVE AUTO VILLAGE ERROR: id=${json.optString("id")}; " +
                        "error=${json.optString("error")}; href=${json.optString("href").ifBlank { "-" }}"
                )
            }
            "VILLAGE_CLICK" -> {
                logEvent(
                    "LIVE VILLAGE CLICK: id=${json.optString("dataDid", "-")}; " +
                        "name=${json.optString("text").ifBlank { "-" }}; " +
                        "href=${json.optString("href").ifBlank { "-" }}; " +
                        "tag=${json.optString("tag")}; " +
                        "idAttr=${json.optString("idAttr").ifBlank { "-" }}; " +
                        "class=${json.optString("className").ifBlank { "-" }}; " +
                        "xy=${json.optInt("x")},${json.optInt("y")}; " +
                        "defaultPrevented=${json.optBoolean("defaultPrevented")}; " +
                        "page=${json.optString("pageUrl")}" 
                )
            }
            "CLICK" -> {
                logEvent(
                    "LIVE CLICK: tag=${json.optString("tag")}; " +
                        "text=${json.optString("text").ifBlank { "-" }}; " +
                        "href=${json.optString("href").ifBlank { "-" }}; " +
                        "id=${json.optString("id").ifBlank { "-" }}; " +
                        "class=${json.optString("className").ifBlank { "-" }}; " +
                        "xy=${json.optInt("x")},${json.optInt("y")}; " +
                        "page=${json.optString("pageUrl")}" 
                )
            }
            "CLICK_ERROR", "CLICK_LOGGER_ERROR" -> {
                logEvent(
                    "LIVE DEBUG ERROR: ${json.optString("error").ifBlank { "unknown" }}; " +
                        "url=${json.optString("pageUrl")}" 
                )
            }
            else -> logEvent("LIVE DEBUG: event=${json.optString("kind")}; data=${rawJson.take(400)}")
        }
    }

    override fun onResume() {
        debugTrace("ENTER onResume")
        super.onResume()
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serviceRunning = prefs.getBoolean("service_running", false)
        running = serviceRunning
        if (::botToggle.isInitialized) {
            botToggle.setOnCheckedChangeListener(null)
            botToggle.isChecked = serviceRunning
            updateBotToggleVisual(serviceRunning)
            botToggle.setOnCheckedChangeListener(this@MainActivity::handleBotToggle)
            setSelectionControlsLocked(serviceRunning)
        }
        if (serviceRunning) {
            status.text = "Status: RUNNING — BACKGROUND"
            updateCountdown()
        } else {
            status.text = "Status: STOPPED"
            nextRun.text = "Next run: --"
        }
        val last = prefs.getString("last_run", "").orEmpty()
        lastRun.text = if (last.isBlank()) "Last run: --" else "Last run: $last"
        refreshLogOverview()
        refreshRecentLogs()
        handler.removeCallbacks(recentLogRefreshRunnable)
        handler.post(recentLogRefreshRunnable)
        if (capacityTab.visibility == View.VISIBLE) renderCapacityOverview()
    }

    override fun onPause() {
        debugTrace("ENTER onPause")
        handler.removeCallbacks(recentLogRefreshRunnable)
        super.onPause()
    }

    private fun scheduleRecentLogRefresh() {
        if (!::recentLogs.isInitialized || isFinishing || recentLogRefreshScheduled) return
        recentLogRefreshScheduled = true
        handler.postDelayed({
            recentLogRefreshScheduled = false
            if (!isFinishing) refreshRecentLogs()
        }, 250L)
    }

    private fun updateVillageLinkPreviews() {
        if (!::refreshVillageLinkPreview.isInitialized ||
            !::resourceBuilderVillageLinkPreview.isInitialized) return

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val refreshLink = prefs.getString("debug_last_refresh_village_link", "").orEmpty()
        val builderLink = prefs.getString("debug_last_resource_builder_village_link", "").orEmpty()

        refreshVillageLinkPreview.text =
            "REFRESH VILLAGE LINK: ${if (refreshLink.isBlank()) "-" else refreshLink}"
        resourceBuilderVillageLinkPreview.text =
            "RES BUILDER LINK: ${if (builderLink.isBlank()) "-" else builderLink}"
    }

    private fun refreshRecentLogs() {
        updateVillageLinkPreviews()
        if (!::recentLogs.isInitialized || isFinishing) return
        logIoExecutor.execute {
            val lines = readLastLogLines(5, 32768)
            handler.post {
                if (isFinishing || !::recentLogs.isInitialized) return@post
                try {
                    if (lines.isEmpty()) {
                        recentLogs.text = "Belum ada log."
                        return@post
                    }
                    val palette = intArrayOf(
                        Color.rgb(255, 214, 64),
                        Color.rgb(100, 181, 246),
                        Color.rgb(129, 199, 132),
                        Color.rgb(186, 104, 200),
                        Color.rgb(255, 167, 38),
                        Color.rgb(77, 208, 225)
                    )
                    val spannable = SpannableString(lines.joinToString("\n"))
                    var offset = 0
                    lines.forEach { line ->
                        val cycle = Regex("\\[CYCLE (\\d+)\\]").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val color = if (cycle != null) palette[(cycle - 1).mod(palette.size)] else Color.LTGRAY
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(color),
                            offset,
                            offset + line.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        offset += line.length + 1
                    }
                    recentLogs.text = spannable
                } catch (_: Exception) {
                    // Preview must never interrupt automation.
                }
            }
        }
    }

    private fun restoreResourceSnapshots(prefs: android.content.SharedPreferences) {
        debugTrace("ENTER restoreResourceSnapshots")
        resourceSnapshots.clear()
        val raw = prefs.getString("resource_snapshots_json", "").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            resourceSnapshots[id] = ResourceSnapshot(
                villageId = id,
                villageName = o.optString("name", id),
                wood = o.optInt("wood", -1), woodCap = o.optInt("woodCap", -1),
                clay = o.optInt("clay", -1), clayCap = o.optInt("clayCap", -1),
                iron = o.optInt("iron", -1), ironCap = o.optInt("ironCap", -1),
                crop = o.optInt("crop", -1), cropCap = o.optInt("cropCap", -1),
                woodProd = o.optInt("woodProd", -1), clayProd = o.optInt("clayProd", -1),
                ironProd = o.optInt("ironProd", -1), cropProd = o.optInt("cropProd", -1),
                updatedAt = o.optString("updatedAt", "--")
            )
        }
    }

    private fun saveResourceSnapshots() {
        debugTrace("ENTER saveResourceSnapshots")
        try {
            val array = JSONArray()
            resourceSnapshots.values.forEach { d ->
                array.put(JSONObject().apply {
                    put("id", d.villageId)
                    put("name", d.villageName)
                    put("wood", d.wood); put("woodCap", d.woodCap)
                    put("clay", d.clay); put("clayCap", d.clayCap)
                    put("iron", d.iron); put("ironCap", d.ironCap)
                    put("crop", d.crop); put("cropCap", d.cropCap)
                    put("woodProd", d.woodProd); put("clayProd", d.clayProd)
                    put("ironProd", d.ironProd); put("cropProd", d.cropProd)
                    put("updatedAt", d.updatedAt)
                })
            }
            getSharedPreferences("config", MODE_PRIVATE).edit()
                .putString("resource_snapshots_json", array.toString())
                .apply()
        } catch (_: Exception) { }
    }

    private fun setupTabs() {
        // Jangan melakukan pembacaan/parsing log berat langsung di callback klik tab.
        // Visibility diubah dulu, lalu konten dirender setelah UI punya kesempatan
        // menggambar frame berikutnya. Pembacaan file Log sendiri dilakukan async.
        fun showTab(tab: View) {
            android.util.Log.d("TravianFarmAssistant", "TAB -> ${when (tab) {
                farmTab -> "FARM"
                capacityTab -> "CAPACITY"
                logTab -> "LOG"
                else -> "UNKNOWN"
            }}")

            farmTab.visibility = if (tab === farmTab) View.VISIBLE else View.GONE
            capacityTab.visibility = if (tab === capacityTab) View.VISIBLE else View.GONE
            logTab.visibility = if (tab === logTab) View.VISIBLE else View.GONE

            handler.post {
                when {
                    tab === capacityTab -> renderCapacityOverview()
                    tab === logTab -> refreshLogOverview()
                }
            }
        }
        addLogControlsIfNeeded()
        farmTabButton.setOnClickListener { showTab(farmTab) }
        capacityTabButton.setOnClickListener { showTab(capacityTab) }
        logTabButton.setOnClickListener { showTab(logTab) }
        showTab(farmTab)
    }

    private fun addLogControlsIfNeeded() {
        debugTrace("ENTER addLogControlsIfNeeded")
        val parent = logOverview.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>("log_controls") != null) return

        val index = parent.indexOfChild(logOverview).coerceAtLeast(0)
        val originalParams = logOverview.layoutParams
        parent.removeView(logOverview)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = originalParams
            tag = "log_controls_wrapper"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(0, 0, 0, 8)
            tag = "log_controls"
        }
        val refresh = Button(this).apply {
            text = "REFRESH"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
            setOnClickListener {
                refreshLogOverview()
                refreshRecentLogs()
            }
        }
        val clear = Button(this).apply {
            text = "HAPUS"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Hapus log?")
                    .setMessage("Semua log aktivitas akan dihapus.")
                    .setNegativeButton("BATAL", null)
                    .setPositiveButton("HAPUS") { _, _ -> clearActivityLog() }
                    .show()
            }
        }
        row.addView(refresh)
        row.addView(clear)
        wrapper.addView(row)
        wrapper.addView(logOverview, LinearLayout.LayoutParams(-1, -2))
        parent.addView(wrapper, index)
    }

    private fun clearActivityLog() {
        logIoExecutor.execute {
            runCatching { getFileStreamPath(logFileName).delete() }
            handler.post {
                if (isFinishing) return@post
                logOverview.text = "Belum ada log."
                recentLogs.text = "Belum ada log."
            }
        }
    }

    private fun parseResourcePair(raw: String): Pair<Int, Int> {
        debugTrace("ENTER parseResourcePair")
        val normalized = raw.replace("\u00a0", " ").replace(".", "").replace(",", "")
        val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(normalized)
        return if (m != null) {
            (m.groupValues[1].toIntOrNull() ?: -1) to (m.groupValues[2].toIntOrNull() ?: -1)
        } else {
            val nums = Regex("\\d+").findAll(normalized).mapNotNull { it.value.toIntOrNull() }.toList()
            if (nums.size >= 2) nums[0] to nums[1] else -1 to -1
        }
    }

    private fun percent(current: Int, capacity: Int): Int =
        if (current >= 0 && capacity > 0) ((current.toDouble() / capacity) * 100.0).toInt().coerceIn(0, 100) else -1

    private fun renderCapacityOverview() {
        debugTrace("ENTER renderCapacityOverview")
        capacityOverview.removeAllViews()
        if (resourceSnapshots.isEmpty()) {
            capacityStatus.text = "Belum ada data resource. Tekan REFRESH VILLAGE untuk membaca semua village."
            return
        }
        capacityStatus.text = "${resourceSnapshots.size} village | update terakhir dari scanner"
        for ((_, data) in resourceSnapshots) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.parseColor("#202124"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, 10)
                }
            }
            val title = TextView(this).apply {
                text = data.villageName
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            card.addView(title)
            card.addView(resourceLine("🌲 Kayu", data.wood, data.woodCap, data.woodProd))
            card.addView(resourceLine("🧱 Liat", data.clay, data.clayCap, data.clayProd))
            card.addView(resourceLine("⚙️ Besi", data.iron, data.ironCap, data.ironProd))
            card.addView(resourceLine("🌾 Gandum", data.crop, data.cropCap, data.cropProd))
            val updated = TextView(this).apply {
                text = "Update: ${data.updatedAt}"
                textSize = 11f
                setTextColor(Color.LTGRAY)
            }
            card.addView(updated)
            capacityOverview.addView(card)
        }
    }

    private fun resourceLine(label: String, current: Int, capacity: Int, production: Int): View {
        debugTrace("ENTER resourceLine")
        val pct = percent(current, capacity)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 3, 0, 3)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val left = TextView(this).apply {
            text = if (pct >= 0) "$label   $current / $capacity   $pct%" else "$label   data belum terbaca"
            textSize = 14f
            setTextColor(if (pct >= 90) Color.YELLOW else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val right = TextView(this).apply {
            text = when {
                production > 0 -> "+$production/jam"
                production == 0 -> "0/jam"
                production < 0 -> "$production/jam"
                else -> "--/jam"
            }
            textSize = 13f
            setTextColor(if (production < 0) Color.RED else Color.LTGRAY)
            if (production < 0) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        row.addView(left)
        row.addView(right)
        return row
    }

    private fun refreshLogOverview() {
        if (!::logOverview.isInitialized || isFinishing) return
        val requestId = ++logOverviewRequestId
        logOverview.text = "Memuat log..."

        logIoExecutor.execute {
            val lines = readLastLogLines(120, 131072)
            handler.post {
                if (isFinishing || requestId != logOverviewRequestId || logTab.visibility != View.VISIBLE) return@post
                logOverview.text = if (lines.isEmpty()) {
                    "Belum ada log."
                } else {
                    buildColoredLog(lines.asReversed())
                }
            }
        }
    }

    private fun buildColoredLog(lines: List<String>): CharSequence {
        val palette = intArrayOf(
            Color.rgb(245, 166, 35),   // kuning/amber
            Color.rgb(30, 100, 210),   // biru
            Color.rgb(30, 140, 80),    // hijau
            Color.rgb(125, 70, 180),   // ungu
            Color.rgb(220, 95, 35),    // orange
            Color.rgb(0, 125, 145)     // teal
        )
        val out = SpannableString(lines.joinToString("\n"))
        var offset = 0
        lines.forEach { line ->
            val match = Regex("\\[CYCLE (\\d+)\\]").find(line)
            val color = if (match != null) {
                val number = match.groupValues[1].toIntOrNull() ?: 0
                palette[((number - 1).coerceAtLeast(0)) % palette.size]
            } else {
                Color.DKGRAY
            }
            val end = offset + line.length
            out.setSpan(android.text.style.ForegroundColorSpan(color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = end + 1
        }
        return out
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateCycleTimes(prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val farmStart = prefs.getLong("farm_cycle_started_at", 0L)
        val resourceStart = prefs.getLong("resource_cycle_started_at", 0L)
        val farmDuration = if (farmStart > 0L) now - farmStart else prefs.getLong("farm_cycle_duration_ms", 0L)
        val resourceDuration = if (resourceStart > 0L) now - resourceStart else prefs.getLong("resource_cycle_duration_ms", 0L)
        farmCycleTime.text = "Waktu Siklus Farm List: ${formatDuration(farmDuration)}"
        resourceCycleTime.text = "Waktu Siklus Resource Builder: ${formatDuration(resourceDuration)}"
    }

    private fun updateCountdown() {
        debugTrace("ENTER updateCountdown")
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        updateCycleTimes(prefs)
        val serviceRunning = prefs.getBoolean("service_running", false)
        val next = prefs.getLong("next_run_at", 0L)
        val last = prefs.getString("last_run", "").orEmpty()
        lastRun.text = if (last.isBlank()) "Last run: --" else "Last run: $last"
        if (!serviceRunning) {
            nextRun.text = "Next run: --"
            return
        }
        if (next <= 0L) {
            nextRun.text = "Next run: setelah siklus selesai"
            return
        }

        val remaining = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val totalSeconds = remaining / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val target = timeFormat.format(Date(next))
        nextRun.text = String.format(
            Locale.getDefault(),
            "Next run: %s | Countdown: %02d:%02d:%02d",
            target, hours, minutes, seconds
        )

        if (remaining == 0L) {
            status.text = "Status: RUNNING — menunggu siklus berikutnya"
        } else {
            status.text = "Status: RUNNING — BACKGROUND"
        }

    }

    override fun onDestroy() {
        if (instanceRef?.get() === this) instanceRef = null
        debugTrace("ENTER onDestroy")
        villageScanActive = false
        villageScanTargets.clear()
        FarmAutomationService.detachVisibleWebView(webView)
        FarmAutomationService.onVisibleWebViewDetached()
        handler.removeCallbacks(countdownUpdater)
        logEvent("MainActivity ditutup; background service tetap dapat berjalan")
        logIoExecutor.shutdownNow()
        super.onDestroy()
    }
}
