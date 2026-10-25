package pl.zlapegzamin.test

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only repeats a captured exam-list query. Never sends a reservation request.
 * Authenticated request data stays in process memory, never in preferences/logs.
 */
internal object NativeExamReader {
    data class Request(val url: String, val body: String, val headers: Map<String, String>)
    data class Response(val status: Int, val body: String, val retryAfter: String, val cookies: List<String> = emptyList())
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "exam-read-deadline").apply { isDaemon = true }
    }

    /** The portal uses nested/plural OneCenterExams routes as well as the
     * singular route. Keep the API/operation allowlist, not one invented URL. */
    internal fun isExamListPath(path: String): Boolean {
        if (!Regex("^/bknd/exam/api/v[0-9]+/", RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
        val segments = path.trimEnd('/').split('/')
        if (segments.any { it == "." || it == ".." || it.contains('%') }) return false
        if (segments.any { Regex("reservation|create|confirm|cancel|delete|payment|update|book", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return false
        return Regex("^(?:get[-_]?)?onecenterexams?$", RegexOption.IGNORE_CASE)
            .matches(segments.last())
    }

    fun capture(message: JSONObject): Request? = try {
        val url = URL(message.getString("url"))
        val validHost = url.host == "info-kierowca.pl" || url.host.endsWith(".info-kierowca.pl")
        if (url.protocol != "https" || !validHost || url.userInfo != null ||
            (url.port != -1 && url.port != 443) ||
            !isExamListPath(url.path)) null
        else {
            val body = message.getString("body")
            val data = JSONObject(body)
            val org = data.optJSONArray("organizationId") ?: JSONArray().put(data.opt("organizationId"))
            val target = (0 until org.length()).any { org.optInt(it) == 21 } &&
                data.optString("category").uppercase() in setOf("B", "5")
            if (!target || body.length > 60000) null else {
                val headers = message.optJSONObject("headers") ?: JSONObject()
                val allowed = setOf("accept", "content-type", "authorization", "x-xsrf-token", "x-csrf-token", "x-requested-with")
                val values = mutableMapOf<String, String>()
                for (key in headers.keys()) {
                    val value = headers.optString(key)
                    if (key.lowercase() in allowed && !value.contains('\r') && !value.contains('\n')) values[key] = value
                }
                Request(url.toString(), body, values)
            }
        }
    } catch (_: Exception) { null }

    /** Called on a worker. Both socket timeouts and the deadline are native. */
    fun read(request: Request, cookies: String, userAgent: String,
             deadlineMs: Long = 45000L,
             open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): Response {
        val connection = open(URL(request.url))
        val deadline = deadlines.schedule({ connection.disconnect() }, deadlineMs, TimeUnit.MILLISECONDS)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.doOutput = true
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Origin", "https://info-kierowca.pl")
            connection.setRequestProperty("Referer", "https://info-kierowca.pl/reservation")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = request.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= 2_000_000) { "Exam response too large" }
                    out.append(buffer, 0, count)
                }
                out.toString()
            }.orEmpty()
            val setCookies = connection.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
            return Response(status, raw, connection.getHeaderField("Retry-After").orEmpty(), setCookies)
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    fun message(response: Response): JSONObject {
        val status = response.status
        if (status == 429) return JSONObject().put("kind", "rate")
            .put("retryAfter", response.retryAfter.ifBlank { response.body.take(200) })
        if (status == 401 || status == 403 || status in 300..399 ||
            (status == 200 && response.body.trimStart().startsWith("<")))
            return JSONObject().put("kind", "auth").put("status", status)
        if (status != 200) return JSONObject().put("kind", "http").put("status", status)
        return try {
            val days = JSONObject(response.body).getJSONArray("examCollectionForDay")
            val terms = JSONArray()
            for (i in 0 until days.length()) {
                val exams = days.optJSONObject(i)?.optJSONArray("examCollections") ?: continue
                for (j in 0 until exams.length()) {
                    val exam = exams.optJSONObject(j) ?: continue
                    if (exam.optString("examType") != "Practice" || exam.optString("category") != "B" ||
                        exam.optInt("organizationId") != 21 || exam.optInt("placePracticeAmount") <= 0 ||
                        exam.optString("practiceId").isBlank() || exam.optString("practiceDateTime").isBlank()) continue
                    if (terms.length() < 300) terms.put(JSONObject()
                        .put("id", exam.getString("practiceId").take(90))
                        .put("date", exam.getString("practiceDateTime").take(32))
                        .put("places", exam.optInt("placePracticeAmount")))
                }
            }
            JSONObject().put("kind", "terms").put("terms", terms)
        } catch (_: Exception) { JSONObject().put("kind", "format") }
    }
}
