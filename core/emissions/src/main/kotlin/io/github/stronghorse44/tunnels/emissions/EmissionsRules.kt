package io.github.stronghorse44.tunnels.emissions

import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/**
 * Findings for transmitters left on where they are easy to forget. All are state findings: they clear when the
 * transmitter goes off. A radio in use (Wi-Fi, Bluetooth, cellular) is never a finding by itself. Readings that are
 * not confirmed on the owner's phone ([EmitterReading.confirmed]) never raise one. Each evidence ends with its Settings path.
 */
object EmissionsRules {
    const val AIRPLANE_LEAK = "EMISSION_AIRPLANE_LEAK"
    const val SCANNING_WHILE_OFF = "EMISSION_SCANNING_WHILE_OFF"
    const val NFC_ON = "EMISSION_NFC_ON"
    const val UWB_ON = "EMISSION_UWB_ON"
    const val HOTSPOT_ON = "EMISSION_HOTSPOT_ON"

    val kinds: Set<String> = setOf(AIRPLANE_LEAK, SCANNING_WHILE_OFF, NFC_ON, UWB_ON, HOTSPOT_ON)

    private fun EmissionsReading.isOn(e: Emitter) = reading(e).let { it.state == EmitterState.ON && it.confirmed }

    private fun withPath(text: String, e: Emitter) = "$text ${EmitterSettings.routeFor(e).path}."

    /** One finding a rule raises: its subject, kind and evidence. */
    private data class Hit(val subject: Emitter, val kind: String, val evidence: String)

    private fun rule(body: (EmissionsReading) -> List<Hit>) = FindingRule { ctx ->
        if (ctx.current.isEmpty()) return@FindingRule emptyList()
        body(EmissionsObservations.from(ctx.current)).map { h ->
            FindingDraft(ctx.tunnelId, h.subject.id, h.kind, if (h.kind == AIRPLANE_LEAK) Severity.NOTICE else Severity.INFO, h.evidence)
        }
    }

    val airplaneLeak: FindingRule = rule { r ->
        val leaking = listOf(Emitter.WIFI, Emitter.BLUETOOTH).filter { r.isOn(it) }
        if (r.airplane != true || leaking.isEmpty()) return@rule emptyList()
        val verb = if (leaking.size == 1) "is" else "are"
        listOf(
            Hit(
                Emitter.CELLULAR, AIRPLANE_LEAK,
                "Airplane mode is on, but ${leaking.joinToString(" and ") { it.title }} $verb still transmitting. Android " +
                    "remembers turning them back on during airplane mode and does it again next time.",
            ),
        )
    }

    val scanningWhileOff: FindingRule = rule { r ->
        listOf(Emitter.WIFI to Emitter.WIFI_SCANNING, Emitter.BLUETOOTH to Emitter.BLE_SCANNING).mapNotNull { (radio, scan) ->
            if (r.state(radio) != EmitterState.OFF || !r.isOn(scan)) return@mapNotNull null
            Hit(
                scan, SCANNING_WHILE_OFF,
                withPath("${radio.title} is off, but ${scan.title} is on: the phone still uses the radio so apps can find your location.", scan),
            )
        }
    }

    val nfcOn: FindingRule = rule { r ->
        if (!r.isOn(Emitter.NFC)) emptyList()
        else listOf(Hit(Emitter.NFC, NFC_ON, withPath("NFC is on: it polls for tags and cards whenever the screen is on.", Emitter.NFC)))
    }

    val uwbOn: FindingRule = rule { r ->
        if (!r.isOn(Emitter.UWB)) emptyList()
        else listOf(Hit(Emitter.UWB, UWB_ON, withPath("Ultra-wideband is on: apps and nearby tags can range with it.", Emitter.UWB)))
    }

    val hotspotOn: FindingRule = rule { r ->
        if (!r.isOn(Emitter.HOTSPOT)) return@rule emptyList()
        val kinds = r.hotspotKinds.map { k -> when (k) { "wifi" -> "Wi-Fi"; "usb" -> "USB"; "bluetooth" -> "Bluetooth"; else -> k } }
        val what = if (kinds.isEmpty()) "a hotspot or tethering" else "${kinds.joinToString(" and ")} tethering"
        listOf(Hit(Emitter.HOTSPOT, HOTSPOT_ON, withPath("The phone is serving $what.", Emitter.HOTSPOT)))
    }

    val all: List<FindingRule> = listOf(airplaneLeak, scanningWhileOff, nfcOn, uwbOn, hotspotOn)

    /** The transmitters a finding asks to turn off. */
    fun emittersFor(draft: FindingDraft): List<Emitter> = when (draft.kind) {
        AIRPLANE_LEAK -> listOf(Emitter.WIFI, Emitter.BLUETOOTH)
        in kinds -> listOfNotNull(Emitter.byId(draft.subject))
        else -> emptyList()
    }

    /** The Settings screens for a finding; the Android side adds the shell action in front when Shizuku is ready. */
    fun settingsActions(draft: FindingDraft): List<FindingAction> =
        emittersFor(draft).map(EmitterSettings::routeFor).distinctBy { it.action }.map { FindingAction.OpenSettings(it.action, it.label) }
}
