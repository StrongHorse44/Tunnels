package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DultProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A synthetic indication: little-endian opcode then operands. */
    private fun frame(opcode: Int, vararg body: Int) = DultProtocol.command(opcode) + bytes(*body)

    @Test
    fun uuidsAndOpcodesAreTheDraftsValues() {
        assertEquals("15190001-12f4-c226-88ed-2ac5579f2a85", DultProtocol.SERVICE_UUID)
        assertEquals("8e0c0001-1d68-fb92-bf61-48377421680e", DultProtocol.CHARACTERISTIC_UUID)
        assertEquals(0x0003, DultProtocol.GET_PRODUCT_DATA)
        assertEquals(0x0300, DultProtocol.SOUND_START)
        assertEquals(0x0301, DultProtocol.SOUND_STOP)
        assertEquals(0x0302, DultProtocol.COMMAND_RESPONSE)
        assertEquals(0x0303, DultProtocol.SOUND_COMPLETED)
        assertEquals(0x0404, DultProtocol.GET_IDENTIFIER)
        assertEquals(0x0405, DultProtocol.GET_IDENTIFIER_RESPONSE)
        // Information responses are the request opcode with 0x0800 set.
        for ((req, rsp) in listOf(
            DultProtocol.GET_PRODUCT_DATA to DultProtocol.GET_PRODUCT_DATA_RESPONSE,
            DultProtocol.GET_MANUFACTURER_NAME to DultProtocol.GET_MANUFACTURER_NAME_RESPONSE,
            DultProtocol.GET_MODEL_NAME to DultProtocol.GET_MODEL_NAME_RESPONSE,
            DultProtocol.GET_ACCESSORY_CATEGORY to DultProtocol.GET_ACCESSORY_CATEGORY_RESPONSE,
            DultProtocol.GET_PROTOCOL_IMPLEMENTATION_VERSION to DultProtocol.GET_PROTOCOL_IMPLEMENTATION_VERSION_RESPONSE,
            DultProtocol.GET_ACCESSORY_CAPABILITIES to DultProtocol.GET_ACCESSORY_CAPABILITIES_RESPONSE,
            DultProtocol.GET_NETWORK_ID to DultProtocol.GET_NETWORK_ID_RESPONSE,
            DultProtocol.GET_FIRMWARE_VERSION to DultProtocol.GET_FIRMWARE_VERSION_RESPONSE,
            DultProtocol.GET_BATTERY_TYPE to DultProtocol.GET_BATTERY_TYPE_RESPONSE,
            DultProtocol.GET_BATTERY_LEVEL to DultProtocol.GET_BATTERY_LEVEL_RESPONSE,
        )) assertEquals(req or 0x0800, rsp)
    }

    @Test
    fun commandsAreLittleEndian() {
        assertArrayEquals(bytes(0x00, 0x03), DultProtocol.command(DultProtocol.SOUND_START))
        assertArrayEquals(bytes(0x01, 0x03), DultProtocol.command(DultProtocol.SOUND_STOP))
        assertArrayEquals(bytes(0x04, 0x04), DultProtocol.command(DultProtocol.GET_IDENTIFIER))
        assertArrayEquals(bytes(0x0C, 0x00), DultProtocol.command(DultProtocol.GET_BATTERY_LEVEL))
        assertEquals(0x0803, DultProtocol.opcodeOf(bytes(0x03, 0x08, 0xAA)))
        assertNull(DultProtocol.opcodeOf(bytes(0x03)))
        assertNull(DultProtocol.parse(ByteArray(0)))
    }

    @Test
    fun informationResponsesParse() {
        val product = DultProtocol.parse(frame(DultProtocol.GET_PRODUCT_DATA_RESPONSE, 1, 2, 3, 4, 5, 6, 7, 8)) as DultProtocol.Response.ProductData
        assertEquals("0102030405060708", product.hex)
        // Names: exact length or zero-terminated and zero-padded to 64 bytes.
        val exact = DultProtocol.parse(DultProtocol.command(DultProtocol.GET_MANUFACTURER_NAME_RESPONSE) + "Apple Inc.".toByteArray())
        assertEquals(DultProtocol.Response.ManufacturerName("Apple Inc."), exact)
        val padded = DultProtocol.command(DultProtocol.GET_MODEL_NAME_RESPONSE) + ("AirTag".toByteArray() + ByteArray(58))
        assertEquals(DultProtocol.Response.ModelName("AirTag"), DultProtocol.parse(padded))
        // Control characters and line breaks from a hostile tag never reach the UI or the events row.
        val hostile = DultProtocol.command(DultProtocol.GET_MANUFACTURER_NAME_RESPONSE) + "Ev\u0007il\nCorp\r\u001b[31m".toByteArray()
        assertEquals(DultProtocol.Response.ManufacturerName("EvilCorp[31m"), DultProtocol.parse(hostile))
        assertEquals("location tracker", (DultProtocol.parse(frame(DultProtocol.GET_ACCESSORY_CATEGORY_RESPONSE, 1)) as DultProtocol.Response.AccessoryCategory).name)
        assertEquals("keys", DultProtocol.categoryName(158))
        assertEquals("category 99", DultProtocol.categoryName(99))
        // 1.0.0 = 0x00010000 little-endian: 00 00 01 00.
        assertEquals("1.0.0", DultProtocol.parse(frame(DultProtocol.GET_PROTOCOL_IMPLEMENTATION_VERSION_RESPONSE, 0x00, 0x00, 0x01, 0x00)).toString())
        // 2.3.7: revision 7, minor 3, major 2.
        assertEquals("2.3.7", DultProtocol.parse(frame(DultProtocol.GET_FIRMWARE_VERSION_RESPONSE, 0x07, 0x03, 0x02, 0x00)).toString())
        val caps = DultProtocol.parse(frame(DultProtocol.GET_ACCESSORY_CAPABILITIES_RESPONSE, 0x05, 0x00, 0x00, 0x00)) as DultProtocol.Response.Capabilities
        assertTrue(caps.playSound && caps.identifierByNfc)
        assertFalse(caps.motionDetector || caps.identifierByBle)
        assertEquals(listOf("play sound", "serial over NFC"), caps.labels)
        assertEquals(DultProtocol.Response.NetworkId(2), DultProtocol.parse(frame(DultProtocol.GET_NETWORK_ID_RESPONSE, 2)))
        assertEquals("non-rechargeable battery", (DultProtocol.parse(frame(DultProtocol.GET_BATTERY_TYPE_RESPONSE, 1)) as DultProtocol.Response.BatteryType).name)
        assertEquals("critically low", (DultProtocol.parse(frame(DultProtocol.GET_BATTERY_LEVEL_RESPONSE, 3)) as DultProtocol.Response.BatteryLevel).name)
        assertEquals("full", DultProtocol.batteryLevelName(0))
        // Truncated numeric responses do not throw.
        assertTrue(DultProtocol.parse(frame(DultProtocol.GET_FIRMWARE_VERSION_RESPONSE, 0x01)) is DultProtocol.Response.Unknown)
        assertEquals(DultProtocol.Response.Unknown(0x0999, 1), DultProtocol.parse(frame(0x0999, 0)))
    }

    @Test
    fun controlResponsesParse() {
        val ok = DultProtocol.parse(frame(DultProtocol.COMMAND_RESPONSE, 0x00, 0x03, 0x00, 0x00)) as DultProtocol.Response.CommandResponse
        assertEquals(DultProtocol.SOUND_START, ok.commandOpcode)
        assertTrue(ok.ok)
        assertEquals("success", ok.statusName)
        val refused = DultProtocol.parse(frame(DultProtocol.COMMAND_RESPONSE, 0x04, 0x04, 0xFF, 0xFF)) as DultProtocol.Response.CommandResponse
        assertEquals(DultProtocol.GET_IDENTIFIER, refused.commandOpcode)
        assertEquals(DultProtocol.STATUS_INVALID_COMMAND, refused.status)
        assertEquals("invalid command", refused.statusName)
        assertTrue(DultProtocol.explainRefusal(refused.commandOpcode, refused.status).contains("identifier-read mode"))
        assertTrue(DultProtocol.explainRefusal(DultProtocol.SOUND_START, DultProtocol.STATUS_INVALID_COMMAND).contains("separated mode"))
        assertEquals("The tag is already ringing.", DultProtocol.explainRefusal(DultProtocol.SOUND_START, DultProtocol.STATUS_INVALID_STATE))
        assertEquals("The tag refused (invalid length).", DultProtocol.explainRefusal(DultProtocol.GET_BATTERY_LEVEL, DultProtocol.STATUS_INVALID_LENGTH))
        assertEquals("status 0x0042", DultProtocol.statusName(0x42))
        assertEquals(DultProtocol.Response.SoundCompleted, DultProtocol.parse(frame(DultProtocol.SOUND_COMPLETED)))
        // Only the identifier's length is kept, never its bytes.
        assertEquals(DultProtocol.Response.Identifier(16), DultProtocol.parse(DultProtocol.command(DultProtocol.GET_IDENTIFIER_RESPONSE) + ByteArray(16) { 0x5A }))
        assertTrue(DultProtocol.parse(frame(DultProtocol.COMMAND_RESPONSE, 0x00, 0x03)) is DultProtocol.Response.Unknown)
    }

    @Test
    fun summaryFoldsIntoOneLine() {
        var s = DultProtocol.Summary()
        assertEquals("DULT: unknown tag", s.line())
        s = s.with(DultProtocol.Response.ManufacturerName("Apple"))
            .with(DultProtocol.Response.ModelName("AirTag"))
            .with(DultProtocol.Response.AccessoryCategory(1))
            .with(DultProtocol.Response.BatteryLevel(0))
            .with(DultProtocol.Response.Capabilities(0x05))
            .with(DultProtocol.Response.ProductData(ByteArray(8)))
        assertEquals("DULT: Apple AirTag, location tracker, battery full", s.line())
        assertEquals("DULT: Apple AirTag, location tracker, battery full, sound played", s.copy(soundPlayed = true).line())
        assertEquals("DULT: Apple AirTag, location tracker, battery full, identifier read", s.with(DultProtocol.Response.Identifier(16)).line())
        assertEquals("DULT: keys, battery low", DultProtocol.Summary().with(DultProtocol.Response.AccessoryCategory(158)).with(DultProtocol.Response.BatteryLevel(2)).line())
        assertEquals(listOf("play sound", "serial over NFC"), s.capabilities)
        // Nothing identifying is in the line: no product data hex, no identifier bytes.
        assertFalse(s.line().contains("00000000"))
    }
}
