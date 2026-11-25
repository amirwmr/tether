package app.tether.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.R
import app.tether.TetherApp
import app.tether.telecom.CallRegistry

/**
 * The call screen on this phone (Tether is its phone app; the call screen of the phone's maker never shows while
 * Tether has that role). Ringing: Decline and Answer. In a call: Mute, the audio route (earpiece, speaker, Bluetooth,
 * headset), Keypad, Hold and End. While the call's audio is on a client (the phone in your hand), "Take the call here"
 * keeps it on this phone's own earpiece and microphone instead; the client then shows it "on <this phone>" with Move
 * here to take it back.
 */
class CallActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var number: TextView
    private lateinit var state: TextView
    private lateinit var note: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var actions: LinearLayout
    private var showKeys = false
    private var lookedUp: String? = null
    private var lookedUpName: String? = null
    /** What the buttons were built for: they are rebuilt only when it changes (a ripple is not cut twice a second). */
    private var built = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest's showWhenLocked / turnScreenOn exist from Android 8.1; on 8.0 the window flags do the same.
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 27) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(getColor(R.color.background))
            setPadding(dp(24), dp(56), dp(24), dp(32))
        }
        title = TextView(this).apply { textSize = 30f; setTextColor(getColor(R.color.text)); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        number = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.muted)); gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        state = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.muted)); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)) }
        note = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(title); root.addView(number); root.addView(state); root.addView(note)
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(keys); root.addView(actions)
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val cid = i.getStringExtra("cid")
        when (i.getStringExtra("action")) {
            "answer" -> cid?.let(CallRegistry::answer)
            "decline" -> cid?.let(CallRegistry::reject)
            "end" -> cid?.let(CallRegistry::end)
        }
    }

    override fun onResume() { super.onResume(); built = ""; refresh() }
    override fun onPause() { main.removeCallbacksAndMessages(null); super.onPause() }

    private val hub get() = (application as TetherApp).hub

    private fun refresh() {
        main.removeCallbacksAndMessages(null)
        val call = CallRegistry.active().firstOrNull()
        if (call == null) { finish(); return }
        val info = CallRegistry.info(call)
        if (info.name == null && info.num.isNotEmpty() && lookedUp != info.num) {
            lookedUp = info.num
            Thread { val n = (application as TetherApp).contacts.nameFor(info.num); main.post { lookedUpName = n } }.start()
        }
        val name = info.name ?: lookedUpName
        title.text = name ?: info.num.ifEmpty { getString(R.string.unknown_caller) }.let { if (info.num.isEmpty()) it else displayNumber(info.num) }
        number.text = if (name != null && info.num.isNotEmpty()) displayNumber(info.num) else ""
        number.visibility = if (number.text.isEmpty()) View.GONE else View.VISIBLE
        val since = call.details?.connectTimeMillis?.takeIf { it > 0 && info.state == "active" }
        state.text = since?.let { val sec = ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0); "%d:%02d".format(sec / 60, sec % 60) }
            ?: getString(when (info.state) {
                "ringing" -> R.string.call_incoming
                "dialing", "connecting" -> R.string.call_outgoing
                "holding" -> R.string.call_holding
                "ended" -> R.string.call_ended
                else -> R.string.call_active
            })

        // The call's audio is on a client (the phone in your hand) while a client owns it; else on this phone.
        val owner = hub.audio.owner?.takeIf { info.state != "ringing" && info.state != "ended" }
        val audio = CallRegistry.audioState()
        val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val muted = audio?.isMuted == true
        val canHold = call.details?.can(Call.Details.CAPABILITY_HOLD) == true || info.state == "holding"
        val key = listOf(info.cid, info.state, owner?.name, route, audio?.supportedRouteMask, muted, canHold, showKeys).joinToString("|")
        if (key != built) { built = key; build(info.cid, info.state, owner?.name, route, audio?.supportedRouteMask, muted, canHold) }
        main.postDelayed(::refresh, 500)
    }

    private fun build(cid: String, st: String, ownerName: String?, route: Int, routes: Int?, muted: Boolean, canHold: Boolean) {
        note.removeAllViews(); keys.removeAllViews(); actions.removeAllViews()
        if (st == "ringing") {
            actions.addView(actionRow(
                roundAction(R.drawable.ic_call_end, getString(R.string.decline), R.color.bad, null, 72) { CallRegistry.reject(cid); later() },
                roundAction(R.drawable.ic_call, getString(R.string.answer), R.color.ok, null, 72) { CallRegistry.answer(cid); later() },
            ))
            return
        }
        if (ownerName != null) {
            // On the client: this phone's own mic and earpiece are out of the call until it is taken here.
            note.addView(TextView(this).apply {
                text = getString(R.string.call_on_client, ownerName); textSize = 15f; setTextColor(getColor(R.color.accent)); gravity = Gravity.CENTER
            })
            note.addView(pill(getString(R.string.take_here), R.drawable.ic_call) { hub.audio.takeHere(); later() })
        }
        if (showKeys) keys.addView(keypad(cid))
        val local = ownerName == null
        val (routeName, routeIcon) = when (route) {
            CallAudioState.ROUTE_SPEAKER -> R.string.speaker to R.drawable.ic_speaker
            CallAudioState.ROUTE_BLUETOOTH -> R.string.route_bluetooth to R.drawable.ic_bluetooth
            CallAudioState.ROUTE_WIRED_HEADSET -> R.string.route_headset to R.drawable.ic_headset
            else -> R.string.speaker to R.drawable.ic_speaker
        }
        actions.addView(actionRow(*listOfNotNull(
            if (local) roundAction(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, getString(R.string.mute), R.color.accent, muted) {
                CallRegistry.mute(!muted); later()
            } else null,
            if (local) roundAction(routeIcon, getString(routeName), R.color.accent, route != CallAudioState.ROUTE_EARPIECE) {
                nextRoute(routes, route)?.let(CallRegistry::setRoute); later()
            } else null,
            roundAction(R.drawable.ic_dialpad, getString(R.string.keypad), R.color.accent, showKeys) { showKeys = !showKeys; built = ""; refresh() },
            if (canHold) roundAction(R.drawable.ic_pause, getString(R.string.hold), R.color.accent, st == "holding") {
                CallRegistry.hold(cid, st != "holding"); later()
            } else null,
        ).toTypedArray()))
        actions.addView(actionRow(roundAction(R.drawable.ic_call_end, getString(R.string.end_call), R.color.bad, null, 72) { CallRegistry.end(cid); later() }))
    }

    /** Earpiece → speaker → Bluetooth / wired (whatever there is) → earpiece. */
    private fun nextRoute(supported: Int?, current: Int): Int? {
        val mask = supported ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)
        val order = listOf(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_BLUETOOTH,
            CallAudioState.ROUTE_WIRED_HEADSET).filter { mask and it != 0 }
        if (order.isEmpty()) return null
        return order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
    }

    /** Looks again shortly after a button (Telecom applies it asynchronously). */
    private fun later() { main.postDelayed({ built = ""; refresh() }, 250) }

    private fun keypad(cid: String) = GridLayout(this).apply {
        columnCount = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR
        for (k in "123456789*0#") addView(FrameLayout(context).apply {
            background = pressable(getColor(R.color.surface), R.color.text)
            addView(TextView(context).apply { text = k.toString(); textSize = 26f; setTextColor(getColor(R.color.text)); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            contentDescription = k.toString()
            isClickable = true; isFocusable = true
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); CallRegistry.dtmf(cid, k) }
        }, GridLayout.LayoutParams().apply { width = dp(68); height = dp(68); setMargins(dp(12), dp(6), dp(12), dp(6)) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** An oval with a ripple confined to it. */
    private fun pressable(fill: Int, rippleId: Int): RippleDrawable {
        val body = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.text)) }
        return RippleDrawable(ColorStateList.valueOf(getColor(rippleId)).withAlpha(0x33), body, mask)
    }

    /**
     * A round action with its label underneath. [checked] null: a plain action, filled with [colorId]; true / false:
     * a switch, on filled with the accent, off the dark grey surface.
     */
    private fun roundAction(iconId: Int, text: String, colorId: Int, checked: Boolean?, sizeDp: Int = 64, onClick: () -> Unit): LinearLayout {
        val fill = when (checked) { null -> colorId; true -> R.color.accent; false -> R.color.surface }
        val fg = if (checked == false) R.color.text else R.color.background
        val circle = FrameLayout(this).apply {
            background = pressable(getColor(fill), fg)
            addView(ImageView(context).apply { setImageDrawable(getDrawable(iconId)!!.mutate().apply { setTint(getColor(fg)) }) },
                FrameLayout.LayoutParams(dp(sizeDp * 2 / 5), dp(sizeDp * 2 / 5), Gravity.CENTER))
            contentDescription = text
            isClickable = true; isFocusable = true
            if (checked != null) accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.isCheckable = true; info.isChecked = checked
                }
            }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(circle, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
            addView(TextView(context).apply {
                this.text = text; textSize = 13f; setTextColor(getColor(R.color.muted)); gravity = Gravity.CENTER; maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
    }

    /** Round actions sharing the width, centred, with even margins (a centred row cuts off a child's uneven margin). */
    private fun actionRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) }) }
    }

    /** A filled accent button with an icon ("Take the call here"). */
    private fun pill(text: String, iconId: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(getColor(R.color.background))
        val icon = getDrawable(iconId)!!.mutate().apply { setTint(getColor(R.color.background)); setBounds(0, 0, dp(20), dp(20)) }
        setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = dp(10)
        val shape = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(getColor(R.color.accent)) }
        val mask = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(getColor(R.color.text)) }
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.background)).withAlpha(0x33), shape, mask)
        minHeight = dp(52); setPadding(dp(24), dp(12), dp(24), dp(12))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) }
        isClickable = true; isFocusable = true
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }
}

/** The number grouped for display by the SIM / network country ("+98 912 345 6789"); unchanged when Android cannot. */
private fun Activity.displayNumber(n: String): String {
    val tm = getSystemService(android.telephony.TelephonyManager::class.java)
    val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        ?: java.util.Locale.getDefault().country).uppercase()
    return android.telephony.PhoneNumberUtils.formatNumber(n, iso) ?: n
}

/** ACTION_DIAL handler (required for the phone-app role): a number field and a Call button. */
class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TetherApp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); setBackgroundColor(getColor(R.color.background))
        }
        val number = EditText(this).apply {
            hint = getString(R.string.dialer_hint); textSize = 28f; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(intent?.data?.schemeSpecificPart ?: "")
        }
        val call = Button(this).apply {
            text = getString(R.string.dial); isAllCaps = false; setTextColor(getColor(R.color.text))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(getColor(R.color.ok)) }
            setOnClickListener { CallRegistry.dial(this@DialerActivity, number.text.toString(), null); finish() }
        }
        root.addView(number); root.addView(call)
        setContentView(root)
        app.hashCode()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
