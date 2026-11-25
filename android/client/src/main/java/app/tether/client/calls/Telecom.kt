package app.tether.client.calls

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import app.tether.client.ClientApp
import app.tether.client.R
import app.tether.client.model.Call
import app.tether.client.model.Problem
import java.util.UUID

/**
 * The calls of the Android phone as self-managed Telecom calls on this phone: Telecom routes their audio (earpiece,
 * speaker, wired and Bluetooth headsets, the car) and shows them to Bluetooth devices; the app shows its own call
 * screen and rings itself. [CallTracker] decides; this applies its decisions to Telecom connections.
 */
class TelecomBridge(private val app: ClientApp) : CallSink {
    private val tm = app.getSystemService(TelecomManager::class.java)
    val account = PhoneAccountHandle(ComponentName(app, TetherConnectionService::class.java), "tether")
    private val conns = HashMap<String, TetherConnection>()
    /** State for calls whose Telecom connection does not exist yet (Telecom creates it asynchronously). */
    private val pending = HashMap<String, Call>()
    private val pendingActive = HashSet<String>()
    private val endedEarly = HashMap<String, EndReason>()
    private val main = Handler(Looper.getMainLooper())
    var audioState: CallAudioState? = null; private set
    private val prefs = app.getSharedPreferences("calls", Context.MODE_PRIVATE)
    /** Numbers of calls that rang in the Phone app and were not answered, with when: the Phone app shows them as
     *  missed calls itself, so this app does not notify them a second time. */
    private val missedInPhoneApp = HashMap<String, Long>()

    fun register() {
        runCatching {
            tm.registerPhoneAccount(PhoneAccount.builder(account, app.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build())
        }.onFailure { app.journal.add("phone account not registered: ${it.message}") }
        syncPhoneAccounts()
    }

    // ------------------------------------------------------------------ the Phone app (managed calling accounts)

    /**
     * "Use the Phone app for calls" (Link tab), on by default. When on, calls ring and run in this phone's own Phone
     * app through a calling account per SIM of the Android phone ("Tether · Work"): its call screen, ringtone, keypad,
     * contacts and call history, as for a SIM of its own. The protocol does not change: only how the calls are shown
     * here. A call still uses this app's own call screen while no such account is turned on in the Phone app's calling
     * accounts (Android asks the user to do that once), so calls never fail because of the setting.
     */
    var usePhoneApp: Boolean
        get() = prefs.getBoolean(PREF_PHONE_APP, true)
        set(v) { prefs.edit().putBoolean(PREF_PHONE_APP, v).apply(); syncPhoneAccounts(); app.changed() }

    /** One account per SIM of a dual-SIM Android phone, so the Phone app asks which SIM; one ("Tether") otherwise. */
    private fun wantedPhoneAccounts(): List<Pair<Int?, String>> {
        if (!usePhoneApp) return emptyList()
        val sims = app.state.sims
        val name = app.getString(R.string.app_name)
        return if (sims.size < 2) listOf(null to name)
            else sims.map { it.sub to "$name · " + it.name.ifEmpty { app.getString(R.string.sim_n, it.slot + 1) } }
    }

    fun phoneHandle(sub: Int?) = PhoneAccountHandle(ComponentName(app, TetherPhoneConnectionService::class.java),
        if (sub == null) PHONE_ID else "$PHONE_ID-$sub")

    /** The SIM a Phone-app account calls with (null: the plain account, the Android phone's choice). */
    fun subOf(h: PhoneAccountHandle?): Int? = h?.id?.removePrefix("$PHONE_ID-")?.takeIf { it != h.id }?.toIntOrNull()

    private var syncedKey: String? = null

    /**
     * Registers the Phone-app accounts the setting and the SIMs call for and removes the others. Registering an
     * account again keeps whether the user turned it on; only one that is no longer wanted is removed. Cheap when
     * nothing changed (called on every state change).
     */
    fun syncPhoneAccounts() {
        val wanted = wantedPhoneAccounts()
        val key = wanted.joinToString("|") { "${it.first}:${it.second}" }
        if (key == syncedKey) return
        syncedKey = key
        val ids = wanted.map { phoneHandle(it.first).id }.toSet()
        for (old in prefs.getStringSet(PREF_PHONE_IDS, emptySet()).orEmpty() - ids)
            runCatching { tm.unregisterPhoneAccount(PhoneAccountHandle(ComponentName(app, TetherPhoneConnectionService::class.java), old)) }
        for ((sub, label) in wanted) runCatching {
            tm.registerPhoneAccount(PhoneAccount.builder(phoneHandle(sub), label)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setShortDescription(app.getString(R.string.phone_account_description))
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .setIcon(Icon.createWithResource(app, R.drawable.ic_tether).setTint(app.getColor(R.color.accent)))
                .setHighlightColor(app.getColor(R.color.accent))
                .build())
        }.onFailure { app.journal.add("Phone app account not registered: ${it.message}") }
        prefs.edit().putStringSet(PREF_PHONE_IDS, ids).apply()
        if (wanted.isNotEmpty()) app.journal.add("Phone app accounts: ${wanted.size}")
    }

    /** Whether the user turned this account on in the Phone app's calling accounts (our own accounts need no permission). */
    private fun isOn(h: PhoneAccountHandle) = runCatching { tm.getPhoneAccount(h)?.isEnabled == true }.getOrDefault(false)

    /** The Phone-app accounts and whether each is on, for the Link tab (empty when the setting is off). */
    fun phoneAccounts(): List<Pair<String, Boolean>> = wantedPhoneAccounts().map { (sub, label) -> label to isOn(phoneHandle(sub)) }

    /** The account a call on [sub] goes to: that SIM's Phone-app account, any Phone-app account that is on, or ours. */
    private fun accountFor(sub: Int?): PhoneAccountHandle {
        val on = wantedPhoneAccounts().map { phoneHandle(it.first) }.filter(::isOn)
        return on.firstOrNull { subOf(it) == sub } ?: on.firstOrNull() ?: account
    }

    /** Opens the Phone app's calling accounts (where the user turns the accounts on); the call settings if not there. */
    fun openCallingAccounts(from: android.app.Activity) {
        runCatching { from.startActivity(android.content.Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
            .recoverCatching { from.startActivity(android.content.Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }
            .onFailure { app.journal.add("calling accounts screen not found: ${it.message}") }
    }

    /** A missed call that already rang in the Phone app (it notifies missed calls itself), within 10 minutes. */
    fun missedInPhoneApp(number: String): Boolean {
        val now = System.currentTimeMillis()
        missedInPhoneApp.values.removeAll { now - it > 600_000 }
        return missedInPhoneApp.remove(numberKey(number)) != null
    }

    private fun numberKey(n: String) = n.filter(Char::isDigit).takeLast(9)

    // ------------------------------------------------------------------ calls

    fun connection(local: String) = conns[local]

    /**
     * The keypad / a tel: link: Telecom creates the outgoing connection (onCreateOutgoingConnection). On a Phone-app
     * account that needs the Phone permission; without it the call goes out on this app's own account.
     */
    @SuppressLint("MissingPermission")   // MANAGE_OWN_CALLS (install time) is what a self-managed account needs
    fun placeCall(number: String, sub: Int?): Boolean {
        val target = accountFor(sub).takeIf { it == account || hasCallPermission() } ?: account
        return try {
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, target)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { sub?.let { putInt(EXTRA_SUB, it) } })
            })
            true
        } catch (t: Throwable) { app.journal.add("placeCall refused: ${t.message}"); false }
    }

    fun hasCallPermission() = app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun reportIncoming(local: String, c: Call) {
        pending[local] = c
        app.service(callMode = true)
        val target = accountFor(c.sub)
        try {
            tm.addNewIncomingCall(target, Bundle().apply {
                // A Phone-app account shows the number before the connection exists (the ringing screen, call waiting).
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_LOCAL, local) })
                putString(EXTRA_LOCAL, local)
            })
        } catch (t: Throwable) {
            app.journal.add("incoming call not reported: ${t.message}")
            pending.remove(local); app.calls.localFailed(local)
        }
    }

    @SuppressLint("MissingPermission")
    override fun reportStarted(local: String, c: Call) {
        pending[local] = c
        if (c.state == "active") pendingActive += local
        app.service(callMode = true)
        val target = accountFor(c.sub).let { if (!c.isIncoming && it != account && !hasCallPermission()) account else it }
        try {
            if (c.isIncoming) tm.addNewIncomingCall(target, Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_LOCAL, local); putBoolean(EXTRA_STARTED, true) })
                putString(EXTRA_LOCAL, local); putBoolean(EXTRA_STARTED, true)
            })
            else tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, target)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_LOCAL, local) })
            })
        } catch (t: Throwable) {
            app.journal.add("started call not reported: ${t.message}")
            pending.remove(local); app.calls.localFailed(local)
        }
    }

    /** Telecom made the connection for [local]: apply what happened meanwhile. */
    fun attach(local: String, conn: TetherConnection): Connection {
        endedEarly.remove(local)?.let { return Connection.createFailedConnection(cause(it)) }
        conns[local] = conn
        pending.remove(local)?.let { conn.show(it) }
        if (pendingActive.remove(local)) conn.setActive()
        refreshAudio()
        return conn
    }

    fun detach(local: String) { conns.remove(local); refreshAudio() }

    override fun update(local: String, c: Call) { conns[local]?.show(c) ?: run { pending[local] = c } }

    override fun setActive(local: String) {
        val c = conns[local] ?: run { pendingActive += local; return }
        if (c.state != Connection.STATE_ACTIVE) c.setActive()
        app.notifier.cancelIncoming(local)
        refreshAudio()
    }

    override fun setHeld(local: String, on: Boolean) {
        val c = conns[local] ?: return
        if (on && c.state != Connection.STATE_HOLDING) c.setOnHold() else if (!on && c.state != Connection.STATE_ACTIVE) c.setActive()
    }

    override fun ended(local: String, reason: EndReason) {
        app.notifier.cancelIncoming(local)
        pending.remove(local); pendingActive.remove(local)
        val c = conns.remove(local)
        if (c == null) { endedEarly[local] = reason; main.postDelayed({ endedEarly.remove(local) }, 10_000); return }
        // Rang in the Phone app and not answered: it logs and notifies the missed call itself.
        if (c.inPhoneApp && c.state == Connection.STATE_RINGING) c.address?.schemeSpecificPart?.let { missedInPhoneApp[numberKey(it)] = System.currentTimeMillis() }
        c.setDisconnected(cause(reason))
        c.destroy()
        refreshAudio()
    }

    private fun cause(r: EndReason) = DisconnectCause(when (r) {
        EndReason.REMOTE -> DisconnectCause.REMOTE
        EndReason.UNANSWERED -> DisconnectCause.MISSED
        EndReason.FAILED -> DisconnectCause.ERROR
        EndReason.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
        EndReason.LOCAL -> DisconnectCause.LOCAL
        EndReason.BUSY -> DisconnectCause.BUSY
    })

    override fun ringback(on: Boolean) { app.audio.ringback = on }
    override fun uplink(open: Boolean) = app.audio.setUplink(open)

    override fun idle() {
        app.audio.stop()
        app.link.setHighPriority(false)
        app.service(callMode = false)
    }

    override fun changed() = app.changed()
    override fun log(line: String) = app.journal.add(line)

    /** Runs the call audio engine while any connection is dialing, active or held. */
    fun refreshAudio() {
        val live = conns.values.any { it.state == Connection.STATE_DIALING || it.state == Connection.STATE_ACTIVE || it.state == Connection.STATE_HOLDING }
        app.audio.engine(live)
        if (live) app.link.setHighPriority(true)
        app.changed()
    }

    fun audioStateChanged(s: CallAudioState) {
        val wasMuted = audioState?.isMuted
        audioState = s
        // Mute from a headset or the car: the uplink goes silent here and the Android phone is told.
        if (wasMuted != null && wasMuted != s.isMuted) setMuted(s.isMuted)
        app.changed()
    }

    fun setMuted(on: Boolean) {
        if (app.audio.muted == on) return
        app.audio.muted = on
        app.calls.performMute(on)
        app.changed()
    }

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) { conns.values.firstOrNull { it.state == Connection.STATE_ACTIVE || it.state == Connection.STATE_DIALING }?.setAudioRoute(route) }

    companion object {
        const val EXTRA_LOCAL = "app.tether.client.LOCAL"
        const val EXTRA_STARTED = "app.tether.client.STARTED"
        const val EXTRA_SUB = "app.tether.client.SUB"
        private const val PHONE_ID = "tether-phone"
        private const val PREF_PHONE_APP = "phone_app"
        private const val PREF_PHONE_IDS = "phone_app_accounts"
    }
}

/**
 * One call of the Android phone as Telecom sees it. Telecom calls these on the main thread. [inPhoneApp]: on a
 * Phone-app account (the Phone app rings and shows the call); else self-managed (this app rings and shows it).
 */
class TetherConnection(private val app: ClientApp, val local: String, val inPhoneApp: Boolean = false) : Connection() {
    init {
        if (!inPhoneApp) connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE
    }

    fun show(c: Call) {
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null), TelecomManager.PRESENTATION_ALLOWED)
        val name = app.state.name(c.num) ?: c.name
        val sim = app.state.simName(c.sub)
        setCallerDisplayName(listOfNotNull(name ?: app.format(c.num), sim).joinToString(" · "), TelecomManager.PRESENTATION_ALLOWED)
        connectionCapabilities = CAPABILITY_MUTE or if (c.hold) CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD else 0
    }

    override fun onShowIncomingCallUi() { app.notifier.showIncomingCall(local) }
    override fun onAnswer() { answer() }
    override fun onAnswer(videoState: Int) { answer() }

    private fun answer() {
        app.calls.performAnswer(local)
        setActive()
        app.notifier.cancelIncoming(local)
        app.telecom.refreshAudio()
        if (!inPhoneApp) app.openCallScreen()   // the Phone app shows its own call screen
    }

    override fun onReject() = end(DisconnectCause.REJECTED)
    override fun onDisconnect() = end(DisconnectCause.LOCAL)
    override fun onAbort() = end(DisconnectCause.LOCAL)

    private fun end(code: Int) {
        app.notifier.cancelIncoming(local)
        app.calls.performEnd(local)
        setDisconnected(DisconnectCause(code))
        destroy()
        app.telecom.detach(local)
    }

    override fun onHold() { if (app.calls.performHold(local, true)) setOnHold() }
    override fun onUnhold() { if (app.calls.performHold(local, false)) { setActive(); app.telecom.refreshAudio() } }
    override fun onPlayDtmfTone(c: Char) = app.calls.performDtmf(local, c.toString())
    override fun onSilence() { if (!inPhoneApp) app.notifier.silenceIncoming(local) }
    override fun onStateChanged(state: Int) { app.telecom.refreshAudio() }
    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState) { app.telecom.audioStateChanged(state) }
}

/** The calls on this app's own (self-managed) account. [TetherPhoneConnectionService] serves the Phone-app accounts. */
open class TetherConnectionService : ConnectionService() {
    private val app get() = application as ClientApp
    /** Whether this service's calls show in the Phone app. */
    protected open val inPhoneApp = false

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val local = request.extras?.getString(TelecomBridge.EXTRA_LOCAL)
            ?: request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(TelecomBridge.EXTRA_LOCAL)
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val conn = TetherConnection(app, local, inPhoneApp)
        // A started call (answered on the Android phone, moved here) is live already: no ring.
        val started = request.extras?.getBoolean(TelecomBridge.EXTRA_STARTED) == true ||
            request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getBoolean(TelecomBridge.EXTRA_STARTED) == true
        if (!started) conn.setRinging() else conn.setActive()
        conn.setInitialized()
        return app.telecom.attach(local, conn)
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        // Telecom refused the call (too many calls, Do Not Disturb policy…): forget the binding, so that if it is
        // answered on the Android phone it shows up here as a started call with audio instead of a half-bound one.
        request.extras?.getString(TelecomBridge.EXTRA_LOCAL)?.let { app.journal.add("Telecom refused an incoming call"); app.calls.localFailed(it) }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        val mirrored = extras?.getString(TelecomBridge.EXTRA_LOCAL)
        val local = mirrored ?: UUID.randomUUID().toString()
        // Dialed in the Phone app with this app's account but nothing to call through: say why there.
        if (mirrored == null && !app.link.isConnected) {
            app.problem(Problem.NOT_CONNECTED)
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, app.getString(R.string.err_not_connected),
                app.getString(R.string.err_not_connected), "link down"))
        }
        val conn = TetherConnection(app, local, inPhoneApp)
        conn.setDialing()
        conn.setInitialized()
        val number = request.address?.schemeSpecificPart.orEmpty()
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        val attached = app.telecom.attach(local, conn)
        // A call this phone dials (keypad, a tel: link, Recents): the Android phone is asked to dial it.
        if (mirrored == null) {
            // The SIM: this app's keypad says which; a call from the Phone app's keypad, its contacts or call history
            // comes with the SIM's account (dual SIM), else the SIM last used with that number.
            val sub = extras?.takeIf { it.containsKey(TelecomBridge.EXTRA_SUB) }?.getInt(TelecomBridge.EXTRA_SUB)
                ?: app.telecom.subOf(account) ?: if (inPhoneApp) app.state.preferredSim(number) else null
            app.service(callMode = true)
            app.calls.performStart(local, number, sub)
            if (!inPhoneApp) app.openCallScreen()
        }
        return attached
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        val local = request.extras?.getString(TelecomBridge.EXTRA_LOCAL)
        app.journal.add("Telecom refused an outgoing call")
        if (local != null) app.calls.localFailed(local) else app.problem(Problem.CALL_FAILED)
    }
}

/** The calls on the Phone-app accounts ("Use the Phone app for calls"): the Phone app rings and shows them. */
class TetherPhoneConnectionService : TetherConnectionService() {
    override val inPhoneApp = true
}

/** The route after [current]: earpiece → speaker → Bluetooth / wired (whatever [supported] has) → earpiece; null: none. */
fun nextAudioRoute(supported: Int?, current: Int?): Int? {
    val mask = supported ?: (android.telecom.CallAudioState.ROUTE_EARPIECE or android.telecom.CallAudioState.ROUTE_SPEAKER)
    val order = listOf(android.telecom.CallAudioState.ROUTE_EARPIECE, android.telecom.CallAudioState.ROUTE_SPEAKER,
        android.telecom.CallAudioState.ROUTE_BLUETOOTH, android.telecom.CallAudioState.ROUTE_WIRED_HEADSET).filter { mask and it != 0 }
    if (order.isEmpty()) return null
    val cur = order.indexOf(current ?: android.telecom.CallAudioState.ROUTE_EARPIECE)
    return order[(cur + 1) % order.size]
}
