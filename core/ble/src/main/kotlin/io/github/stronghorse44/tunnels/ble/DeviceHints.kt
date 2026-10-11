package io.github.stronghorse44.tunnels.ble

import java.util.Locale

/** What a name or maker suggests a nearby device is. */
enum class HintKind(val slug: String, val label: String) {
    CAMERA("camera", "a camera"),
    MICROPHONE("microphone", "a microphone or recorder"),
    SPY("spy", "a spy or hidden device"),
    CAMERA_GLASSES("glasses", "camera glasses"),
}

/**
 * One entry of the catalog: [id] is what a scan row keeps (lower case, digits and dashes), [kind] what it suggests and
 * [because] the reason in words ("its name says \"cam\"").
 */
data class Hint(val id: String, val kind: HintKind, val because: String) {
    /** "Its name says \"cam\": it may be a camera." */
    val sentence: String get() = "${because.replaceFirstChar { it.uppercase() }}: it may be ${kind.label}."
}

/**
 * Hints that a nearby Wi-Fi access point or Bluetooth device is a camera, a microphone or a spy device (RJ, 2026-10-11),
 * from what it says about itself: words in the name it broadcasts, and a few makers' Bluetooth company ids. A hint is
 * never proof, only a reason to look: a device can be named anything, and most cameras nearby are someone's doorbell.
 *
 * The catalog is deliberately small. A name entry is a word a device uses to describe itself, so a match can be read
 * and checked by anyone; a maker entry is a Bluetooth SIG company id whose owner makes only cameras. Prefixes of
 * camera apps' setup networks and Wi-Fi vendor prefixes are left out until confirmed on a device (docs/BUILD_PLAN.md).
 * Plain JVM, unit-tested.
 */
object DeviceHints {
    /** Ids are kept in scan rows; this is their shape. */
    val ID = Regex("[a-z0-9-]{1,32}")

    /** Whole words of a name (split at spaces, punctuation, digits and lower-to-upper case changes). */
    private val WORDS: Map<String, HintKind> = mapOf(
        "cam" to HintKind.CAMERA,
        "camera" to HintKind.CAMERA,
        "webcam" to HintKind.CAMERA,
        "ipcam" to HintKind.CAMERA,
        "minicam" to HintKind.CAMERA,
        "hdcam" to HintKind.CAMERA,
        "wificam" to HintKind.CAMERA,
        "nannycam" to HintKind.CAMERA,
        "babycam" to HintKind.CAMERA,
        "dashcam" to HintKind.CAMERA,
        "bodycam" to HintKind.CAMERA,
        "doorbell" to HintKind.CAMERA,
        "dvr" to HintKind.CAMERA,
        "nvr" to HintKind.CAMERA,
        "gopro" to HintKind.CAMERA,
        "insta360" to HintKind.CAMERA,
        "mic" to HintKind.MICROPHONE,
        "microphone" to HintKind.MICROPHONE,
        "recorder" to HintKind.MICROPHONE,
        "dictaphone" to HintKind.MICROPHONE,
        "spy" to HintKind.SPY,
        "spycam" to HintKind.SPY,
        "hidden" to HintKind.SPY,
        "hiddencam" to HintKind.SPY,
        "covert" to HintKind.SPY,
        "spectacles" to HintKind.CAMERA_GLASSES,
    )

    /** Runs of letters and digits with no separator that only these devices use ("RayBanMeta", "VoiceRecorder"). */
    private val JOINED: Map<String, HintKind> = mapOf(
        "raybanmeta" to HintKind.CAMERA_GLASSES,
        "voicerecorder" to HintKind.MICROPHONE,
        "audiorecorder" to HintKind.MICROPHONE,
        "spycamera" to HintKind.SPY,
        "hiddencamera" to HintKind.SPY,
    )

    /** Bluetooth SIG company ids (manufacturer data) of makers whose devices are cameras. */
    private val MAKERS: Map<Int, Pair<String, HintKind>> = mapOf(
        0x02F2 to ("GoPro" to HintKind.CAMERA),
    )

    /** "Cam's iPhone": a possessive is a person's name, never a description. */
    private val POSSESSIVE = Regex("""\b[\p{L}\p{N}]+['’]s\b""")
    private val CASE_CHANGE = Regex("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

    /** The words of [name] as the catalog reads them, lower case. */
    fun words(name: String): List<String> =
        POSSESSIVE.replace(name, " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .flatMap { it.split(CASE_CHANGE) }
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

    /** The hint a broadcast name gives, or null. Hidden and empty names give none. */
    fun forName(name: String?): Hint? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n == WifiHeuristics.HIDDEN) return null
        // Whole words first: "HD WiFi Cam" says "cam" as a word.
        for (w in words(n)) WORDS[w]?.let { return Hint("name-$w", it, "its name says \"$w\"") }
        // Phrases only these devices use, wherever the separators fall ("Ray-Ban Meta", "VOICERECORDER").
        val compact = POSSESSIVE.replace(n, " ").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        JOINED.entries.firstOrNull { compact.contains(it.key) }?.let { (word, kind) -> return Hint("name-$word", kind, "its name says \"$word\"") }
        // Runs written together: "HDWIFICAM", "GoPro" (case changes split nothing in all caps, and split brand names).
        val runs = POSSESSIVE.replace(n, " ").split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase(Locale.ROOT) }
        for (run in runs) {
            WORDS[run]?.let { return Hint("name-$run", it, "its name says \"$run\"") }
            WORDS.entries.firstOrNull { it.key.length >= MIN_JOINED && run.contains(it.key) }?.let { (word, kind) ->
                return Hint("name-$word", kind, "its name says \"$word\"")
            }
            // "SMARTCAM", "LOOKCAM": a run that ends in "cam" names a camera ("scam" excepted).
            if (run.length > 3 && run.endsWith("cam") && run != "scam") return Hint("name-cam", HintKind.CAMERA, "its name says \"cam\"")
        }
        return null
    }

    /** Shorter words ("cam", "mic", "spy", "dvr") match only on their own: inside a run they are parts of other words. */
    private const val MIN_JOINED = 6

    /** The hint the Bluetooth company ids in an advertisement give, or null. */
    fun forMakers(companyIds: Collection<Int>): Hint? {
        for (id in companyIds.sorted()) MAKERS[id]?.let { (maker, kind) -> return Hint(makerId(id), kind, "it says it is made by $maker") }
        return null
    }

    /** The name first (it says more), then the maker. */
    fun of(name: String?, companyIds: Collection<Int> = emptyList()): Hint? = forName(name) ?: forMakers(companyIds)

    /** A hint read back from a stored id; null for an id this catalog no longer has (it is dropped, never guessed). */
    fun byId(id: String?): Hint? {
        if (id == null || !ID.matches(id)) return null
        if (id.startsWith("name-")) {
            val word = id.removePrefix("name-")
            val kind = WORDS[word] ?: JOINED[word] ?: return null
            return Hint(id, kind, "its name says \"$word\"")
        }
        if (id.startsWith("maker-")) {
            val company = id.removePrefix("maker-").toIntOrNull(16) ?: return null
            MAKERS[company]?.let { (maker, kind) -> return Hint(id, kind, "it says it is made by $maker") }
        }
        return null
    }

    private fun makerId(companyId: Int): String = "maker-%04x".format(companyId)
}
