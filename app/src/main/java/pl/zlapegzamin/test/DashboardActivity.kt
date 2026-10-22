package pl.zlapegzamin.test

import android.Manifest
import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DashboardActivity : Activity() {
    private val bg = Color.rgb(10, 16, 30)
    private val cardBg = Color.rgb(21, 31, 51)
    private val muted = Color.rgb(158, 174, 199)
    private val blue = Color.rgb(53, 120, 246)
    private val green = Color.rgb(81, 220, 163)
    private val portal = "https://info-kierowca.pl/reservation"
    private lateinit var pages: FrameLayout
    private lateinit var browserPane: LinearLayout
    private lateinit var browser: WebView
    private lateinit var navigation: LinearLayout
    private lateinit var monitor: SlotMonitor
    private var tab = "start"

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    private fun round(color: Int, r: Int = 18) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun text(s: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
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
        background = round(cardBg, 20)
        setPadding(dp(19), dp(19), dp(19), dp(19))
    }
    private fun action(title: String, bright: Boolean = true, perform: () -> Unit) =
        text(title, 15f, if (bright) Color.WHITE else Color.rgb(164, 195, 255), true).apply {
            background = round(if (bright) blue else Color.rgb(35, 53, 83), 12)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(15), dp(12), dp(15))
            setOnClickListener { perform() }
        }
    private fun info(key: String, value: String) = column().apply {
        put(this, text(key, 12f, muted), 5)
        put(this, text(value, 16f, Color.WHITE, true))
    }
    private fun outside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Nie można otworzyć tego linku", Toast.LENGTH_LONG).show()
        }
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
            "market", "tel", "mailto", "sms" -> { outside(uri); true }
            else -> {
                Toast.makeText(this, "Nieznany typ linku: " + uri.scheme, Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = column().apply { setBackgroundColor(bg) }
        val header = row().apply { setPadding(dp(22), dp(18), dp(22), dp(18)) }
        val brand = column()
        put(brand, text("ZŁAP", 12f, Color.rgb(120, 172, 255), true))
        put(brand, text("EGZAMIN", 26f, Color.WHITE, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("●  MONITOR", 12f, green, true))
        put(root, header)

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        pages = FrameLayout(this)
        body.addView(pages, FrameLayout.LayoutParams(-1, -1))

        browserPane = column()
        val toolbar = row().apply { setPadding(dp(16), dp(7), dp(16), dp(13)) }
        toolbar.addView(text("info-kierowca.pl", 15f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(text("↗  Przeglądarka", 13f, Color.rgb(170, 194, 255), true).apply {
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
            setBackgroundColor(Color.rgb(16, 24, 42))
            setPadding(dp(12), dp(9), dp(12), dp(10))
        }
        put(root, navigation)
        setContentView(root)

        monitor = SlotMonitor(this, browser) {
            if (::pages.isInitialized && ::monitor.isInitialized && tab != "portal") show(tab)
        }
        monitor.install()
        browser.loadUrl(portal)
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
        browserPane.visibility = if (which == "portal") View.VISIBLE else View.INVISIBLE
        pages.visibility = if (which == "portal") View.GONE else View.VISIBLE
        when (which) {
            "settings" -> settingsPage()
            "start" -> homePage()
        }
        navigation.removeAllViews()
        for ((id, title) in listOf("start" to "⌂  Start", "portal" to "◎  Portal",
            "settings" to "⚙  Opcje")) {
            val selected = id == which
            val label = text(title, 13f, if (selected) Color.WHITE else muted, selected).apply {
                gravity = Gravity.CENTER
                background = round(if (selected) Color.rgb(42, 66, 108) else Color.rgb(16, 24, 42), 12)
                setPadding(dp(4), dp(13), dp(4), dp(13))
                setOnClickListener { if (id != tab) show(id) }
            }
            navigation.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }
    private fun homePage() {
        val page = scroll()
        put(page, text("Monitor terminów", 24f, Color.WHITE, true), 6)
        put(page, text("MORD Kraków • Kategoria B • Praktyczny", 14f, muted), 22)

        val status = card()
        put(status, text("AUTOMATYCZNE SPRAWDZANIE", 12f, muted, true), 12)
        put(status, text(if (monitor.enabled) "●  Włączone" else "●  Wyłączone",
            25f, if (monitor.enabled) green else Color.rgb(255, 188, 105), true), 10)
        put(status, text(monitor.status(), 14f, muted), 15)
        put(status, action(if (monitor.enabled) "Wstrzymaj monitorowanie" else "Włącz monitorowanie") {
            monitor.setEnabled(!monitor.enabled)
        }, 11)
        put(status, action("Zaloguj się i wybierz egzamin  →", false) { show("portal") })
        put(page, status, 20)

        put(page, text("Odczytane terminy", 19f, Color.WHITE, true), 12)
        val results = card()
        put(results, text("NAJBLIŻSZY TERMIN", 11f, Color.rgb(143, 182, 255), true), 10)
        put(results, text(monitor.earliest.ifBlank { "Jeszcze nieodczytany" },
            23f, Color.WHITE, true), 12)
        put(results, text("Wolnych terminów w odczycie: " + monitor.slotCount, 14f, muted), 14)
        put(results, action("Zobacz terminy w portalu", false) { show("portal") })
        put(page, results, 20)

        put(page, text("Szybkie działania", 19f, Color.WHITE, true), 12)
        val tools = card()
        put(tools, action("↗  Zaloguj się przez przeglądarkę") { outside(Uri.parse(portal)) }, 11)
        put(tools, action("🔔  Test powiadomienia", false) { monitor.testNotification() })
        put(page, tools, 20)
        put(page, text("Wersja 0.3 • Monitoring co minimum 5 minut tylko podczas używania aplikacji.\n"+
            "Bez działania w tle i bez automatycznej rezerwacji.", 12f, muted))
    }
    private fun settingsPage() {
        val page = scroll()
        put(page, text("Ustawienia", 24f, Color.WHITE, true), 6)
        put(page, text("Powiadomienia i monitorowanie", 14f, muted), 22)
        val preferences = card()
        put(preferences, text("WYBRANY EGZAMIN", 12f, Color.rgb(143, 182, 255), true), 17)
        put(preferences, info("Ośrodek", "MORD Kraków (ID 21)"), 17)
        put(preferences, info("Kategoria", "B"), 17)
        put(preferences, info("Rodzaj", "Praktyczny"))
        put(page, preferences, 19)

        val debug = card()
        put(debug, text("DIAGNOSTYKA POŁĄCZENIA", 12f, Color.rgb(143, 182, 255), true), 12)
        put(debug, text(monitor.diagnostics, 14f, muted), 12)
        put(debug, text("Ostatni błąd: " + monitor.lastError.ifBlank { "brak" }, 13f, muted), 12)
        put(debug, action("Otwórz portal i ponów wybór egzaminu", false) { show("portal") })
        put(page, debug, 19)

        val alerts = card()
        put(alerts, text("POWIADOMIENIA NA TELEFONIE", 12f, Color.rgb(143, 182, 255), true), 12)
        put(alerts, text("Domyślnie pierwszy prawdziwy odczyt ustala listę znanych terminów.", 14f, muted), 14)
        put(alerts, action(if (monitor.notifyFirst) "Powiadom też przy pierwszym odczycie: TAK"
            else "Powiadom też przy pierwszym odczycie: NIE", false) {
            monitor.setNotifyFirst(!monitor.notifyFirst)
        }, 12)
        put(alerts, action("Wyślij test powiadomienia") { monitor.testNotification() })
        put(page, alerts, 19)

        val guide = card()
        put(guide, text("JAK WŁĄCZYĆ MONITOR", 12f, Color.rgb(143, 182, 255), true), 12)
        put(guide, text("1. Przejdź do Portal i zaloguj się.\n"+
            "2. Wybierz MORD Kraków, kategorię B i egzamin praktyczny.\n"+
            "3. Otwórz na portalu listę dostępnych terminów.\n"+
            "4. Możesz wrócić do Start — aplikacja sprawdza terminy co minimum 5 minut, gdy jest otwarta.\n\n"+
            "Gdy sesja wygaśnie, konieczne będzie ponowne zalogowanie. "+
            "Nie działa po zminimalizowaniu lub zamknięciu aplikacji.", 14f, muted))
        put(page, guide, 18)
        put(page, action("Otwórz Portal") { show("portal") })
    }
    override fun onResume() {
        super.onResume()
        if (::monitor.isInitialized) monitor.resume()
    }
    override fun onPause() {
        if (::monitor.isInitialized) monitor.pause()
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
