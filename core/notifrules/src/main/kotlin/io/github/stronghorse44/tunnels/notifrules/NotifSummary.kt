package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.model.Observation

/** What the tunnel panel shows, read back from the last scan's observations. */
data class NotifSummary(
    val total7: Int,
    val total30: Int,
    val appsActive7: Int,
    val appsNoisy: Int,
    val listenerConnected: Boolean,
    val accessGranted: Boolean,
    /** Noisiest apps first. */
    val top: List<TopApp>,
) {
    data class TopApp(val packageName: String, val label: String, val count7: Int, val perDay7: Double, val night7: Int, val urgent7: Int)

    val hasData: Boolean get() = total30 > 0

    companion object {
        const val TOP_N = 5

        val EMPTY = NotifSummary(0, 0, 0, 0, listenerConnected = false, accessGranted = false, top = emptyList())

        fun from(observations: List<Observation>, topN: Int = TOP_N): NotifSummary {
            val mine = observations.filter { it.tunnelId == NotifKeys.TUNNEL_ID }
            if (mine.isEmpty()) return EMPTY
            val bySubject = mine.groupBy { it.subject }
            val summary = bySubject[NotifKeys.SUMMARY].orEmpty()
            val top = bySubject.filterKeys { it != NotifKeys.SUMMARY }.map { (pkg, obs) ->
                TopApp(
                    packageName = pkg,
                    label = NotifKeys.value(obs, NotifKeys.LABEL)?.takeIf { it.isNotBlank() } ?: pkg,
                    count7 = NotifKeys.int(obs, NotifKeys.COUNT_7),
                    perDay7 = NotifKeys.double(obs, NotifKeys.PER_DAY_7),
                    night7 = NotifKeys.int(obs, NotifKeys.NIGHT_7),
                    urgent7 = NotifKeys.int(obs, NotifKeys.URGENT_7),
                )
            }.filter { it.count7 > 0 }
                .sortedWith(compareByDescending<TopApp> { it.count7 }.thenBy { it.label.lowercase() })
                .take(topN)
            return NotifSummary(
                total7 = NotifKeys.int(summary, NotifKeys.TOTAL_7),
                total30 = NotifKeys.int(summary, NotifKeys.TOTAL_30),
                appsActive7 = NotifKeys.int(summary, NotifKeys.APPS_ACTIVE_7),
                appsNoisy = NotifKeys.int(summary, NotifKeys.APPS_NOISY),
                listenerConnected = NotifKeys.value(summary, NotifKeys.LISTENER_CONNECTED) == "true",
                accessGranted = NotifKeys.value(summary, NotifKeys.ACCESS_GRANTED) == "true",
                top = top,
            )
        }
    }
}
