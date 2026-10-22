package pl.zlapegzamin.test

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var browser: WebView
    private val portal = "https://info-kierowca.pl/reservation"

    private fun openExternal(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Brak aplikacji do otwarcia linku", Toast.LENGTH_LONG).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(this, "Nie można otworzyć tego linku", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val url = uri.toString()
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> false // WebView handles ordinary web navigation.
            "about", "data", "blob" -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    if (!openExternal(intent) && fallback != null) {
                        val fallbackUri = Uri.parse(fallback)
                        if (fallbackUri.scheme == "https") {
                            openExternal(Intent(Intent.ACTION_VIEW, fallbackUri))
                        }
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Nieobsługiwany link logowania", Toast.LENGTH_LONG).show()
                }
                true
            }
            "mailto", "tel", "sms", "market" -> {
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                true
            }
            else -> {
                Toast.makeText(this, "Nieobsługiwany typ linku: ${uri.scheme}", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            text = "ZłapEgzamin TEST — portal egzaminacyjny. Automatyczna rezerwacja nie jest jeszcze dostępna."
            setPadding(16, 16, 16, 16)
        }
        val refresh = Button(this).apply {
            text = "Otwórz portal w aplikacji"
            setOnClickListener { browser.loadUrl(portal) }
        }
        val external = Button(this).apply {
            text = "Logowanie w przeglądarce (gdy WebView nie działa)"
            setOnClickListener { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(portal))) }
        }
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    return handleNavigation(request.url)
                }
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return url?.let { handleNavigation(Uri.parse(it)) } ?: false
                }
            }
            loadUrl(portal)
        }
        layout.addView(status)
        layout.addView(refresh)
        layout.addView(external)
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
