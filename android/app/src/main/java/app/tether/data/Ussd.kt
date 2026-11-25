package app.tether.data

import android.content.Context
import app.tether.TetherApp
import app.tether.adb.Adb
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Carrier (USSD) codes such as *123#: validation shared by the message handler and the debug hook. */
object UssdCode {
    private val re = Regex("^[*#][0-9*#]{1,30}#$")
    fun valid(code: String) = re.matches(code)
}

/** Sends one USSD request on a chosen SIM and waits (at most 30 s) for the carrier's answer. Blocks: call off the hub thread. */
class Ussd(private val ctx: Context) {
    companion object { const val TIMEOUT_S = 30L }

    /** `{ok, text?, err?}`. Never throws. */
    fun send(code: String, sub: Int?): JSONObject {
        if (!UssdCode.valid(code)) return JSONObject().put("ok", false).put("err", "Not a carrier code (like *123#)")
        return try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val t = if (sub != null && sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(sub) else tm
            val done = CountDownLatch(1)
            val out = AtomicReference<JSONObject>()
            t.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                    out.set(JSONObject().put("ok", true).put("text", response.toString())); done.countDown()
                }
                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                    val why = if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) "The network has no service for this code" else "The carrier refused the code"
                    out.set(JSONObject().put("ok", false).put("err", why)); done.countDown()
                }
            }, Handler(Looper.getMainLooper()))
            if (done.await(TIMEOUT_S, TimeUnit.SECONDS)) out.get() else JSONObject().put("ok", false).put("err", "The carrier did not answer in 30 seconds")
        } catch (e: SecurityException) {
            JSONObject().put("ok", false).put("err", "Android denied the request (phone permission)")
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Reads the phone's own USSD dialog from a `uiautomator dump` (the phone service draws it: com.android.phone, or a
 * maker's telephony UI). Pure, so the parsing is tested without a phone.
 */
object UssdScreen {
    /** The packages that draw USSD dialogs: AOSP's phone service, Samsung's telephony UI, some makers' MMS app. */
    private val phonePkg = Regex("package=\"(com\\.android\\.phone|com\\.samsung[^\"]*tele[^\"]*|com\\.android\\.mms[^\"]*)\"")
    private val node = Regex("<node [^>]*>")

    /** The dialog's nodes (of the phone's packages only). */
    fun nodes(xml: String): List<String> = node.findAll(xml).map { it.value }.filter { phonePkg.containsMatchIn(it) }.toList()

    fun attr(n: String, k: String): String = Regex("$k=\"([^\"]*)\"").find(n)?.groupValues?.get(1).orEmpty()

    /** The dialog's message (the carrier's answer, or "USSD code running…"), if one shows. */
    fun text(xml: String): String? {
        val ns = nodes(xml)
        val msg = ns.firstOrNull { attr(it, "resource-id").endsWith("id/message") }?.let { attr(it, "text") }
            ?: ns.filter { attr(it, "class") != "android.widget.EditText" }.map { attr(it, "text") }.filter { it.length > 12 }.maxByOrNull { it.length }
        return msg?.takeIf { it.isNotBlank() }?.let(::unescape)
    }

    /** Whether the dialog waits for a reply (a menu: it has a text field). */
    fun waitsForReply(xml: String): Boolean = nodes(xml).any { attr(it, "class") == "android.widget.EditText" }

    /** The centre of the dialog's button [id] ("button1": Send / OK, "button2": Cancel), or of its text field (null id). */
    fun centre(xml: String, id: String?): Pair<Int, Int>? {
        val n = nodes(xml).firstOrNull { if (id == null) attr(it, "class") == "android.widget.EditText" else attr(it, "resource-id").endsWith("id/$id") }
            ?: return null
        val g = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(attr(n, "bounds"))?.groupValues ?: return null
        return (g[1].toInt() + g[3].toInt()) / 2 to (g[2].toInt() + g[4].toInt()) / 2
    }

    private fun unescape(s: String) = s.replace("&#10;", "\n").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** A reply typed into a menu: digits, * # + only (it goes through `input text`). */
    fun validReply(t: String) = Regex("^[0-9*#+]{1,40}$").matches(t)
}

/**
 * USSD the way the phone's own dialer runs it, for carriers that refuse Android's background USSD API (the user's
 * carrier answered "refused" there but answers a dialed code) and for menus: the code is dialed on the SIM's calling
 * account, the phone service's USSD dialog is read through Tether's adb (uiautomator), a final answer is closed (OK),
 * a menu stays open for [reply] / [cancel]. Needs Tether's adb (Wireless debugging paired). Blocks: call off the hub
 * thread, one at a time.
 */
class UssdDialer(private val app: TetherApp) {
    private val tmp = "/data/local/tmp/tether_ussd.xml"

    /** Whether Tether's adb can drive the phone's USSD dialog now. */
    fun available(): Boolean = runCatching { Adb.get(app).ensureConnected() }.getOrDefault(false)

    private fun sh(cmd: String): String = app.adbShell(cmd)
    /**
     * The screen as uiautomator sees it, woken first (same shell command, no extra round trip): a locked phone's screen
     * goes off while the carrier's menu waits for the client's reply, and an off screen shows no dialog to read.
     */
    private fun dump(): String =
        runCatching { sh("input keyevent KEYCODE_WAKEUP; uiautomator dump $tmp >/dev/null 2>&1; cat $tmp; rm -f $tmp") }.getOrDefault("")

    /** [dump] until [ok] holds (the screen may still be waking and drawing the dialog again): at most [tries] reads. */
    private fun dumpUntil(tries: Int = 8, ok: (String) -> Boolean): String {
        var xml = ""
        repeat(tries) { i ->
            xml = dump()
            if (ok(xml)) return xml
            if (i < tries - 1) Thread.sleep(400)
        }
        return xml
    }
    private fun tap(p: Pair<Int, Int>) { sh("input tap ${p.first} ${p.second}") }

    /** `{ok, text?, err?, session?}` like [Ussd.send]; `session` true: a menu waits for [reply]. */
    @android.annotation.SuppressLint("MissingPermission")
    fun dial(code: String, sub: Int?): JSONObject {
        if (!UssdCode.valid(code)) return JSONObject().put("ok", false).put("err", "Not a carrier code (like *123#)")
        return try {
            closeOpenDialog()   // a dialog left from before (an answer nobody closed) would be read as this answer
            val extras = android.os.Bundle()
            app.sims.accountFor(sub)?.let { extras.putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            app.getSystemService(android.telecom.TelecomManager::class.java)
                .placeCall(android.net.Uri.fromParts("tel", code, null), extras)
            await(null)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
    }

    /** Types [text] into the open menu and presses Send; the next answer as from [dial]. */
    fun reply(text: String): JSONObject {
        if (!UssdScreen.validReply(text)) return JSONObject().put("ok", false).put("err", "A reply is digits, * or #")
        return try {
            // The screen went off since the menu came (a locked phone): woken, the menu shows again in a moment.
            val xml = dumpUntil { UssdScreen.centre(it, null) != null }
            val field = UssdScreen.centre(xml, null) ?: return JSONObject().put("ok", false).put("err", "No carrier menu is open (it may have timed out)")
            val before = UssdScreen.text(xml)
            tap(field)
            sh("input text '$text'")
            UssdScreen.centre(dump(), "button1")?.let(::tap) ?: sh("input keyevent KEYCODE_ENTER")
            await(before)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
    }

    /** Closes an open menu (Cancel, else Back). */
    fun cancel() { runCatching { UssdScreen.centre(dumpUntil(4) { UssdScreen.centre(it, "button2") != null }, "button2")?.let(::tap) ?: sh("input keyevent KEYCODE_BACK") } }

    /** A USSD dialog still on the screen (a final answer, an error): closed with OK, else Back. */
    private fun closeOpenDialog() {
        val xml = dump()
        if (UssdScreen.text(xml) == null) return
        UssdScreen.centre(xml, if (UssdScreen.waitsForReply(xml)) "button2" else "button1")?.let(::tap) ?: sh("input keyevent KEYCODE_BACK")
    }

    /**
     * Waits (at most 25 s) for a new dialog text that reads the same twice in a row (not the "running…" message);
     * closes final answers. Reads every 0.3 s (a read itself takes about a second), so the answer reaches the client
     * soon after the Android phone shows it.
     */
    private fun await(previous: String?): JSONObject {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
        var last = ""
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(300)
            val xml = dump()
            val text = UssdScreen.text(xml)?.takeIf { it != previous } ?: continue
            if (text == last) {
                if (UssdScreen.waitsForReply(xml)) return JSONObject().put("ok", true).put("text", text).put("session", true)
                UssdScreen.centre(xml, "button1")?.let(::tap) ?: sh("input keyevent KEYCODE_BACK")
                return JSONObject().put("ok", true).put("text", text)
            }
            last = text
        }
        return if (last.isNotEmpty()) JSONObject().put("ok", true).put("text", last)
        else JSONObject().put("ok", false).put("err", "The carrier did not answer in 25 seconds")
    }
}
