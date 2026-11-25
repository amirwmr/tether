package app.tether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the phone's own USSD dialog from a uiautomator dump (the dialer-style USSD for carriers that refuse the API). */
class UssdScreenTest {
    private fun n(cls: String, text: String = "", id: String = "", bounds: String = "[0,0][10,10]", pkg: String = "com.android.phone") =
        """<node index="0" text="$text" resource-id="$id" class="$cls" package="$pkg" bounds="$bounds" />"""

    private val launcher = n("android.widget.TextView", "Some long text on the home screen", pkg = "com.sec.android.app.launcher")

    @Test fun aFinalAnswerIsReadAndClosedWithOk() {
        val xml = "<hierarchy>$launcher" + n("android.widget.TextView", "Your balance: 12,000&#10;Valid until 1405/01/01", "android:id/message") +
            n("android.widget.Button", "OK", "android:id/button1", "[400,1500][700,1600]") + "</hierarchy>"
        assertEquals("Your balance: 12,000\nValid until 1405/01/01", UssdScreen.text(xml))
        assertFalse(UssdScreen.waitsForReply(xml))
        assertEquals(550 to 1550, UssdScreen.centre(xml, "button1"))
    }

    @Test fun aMenuWaitsForAReplyInItsField() {
        val pkg = "com.samsung.android.app.telephonyui"
        val xml = "<hierarchy>" + n("android.widget.TextView", "1. Balance&#10;2. Packages", "android:id/message", pkg = pkg) +
            n("android.widget.EditText", "", "com.samsung.android.app.telephonyui:id/input_field", "[100,900][900,1000]", pkg) +
            n("android.widget.Button", "Send", "android:id/button1", pkg = pkg) + n("android.widget.Button", "Cancel", "android:id/button2", pkg = pkg) + "</hierarchy>"
        assertEquals("1. Balance\n2. Packages", UssdScreen.text(xml))
        assertTrue(UssdScreen.waitsForReply(xml))
        assertEquals("the field", 500 to 950, UssdScreen.centre(xml, null))
    }

    @Test fun nothingOfOtherAppsIsTakenForAnAnswer() {
        assertNull(UssdScreen.text("<hierarchy>$launcher</hierarchy>"))
        assertNull(UssdScreen.centre("<hierarchy>$launcher</hierarchy>", "button1"))
    }

    @Test fun aReplyIsOnlyWhatInputTextCanTypeSafely() {
        assertTrue(UssdScreen.validReply("2")); assertTrue(UssdScreen.validReply("*1#"))
        assertFalse("no shell quoting games", UssdScreen.validReply("1'; reboot")); assertFalse(UssdScreen.validReply(""))
    }
}
