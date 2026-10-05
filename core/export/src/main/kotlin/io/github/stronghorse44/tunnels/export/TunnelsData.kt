package io.github.stronghorse44.tunnels.export

/** How much a bundle holds, for the manifest and for what the screens report. */
data class BundleCounts(
    val snapshots: Int,
    val observations: Int,
    val settings: Int,
    val pairingPins: Int,
    val networks: Int,
)

/**
 * Everything a Tunnels bundle (schema 1) carries, in the canonical form an import writes back:
 *
 * - [snapshots]: every snapshot with its observations. Findings are derived on the phone and never carried.
 * - [settings]: the user's choices from the encrypted settings table, by key ([BundleSettings.KEYS]); a key the
 *   phone never set is absent. Values are the canonical encodings of their owning code.
 * - [pairingPins]: the second phones this one paired with (pinned identity keys), as `Pin.encode` writes them.
 * - [networks]: SHA-256 hex of each Wi-Fi network the user confirmed as their own (Home network's gate).
 *
 * Equality is by content, so a round trip can be compared with `==`.
 */
data class TunnelsData(
    val snapshots: SnapshotBundle = SnapshotBundle.EMPTY,
    val settings: Map<String, String> = emptyMap(),
    val pairingPins: String = "",
    val networks: Set<String> = emptySet(),
) {
    val counts: BundleCounts
        get() = BundleCounts(
            snapshots.snapshots.size,
            snapshots.observations.size,
            settings.size,
            pairingPins.lines().count { it.isNotBlank() },
            networks.size,
        )

    companion object {
        val EMPTY = TunnelsData()
    }
}
