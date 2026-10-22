package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMsg(val nick: String, val text: String, val mine: Boolean)

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/**
 * كل هاتف: يعلن عن نفسه (Peripheral) + يبحث ويتصل بالجيران (Central).
 * الإرسال = كتابة على characteristic عند كل جار متصل.
 * التوجيه = Flooding مع TTL ومنع التكرار.
 */
@SuppressLint("MissingPermission")
class MeshManager(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E4D4C01-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID = UUID.fromString("6E4D4C02-0000-1000-8000-00805F9B34FB")
        const val MAX_PACKET = 244
        const val DEFAULT_TTL = 7
    }

    private class Conn(val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic) {
        val queue = ConcurrentLinkedQueue<ByteArray>()
        val busy = AtomicBoolean(false)
    }

    private val prefs = ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE)
    val myId: ByteArray = loadId()
    val nick: String = "User-" + myId.copyOf(2).toHex()

    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val peers = MutableStateFlow(0)
    val status = MutableStateFlow("Idle")

    private val clients = ConcurrentHashMap<String, Conn>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }

    private var server: BluetoothGattServer? = null
    private var adv: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var started = false

    private fun loadId(): ByteArray {
        val saved = prefs.getString("id", null)
        if (saved != null && saved.length == 16) {
            return ByteArray(8) { saved.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("id", id.toHex()).apply()
        return id
    }

    // ---------- start / stop ----------
    fun start() {
        if (started) return
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.value = "Bluetooth is off. Turn it on and reopen the app."
            return
        }
        started = true

        server = bm.openGattServer(ctx, serverCb)
        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        server?.addService(svc)

        adv = adapter.bluetoothLeAdvertiser
        adv?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
            advCb
        )

        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb
        )
        status.value = "Running as $nick"
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { scanner?.stopScan(scanCb) }
        runCatching { adv?.stopAdvertising(advCb) }
        clients.values.forEach { runCatching { it.gatt.close() } }
        clients.clear()
        connecting.clear()
        runCatching { server?.close() }
        peers.value = 0
    }

    // ---------- send / receive ----------
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val payload = buildPayload(t)
        val p = Packet(
            0, DEFAULT_TTL,
            ByteArray(16).also { SecureRandom().nextBytes(it) },
            myId, Packet.BROADCAST, System.currentTimeMillis(), payload
        )
        markSeen(p.id.toHex())
        messages.update { it + ChatMsg(nick, t, true) }
        forward(p.encode())
    }

    private fun handle(data: ByteArray) {
        val p = Packet.decode(data) ?: return
        if (!markSeen(p.id.toHex())) return
        val forMe = p.recipient.contentEquals(Packet.BROADCAST) || p.recipient.contentEquals(myId)
        if (p.type == 0 && forMe) {
            parsePayload(p.payload)?.let { (n, t) -> messages.update { it + ChatMsg(n, t, false) } }
        }
        if (p.ttl > 0 && !p.recipient.contentEquals(myId)) {
            forward(p.withTtl(p.ttl - 1).encode())
        }
    }

    private fun markSeen(key: String): Boolean = synchronized(seen) {
        if (seen.containsKey(key)) false else { seen[key] = true; true }
    }

    private fun forward(bytes: ByteArray) {
        clients.values.forEach { c -> c.queue.add(bytes); pump(c) }
    }

    @Suppress("DEPRECATION")
    private fun pump(c: Conn) {
        if (!c.busy.compareAndSet(false, true)) return
        val next = c.queue.poll()
        if (next == null) { c.busy.set(false); return }
        c.ch.value = next
        c.ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!c.gatt.writeCharacteristic(c.ch)) c.busy.set(false)
    }

    private fun buildPayload(text: String): ByteArray {
        val n = nick.toByteArray(Charsets.UTF_8).copyOf(minOf(12, nick.length))
        val maxText = MAX_PACKET - Packet.HEADER - 1 - n.size
        var t = text
        while (t.toByteArray(Charsets.UTF_8).size > maxText) t = t.dropLast(1)
        return byteArrayOf(n.size.toByte()) + n + t.toByteArray(Charsets.UTF_8)
    }

    private fun parsePayload(d: ByteArray): Pair<String, String>? {
        if (d.isEmpty()) return null
        val nl = d[0].toInt() and 0xFF
        if (d.size < 1 + nl) return null
        return String(d, 1, nl, Charsets.UTF_8) to String(d, 1 + nl, d.size - 1 - nl, Charsets.UTF_8)
    }

    // ---------- callbacks ----------
    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { status.value = "Advertise failed: $errorCode" }
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (value != null) handle(value)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            if (clients.containsKey(addr) || !connecting.add(addr)) return
            result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) { status.value = "Scan failed: $errorCode" }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(247)
            } else {
                g.close()
                clients.remove(addr)
                connecting.remove(addr)
                peers.value = clients.size
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (ch == null) { g.disconnect(); return }
            clients[g.device.address] = Conn(g, ch)
            peers.value = clients.size
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            clients[g.device.address]?.let { it.busy.set(false); pump(it) }
        }
    }
}
