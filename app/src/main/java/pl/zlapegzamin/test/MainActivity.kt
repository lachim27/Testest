package pl.zlapegzamin.test

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var browser: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            text = "ZłapEgzamin TEST — przeglądarka portalu. Automatyczne monitorowanie i rezerwacja nie są jeszcze dostępne."
            setPadding(16, 16, 16, 16)
        }
        val refresh = Button(this).apply {
            text = "Otwórz portal"
            setOnClickListener { browser.loadUrl("https://info-kierowca.pl/reservation") }
        }
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("https://info-kierowca.pl/reservation")
        }
        layout.addView(status)
        layout.addView(refresh)
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }
}
