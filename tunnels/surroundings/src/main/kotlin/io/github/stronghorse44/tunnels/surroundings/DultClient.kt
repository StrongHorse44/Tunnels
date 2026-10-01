package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.stronghorse44.tunnels.ble.DultProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT session with a tag's DULT accessory non-owner service. Commands are written to the single
 * characteristic and answered by indications on it; this client runs them one at a time with timeouts
 * and never stores anything: the caller folds what it learnt into a [DultProtocol.Summary].
 *
 * Experimental: verified against the draft and open implementations, not yet against a real tag.
 */
class DultClient(private val context: Context, private val device: BluetoothDevice) {
    private sealed interface Event {
        data class Connection(val status: Int, val state: Int) : Event
        data class Services(val status: Int) : Event
        data class Written(val status: Int) : Event
        data class DescriptorWritten(val status: Int) : Event
        data class Mtu(val mtu: Int, val status: Int) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val indications = Channel<ByteArray>(Channel.UNLIMITED)
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.trySend(Event.Connection(status, newState))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.trySend(Event.Services(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            events.trySend(Event.Mtu(mtu, status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            events.trySend(Event.Written(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            events.trySend(Event.DescriptorWritten(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == CHARACTERISTIC) indications.trySend(value.copyOf())
        }
    }

    val connected: Boolean get() = characteristic != null

    /**
     * Connects, finds the service and enables indications. Returns null on success or the reason it
     * could not, in the user's words.
     */
    suspend fun open(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Bluetooth connect permission was not granted."
        }
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            return "Bluetooth connect permission was not granted."
        } catch (e: Exception) {
            return "Could not start the connection: ${e.javaClass.simpleName}."
        } ?: return "Could not start the connection."
        gatt = g
        val conn = awaitEvent<Event.Connection>(CONNECT_TIMEOUT_MS) { it.state == BluetoothProfile.STATE_CONNECTED || it.state == BluetoothProfile.STATE_DISCONNECTED }
        if (conn == null || conn.state != BluetoothProfile.STATE_CONNECTED) {
            close()
            return "The tag did not accept a connection. Tags only listen for a few seconds after each advertisement and often only in separated mode; stay close and try again."
        }
        // Names are up to 64 bytes: ask for a larger MTU, carry on with the default if the tag refuses.
        if (safe { g.requestMtu(MTU) } == true) awaitEvent<Event.Mtu>(STEP_TIMEOUT_MS) { true }
        if (safe { g.discoverServices() } != true) {
            close()
            return "Could not read the tag's services."
        }
        val services = awaitEvent<Event.Services>(STEP_TIMEOUT_MS) { true }
        if (services == null || services.status != BluetoothGatt.GATT_SUCCESS) {
            close()
            return "The tag's services did not come back in time."
        }
        val c = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
        if (c == null) {
            close()
            return "This tag does not offer the DULT non-owner service (older AirTags and most Tiles do not)."
        }
        if (safe { g.setCharacteristicNotification(c, true) } != true) {
            close()
            return "Could not subscribe to the tag's responses."
        }
        val cccd = c.getDescriptor(CCCD)
        if (cccd != null) {
            val wrote = safe { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) } == BluetoothGatt.GATT_SUCCESS
            if (!wrote || awaitEvent<Event.DescriptorWritten>(STEP_TIMEOUT_MS) { true }?.status != BluetoothGatt.GATT_SUCCESS) {
                close()
                return "The tag refused the indication subscription."
            }
        }
        characteristic = c
        return null
    }

    /** Sends one opcode and returns the first indication that follows, parsed; null when nothing came back in time. */
    suspend fun request(opcode: Int): DultProtocol.Response? {
        val g = gatt ?: return null
        val c = characteristic ?: return null
        while (indications.tryReceive().isSuccess) { /* drop stale indications */ }
        val code = safe { g.writeCharacteristic(c, DultProtocol.command(opcode), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
        if (code != BluetoothGatt.GATT_SUCCESS) return null
        val written = awaitEvent<Event.Written>(STEP_TIMEOUT_MS) { true }
        if (written == null || written.status != BluetoothGatt.GATT_SUCCESS) return null
        val frame = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { indications.receive() } ?: return null
        return DultProtocol.parse(frame)
    }

    /** Reads everything the information opcodes offer; failures of single reads are skipped. */
    suspend fun readInformation(): DultProtocol.Summary {
        var summary = DultProtocol.Summary()
        for (opcode in INFORMATION_OPCODES) {
            val r = request(opcode) ?: continue
            summary = summary.with(r)
        }
        return summary
    }

    fun close() {
        characteristic = null
        gatt?.let { g ->
            safe { g.disconnect() }
            safe { g.close() }
        }
        gatt = null
    }

    private suspend inline fun <reified T : Event> awaitEvent(timeoutMs: Long, crossinline accept: (T) -> Boolean): T? = withTimeoutOrNull(timeoutMs) {
        var found: T? = null
        while (found == null) {
            val e = events.receive()
            if (e is T && accept(e)) found = e
        }
        found
    }

    private inline fun <R> safe(block: () -> R): R? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "gatt call failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "SurroundingsDult"
        val SERVICE: UUID = UUID.fromString(DultProtocol.SERVICE_UUID)
        val CHARACTERISTIC: UUID = UUID.fromString(DultProtocol.CHARACTERISTIC_UUID)
        val CCCD: UUID = UUID.fromString(DultProtocol.CCCD_UUID)
        private const val MTU = 185
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val STEP_TIMEOUT_MS = 6_000L
        private const val RESPONSE_TIMEOUT_MS = 5_000L

        val INFORMATION_OPCODES = listOf(
            DultProtocol.GET_MANUFACTURER_NAME,
            DultProtocol.GET_MODEL_NAME,
            DultProtocol.GET_ACCESSORY_CATEGORY,
            DultProtocol.GET_ACCESSORY_CAPABILITIES,
            DultProtocol.GET_BATTERY_TYPE,
            DultProtocol.GET_BATTERY_LEVEL,
            DultProtocol.GET_FIRMWARE_VERSION,
        )
    }
}
