package pl.zlapegzamin.test

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeExamReaderTest {
    private val url = "https://info-kierowca.pl/bknd/exam/api/v1/OneCenterExam"
    private fun query(address: String = url) = JSONObject().put("url", address)
        .put("body", """{"organizationId":21,"category":"B","profileNumber":"test"}""")
        .put("headers", JSONObject().put("Content-Type", "application/json").put("Host", "evil.example"))

    @Test fun acceptsOnlyExamQueryToOfficialHttpsEndpoint() {
        assertNotNull(NativeExamReader.capture(query()))
        assertFalse(NativeExamReader.capture(query())!!.headers.containsKey("Host"))
        for (address in listOf(url.replace("https:", "http:"),
                url.replace("info-kierowca.pl", "info-kierowca.pl.evil.example"),
                url.replace("OneCenterExam", "Reservations/create"),
                url.replace("info-kierowca.pl", "user@info-kierowca.pl"))) {
            assertNull(NativeExamReader.capture(query(address)))
        }
        assertNull(NativeExamReader.capture(query().put("body", """{"organizationId":22,"category":"A"}""")))
    }

    @Test fun parsesTermsWithoutAnyWebViewCallback() {
        val body = """{"examCollectionForDay":[{"examCollections":[
          {"examType":"Practice","category":"B","organizationId":21,"practiceId":"valid","practiceDateTime":"2026-11-02T11:00:00","placePracticeAmount":2},
          {"examType":"Practice","category":"A","organizationId":21,"practiceId":"wrong","practiceDateTime":"2026-11-02T11:00:00","placePracticeAmount":2},
          {"examType":"Practice","category":"B","organizationId":21,"practiceId":"full","practiceDateTime":"2026-11-02T11:00:00","placePracticeAmount":0}
        ]}]}"""
        val message = NativeExamReader.message(NativeExamReader.Response(200, body, ""))
        assertEquals("terms", message.getString("kind"))
        assertEquals(1, message.getJSONArray("terms").length())
        assertEquals("valid", message.getJSONArray("terms").getJSONObject(0).getString("id"))
    }

    @Test fun rejectsInvalidResultsAndPreservesRateLimit() {
        fun result(status: Int, body: String = "") = NativeExamReader.message(NativeExamReader.Response(status, body, "120"))
        assertEquals("rate", result(429).getString("kind"))
        assertEquals("120", result(429).getString("retryAfter"))
        assertEquals("auth", result(401).getString("kind"))
        assertEquals("auth", result(302).getString("kind"))
        assertEquals("format", result(200, "{}").getString("kind"))
        assertEquals("terms", result(200, """{"examCollectionForDay":[]}""").getString("kind"))
    }

    @Test(timeout = 3000) fun nativeDeadlineDisconnectsAStalledReadWithoutJavascriptTimers() {
        val disconnected = CountDownLatch(1)
        val fake = object : HttpURLConnection(URL(url)) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown() }
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                assertTrue(disconnected.await(2, TimeUnit.SECONDS))
                throw IOException("Read cancelled by native deadline")
            }
        }
        try {
            NativeExamReader.read(NativeExamReader.capture(query())!!, "session=test", "test", 50L) { fake }
            fail("Expected native timeout")
        } catch (_: IOException) { }
        assertEquals(0L, disconnected.count)
        assertFalse(fake.instanceFollowRedirects)
        assertEquals(15000, fake.connectTimeout)
        assertEquals(15000, fake.readTimeout)
    }
}
