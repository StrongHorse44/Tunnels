package io.github.stronghorse44.tunnels.emissions

import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmissionsRulesTest {
    private fun findings(r: EmissionsReading): List<FindingDraft> {
        val ctx = RuleContext(EmissionsKeys.TUNNEL_ID, EmissionsObservations.toObservations(r), emptyList(), isFirstScan = true)
        return EmissionsRules.all.flatMap { it.evaluate(ctx) }
    }

    @Test
    fun aRadioInUseIsNotAFinding() {
        val r = reading(states = mapOf(Emitter.CELLULAR to EmitterState.ON, Emitter.WIFI to EmitterState.ON, Emitter.BLUETOOTH to EmitterState.ON))
        assertTrue(findings(r).isEmpty())
    }

    @Test
    fun airplaneModeWithWifiOrBluetoothStillOn() {
        val r = reading(states = mapOf(Emitter.BLUETOOTH to EmitterState.ON), airplane = true)
        val f = findings(r).single()
        assertEquals(EmissionsRules.AIRPLANE_LEAK, f.kind)
        assertEquals(Severity.NOTICE, f.severity)
        assertEquals(Emitter.CELLULAR.id, f.subject)
        assertTrue(f.evidence, f.evidence.contains("Bluetooth is still transmitting"))
    }

    @Test
    fun scanningOnlyMattersWhileItsRadioIsOff() {
        val wifiOff = reading(states = mapOf(Emitter.WIFI_SCANNING to EmitterState.ON, Emitter.BLE_SCANNING to EmitterState.ON, Emitter.BLUETOOTH to EmitterState.ON))
        val f = findings(wifiOff).single()
        assertEquals(EmissionsRules.SCANNING_WHILE_OFF, f.kind)
        assertEquals(Emitter.WIFI_SCANNING.id, f.subject)
        assertTrue(f.evidence, f.evidence.endsWith("Location > Location services > Wi-Fi scanning."))
    }

    @Test
    fun unconfirmedReadingsNeverRaiseAFinding() {
        val on = mapOf(Emitter.UWB to EmitterState.ON, Emitter.HOTSPOT to EmitterState.ON, Emitter.NFC to EmitterState.ON)
        assertEquals(
            setOf(EmissionsRules.UWB_ON, EmissionsRules.HOTSPOT_ON, EmissionsRules.NFC_ON),
            findings(reading(states = on, hotspotKinds = listOf("wifi"))).map { it.kind }.toSet(),
        )
        val unconfirmed = reading(states = on, unconfirmed = setOf(Emitter.UWB, Emitter.HOTSPOT))
        assertEquals(listOf(EmissionsRules.NFC_ON), findings(unconfirmed).map { it.kind })
    }

    @Test
    fun hotspotEvidenceNamesTheKinds() {
        val f = findings(reading(states = mapOf(Emitter.HOTSPOT to EmitterState.ON), hotspotKinds = listOf("usb", "wifi"))).single()
        assertTrue(f.evidence, f.evidence.startsWith("The phone is serving USB and Wi-Fi tethering."))
    }

    @Test
    fun everyKindHasASettingsAction() {
        // Rule 7: every finding carries an action even without Shizuku.
        EmissionsRules.kinds.forEach { kind ->
            val subject = if (kind == EmissionsRules.AIRPLANE_LEAK) Emitter.CELLULAR.id else when (kind) {
                EmissionsRules.SCANNING_WHILE_OFF -> Emitter.BLE_SCANNING.id
                EmissionsRules.NFC_ON -> Emitter.NFC.id
                EmissionsRules.UWB_ON -> Emitter.UWB.id
                else -> Emitter.HOTSPOT.id
            }
            val draft = FindingDraft(EmissionsKeys.TUNNEL_ID, subject, kind, Severity.INFO, "")
            assertTrue(kind, EmissionsRules.settingsActions(draft).isNotEmpty())
        }
        val airplane = FindingDraft(EmissionsKeys.TUNNEL_ID, Emitter.CELLULAR.id, EmissionsRules.AIRPLANE_LEAK, Severity.NOTICE, "")
        assertEquals(listOf(Emitter.WIFI, Emitter.BLUETOOTH), EmissionsRules.emittersFor(airplane))
    }

    @Test
    fun noObservationsNoFindings() {
        val ctx = RuleContext(EmissionsKeys.TUNNEL_ID, emptyList(), emptyList(), isFirstScan = true)
        assertTrue(EmissionsRules.all.all { it.evaluate(ctx).isEmpty() })
    }
}
