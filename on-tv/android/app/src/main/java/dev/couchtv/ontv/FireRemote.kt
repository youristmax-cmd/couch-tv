package dev.couchtv.ontv

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Keys and typing on a Fire TV whose own ADB refuses connections from apps on the TV (seen on Fire
 * OS 7.6.8.5: adbd accepts 127.0.0.1:5555 and hangs up before the handshake, while a computer on
 * the network connects fine).
 *
 * Fire OS has its own remote service for Amazon's phone app (com.amazon.tv.turnstile). It is
 * started over DIAL (port 8009) and then listens on HTTPS port 8080. One 4 digit PIN shown on the TV
 * gives a client token; with it the service takes D-pad, select, back, home, menu, volume and text.
 * This talks to it on 127.0.0.1 only, so nothing leaves the TV. The token stays in the app's private
 * storage. It is undocumented: if an Amazon update changes it, ADB keeps working where it did before.
 */
object FireRemote {
    private const val TAG = "couchtv.fire"
    private const val HOST = "127.0.0.1"
    private const val API_KEY = "0987654321"   // the fixed key Amazon's own phone app sends
    private const val TOKEN = "fire_remote_token"

    @Volatile var lastError: String? = null
        private set

    val available: Boolean get() = FireHome.isFireTv
    val paired: Boolean get() = !Store.str(TOKEN).isNullOrEmpty()

    fun status(): Map<String, Any?> = mapOf("fire_tv" to available, "paired" to paired, "error" to lastError)

    /** D-pad, select and the rest, by Android key code. Media keys go through Android itself (Tv.mediaKey). */
    private val ACTIONS = mapOf(
        "KEYCODE_DPAD_UP" to "dpad_up", "KEYCODE_DPAD_DOWN" to "dpad_down",
        "KEYCODE_DPAD_LEFT" to "dpad_left", "KEYCODE_DPAD_RIGHT" to "dpad_right",
        "KEYCODE_DPAD_CENTER" to "select", "KEYCODE_ENTER" to "select",
        "KEYCODE_BACK" to "back", "KEYCODE_ESCAPE" to "back", "KEYCODE_HOME" to "home", "KEYCODE_MENU" to "menu",
        "KEYCODE_VOLUME_UP" to "volume_up", "KEYCODE_VOLUME_DOWN" to "volume_down", "KEYCODE_VOLUME_MUTE" to "mute",
        "KEYCODE_SLEEP" to "sleep", "KEYCODE_POWER" to "sleep",
    )

    fun handles(code: String): Boolean = code in ACTIONS

    // ---------- pairing ----------

    /** Shows a 4 digit PIN on the TV. */
    fun showPin() {
        if (!available) throw ApiError(409, "this is not a Fire TV")
        wake()
        val (st, body) = request("POST", "/v1/FireTV/pin/display", JSONObject().put("friendlyName", "Couch TV").toString(), auth = false)
        if (st != 200) throw ApiError(502, "the Fire TV did not show a PIN (${describe(st, body)})")
    }

    fun pair(pin: String) {
        val (st, body) = request("POST", "/v1/FireTV/pin/verify", JSONObject().put("pin", pin).toString(), auth = false)
        val tok = runCatching { JSONObject(body).optString("description") }.getOrNull().orEmpty()
        // A good answer is a short token; a bad PIN comes back as a sentence in the same field.
        if (st != 200 || tok.isEmpty() || tok.contains(' ')) {
            throw ApiError(400, "the TV did not accept that PIN. Show a new one and type it within a minute.")
        }
        Store.putStr(TOKEN, tok)
        lastError = null
        Log.i(TAG, "paired with the Fire TV remote service")
    }

    fun forget() { Store.putStr(TOKEN, null) }

    // ---------- commands ----------

    fun key(code: String) {
        val a = ACTIONS[code] ?: throw ApiError(400, "the Fire TV remote has no $code")
        command("/v1/FireTV?action=$a", null)
    }

    /**
     * Types into the open on-screen keyboard, adding to what is in the box (like `input text`).
     * The service takes ONE character per call ("lu" is "Bad arguments", "l" then "u" works), and
     * only while a keyboard is up, so check first for a clear message, then send each character.
     * Keyboard states: "hidden", "visible" (empty box), "text" (box has text).
     */
    fun text(s: String) {
        if (!paired) throw ApiError(503, "pair the Fire TV remote on the setup page first")
        var (st, kb) = request("GET", "/v1/FireTV/keyboard", null)
        if (st == -1) { wake(); val r = request("GET", "/v1/FireTV/keyboard", null); st = r.first; kb = r.second }
        val state = runCatching { JSONObject(kb).optString("state") }.getOrNull()
        if (st == 200 && state == "hidden") {
            throw ApiError(409, "no keyboard is open on the TV. Select a search box first, then type.")
        }
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            command("/v1/FireTV/text", JSONObject().put("text", s.substring(i, i + n)).toString())
            i += n
        }
    }

    private fun command(path: String, body: String?) {
        if (!paired) throw ApiError(503, "pair the Fire TV remote on the setup page first")
        var (st, out) = request("POST", path, body)
        if (st == -1) {           // not listening or stuck: start it again and try once more
            wake()
            val r = request("POST", path, body); st = r.first; out = r.second
        }
        when (st) {
            200 -> { lastError = null; return }
            401, 403 -> { forget(); fail(409, "the Fire TV forgot this app. Pair the Fire TV remote again on the setup page.") }
            400 -> fail(400, "the Fire TV did not take that (${describe(st, out)})")
            else -> fail(502, "the Fire TV remote service is not answering (${describe(st, out)}). Restarting the TV fixes it.")
        }
    }

    private fun fail(status: Int, msg: String): Nothing { lastError = msg; throw ApiError(status, msg) }

    private fun describe(st: Int, body: String) =
        if (st == -1) "no answer" else "HTTP $st" + (runCatching { JSONObject(body).optString("description") }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.contains(' ') }?.let { ": $it" } ?: "")

    // ---------- transport ----------

    /** DIAL start of Amazon's remote service: POST http://127.0.0.1:8009/apps/FireTVRemote. Plain socket, no cleartext config needed. */
    private fun wake() {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, 8009), 2000)
                s.soTimeout = 3000
                s.getOutputStream().write("POST /apps/FireTVRemote HTTP/1.1\r\nHost: $HOST:8009\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                s.getInputStream().read(ByteArray(256))
            }
        } catch (e: Exception) { Log.w(TAG, "wake: ${e.message}") }
        // The service takes a moment to open 8080.
        for (i in 0 until 10) {
            try { Socket().use { it.connect(InetSocketAddress(HOST, 8080), 500) }; return } catch (e: Exception) { Thread.sleep(300) }
        }
    }

    /** One HTTPS call to the service. Returns (-1, "") when it does not connect or answer in time. */
    private fun request(method: String, path: String, body: String?, auth: Boolean = true, timeoutMs: Int = 5000): Pair<Int, String> {
        return try {
            val c = URL("https://$HOST:8080$path").openConnection() as HttpsURLConnection
            c.sslSocketFactory = localTls
            c.setHostnameVerifier { h, _ -> h == HOST }
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("x-api-key", API_KEY)
            if (auth) Store.str(TOKEN)?.let { c.setRequestProperty("x-client-token", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val st = c.responseCode
            val stream = if (st < 400) c.inputStream else c.errorStream
            val out = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") }.orEmpty()
            c.disconnect()
            st to out
        } catch (e: Exception) {
            Log.w(TAG, "$method $path: ${e.javaClass.simpleName} ${e.message}")
            -1 to ""
        }
    }

    /**
     * The service uses a certificate from Amazon's own device CA, which Android does not trust. It
     * is only ever reached on 127.0.0.1 (checked by the hostname verifier above), so the certificate
     * is not checked: nothing leaves the TV.
     */
    private val localTls: SSLSocketFactory by lazy {
        val trustLocal = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustLocal), SecureRandom()) }.socketFactory
    }
}
