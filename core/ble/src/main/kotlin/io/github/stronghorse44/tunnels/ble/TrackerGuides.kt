package io.github.stronghorse44.tunnels.ble

/** What to do about one tracker family, in the user's hands: identify, disable, report. Shown verbatim. */
data class TrackerGuide(
    /** How to learn whose tag it is or at least its serial number. */
    val identify: String,
    /** How to stop it transmitting. */
    val disable: String,
    /** Brand-specific notes for a report; the generic report steps are in [TrackerGuides.report]. */
    val reportNote: String,
) {
    init {
        require(identify.isNotBlank() && disable.isNotBlank() && reportNote.isNotBlank())
    }
}

/**
 * Hands-on guidance per tracker family. Nothing here needs a network: Tunnels never opens the vendor
 * pages itself, it tells the user what to tap on their own phone.
 */
object TrackerGuides {
    val apple = TrackerGuide(
        identify = "Hold the top of your phone (the NFC spot) against the white side of the AirTag. Any NFC phone opens found.apple.com " +
            "with the tag's serial number and, when the owner marked it lost, a partial phone number and message. " +
            "Third-party Find My tags open their maker's page the same way. Write the serial down or screenshot it.",
        disable = "Press down on the polished steel back and twist it counter-clockwise until it stops, lift the cover off and take out the " +
            "CR2032 coin cell. Without the battery it cannot transmit or chirp. Keep the tag and the battery apart.",
        reportNote = "Apple can tell the police which Apple ID paired the tag from its serial number (from the NFC page or printed under the battery).",
    )

    val samsung = TrackerGuide(
        identify = "SmartTags have no NFC page. On any phone, open the SmartThings app → SmartThings Find → Unknown tag search (\"Search nearby\"); " +
            "it lists tags in separated mode near you and lets them ring. The tag's serial is printed inside the back cover.",
        disable = "Pry the back cover off at the seam (a coin or fingernail in the notch), take out the CR2032 (SmartTag, SmartTag2) or " +
            "CR2450 (SmartTag+). Without the battery it stays silent. Keep the tag as evidence.",
        reportNote = "Samsung can link the tag to its owner's Samsung account from the serial under the back cover.",
    )

    val tile = TrackerGuide(
        identify = "Open the Tile app (an account is enough, no Tile needed) → Settings → \"Scan and Secure\" and let it run for about ten minutes " +
            "while you move; it lists Tiles that move with you. The Tile's serial is printed on its back or under the battery door.",
        disable = "Tile Pro and Tile Mate (2022 and newer) have a battery door: slide it open and remove the CR2032 or CR1632. Tile Slim and " +
            "Sticker have sealed batteries and cannot be switched off: put the Tile in a metal tin or wrap it in several layers of foil " +
            "until it is handed over; the metal blocks its signal.",
        reportNote = "Tile (Life360) hands owner details to the police on request, matched by the serial on the Tile.",
    )

    val chipolo = TrackerGuide(
        identify = "Chipolo ONE Point and CARD Point join Google's network: use Find My Device (Android) → Unknown tracker alerts → Scan. " +
            "Chipolo ONE Spot joins Apple's network: tap it with an NFC phone to open its info page. The serial is printed on the back.",
        disable = "Chipolo ONE and ONE Spot: twist the cover open and remove the CR2032. Chipolo CARD models are sealed: keep the card in a metal " +
            "tin or wrapped in foil so it cannot transmit.",
        reportNote = "Chipolo can identify the registered owner from the serial number on the back.",
    )

    val pebblebee = TrackerGuide(
        identify = "Pebblebee Clip and Card work on Apple's or Google's network. Tap the tag with an NFC phone (Apple-network tags open " +
            "found.apple.com) or use Find My Device → Unknown tracker alerts → Scan. The serial is on the back of the tag.",
        disable = "Pebblebee tags are rechargeable and sealed: hold the button until the LED turns off to power the tag down, or keep it in a " +
            "metal tin until it is handed over.",
        reportNote = "Pebblebee can match the serial to the account that registered the tag.",
    )

    val google = TrackerGuide(
        identify = "Open Find My Device on any Android phone (or Settings → Safety & emergency → Unknown tracker alerts) and run a manual " +
            "scan; it lists tags in separated mode near you and can make them ring. Tapping most tags with NFC opens their maker's page " +
            "with the serial. The serial is also printed on the tag.",
        disable = "Most Find My Device tags (Chipolo, Pebblebee, Moto, Eufy) open with a twist or a pry at the seam; take out the coin cell. " +
            "A sealed rechargeable tag goes in a metal tin or foil until it is handed over.",
        reportNote = "Google and the tag's maker can identify the owner from the serial read over NFC or printed on the tag.",
    )

    val byType: Map<TrackerType, TrackerGuide> = mapOf(
        TrackerType.APPLE_FINDMY to apple,
        TrackerType.SAMSUNG_SMARTTAG to samsung,
        TrackerType.TILE to tile,
        TrackerType.CHIPOLO to chipolo,
        TrackerType.PEBBLEBEE to pebblebee,
        TrackerType.GOOGLE_FMDN to google,
    )

    fun of(type: TrackerType): TrackerGuide = byType[type] ?: error("No guide for $type")

    /** Where to look, for every family. */
    const val SEARCH = "Listen first: most tags chirp when moved after hours away from their owner. Then search bags, coat linings and pockets, " +
        "the car (wheel wells, bumper, under seats, the charging port), a bike or pram, and anything given to you recently. " +
        "Find-it mode in Tunnels follows the signal: it gets warmer as you close in."

    /** The report steps shared by all families; the brand's [TrackerGuide.reportNote] goes after it. */
    val report: List<String> = listOf(
        "Screenshot this detail: the first and last seen times, how many scans, and the state. Screenshot the Timeline of your day if you can.",
        "Read the serial number: tap the tag with NFC where the family supports it, or read it off the tag (often under the battery).",
        "Do not destroy the tag. Remove its battery or put it in a metal tin so it stops reporting, and keep it as evidence.",
        "Local police can ask the network operator (Apple, Samsung, Google, Tile, Chipolo) for the owner behind a serial; that needs a report, " +
            "not a call from you. Domestic-violence services can help you decide whether removing it tips off the person who placed it.",
    )

    /** What Android's own alerts do and why Tunnels offers them next to its own. */
    const val UNKNOWN_TRACKER_ALERTS = "Android 14+ ships Unknown tracker alerts (Settings → Safety & emergency) that use Google's and Apple's " +
        "cross-platform spec to warn about a tag travelling with you and to make it ring. It runs through Google Play services, so a " +
        "GrapheneOS phone without them has no such screen; Tunnels' background monitor is the substitute."
}
