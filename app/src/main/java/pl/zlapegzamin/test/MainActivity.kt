package pl.zlapegzamin.test

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val bg = Color.rgb(10, 16, 30)
    private val panel = Color.rgb(21, 31, 51)
    private val muted = Color.rgb(158, 174, 199)
    private val accent = Color.rgb(53, 120, 246)
    private val portal = "https://info-kierowca.pl/reservation"
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private var web: WebView? = null
    private var current = "start"

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun background(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun put(parent: LinearLayout, child: View, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) })
    }
    private fun card() = col().apply {
        background = background(panel, 20)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
    private fun action(title: String, primary: Boolean = true, click: () -> Unit) =
        label(title, 15f, if (primary) Color.WHITE else Color.rgb(167, 198, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = background(if (primary) accent else Color.rgb(35, 52, 80), 12)
            setOnClickListener { click() }
        }
    private fun info(key: String, value: String) = col().apply {
        put(this, label(key, 12f, muted), 5)
        put(this, label(value, 16f, Color.WHITE, true))
    }
    private fun external(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: Exception) {
        Toast.makeText(this, "Nie można otworzyć linku", Toast.LENGTH_LONG).show()
        false
    }
    private fun navigate(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https", "about", "data", "blob" -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        if (fallback != null && Uri.parse(fallback).scheme == "https") external(Uri.parse(fallback))
                        else Toast.makeText(this, "Brak aplikacji do logowania", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Nieobsługiwane przekierowanie", Toast.LENGTH_LONG).show()
                }
                true
            }
            "mailto", "tel", "sms", "market" -> { external(uri); true }
            else -> { Toast.makeText(this, "Nieobsługiwany link", Toast.LENGTH_LONG).show(); true }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        root = col().apply { setBackgroundColor(bg) }
        val header = row().apply { setPadding(dp(22), dp(19), dp(22), dp(18)) }
        val brand = col()
        put(brand, label("ZŁAP", 12f, Color.rgb(119, 175, 255), true))
        put(brand, label("EGZAMIN", 26f, Color.WHITE, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("●  TEST", 12f, Color.rgb(81, 220, 163), true))
        put(root, header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        tabs = row().apply {
            setBackgroundColor(Color.rgb(16, 24, 42))
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        put(root, tabs)
        setContentView(root)
        show("start")
    }
    private fun scroll(): LinearLayout {
        val wrapper = ScrollView(this)
        val inside = col().apply { setPadding(dp(20), dp(10), dp(20), dp(30)) }
        wrapper.addView(inside)
        content.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        return inside
    }
    private fun show(tab: String) {
        current = tab
        content.removeAllViews()
        when (tab) {
            "portal" -> portalView()
            "settings" -> settingsView()
            else -> homeView()
        }
        tabs.removeAllViews()
        for ((id, name) in listOf("start" to "⌂  Start", "portal" to "◎  Portal", "settings" to "⚙  Opcje")) {
            val item = label(name, 13f, if (tab == id) Color.WHITE else muted, tab == id).apply {
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(14), dp(3), dp(14))
                background = background(if (tab == id) Color.rgb(42, 66, 108) else Color.rgb(16, 24, 42), 12)
                setOnClickListener { if (current != id) show(id) }
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }
    private fun homeView() {
        val c = scroll()
        put(c, label("Panel egzaminu", 25f, Color.WHITE, true), 5)
        put(c, label("Twój egzamin na prawo jazdy", 14f, muted), 22)
        val status = card()
        put(status, label("STATUS MONITOROWANIA", 12f, muted, true), 14)
        put(status, label("●  Nieaktywne", 25f, Color.rgb(255, 188, 105), true), 10)
        put(status, label("Automatyczne sprawdzanie terminów nie jest jeszcze dostępne.", 14f, muted), 18)
        put(status, action("Otwórz portal egzaminacyjny  →") { show("portal") })
        put(c, status, 22)
        put(c, label("Twój egzamin", 19f, Color.WHITE, true), 12)
        val exam = card()
        put(exam, label("EGZAMIN PAŃSTWOWY", 11f, Color.rgb(136, 182, 255), true), 12)
        put(exam, label("MORD Kraków", 23f, Color.WHITE, true), 18)
        val fields = row()
        fields.addView(info("Kategoria", "B"), LinearLayout.LayoutParams(0, -2, 1f))
        fields.addView(info("Rodzaj", "Praktyczny"), LinearLayout.LayoutParams(0, -2, 1f))
        put(exam, fields, 18)
        put(exam, label("Najbliższy termin: nieodczytany", 14f, muted), 15)
        put(exam, action("Sprawdź terminy w portalu", false) { show("portal") })
        put(c, exam, 22)
        put(c, label("Szybkie działania", 19f, Color.WHITE, true), 12)
        val quick = card()
        put(quick, action("↗  Logowanie w przeglądarce") { external(Uri.parse(portal)) }, 12)
        put(quick, action("⚙  Ustawienia", false) { show("settings") })
        put(c, quick, 22)
        put(c, label("ZłapEgzamin 0.2 • wersja testowa\nAutorezerwacja nie jest jeszcze dostępna.", 12f, muted))
    }
    private fun settingsView() {
        val c = scroll()
        put(c, label("Ustawienia", 25f, Color.WHITE, true), 6)
        put(c, label("Konfiguracja egzaminu", 14f, muted), 22)
        val exam = card()
        put(exam, label("WYBRANY EGZAMIN", 12f, Color.rgb(136, 182, 255), true), 20)
        put(exam, info("Ośrodek", "MORD Kraków"), 20)
        put(exam, info("Kategoria", "B"), 20)
        put(exam, info("Typ egzaminu", "Praktyczny"), 18)
        put(exam, label("Ustawienia informacyjne. Wybór terminów odbywa się w portalu.", 13f, muted))
        put(c, exam, 18)
        val notifications = card()
        put(notifications, label("POWIADOMIENIA", 12f, Color.rgb(136, 182, 255), true), 12)
        put(notifications, label("Telegram i alerty", 18f, Color.WHITE, true), 10)
        put(notifications, label("Moduł automatycznego monitorowania i powiadomień nie został jeszcze uruchomiony.", 14f, muted))
        put(c, notifications, 18)
        put(c, action("Przejdź do portalu") { show("portal") })
    }
    private fun portalView() {
        val container = col()
        val toolbar = row().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        toolbar.addView(label("Portal info-kierowca.pl", 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(label("↗  Przeglądarka", 12f, Color.rgb(150, 190, 255), true).apply {
            setOnClickListener { external(Uri.parse(portal)) }
        })
        put(container, toolbar)
        val browser = web ?: WebView(this).also { web = it }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                    navigate(request.url)
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.let { navigate(Uri.parse(it)) } ?: false
            }
            loadUrl(portal)
        }
        (browser.parent as? android.view.ViewGroup)?.removeView(browser)
        container.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(container, FrameLayout.LayoutParams(-1, -1))
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == "portal" && web?.canGoBack() == true) web?.goBack()
        else if (current != "start") show("start")
        else super.onBackPressed()
    }
    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
