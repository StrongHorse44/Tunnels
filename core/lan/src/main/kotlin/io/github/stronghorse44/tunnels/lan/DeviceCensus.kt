package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import io.github.stronghorse44.tunnels.model.Observation

/**
 * The device census: the list of devices the user accepted on one confirmed network, as identity tokens
 * ([DeviceIdentity]). Nothing here reads a store; the Android adapter feeds it what it read.
 *
 * Where the list lives, with no new table:
 *  - every confirmed scan carries it as `census:known` and carries it forward from the newest snapshot of that network
 *    that holds a set-up list (the baseline);
 *  - acknowledgements ("Mine", "These are all mine") are events in the stream [STREAM], which expire after 30 days
 *    like every event; the next scan folds them into `census:known`, so after one scan they live in the snapshot;
 *  - [CensusPins] keeps exactly one Home-network-only snapshot of each network pinned, so the list outlives the
 *    snapshot retention without keeping another tunnel's data past it.
 *
 * A reset ("Start the list again", or Forget on the network) is also an event: whatever was acknowledged or carried
 * before it is ignored. Fail closed throughout: a list that cannot be read is "unavailable" and nothing is judged,
 * never "all known".
 */
object DeviceCensus {
    /** The events stream of acknowledgements and resets; the event subject is the network tag. */
    const val STREAM = "homenet.census"
    const val KIND_ACK = "ack"
    const val KIND_RESET = "reset"
    const val RESET_SUMMARY = "reset"
    private const val ACK_PREFIX = "ids="

    const val STATE_SET = "set"
    const val STATE_UNSET = "unset"
    const val STATE_UNAVAILABLE = "unavailable"

    /** The most tokens a list holds. Past it further acknowledgements are left out and the state says the list is full. */
    const val MAX_KNOWN = 512

    private const val SEPARATOR = " · "
    private const val SETUP_TITLE = "Device census"
    private val tagPattern = Regex("[0-9a-f]{${NetworkFingerprint.PREFIX_LENGTH}}")

    /** The newest snapshot of one network that carries a set-up or empty list: when it was taken, its state, its tokens. */
    data class Baseline(val takenAt: Long, val state: String, val known: Set<String>, val pinned: Boolean = false)

    /** One acknowledgement: when, and the tokens of the device (primary first). */
    data class Ack(val at: Long, val ids: List<String>)

    data class Census(val state: String, val known: Set<String>, val full: Boolean)

    /** A row of the [STREAM] events, as the adapter read it. */
    data class CensusEvent(val at: Long, val kind: String, val subject: String, val summary: String)

    /** The events of one network, folded: when the list was last reset and the acknowledgements that parsed. */
    data class Folded(val lastReset: Long?, val acks: List<Ack>)

    /**
     * True when a snapshot may still stand for the list: it is a pinned Home-network-only snapshot (the only kind the census
     * pins and unpins, so a reset reaches it; a mixed snapshot someone pinned is not exempt), or it was taken within the 30
     * days an event lives.
     * A reset is only an event and expires; an old unpinned list that survived it (snapshots are never deleted by age)
     * must not come back to life once the event is gone. A reset always postdates the snapshots it ends, so one older than
     * the event's life was either before a reset that has expired or simply too old to trust.
     */
    fun isCurrent(takenAt: Long, pinned: Boolean, now: Long): Boolean =
        pinned || now - takenAt < RetentionPolicy.EVENT_TTL.toMillis()

    /** A stored Home network snapshot offered as a baseline; [homenetOnly] only needs to be right when [pinned] is true. */
    class Candidate(val takenAt: Long, val pinned: Boolean, val homenetOnly: Boolean, val observations: List<Observation>)

    /**
     * The baseline for the network [tag] out of [candidates], newest first. Walks past snapshots of other networks, ones
     * whose census was `unavailable` and `set` lists that are no longer current ([isCurrent]): a stale unpinned list in
     * front must not hide a current pinned one behind it. It stops at the first `unset` snapshot (what a reset produces)
     * and at the first current `set` one.
     */
    fun baselineFrom(tag: String, candidates: List<Candidate>, now: Long): Baseline? {
        for (c in candidates) {
            val b = baselineOf(tag, c.takenAt, c.observations, pinned = c.pinned && c.homenetOnly) ?: continue
            if (b.state == STATE_SET && !isCurrent(b.takenAt, b.pinned, now)) continue
            return b
        }
        return null
    }

    /** True when all of a device's tokens that are not yet listed still fit under [MAX_KNOWN]. */
    fun fits(known: Set<String>, ids: List<String>): Boolean =
        known.size + ids.take(DeviceIdentity.MAX_TOKENS).filter { DeviceIdentity.isToken(it) && it !in known }.distinct().size <= MAX_KNOWN

    /**
     * The list for this scan at time [now]. The base is the [baseline]'s tokens when it was a set-up list, was taken after
     * the last reset and is current ([isCurrent]); then come the acknowledgements after the last reset, oldest first, until [MAX_KNOWN] tokens (what does not
     * fit sets `full`). `set` when anything is on the list, else `unset`.
     */
    fun compute(baseline: Baseline?, lastReset: Long?, acks: List<Ack>, now: Long): Census {
        val known = LinkedHashSet<String>()
        var full = false
        if (baseline != null && baseline.state == STATE_SET && (lastReset == null || baseline.takenAt > lastReset) &&
            isCurrent(baseline.takenAt, baseline.pinned, now)
        ) {
            baseline.known.filter(DeviceIdentity::isToken).sorted().take(MAX_KNOWN).forEach { known += it }
        }
        for (ack in acks.filter { lastReset == null || it.at > lastReset }.sortedBy { it.at }) {
            for (id in ack.ids.take(DeviceIdentity.MAX_TOKENS)) {
                if (!DeviceIdentity.isToken(id) || id in known) continue
                if (known.size >= MAX_KNOWN) {
                    full = true
                    continue
                }
                known += id
            }
        }
        return Census(if (known.isEmpty()) STATE_UNSET else STATE_SET, known, full)
    }

    /** True when any of a host's tokens is on the list. A host with no token is never listed. */
    fun isListed(ids: List<String>, known: Set<String>): Boolean = ids.any { it in known }

    // ---- Snapshots -------------------------------------------------------------------------------

    /** The scan summary subject's observations out of a snapshot's home_network rows. */
    fun summaryOf(observations: List<Observation>): List<Observation> =
        observations.filter { it.tunnelId == LanKeys.TUNNEL_ID && it.subject == LanKeys.SUBJECT_SUMMARY }

    /**
     * The tokens a snapshot's `census:known` holds: only well-formed ones, at most [MAX_KNOWN], so a damaged row can
     * never make the list longer than the cap or put free text on it.
     */
    fun parseKnown(value: String?): Set<String> =
        LanKeys.items(value).filter(DeviceIdentity::isToken).take(MAX_KNOWN).toCollection(LinkedHashSet())

    /**
     * The baseline a snapshot offers for the network [tag], or null when it is of another network or carries no list.
     * A snapshot whose state is `unavailable` carries none (nothing was judged then): it is skipped, so an
     * unreadable store at one scan never hides the list kept in an older snapshot.
     */
    fun baselineOf(tag: String, takenAt: Long, observations: List<Observation>, pinned: Boolean = false): Baseline? {
        val summary = summaryOf(observations)
        if (LanKeys.value(summary, LanKeys.SCAN_NETWORK) != tag) return null
        return when (val state = LanKeys.value(summary, LanKeys.CENSUS_STATE)) {
            STATE_SET -> Baseline(takenAt, state, parseKnown(LanKeys.value(summary, LanKeys.CENSUS_KNOWN)), pinned)
            STATE_UNSET -> Baseline(takenAt, state, emptySet(), pinned)
            else -> null
        }
    }

    // ---- Events ----------------------------------------------------------------------------------

    /** `ids=<token>,<token>`: one acknowledged device, up to [DeviceIdentity.MAX_TOKENS] tokens. */
    fun eventSummary(ids: List<String>): String = ACK_PREFIX + ids.take(DeviceIdentity.MAX_TOKENS).joinToString(",")

    /** The tokens of an acknowledgement's summary, or null for anything that is not exactly the form [eventSummary] writes. */
    fun parseEvent(summary: String): List<String>? {
        if (!summary.startsWith(ACK_PREFIX)) return null
        val ids = summary.removePrefix(ACK_PREFIX).split(',')
        if (ids.isEmpty() || ids.size > DeviceIdentity.MAX_TOKENS) return null
        if (!ids.all(DeviceIdentity::isToken) || ids.toSet().size != ids.size) return null
        return ids
    }

    /** Folds the [STREAM] rows of the network [tag]: the latest reset, and every acknowledgement that parses. A row that does not is ignored. */
    fun fold(tag: String, events: List<CensusEvent>): Folded {
        var lastReset: Long? = null
        val acks = ArrayList<Ack>()
        for (e in events) {
            if (e.subject != tag) continue
            when (e.kind) {
                KIND_RESET -> if (e.summary == RESET_SUMMARY && (lastReset == null || e.at > lastReset)) lastReset = e.at
                KIND_ACK -> parseEvent(e.summary)?.let { acks += Ack(e.at, it) }
            }
        }
        return Folded(lastReset, acks)
    }

    // ---- Finding subjects ------------------------------------------------------------------------

    /** What a device is called in a finding and on the panel: its name, else its vendor, else a plain word (never its address, which moves). */
    fun title(name: String?, vendor: String?): String = name?.takeIf { it.isNotBlank() } ?: vendor?.takeIf { it.isNotBlank() } ?: "Unnamed device"

    /** The subject of an UNKNOWN_DEVICE finding: `<title> · <primary token>`. */
    fun subject(title: String, primary: String): String = "$title$SEPARATOR$primary"

    /** The primary token at the end of an UNKNOWN_DEVICE subject, or null when the subject carries none (an unidentified host). */
    fun parsePrimary(subject: String): String? = subject.substringAfterLast(SEPARATOR, "").takeIf { DeviceIdentity.isToken(it) }

    /** The subject of the CENSUS_NOT_SET_UP finding: `Device census · <network tag>`. */
    fun setupSubject(tag: String): String = "$SETUP_TITLE$SEPARATOR$tag"

    fun parseTag(subject: String): String? =
        subject.takeIf { it.startsWith("$SETUP_TITLE$SEPARATOR") }?.substringAfter(SEPARATOR)?.takeIf { tagPattern.matches(it) }
}

/** The one-line results of the census actions, in one place so the panel and the findings say the same thing. */
object CensusMessages {
    const val ADDED = "Added to this network's list. The panel updates at the next scan."
    const val NOT_IN_SCAN = "Not in a recent scan of this network: scan again while it is connected, then tap Mine."
    const val NO_SCAN_FOR_SETUP = "No recent scan of this network: scan it while it is connected, then tap These are all mine."
    const val NOT_SAVED = "Tunnels' encrypted store could not be changed, so nothing was saved."
    const val FULL = "The list is full (${DeviceCensus.MAX_KNOWN} entries): nothing was added. Start the list again to make room."
    const val NOTHING_TO_ADD = "No device in the last scan gave anything to recognise it by, so nothing was added."
    const val RESET = "The list was cleared. Scan again to start it over."

    fun setupDone(added: Int, unrecognisable: Int, notFitting: Int = 0): String {
        val base = (if (added == 1) "1 device added." else "$added devices added.") + " Scan again to start the census."
        val none = if (unrecognisable == 0) "" else " $unrecognisable gave nothing to recognise it by and stay off the list."
        val full = if (notFitting == 0) "" else " The list is full, so $notFitting did not fit."
        return base + none + full
    }
}
