package pl.zlapegzamin.test

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.view.WindowInsets
import android.view.ViewGroup
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.text.InputType
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DashboardActivity : Activity() {
    private val uiPrefs by lazy { getSharedPreferences("zlap_ui_v1", MODE_PRIVATE) }
    private val darkMode: Boolean get() = uiPrefs.getBoolean("dark_mode", false)
    private val bg: Int get() = if (darkMode) Color.rgb(12, 19, 32) else Color.rgb(246, 249, 253)
    private val cardBg: Int get() = if (darkMode) Color.rgb(25, 36, 54) else Color.WHITE
    private val ink: Int get() = if (darkMode) Color.rgb(238, 244, 255) else Color.rgb(28, 43, 70)
    private val muted: Int get() = if (darkMode) Color.rgb(169, 183, 204) else Color.rgb(99, 115, 137)
    private val blue: Int get() = if (darkMode) Color.rgb(113, 163, 255) else Color.rgb(42, 110, 234)
    private val paleBlue: Int get() = if (darkMode) Color.rgb(36, 57, 89) else Color.rgb(233, 241, 255)
    private val lineColor: Int get() = if (darkMode) Color.rgb(54, 71, 97) else Color.rgb(229, 235, 244)
    private val green = Color.rgb(8, 142, 101)
    private val portal = "https://info-kierowca.pl/reservation"
    private lateinit var pages: FrameLayout
    private lateinit var browserPane: LinearLayout
    private lateinit var browser: WebView
    private lateinit var navigation: LinearLayout
    private lateinit var appRoot: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var portalToolbar: LinearLayout
    private lateinit var monitor: SlotMonitor
    private var tab = "start"
    private var calendarMonthOffset = 0

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    private fun round(color: Int, r: Int = 18) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun text(s: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            this.text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun put(parent: LinearLayout, view: View, bottom: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) })
    }
    private fun card() = column().apply {
        background = GradientDrawable().apply {
            setColor(cardBg)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), lineColor)
        }
        elevation = dp(1).toFloat()
        setPadding(dp(19), dp(19), dp(19), dp(19))
    }
    private fun action(title: String, bright: Boolean = true, perform: () -> Unit) =
        text(title, 15f, if (bright) Color.WHITE else blue, true).apply {
            background = round(if (bright) blue else paleBlue, 12)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(15), dp(12), dp(15))
            setOnClickListener { perform() }
        }
    private fun info(key: String, value: String) = column().apply {
        put(this, text(key, 12f, muted), 5)
        put(this, text(value, 16f, ink, true))
    }
    private fun outside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Nie można otworzyć tego linku", Toast.LENGTH_LONG).show()
        }
    }
    private fun applyTheme() {
        window.statusBarColor = if (darkMode) bg else Color.rgb(42, 110, 234)
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        appRoot.setBackgroundColor(bg)
        pages.setBackgroundColor(bg)
        headerBar.setBackgroundColor(cardBg)
        headerBar.removeAllViews()
        val brand = column()
        put(brand, text("ZłapEgzamin", 25f, ink, true), 2)
        put(brand, text("Terminy prawa jazdy", 12f, muted))
        headerBar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        headerBar.addView(text("●  MONITOR", 12f, green, true))
        portalToolbar.removeAllViews()
        portalToolbar.addView(text("info-kierowca.pl", 15f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        portalToolbar.addView(text("↗  Przeglądarka", 13f, blue, true).apply {
            setOnClickListener { outside(Uri.parse(portal)) }
        })
        navigation.setBackgroundColor(cardBg)
        show(tab)
    }

    private fun go(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "https", "http", "about", "data", "blob" -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try { startActivity(intent) } catch (_: Exception) {
                        if (fallback != null && Uri.parse(fallback).scheme == "https")
                            outside(Uri.parse(fallback))
                        else Toast.makeText(this, "Brak aplikacji do logowania", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Nieobsługiwany link logowania", Toast.LENGTH_LONG).show()
                }
                true
            }
            "mobywatel" -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    })
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Zainstaluj lub zaktualizuj mObywatel.", Toast.LENGTH_LONG).show()
                    outside(Uri.parse("https://www.gov.pl/web/mobywatel"))
                } catch (_: Exception) {
                    Toast.makeText(this, "Nie udało się otworzyć mObywatel.", Toast.LENGTH_LONG).show()
                }
                true
            }
            "market", "tel", "mailto", "sms" -> { outside(uri); true }
            else -> {
                Toast.makeText(this, "Nieznany typ linku: " + uri.scheme, Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = if (darkMode) Color.rgb(12, 19, 32) else Color.rgb(42, 110, 234)
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val root = column().apply { setBackgroundColor(bg) }
        appRoot = root
        val header = row().apply {
            setPadding(dp(22), dp(17), dp(22), dp(17))
            setBackgroundColor(cardBg)
        }
        headerBar = header
        val brand = column()
        put(brand, text("ZłapEgzamin", 25f, ink, true), 2)
        put(brand, text("Terminy prawa jazdy", 12f, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("●  MONITOR", 12f, green, true))
        put(root, header)

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        pages = FrameLayout(this).apply {
            setBackgroundColor(bg)
            isClickable = true
        }
        body.addView(pages, FrameLayout.LayoutParams(-1, -1))

        browserPane = column()
        val toolbar = row().apply { setPadding(dp(16), dp(7), dp(16), dp(13)) }
        portalToolbar = toolbar
        toolbar.addView(text("info-kierowca.pl", 15f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(text("↗  Przeglądarka", 13f, blue, true).apply {
            setOnClickListener { outside(Uri.parse(portal)) }
        })
        put(browserPane, toolbar)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (::monitor.isInitialized) monitor.onPortalNavigation(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                    go(request.url)
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.let { go(Uri.parse(it)) } ?: false
            }
        }
        browserPane.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(browserPane, FrameLayout.LayoutParams(-1, -1))

        navigation = row().apply {
            setBackgroundColor(cardBg)
            setPadding(dp(12), dp(9), dp(12), dp(10))
        }
        put(root, navigation)
        // Reserve real space for the system status and navigation bars.
        // Never manually add their insets to app controls as well.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) root.requestApplyInsets()

        monitor = SlotMonitor(this, browser) {
            if (::pages.isInitialized && ::monitor.isInitialized && (tab == "start" || tab == "slots")) show(tab)
        }
        monitor.install()
        browser.loadUrl(portal)
        if (monitor.enabled) startBackgroundMonitor()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        show(if (intent.getBooleanExtra("open_portal", false)) "portal" else "start")
    }

    private fun scroll(): LinearLayout {
        val scroll = ScrollView(this)
        val inside = column().apply { setPadding(dp(20), dp(10), dp(20), dp(30)) }
        scroll.addView(inside)
        pages.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return inside
    }
    private fun show(which: String) {
        tab = which
        pages.removeAllViews()
        // Keep WebView in VISIBLE state even behind the opaque dashboard.
        // Android may throttle JS in an INVISIBLE WebView before the portal
        // exposes its session-extension prompt.
        browserPane.visibility = View.VISIBLE
        pages.visibility = if (which == "portal") View.GONE else View.VISIBLE
        if (which == "portal") browserPane.bringToFront() else pages.bringToFront()
        when (which) {
            "settings" -> settingsPage()
            "slots" -> slotsPage()
            "start" -> homePage()
        }
        navigation.removeAllViews()
        for ((id, title) in listOf("start" to "⌂ Panel", "slots" to "◷ Terminy",
            "portal" to "◎ Portal", "settings" to "⚙ Opcje")) {
            val selected = id == which
            val label = text(title, 12f, if (selected) blue else muted, selected).apply {
                gravity = Gravity.CENTER
                background = round(if (selected) paleBlue else cardBg, 12)
                setPadding(dp(4), dp(13), dp(4), dp(13))
                setOnClickListener { if (id != tab) show(id) }
            }
            navigation.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }
    /** Redraw settings without jumping back to the top after a quick preset. */
    private fun refreshSettingsAtSamePosition() {
        val old = (pages.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        show("settings")
        (pages.getChildAt(0) as? ScrollView)?.post {
            (pages.getChildAt(0) as? ScrollView)?.scrollTo(0, old)
        }
    }

    private fun manualCheck() {
        monitor.checkNow { result ->
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        }
    }


    private fun switchAutoReserve() {
        if (monitor.autoReserveEnabled) {
            monitor.setAutoReserve(false)
            show("settings")
            return
        }
        if (monitor.dateFrom.isBlank() || monitor.dateTo.isBlank()) {
            Toast.makeText(this, "Najpierw ustaw dokładny zakres dat Od–Do.", Toast.LENGTH_LONG).show()
            return
        }
        val style = if (darkMode) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert
        android.app.AlertDialog.Builder(this, style)
            .setTitle("Włączyć autorezerwację?")
            .setMessage(
                "ZłapEgzamin spróbuje samodzielnie ZAJĄĆ jedno wolne miejsce na egzamin " +
                "praktyczny kat. B w MORD Kraków w zakresie " +
                monitor.dateFrom + " – " + monitor.dateTo + ".\n\n" +
                "To wykonuje rzeczywistą operację na Twoim koncie info-kierowca.pl. " +
                "Po potwierdzeniu rezerwacji dostaniesz alert na telefon i Telegram. " +
                "Opłacenie egzaminu nadal należy do Ciebie (zwykle do 30 minut). " +
                "Po sukcesie autorezerwacja wyłączy się sama."
            )
            .setPositiveButton("Włącz autorezerwację") { _, _ ->
                if (monitor.setAutoReserve(true))
                    Toast.makeText(this, "Autorezerwacja włączona. Czekam na poprawny odczyt.", Toast.LENGTH_LONG).show()
                else
                    Toast.makeText(this, "Zakres dat jest nieprawidłowy (maks. 30 dni).", Toast.LENGTH_LONG).show()
                show("settings")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun homePage() {
        val page = scroll()
        val hero = card().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(27, 92, 226), Color.rgb(79, 150, 249))
            ).apply { cornerRadius = dp(20).toFloat() }
        }
        put(hero, text("Twój asystent egzaminu", 22f, Color.WHITE, true), 8)
        put(hero, text("MORD Kraków   •   Kategoria B   •   Praktyczny",
            13f, Color.rgb(235, 246, 255)), 12)
        put(hero, text("Automatyczne powiadomienia o wolnych miejscach",
            14f, Color.WHITE, true))
        put(page, hero, 19)
        put(page, text("Znajdź wcześniejszy egzamin", 21f, ink, true), 7)
        put(page, text("MORD Kraków • Kategoria B • Praktyczny", 14f, muted), 22)

        val status = card()
        put(status, text("AUTOMATYCZNE SPRAWDZANIE", 12f, muted, true), 12)
        val now = System.currentTimeMillis()
        val connected = monitor.enabled && !monitor.sessionExpired &&
            monitor.requestReady && monitor.lastCheckedAt > 0L &&
            monitor.blockedUntil <= now
        val stateLabel = when {
            !monitor.enabled -> "●  Monitor wyłączony"
            monitor.sessionExpired -> "●  WYLOGOWANO — zaloguj się ponownie"
            monitor.blockedUntil > now -> "●  Limit HTTP 429"
            monitor.requestReady && monitor.lastCheckedAt == 0L -> "●  Czekam na pierwszy odczyt"
            connected -> "●  Odczyt terminów działa"
            else -> "●  Otwórz portal i wybierz egzamin"
        }
        put(status, text(stateLabel,
            20f, if (connected) green else Color.rgb(164, 110, 34), true), 10)
        put(status, text(monitor.status(), 14f, muted), 10)
        if (monitor.requestReady && monitor.lastCheckedAt == 0L) {
            put(status, text("Portal wykryty, ale dane nie zostały jeszcze poprawnie pobrane. " +
                "Sprawdź komunikaty w Opcjach → Diagnostyka.", 13f, muted), 10)
        }
        put(status, text("Zapytania w ciągu godziny: " + monitor.searchesThisHour + "/4", 14f, ink, true), 5)
        val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL"))
        put(status, text("Planowane automatyczne sprawdzenie: " +
            timeFormat.format(java.util.Date(monitor.nextPlannedAutoAt)), 13f, ink, true), 7)
        if (monitor.nextCheckAt > monitor.nextPlannedAutoAt + 30_000L) {
            put(status, text("Opóźnienie ze względu na limit portalu – możliwa próba: " +
                timeFormat.format(java.util.Date(monitor.nextCheckAt)), 13f, muted), 12)
        } else {
            put(status, text("Godzina ręcznego odczytu nie resetuje automatu.", 12f, muted), 12)
        }
        put(status, action(if (monitor.enabled) "Wstrzymaj monitorowanie" else "Włącz monitorowanie") {
            monitor.setEnabled(!monitor.enabled)
            if (monitor.enabled) startBackgroundMonitor() else stopService(Intent(this, MonitorForegroundService::class.java))
        }, 11)
        put(status, action("↻  Sprawdź teraz (ręcznie)", false) { manualCheck() }, 7)
        put(status, text("Minimalny odstęp automatu: " +
            monitor.minimumAutoGapSeconds + " s (fabrycznie 900 s). " +
            "Zmiana w Opcje → Częstotliwość. Ręczny odczyt nie przesuwa planu.",
            12f, muted), 11)
        put(status, action("Zaloguj się i wybierz egzamin  →", false) { show("portal") })
        put(page, status, 12)
        val reservation = card()
        put(reservation, text("TYMCZASOWA AUTOREZERWACJA", 12f, blue, true), 9)
        put(reservation, text(if (monitor.autoReserveEnabled)
            "● Aktywna – aplikacja może zająć jedno pasujące miejsce"
            else "○ Wyłączona", 15f, ink, true), 10)
        put(reservation, text("Ostatni stan: " + monitor.lastBookingStatus, 13f, muted), 8)
        if (monitor.lastBookingDate.isNotBlank()) {
            put(reservation, text("Termin: " + monitor.lastBookingDate.replace('T', ' ').take(16),
                13f, muted), 8)
        }
        put(reservation, action(if (monitor.autoReserveEnabled)
            "Wyłącz autorezerwację" else "Ustaw autorezerwację", false) {
            if (monitor.autoReserveEnabled) monitor.setAutoReserve(false).also { show("start") }
            else show("settings")
        }, 10)
        put(reservation, action("Otwórz portal, aby opłacić rezerwację") { show("portal") })
        put(page, reservation, 14)
        put(page, action("⚙  Ustawienia: interwał, daty, Telegram", false) { show("settings") }, 20)

        put(page, text("Dostępne terminy", 19f, ink, true), 12)
        val results = card()
        put(results, text("NAJBLIŻSZY TERMIN", 11f, Color.rgb(143, 182, 255), true), 10)
        put(results, text(monitor.earliest.ifBlank { "Jeszcze nieodczytany" },
            23f, ink, true), 12)
        put(results, text("Wolnych terminów w odczycie: " + monitor.slotCount, 14f, muted), 10)
        put(results, text("Ostatni udany odczyt: " + (if (monitor.lastCheckedAt > 0L) java.text.SimpleDateFormat("dd.MM HH:mm:ss", Locale("pl", "PL")).format(java.util.Date(monitor.lastCheckedAt)) else "brak"), 14f, muted), 14)
        put(results, action("Zobacz listę znalezionych terminów", false) { show("slots") })
        put(page, results, 18)
        calendarSection(page)
        val datesList = monitor.recentSlots
        if (datesList.isNotEmpty()) {
            val upcoming = card()
            put(upcoming, text("NAJBLIŻSZE W OSTATNIM ODCZYCIE", 12f, blue, true), 12)
            for (date in datesList.take(8)) {
                put(upcoming, text("  •  " + date.replace('T', ' ').take(16), 16f, ink), 9)
            }
            put(upcoming, text("Lista jest zapisana z ostatniego odczytu, dostępność może się zmienić.", 12f, muted))
            put(page, upcoming, 20)
        }

        put(page, text("Historia monitorowania", 19f, ink, true), 12)
        val log = card()
        if (monitor.eventHistory.isEmpty()) {
            put(log, text("Historia pojawi się po pierwszym odczycie.", 13f, muted))
        } else {
            for (entry in monitor.eventHistory.take(5))
                put(log, text("•  " + entry, 13f, ink), 9)
        }
        put(page, log, 20)
        put(page, text("Szybkie działania", 19f, ink, true), 12)
        val tools = card()
        put(tools, action("↗  Zaloguj się przez przeglądarkę") { outside(Uri.parse(portal)) }, 11)
        put(tools, action("↻  Przedłuż sesję", false) { renewSession() }, 11)
        put(tools, action("🔔  Test powiadomienia", false) { monitor.testNotification() })
        put(page, tools, 20)
        put(page, text("ZłapEgzamin Android • Monitor sprawdza terminy w granicach limitów portalu.\n"+
            "Monitor w tle działa dopóki Android utrzymuje proces aplikacji; po zamknięciu lub ubiciu procesu zatrzyma się. Bez automatycznej rezerwacji.", 12f, muted))
    }
    private fun setCalendarBoundary(day: String, fromSide: Boolean) {
        val from = if (fromSide) day else monitor.dateFrom
        val to = if (fromSide) monitor.dateTo else day
        if (from.isNotBlank() && to.isNotBlank() && from > to) {
            Toast.makeText(this, "Data od nie może być późniejsza niż data do.", Toast.LENGTH_LONG).show()
            return
        }
        monitor.setDateRange(from, to)
        Toast.makeText(this, "Zapisano zakres dat powiadomień.", Toast.LENGTH_SHORT).show()
        // SlotMonitor triggers a refresh when the start tab is open.
    }

    private fun calendarDay(day: String) {
        val hours = monitor.recentSlots.filter { it.take(10) == day }
            .map { it.replace('T', ' ').take(16) }
        val detail = if (hours.isEmpty()) {
            "Brak zapisanych wolnych terminów na ten dzień.\n\n" +
                "Możesz wybrać go jako początek lub koniec zakresu powiadomień."
        } else {
            "Terminy z ostatniego odczytu:\n" + hours.joinToString("\n") +
                "\n\nDostępność może się już zmienić."
        }
        val style = if (darkMode) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert
        android.app.AlertDialog.Builder(this, style)
            .setTitle("Data: $day")
            .setMessage(detail)
            .setItems(arrayOf(
                "Ustaw jako datę OD",
                "Ustaw jako datę DO",
                "Otwórz portal info-kierowca.pl"
            )) { _, which ->
                when (which) {
                    0 -> setCalendarBoundary(day, true)
                    1 -> setCalendarBoundary(day, false)
                    2 -> show("portal")
                }
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun calendarSection(page: LinearLayout) {
        val month = java.time.YearMonth.now().plusMonths(calendarMonthOffset.toLong())
        val available = monitor.recentSlots.map { it.take(10) }.toSet()
        val from = monitor.dateFrom
        val to = monitor.dateTo
        val panel = card()
        val heading = row()
        val previous = text("❮", 19f, blue, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                if (calendarMonthOffset > 0) {
                    calendarMonthOffset--
                    show("start")
                }
            }
        }
        heading.addView(previous)
        val monthName = month.atDay(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale("pl", "PL")))
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale("pl", "PL")) else it.toString()
            }
        heading.addView(text(monthName, 17f, ink, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("❯", 19f, blue, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                if (calendarMonthOffset < 5) {
                    calendarMonthOffset++
                    show("start")
                }
            }
        })
        put(panel, heading, 14)

        val weekdays = row()
        for (dayName in listOf("PN", "WT", "ŚR", "CZ", "PT", "SO", "ND")) {
            weekdays.addView(text(dayName, 11f, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        put(panel, weekdays, 8)
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cellCount = ((offset + month.lengthOfMonth() + 6) / 7) * 7
        for (week in 0 until cellCount / 7) {
            val line = row()
            for (weekday in 0 until 7) {
                val number = week * 7 + weekday - offset + 1
                val valid = number in 1..month.lengthOfMonth()
                val date = if (valid) month.atDay(number).toString() else ""
                val highlighted = valid && date in available
                val rangeSelected = valid && (from.isNotBlank() || to.isNotBlank()) &&
                    (from.isBlank() || date >= from) && (to.isBlank() || date <= to)
                val cell = text(
                    if (valid) number.toString() else "",
                    14f,
                    if (highlighted) Color.WHITE else if (rangeSelected) blue else ink,
                    highlighted || rangeSelected
                ).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(1), dp(11), dp(1), dp(11))
                    background = round(
                        if (highlighted) Color.rgb(42, 110, 234)
                        else if (rangeSelected) paleBlue else cardBg, 12
                    )
                    if (valid) {
                        contentDescription = "Dzień $date" +
                            if (highlighted) ", są zapisane terminy" else ", brak zapisanych terminów"
                        isClickable = true
                        setOnClickListener { calendarDay(date) }
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            put(panel, line, 3)
        }
        put(panel, text("Dotknij dowolnego dnia: zobacz terminy lub ustaw zakres dat. " +
            "Niebieskie dni oznaczają ostatnio odczytane terminy, nie rezerwacje.",
            12f, muted))
        if (from.isNotBlank() || to.isNotBlank()) {
            put(panel, text("Zakres: " + from.ifBlank { "bez początku" } + " → " +
                to.ifBlank { "bez końca" }, 12f, blue, true), 8)
        }
        put(page, text("Kalendarz terminów", 19f, ink, true), 12)
        put(page, panel, 20)
    }

    private fun slotsPage() {
        val page = scroll()
        put(page, text("Znalezione terminy", 23f, ink, true), 7)
        put(page, text("Wolne miejsca z ostatniego prawidłowego odczytu portalu.", 13f, muted), 20)
        val result = card()
        val slots = monitor.recentSlots
        if (slots.isEmpty()) {
            put(result, text("Brak zapisanych terminów", 18f, ink, true), 10)
            put(result, text("Zaloguj się w portalu i wyświetl listę dostępnych egzaminów.",
                13f, muted), 16)
        } else {
            put(result, text("Dostępnych w ostatnim odczycie: " + monitor.slotCount,
                14f, blue, true), 12)
            for (date in slots) {
                put(result, text("◷  " + date.replace('T', ' ').take(16), 16f, ink, true), 12)
            }
        }
        put(result, action("↻  Wymuś odczyt teraz", false) { manualCheck() }, 10)
        put(result, action("Przejdź do oficjalnego portalu") { show("portal") })
        put(page, result, 16)
        put(page, text("Terminy mogą zniknąć przed ponownym sprawdzeniem. To nie jest potwierdzenie rezerwacji.", 12f, muted))
    }

    private fun settingsPage() {
        val page = scroll()
        put(page, text("Ustawienia", 24f, ink, true), 6)
        put(page, text("Powiadomienia i monitorowanie", 14f, muted), 18)
        val appearance = card()
        put(appearance, text("WYGLĄD APLIKACJI", 12f, blue, true), 12)
        put(appearance, text("Aktualny motyw: " + (if (darkMode) "ciemny" else "jasny"),
            15f, ink, true), 13)
        put(appearance, action(if (darkMode) "☀  Włącz jasny motyw" else "☾  Włącz ciemny motyw", false) {
            uiPrefs.edit().putBoolean("dark_mode", !darkMode).apply()
            applyTheme()
        })
        put(page, appearance, 19)
        val preferences = card()
        put(preferences, text("WYBRANY EGZAMIN", 12f, Color.rgb(143, 182, 255), true), 17)
        put(preferences, info("Ośrodek", "MORD Kraków (ID 21)"), 17)
        put(preferences, info("Kategoria", "B"), 17)
        put(preferences, info("Rodzaj", "Praktyczny"))
        put(page, preferences, 19)

        val dates = card()
        put(dates, text("ZAKRES DAT EGZAMINU", 12f, Color.rgb(143, 182, 255), true), 12)
        put(dates, text("Powiadomienia tylko o terminach w wybranym zakresie. Nie zmienia to dat wyszukiwanych przez portal.", 13f, muted), 12)
        fun pickDate(isFrom: Boolean) {
            val cal = Calendar.getInstance()
            val stored = if (isFrom) monitor.dateFrom else monitor.dateTo
            try { if (stored.isNotBlank()) cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(stored) ?: cal.time } catch (_: Exception) {}
            val pickerStyle = if (darkMode) android.R.style.Theme_Material_Dialog_Alert
                else android.R.style.Theme_Material_Light_Dialog_Alert
            DatePickerDialog(this, pickerStyle, { _, year, month, day ->
                val chosen = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                val from = if (isFrom) chosen else monitor.dateFrom
                val to = if (isFrom) monitor.dateTo else chosen
                if (from.isNotBlank() && to.isNotBlank() && from > to) Toast.makeText(this, "Data od nie może być późniejsza niż data do", Toast.LENGTH_LONG).show()
                else {
                    monitor.setDateRange(from, to)
                    show("settings")
                }
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        put(dates, action("Od: " + monitor.dateFrom.ifBlank { "wybierz datę" }, false) { pickDate(true) }, 10)
        put(dates, action("Do: " + monitor.dateTo.ifBlank { "wybierz datę" }, false) { pickDate(false) }, 10)
        put(dates, text("Szybki wybór zakresu", 13f, muted), 10)
        val preset = row()
        for (days in listOf(7, 14, 30)) {
            val button = text("$days dni", 14f, blue, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(13), dp(6), dp(13))
                background = round(paleBlue, 12)
                setOnClickListener {
                    val from = Calendar.getInstance()
                    from.add(Calendar.DAY_OF_MONTH, 2)
                    val to = Calendar.getInstance()
                    to.add(Calendar.DAY_OF_MONTH, days)
                    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    monitor.setDateRange(format.format(from.time), format.format(to.time))
                    show("settings")
                }
            }
            preset.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        put(dates, preset, 10)
        put(dates, action("Wyczyść zakres", false) {
            monitor.setDateRange("", "")
            show("settings")
        })
        put(page, dates, 19)

        val booking = card()
        put(booking, text("AUTOREZERWACJA – ZAJĘCIE MIEJSCA", 12f, blue, true), 12)
        put(booking, text("ZłapEgzamin sam utworzy tymczasową rezerwację dla " +
            "jednego terminu spełniającego zakres dat. Po potwierdzeniu przez portal wyśle " +
            "powiadomienie i zatrzyma automat. Nie pobiera pieniędzy.", 14f, muted), 10)
        put(booking, text(if (monitor.autoReserveEnabled) "● WŁĄCZONA" else "○ WYŁĄCZONA",
            17f, if (monitor.autoReserveEnabled) green else muted, true), 9)
        put(booking, text("Status: " + monitor.lastBookingStatus, 13f, muted), 8)
        if (monitor.lastReservationId.isNotBlank()) {
            put(booking, text("ID rezerwacji: " + monitor.lastReservationId,
                12f, muted), 11)
        }
        put(booking, action(if (monitor.autoReserveEnabled)
            "Wyłącz autorezerwację" else "Włącz autorezerwację") {
            switchAutoReserve()
        }, 11)
        put(booking, action("Przejdź do portalu i opłać termin", false) {
            show("portal")
        })
        put(page, booking, 19)

        val frequency = card()
        put(frequency, text("CZĘSTOTLIWOŚĆ SPRAWDZANIA", 12f, Color.rgb(143, 182, 255), true), 12)
        put(frequency, text("Interwał bazowy: " + monitor.intervalSeconds + " s. " +
            "Minimalna przerwa: " + monitor.minimumAutoGapSeconds + " s. " +
            "Rzeczywisty odstęp to dłuższa z tych wartości. " +
            "Limit bezpieczeństwa nadal wynosi 4 odczyty na godzinę; " +
            "HTTP 429 może dodatkowo wydłużyć oczekiwanie.", 14f, muted), 12)
        for (seconds in listOf(30, 60, 300, 900, 1800)) {
            put(frequency, action("$seconds sekund", monitor.intervalSeconds == seconds) {
                monitor.setInterval(seconds)
                show("settings")
            }, 8)
        }
        val custom = EditText(this).apply {
            hint = "Własny interwał (10–3600 sekund)"
            setTextColor(ink); setHintTextColor(muted); inputType = InputType.TYPE_CLASS_NUMBER
            background = round(paleBlue, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        put(frequency, custom, 10)
        put(frequency, action("Zapisz własny interwał") {
            val value = custom.text.toString().toIntOrNull()
            if (value == null || value !in 10..3600) Toast.makeText(this, "Wpisz liczbę od 10 do 3600", Toast.LENGTH_LONG).show()
            else {
                monitor.setInterval(value)
                show("settings")
            }
        })
        put(page, frequency, 19)

        val minGap = card()
        put(minGap, text("MINIMALNY ODSTĘP AUTOMATU", 12f, blue, true), 12)
        put(minGap, text("Fabrycznie: 15 minut (900 sekund). " +
            "Możesz ustawić krótszą lub dłuższą przerwę. " +
            "Nie zmienia to ograniczeń serwera ani limitu 4 prób na godzinę.",
            14f, muted), 12)
        put(minGap, text("Obecnie: " + monitor.minimumAutoGapSeconds + " sekund", 17f, ink, true), 12)
        for (seconds in listOf(30, 60, 120, 300, 600, 900, 1800, 3600)) {
            val description = if (seconds >= 60 && seconds % 60 == 0)
                (seconds / 60).toString() + " min" else seconds.toString() + " s"
            val label = (if (monitor.minimumAutoGapSeconds == seconds) "✓  " else "") +
                description + (if (seconds == 900) " (fabrycznie)" else "")
            put(minGap, action(label, monitor.minimumAutoGapSeconds == seconds) {
                monitor.setMinimumAutoGap(seconds)
                refreshSettingsAtSamePosition()
            }, 7)
        }
        val customMinGap = EditText(this).apply {
            hint = "Własny minimalny odstęp (10–3600 s)"
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = round(paleBlue, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        put(minGap, customMinGap, 10)
        put(minGap, action("Zapisz minimalny odstęp") {
            val seconds = customMinGap.text.toString().toIntOrNull()
            if (seconds == null || !monitor.setMinimumAutoGap(seconds)) {
                Toast.makeText(this, "Podaj od 10 do 3600 sekund", Toast.LENGTH_LONG).show()
            } else refreshSettingsAtSamePosition()
        }, 8)
        put(minGap, action("Przywróć fabryczne 15 minut", false) {
            monitor.setMinimumAutoGap(900)
            refreshSettingsAtSamePosition()
        })
        put(page, minGap, 19)

        val debug = card()
        put(debug, text("DIAGNOSTYKA POŁĄCZENIA", 12f, Color.rgb(143, 182, 255), true), 12)
        put(debug, text(monitor.diagnostics, 14f, muted), 12)
        put(debug, text("Ostatni błąd: " + monitor.lastError.ifBlank { "brak" }, 13f, muted), 12)
        put(debug, text("Ostatni udany odczyt: " + (if (monitor.lastCheckedAt > 0L) java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("pl", "PL")).format(java.util.Date(monitor.lastCheckedAt)) else "brak"), 13f, muted), 12)
        put(debug, text("Zapytania z ostatniej godziny: " + monitor.searchesThisHour + "/4", 13f, muted), 10)
        put(debug, text("Blokada HTTP 429: " +
            (if (monitor.blockedUntil > System.currentTimeMillis())
                SimpleDateFormat("dd.MM HH:mm", Locale("pl", "PL")).format(java.util.Date(monitor.blockedUntil))
             else "brak"), 13f, muted), 12)
        put(debug, action("Kopiuj diagnostykę", false) {
            val report = "ZłapEgzamin Android\\n" +
                "Status: " + monitor.status() + "\\n" +
                "Diagnostyka: " + monitor.diagnostics + "\\n" +
                "Błąd: " + monitor.lastError + "\\n" +
                "HTTP/h: " + monitor.searchesThisHour
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ZłapEgzamin diagnostyka", report))
            Toast.makeText(this, "Skopiowano diagnostykę bez danych logowania", Toast.LENGTH_LONG).show()
        }, 10)
        put(debug, action("↻  Sprawdź terminy teraz", false) { manualCheck() }, 10)
        put(debug, action("Otwórz portal i ponów wybór egzaminu", false) { show("portal") })
        put(page, debug, 19)

        val alerts = card()
        put(alerts, text("POWIADOMIENIA NA TELEFONIE", 12f, Color.rgb(143, 182, 255), true), 12)
        put(alerts, text("Domyślnie pierwszy prawdziwy odczyt ustala listę znanych terminów.", 14f, muted), 14)
        put(alerts, action(if (monitor.notifyFirst) "Powiadom też przy pierwszym odczycie: TAK"
            else "Powiadom też przy pierwszym odczycie: NIE", false) {
            monitor.setNotifyFirst(!monitor.notifyFirst)
            show("settings")
        }, 12)
        put(alerts, action("Wyślij test powiadomienia") { monitor.testNotification() })
        put(page, alerts, 19)

        val telegram = card()
        put(telegram, text("TELEGRAM", 12f, Color.rgb(143, 182, 255), true), 12)
        put(telegram, text(if (monitor.telegramConfigured()) "Telegram skonfigurowany. Wpisz oba pola tylko, aby zmienić dane." else "Wpisz token bota i Chat ID. Dane pozostają na telefonie.", 14f, muted), 12)
        val token = EditText(this).apply {
            hint = "Token Telegram"; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = round(paleBlue, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val chat = EditText(this).apply {
            hint = "Chat ID"; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT
            background = round(paleBlue, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        put(telegram, token, 8)
        put(telegram, chat, 12)
        put(telegram, action("Zapisz ustawienia Telegram") {
            if (token.text.toString().isBlank() || chat.text.toString().isBlank()) {
                Toast.makeText(this, "Podaj token i Chat ID", Toast.LENGTH_LONG).show()
            } else {
                monitor.configureTelegram(token.text.toString(), chat.text.toString())
                Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
                show("settings")
            }
        }, 10)
        put(telegram, action("Wyślij test Telegram", false) { monitor.testTelegram() })
        put(page, telegram, 19)
        val guide = card()
        put(guide, text("JAK WŁĄCZYĆ MONITOR", 12f, Color.rgb(143, 182, 255), true), 12)
        put(guide, text("1. Przejdź do Portal i zaloguj się.\n"+
            "2. Wybierz MORD Kraków, kategorię B i egzamin praktyczny.\n"+
            "3. Otwórz na portalu listę dostępnych terminów.\n"+
            "4. Możesz wrócić do Start — aplikacja sprawdza terminy zgodnie z ustawionym interwałem, gdy jest otwarta.\n\n"+
            "Gdy sesja wygaśnie, konieczne będzie ponowne zalogowanie. "+
            "Po zminimalizowaniu monitor może działać z powiadomieniem, ale po zamknięciu procesu wymaga ponownego uruchomienia.", 14f, muted))
        put(page, guide, 18)
        put(page, action("Otwórz Portal") { show("portal") })
    }
    private fun renewSession() {
        if (!::browser.isInitialized) return
        show("portal")
        val current = browser.url
        val onPortal = current != null &&
            Uri.parse(current).host?.endsWith("info-kierowca.pl") == true
        if (!onPortal) {
            browser.loadUrl(portal)
            Toast.makeText(this, "Otwieram portal. Zaloguj się, jeśli to konieczne.", Toast.LENGTH_LONG).show()
            return
        }
        // Do NOT reload the portal: a reload can reset the observed exam
        // request without prolonging server-side authentication.
        browser.evaluateJavascript(
            "Boolean(window.__zlapExtendNow && window.__zlapExtendNow());"
        ) { clicked ->
            val message = if (clicked == "true")
                "Kliknięto przycisk przedłużenia w portalu. Sprawdź, czy sesja pozostała aktywna."
            else
                "Portal nie pokazuje przycisku Przedłuż sesję. Samo odświeżenie nie przedłuża logowania."
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startBackgroundMonitor() {
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, MonitorForegroundService::class.java))
            else startService(Intent(this, MonitorForegroundService::class.java))
        } catch (_: Exception) { Toast.makeText(this, "Nie można uruchomić usługi monitorowania", Toast.LENGTH_LONG).show() }
    }
    override fun onResume() {
        super.onResume()
        if (::monitor.isInitialized) monitor.resume()
    }
    override fun onPause() {
        // Keep the polling loop alive while the Activity remains in memory.
        // The foreground service keeps the process prioritized after Home/screen lock.
        super.onPause()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_portal", false)) show("portal")
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == "portal" && browser.canGoBack()) browser.goBack()
        else if (tab != "start") show("start")
        else super.onBackPressed()
    }
    override fun onDestroy() {
        if (::monitor.isInitialized) monitor.destroy()
        if (::browser.isInitialized) {
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            browser.destroy()
        }
        super.onDestroy()
    }
}
