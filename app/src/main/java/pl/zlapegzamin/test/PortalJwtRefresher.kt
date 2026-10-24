package pl.zlapegzamin.test

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * Refreshes the existing info-kierowca JWT using the same authenticated
 * cookie jar that WebView already owns. Cookies and PKK never enter prefs,
 * logs, notifications or any third-party service.
 *
 * Call only from a background thread (NOT the UI thread).
 */
internal object PortalJwtRefresher {
    private const val origin = "https://info-kierowca.pl"
    private const val refreshUrl = "$origin/bknd/auth/api/v1/jwt/refresh"

    sealed class Result {
        object Refreshed : Result()
        object NoAuthenticatedCookie : Result()
        data class Rejected(val status: Int) : Result()
        data class TemporaryFailure(val status: Int?) : Result()
        object AcceptedWithoutCookieUpdate : Result()
    }

    fun refresh(): Result {
        var conn: HttpURLConnection? = null
        try {
            val cookieManager = CookieManager.getInstance()
            val initialCookies = cookieManager.getCookie(origin + "/").orEmpty()
            if (!initialCookies.split(';').any {
                    it.trim().startsWith("__Secure-PUDOJT=", ignoreCase = false)
                }) return Result.NoAuthenticatedCookie

            conn = (URL(refreshUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 12000
                readTimeout = 12000
                useCaches = false
                setRequestProperty("Cookie", initialCookies)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Origin", origin)
                setRequestProperty("Referer", "$origin/reservation")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val code = conn.responseCode
            if (code == 401 || code == 403) return Result.Rejected(code)
            if (code != 200 && code != 204) return Result.TemporaryFailure(code)

            // The gateway rotates __Secure-PUDOJT. Preserve Secure, HttpOnly,
            // SameSite, Max-Age and other Set-Cookie attributes in WebView.
            val setCookies = conn.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
                .filter { it.isNotBlank() }
            val authCookieUpdated = setCookies.any {
                it.trimStart().startsWith("__Secure-PUDOJT=", ignoreCase = false)
            }
            for (cookie in setCookies) cookieManager.setCookie(origin + "/", cookie)
            cookieManager.flush()

            return if (authCookieUpdated) Result.Refreshed
                else Result.AcceptedWithoutCookieUpdate
        } catch (_: Exception) {
            return Result.TemporaryFailure(null)
        } finally {
            conn?.disconnect()
        }
    }
}
