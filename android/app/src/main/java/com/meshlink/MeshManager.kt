package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * الإرسال:
 *  - للأجهزة التي اتصلنا بها نحن: كتابة (write) على الـ characteristic.
 *  - للأجهزة التي اتصلت بنا (مثل iPhone): إشعار (notify) عبر نفس الاتصال.
 * التوجيه = Flooding مع TTL ومنع التكرار.
 */
@SuppressLint("MissingPermission")
class MeshManager(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E4D4C01-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID = UUID.fromString("6E4D4C02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val MAX_PACKET = 180
        const val DEFAULT_TTL = 7
    }

    private class Conn(val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic) {
        val queue = ConcurrentLinkedQueue<ByteArray>()
        val busy = AtomicBoolean(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE)
    val myId: ByteArray = loadId()
    val nick: String = "User-" + myId.copyOf(2).toHex()

    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val peers = MutableStateFlow(0)
    val status = MutableStateFlow("Idle")

    // اتصالات نحن بدأناها (Central)
    private val clients = ConcurrentHashMap<String, Conn>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val discovering = ConcurrentHashMap.newKeySet<String>()
    // أجهزة اتصلت بنا واشتركت في الإشعارات (مثل iPhone)
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val notifyQueue = ConcurrentLinkedQueue<Pair<BluetoothDevice, ByteArray>>()
    private val notifyBusy = AtomicBoolean(false)

    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }

    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
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

    private fun updatePeers() { peers.value = clients.size + subscribers.size }

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
        val ch = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(ch)
        serverChar = ch
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

        // بدون فلتر في العتاد (بعض الهواتف فيها خلل)، والفلترة تتم يدوياً في onScanResult
        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(
            null,
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
        discovering.clear()
        subscribers.clear()
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
        subscribers.values.forEach { d -> notifyQueue.add(d to bytes) }
        pumpNotify()
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

    @Suppress("DEPRECATION")
    private fun pumpNotify() {
        val ch = serverChar ?: return
        if (!notifyBusy.compareAndSet(false, true)) return
        val next = notifyQueue.poll()
        if (next == null) { notifyBusy.set(false); return }
        ch.value = next.second
        val ok = server?.notifyCharacteristicChanged(next.first, ch, false) ?: false
        if (!ok) { notifyBusy.set(false); pumpNotify() }
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
        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device.address)
                updatePeers()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (value != null) handle(value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
                if (on) subscribers[device.address] = device else subscribers.remove(device.address)
                updatePeers()
                status.value = if (on) "Peer subscribed: ${device.address}" else status.value
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, st: Int) {
            notifyBusy.set(false)
            pumpNotify()
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (uuids.none { it.uuid == SERVICE_UUID }) return
            val addr = result.device.address
            if (clients.containsKey(addr) || !connecting.add(addr)) return
            status.value = "Found $addr, connecting…"
            result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) { status.value = "Scan failed: $errorCode" }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                status.value = "Connected $addr, discovering…"
                g.requestMtu(247)
                // احتياط: بعض الأجهزة ما ترجع onMtuChanged
                handler.postDelayed({
                    if (connecting.contains(addr) && discovering.add(addr)) g.discoverServices()
                }, 1500)
            } else {
                if (st != BluetoothGatt.GATT_SUCCESS) status.value = "Connection to $addr failed ($st)"
                g.close()
                clients.remove(addr)
                connecting.remove(addr)
                discovering.remove(addr)
                updatePeers()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            if (discovering.add(g.device.address)) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (st != BluetoothGatt.GATT_SUCCESS || ch == null) {
                status.value = "Service not found on ${g.device.address} ($st)"
                g.disconnect()
                return
            }
            clients[g.device.address] = Conn(g, ch)
            status.value = "Linked with ${g.device.address}"
            updatePeers()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            clients[g.device.address]?.let { it.busy.set(false); pump(it) }
        }
    }
}
