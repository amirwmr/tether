package app.tether.audio

import app.tether.helper.UplinkRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which uplink injection routes a Qualcomm phone's mixer configuration wires up (Redmi Note 8, custom Android 16). */
class UplinkRoutesTest {
    // The Redmi Note 8's mixer_paths_idp.xml: in-call music is wired, the AFE proxy playback is not.
    private val redmi = """
        <mixer>
          <path name="deep-buffer-playback afe-proxy"><ctl name="AFE_PCM_RX Audio Mixer MultiMedia1" value="1" /></path>
          <path name="incall_music_uplink"><ctl name="Incall_Music Audio Mixer MultiMedia9" value="1" /></path>
          <path name="incall_music_uplink afe-proxy"><path name="incall_music_uplink" /></path>
        </mixer>
    """.trimIndent()
    private val wired = """
        <mixer>
          <path name="afe-proxy-playback afe-proxy"><ctl name="AFE_PCM_RX Audio Mixer MultiMedia9" value="1" /></path>
          <path name="incall_music_uplink"><ctl name="Incall_Music Audio Mixer MultiMedia9" value="1" /></path>
        </mixer>
    """.trimIndent()
    private val all = listOf("intercept", "telephony_tx", "policy_mix")

    @Test fun aMixerWithoutTheAfeProxyPathLeavesTheInterceptionTrackUnwired() {
        assertTrue(UplinkRoutes.afeProxyMissing(listOf(redmi)))
        assertEquals("in-call music first", listOf("telephony_tx", "policy_mix"), UplinkRoutes.injectOrder(all, interceptDead = true))
    }

    @Test fun anyFileWithThePathOrNothingReadKeepsTheInterceptionApi() {
        assertFalse(UplinkRoutes.afeProxyMissing(listOf(wired)))
        assertFalse("one of several configurations has it", UplinkRoutes.afeProxyMissing(listOf(redmi, wired)))
        assertFalse("nothing readable: no change", UplinkRoutes.afeProxyMissing(emptyList()))
        assertTrue("a lookalike name is not the path", UplinkRoutes.afeProxyMissing(listOf("""<path name="afe-proxy-playbackx">""")))
        assertEquals(all, UplinkRoutes.injectOrder(all, interceptDead = false))
    }

    @Test fun aSavedInterceptChoiceIsIgnoredWhereItGoesNowhere() {
        // The self-test used to choose intercept here (its check reads the same proxy) and the app kept that choice.
        val live = UplinkRoutes.injectOrder(all, interceptDead = true)
        assertEquals(live, UplinkRoutes.fixedOrAuto("intercept", live))
        assertEquals(listOf("telephony_tx"), UplinkRoutes.fixedOrAuto("telephony_tx", live))
        assertEquals(live, UplinkRoutes.fixedOrAuto("auto", live))
        assertEquals(listOf("intercept"), UplinkRoutes.fixedOrAuto("intercept", all))
    }
}
