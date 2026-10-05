package io.github.stronghorse44.tunnels.posture

import io.github.stronghorse44.tunnels.model.Observation

/**
 * The posture observations of one scan, all strings under subject `posture`:
 * `posture:<id>` = good | weak | unknown | n/a, `posture:<id>:value` (only when read, capped), `posture:<id>:why`
 * (only when unknown) and `posture:read:<table>` = ok | timed out | truncated | exit | error | not run.
 * Nothing else is stored: no raw dump, no timestamps, the exempt apps of the VPN lockdown only as a count.
 */
object PostureObservations {
    fun to(tunnelId: String, readings: List<Reading>, reads: Map<PostureTable, TableRead>): List<Observation> = buildList {
        fun add(key: String, value: String) = add(Observation(tunnelId, PostureKeys.SUBJECT, key, value))
        for (r in readings) {
            add(PostureKeys.stateKey(r.item.id), r.state.word)
            r.value?.let { add(PostureKeys.valueKey(r.item.id), PostureReader.cap(it)) }
            r.why?.let { add(PostureKeys.whyKey(r.item.id), it.word) }
        }
        for (table in PostureTable.entries) {
            val read = reads[table] ?: PostureParser.notRun
            add(PostureKeys.readKey(table), if (read is TableRead.Failed) read.reason else PostureParser.OK)
        }
    }

    /** The posture part of [observations]; items with no state observation are left out. Inverse of [to]. */
    fun from(observations: List<Observation>, items: List<PostureItem> = PostureKeys.ITEMS): PostureSnapshot {
        val byKey = HashMap<String, String>()
        for (o in observations) if (o.subject == PostureKeys.SUBJECT) byKey[o.key] = o.value
        val readings = items.mapNotNull { item ->
            val state = PostureState.of(byKey[PostureKeys.stateKey(item.id)]) ?: return@mapNotNull null
            Reading(item, state, byKey[PostureKeys.valueKey(item.id)], PostureWhy.of(byKey[PostureKeys.whyKey(item.id)]))
        }
        val reads = PostureTable.entries.mapNotNull { t -> byKey[PostureKeys.readKey(t)]?.let { t to it } }.toMap()
        return PostureSnapshot(readings, reads)
    }
}
