package app.tether.link

/**
 * Where a call's audio goes (protocol §5.1), as pure rules the hub applies.
 *
 * A call's audio goes to a client only when that client answered it, dialed it or moved it to itself (`call.take`)
 * while the current calls were going on. A call answered or dialed on the Android phone itself (its screen, a
 * headset or a car connected to it) stays on the Android phone: nothing is bridged and nothing on the phone is
 * muted. Before 1.2.2 such a call went to the client used last, else the iPhone, and the phone's own earpiece and
 * microphone were silenced: with the iPhone linked, a call taken on the phone had no audio there (issue #13).
 *
 * A phone whose software gives Tether no call audio (the downlink capture reads only zeros in a real call) is
 * remembered by its build fingerprint and Tether's version: its calls are not bridged until the firmware or Tether
 * changes, or a self-test finds call audio again. Clients are told `helper.audio` "blocked".
 */
object AudioOwner {
    /** The owner when nobody holds the audio now: the live client whose key claimed during the current calls. */
    fun <C> pick(live: List<C>, key: (C) -> String, claimedKey: String?): C? =
        claimedKey?.let { k -> live.firstOrNull { key(it) == k } }

    /** The claim is forgotten once no call is left (ended calls do not count). */
    fun keepClaim(callStates: List<String>): Boolean = callStates.any { it != "ended" }

    /**
     * Whether [client] sees a call in [state] as being elsewhere: answered, dialing or held, with its audio on another
     * client, on the Android phone itself (no owner), or kept on the phone because it gives no call audio.
     */
    fun <C> elsewhere(state: String, owner: C?, client: C, blocked: Boolean): Boolean =
        state != "ringing" && state != "ended" && (blocked || owner == null || owner !== client)

    /** The name a client shows for "on …": the owning client's, else the Android phone's own. */
    fun where(ownerName: String?, phoneName: String): String = ownerName ?: phoneName

    /** Calls in a row whose downlink read only zeros before the phone is remembered as giving no call audio. */
    const val ZERO_CALLS = 2

    /**
     * After a bridged call whose downlink read only zeros: the new count of such calls in a row, and whether the phone
     * is now remembered as giving no call audio. One call is not enough: a far end silent for the first seconds of a
     * call reads as zeros too, and remembering it took call audio away from a phone that gives it (calls then ended on
     * the client that had answered them). A bridged call that heard audio sets the count back to 0.
     */
    fun zeroCall(inARow: Int): Pair<Int, Boolean> = (inARow + 1).let { it to (it >= ZERO_CALLS) }

    /** Whether the remembered "no call audio" still holds ([remembered] and [current]: "fingerprint|versionCode"). */
    fun blocked(remembered: String?, fingerprint: String): Boolean = remembered != null && remembered == fingerprint
}
