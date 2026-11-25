package app.tether

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.tether.data.CallLogRepo
import app.tether.data.ContactsRepo
import app.tether.data.SimRepo
import app.tether.data.SmsRepo
import app.tether.link.Hub
import app.tether.link.Keys
import app.tether.telecom.CallRegistry

class TetherApp : Application() {
    lateinit var keys: Keys; private set
    lateinit var sms: SmsRepo; private set
    lateinit var contacts: ContactsRepo; private set
    lateinit var sims: SimRepo; private set
    lateinit var callLog: CallLogRepo; private set
    lateinit var lists: app.tether.data.Lists; private set
    lateinit var hub: Hub; private set
    val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        keys = Keys(this)
        sms = SmsRepo(this)
        contacts = ContactsRepo(this)
        sims = SimRepo(this)
        callLog = CallLogRepo(this, sims)
        lists = app.tether.data.Lists(this)
        CallRegistry.app = this
        hub = Hub(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LINK, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW))
        // The incoming call's pop-up, silent: Telecom plays the ringtone (IN_CALL_SERVICE_RINGING false), and the
        // channel's default notification sound played just before it. A channel's sound cannot change once it exists,
        // so this is a new channel and the old one ("calls") goes.
        nm.deleteNotificationChannel("calls")
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        // The same call notification once Tether's call screen is up: in the notification bar only, no pop-up over the
        // call screen (which kept the screen awake). Telecom plays the ringtone itself (IN_CALL_SERVICE_RINGING false).
        nm.createNotificationChannel(NotificationChannel(CH_CALLS_QUIET, getString(R.string.channel_calls_quiet), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SETUP, getString(R.string.channel_setup), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FIND, getString(R.string.channel_find), NotificationManager.IMPORTANCE_HIGH))
        ensureLinkService()
    }

    /**
     * Whatever started this process (a call, a notification action, a debug hook, a late boot or update broadcast),
     * the link service should be running once the app is set up: Samsung sometimes holds back MY_PACKAGE_REPLACED
     * after an update, and until then nothing would start it, so the iPhone could not link and calls were not bridged.
     * An app exempt from battery optimisation (part of setup) may start it from the background; refusals are logged.
     */
    private fun ensureLinkService() {
        if (app.tether.link.LinkService.running != null) return
        val bt = if (android.os.Build.VERSION.SDK_INT >= 31) android.Manifest.permission.BLUETOOTH_ADVERTISE else android.Manifest.permission.BLUETOOTH
        if (checkSelfPermission(bt) != android.content.pm.PackageManager.PERMISSION_GRANTED) return   // not set up yet
        try { app.tether.link.LinkService.start(this) }
        catch (t: Throwable) { android.util.Log.w("Tether", "link service not started at process start: ${t.javaClass.simpleName}") }
    }

    /** Same path as the notification's RemoteInput: pair with adbd, grant ourselves what we need, start the helper. */
    fun adbPairAndBootstrap(code: String): String {
        app.tether.adb.Adb.get(this).pairWithCode(code)
        prefs.edit().putBoolean("adb_paired", true).apply()
        val grants = app.tether.adb.Adb.bootstrap(this)
        val helper = hub.helper.ensureRunning() ?: "helper running"
        hub.refresh()
        return "paired; grants: ${grants.trim().ifEmpty { "ok" }}; $helper"
    }

    fun adbShell(cmd: String): String = app.tether.adb.Adb.get(this).shell(cmd)

    companion object {
        const val CH_LINK = "link"
        const val CH_CALLS = "calls_silent"
        const val CH_CALLS_QUIET = "calls_quiet"
        const val CH_SETUP = "setup"
        const val CH_FIND = "find"
    }
}
