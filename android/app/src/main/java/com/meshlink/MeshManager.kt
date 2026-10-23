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

/**
 * قلب التطبيق: BLE (Central + Peripheral) + توجيه Flooding + تشفير + تخزين وإعادة إرسال.
 * - رسائل عامة: غير مشفرة.
 * - رسائل خاصة: X25519 + ChaCha20-Poly1305 من طرف لطرف.
 * - Store-and-Forward: كل جهاز يحتفظ بالرسائل ويسلمها لأي جار جديد.
 */
@SuppressLint("MissingPermission")
class MeshManager(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E4D4C01-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID = UUID.fromString("6E4D4C02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val MAX_PACKET = 180
        const val DEFAULT_TTL = 7
        const val STORE_MAX = 150
        const val STORE_TTL_MS = 6 * 3600 * 1000L
        const val ANNOUNCE_MS = 25_000L
    }

    private class Conn(val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic) {
        val queue = ConcurrentLinkedQueue<ByteArray>()
        val busy = AtomicBoolean(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val storage = Storage(ctx)
    private val rng = SecureRandom()

    // ---- الهوية ----
    val identity: Crypto.KeyPair = storage.privKey
        ?.let { runCatching { Crypto.fromPriv(b64d(it)) }.getOrNull() }
        ?: Crypto.generate().also { storage.privKey = b64e(it.priv) }
    val myId: ByteArray = identity.pub.copyOf(8)
    val myIdHex: String = myId.toHex()

    // ---- الحالة (تقرأها الواجهة) ----
    val nickname = MutableStateFlow(storage.nickname ?: ("User-" + myIdHex.take(4)))
    val messages = MutableStateFlow(storage.loadMessages())
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val links = MutableStateFlow(0)
    val status = MutableStateFlow("Idle")
    val unread = MutableStateFlow<Map<String, Int>>(emptyMap())

    @Volatile var currentConvo: String? = null
    @Volatile var appVisible = false
    var onIncoming: ((ChatMsg) -> Unit)? = null
    var onboarded: Boolean
        get() = storage.onboarded
        set(v) { storage.onboarded = v }

    // ---- داخلي ----
    private val peerMap = ConcurrentHashMap<String, Peer>()
    private val keyCache = ConcurrentHashMap<String, ByteArray>()
    private val store = LinkedHashMap<String, StoredPacket>()
    private val pendingPriv = ArrayList<Packet>()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 3000
    }

    private val clients = ConcurrentHashMap<String, Conn>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val discovering = ConcurrentHashMap.newKeySet<String>()
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val notifyQueue = ConcurrentLinkedQueue<Pair<BluetoothDevice, ByteArray>>()
    private val notifyBusy = AtomicBoolean(false)

    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var adv: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var started = false

    init {
        storage.loadPeers().forEach { peerMap[it.id] = it }
        publishPeers()
        storage.loadStore().forEach { store[it.id] = it }
    }

    private fun now() = System.currentTimeMillis()
    private fun rand16() = ByteArray(16).also { rng.nextBytes(it) }
    private fun publishPeers() { peers.value = peerMap.values.sortedByDescending { it.lastSeen } }
    private fun updateLinks() { links.value = clients.size + subscribers.size }

    // ---------- تشغيل / إيقاف ----------
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

        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 1) بفلتر (يستمر والشاشة مطفأة)
        scanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            settings, scanCbFiltered
        )
        // 2) بدون فلتر (للهواتف اللي فلترها معطل) والفحص يدوياً
        scanner?.startScan(null, settings, scanCbOpen)

        status.value = "Running as ${nickname.value}"
        handler.postDelayed(announceTask, 2000)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(announceTask)
        runCatching { scanner?.stopScan(scanCbFiltered) }
        runCatching { scanner?.stopScan(scanCbOpen) }
        runCatching { adv?.stopAdvertising(advCb) }
        clients.values.forEach { runCatching { it.gatt.close() } }
        clients.clear()
        connecting.clear()
        discovering.clear()
        subscribers.clear()
        runCatching { server?.close() }
        links.value = 0
    }

    private val announceTask = object : Runnable {
        override fun run() {
            if (!started) return
            announce()
            handler.postDelayed(this, ANNOUNCE_MS)
        }
    }

    // ---------- الواجهة العامة ----------
    fun setNickname(name: String) {
        val n = name.trim().take(16)
        if (n.isEmpty()) return
        nickname.value = n
        storage.nickname = n
        announce()
    }

    fun markRead(convo: String) { unread.update { it - convo } }

    fun sendPublic(text: String) {
        val nb = fit(nickname.value, 16).toByteArray(Charsets.UTF_8)
        val t = fit(text.trim(), MAX_PACKET - Packet.HEADER - 1 - nb.size)
        if (t.isEmpty()) return
        val payload = byteArrayOf(nb.size.toByte()) + nb + t.toByteArray(Charsets.UTF_8)
        val p = newPacket(PType.PUBLIC, Packet.BROADCAST, payload)
        addMessage(ChatMsg(p.id.toHex(), PUBLIC, nickname.value, t, true, now()))
        storeAdd(p)
        forward(p.encode())
    }

    fun sendPrivate(peerHex: String, text: String) {
        val peer = peerMap[peerHex] ?: return
        val t = fit(text.trim(), MAX_PACKET - Packet.HEADER - 28)
        if (t.isEmpty()) return
        val peerId = peer.key.copyOf(8)
        val payload = Crypto.seal(keyFor(peer), t.toByteArray(Charsets.UTF_8), myId + peerId)
        val p = newPacket(PType.PRIVATE, peerId, payload)
        addMessage(ChatMsg(p.id.toHex(), peerHex, nickname.value, t, true, now(), false))
        storeAdd(p)
        forward(p.encode())
    }

    // ---------- بناء الرسائل ----------
    private fun fit(s: String, maxBytes: Int): String {
        var t = s
        while (t.toByteArray(Charsets.UTF_8).size > maxBytes && t.isNotEmpty()) t = t.dropLast(1)
        return t
    }

    private fun newPacket(type: Int, recipient: ByteArray, payload: ByteArray): Packet {
        val p = Packet(type, DEFAULT_TTL, rand16(), myId, recipient, now(), payload)
        markSeen(p.id.toHex())
        return p
    }

    private fun keyFor(peer: Peer): ByteArray =
        keyCache.getOrPut(peer.id) { Crypto.sharedKey(identity.priv, peer.key) }

    fun announce() {
        val nb = fit(nickname.value, 16).toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(nb.size.toByte()) + nb + identity.pub
        val p = newPacket(PType.ANNOUNCE, Packet.BROADCAST, payload)
        forward(p.encode())
    }

    private fun sendAck(orig: Packet) {
        val a = Packet(PType.ACK, DEFAULT_TTL, rand16(), myId, orig.sender, now(), orig.id)
        markSeen(a.id.toHex())
        forward(a.encode())
    }

    // ---------- الاستقبال ----------
    private fun handle(data: ByteArray) {
        val p = Packet.decode(data) ?: return
        if (!markSeen(p.id.toHex())) return
        if (p.sender.contentEquals(myId)) return
        val forMe = p.recipient.contentEquals(Packet.BROADCAST) || p.recipient.contentEquals(myId)

        when (p.type) {
            PType.PUBLIC -> if (forMe) handlePublic(p)
            PType.ANNOUNCE -> handleAnnounce(p)
            PType.PRIVATE -> if (p.recipient.contentEquals(myId)) processPrivate(p)
            PType.ACK -> handleAck(p)
        }

        if (p.ttl > 0 && !p.recipient.contentEquals(myId)) {
            val fwd = p.withTtl(p.ttl - 1)
            if (p.type == PType.PUBLIC || p.type == PType.PRIVATE) storeAdd(fwd)
            forward(fwd.encode())
        }
    }

    private fun handlePublic(p: Packet) {
        val d = p.payload
        if (d.isEmpty()) return
        val nl = d[0].toInt() and 0xFF
        if (d.size < 1 + nl) return
        val n = String(d, 1, nl, Charsets.UTF_8)
        val t = String(d, 1 + nl, d.size - 1 - nl, Charsets.UTF_8)
        addMessage(ChatMsg(p.id.toHex(), PUBLIC, n, t, false, now()))
    }

    private fun handleAnnounce(p: Packet) {
        val d = p.payload
        if (d.isEmpty()) return
        val nl = d[0].toInt() and 0xFF
        if (d.size != 1 + nl + 32) return
        val nick = String(d, 1, nl, Charsets.UTF_8)
        val key = d.copyOfRange(1 + nl, d.size)
        if (!key.copyOf(8).contentEquals(p.sender)) return
        val id = p.sender.toHex()
        val old = peerMap[id]
        peerMap[id] = Peer(id, nick, key, now())
        val changed = old == null || old.nick != nick || !old.key.contentEquals(key)
        if (changed) {
            keyCache.remove(id)
            storage.savePeers(peerMap.values.toList())
        }
        publishPeers()
        // رسائل خاصة وصلت قبل ما نعرف مرسلها
        val retry = synchronized(pendingPriv) {
            val r = pendingPriv.filter { it.sender.contentEquals(p.sender) }
            pendingPriv.removeAll { it.sender.contentEquals(p.sender) }
            r
        }
        retry.forEach { processPrivate(it) }
    }

    private fun processPrivate(p: Packet) {
        val sid = p.sender.toHex()
        val peer = peerMap[sid]
        if (peer == null) {
            synchronized(pendingPriv) { if (pendingPriv.size < 20) pendingPriv.add(p) }
            return
        }
        val plain = Crypto.open(keyFor(peer), p.payload, p.sender + p.recipient) ?: return
        addMessage(ChatMsg(p.id.toHex(), sid, peer.nick, String(plain, Charsets.UTF_8), false, now()))
        sendAck(p)
    }

    private fun handleAck(p: Packet) {
        if (p.payload.size != 16) return
        val id = p.payload.toHex()
        synchronized(store) { store.remove(id) }
        persistStore()
        if (p.recipient.contentEquals(myId)) {
            messages.update { l -> l.map { if (it.id == id && it.mine) it.copy(delivered = true) else it } }
            storage.saveMessages(messages.value)
        }
    }

    private fun markSeen(key: String): Boolean = synchronized(seen) {
        if (seen.containsKey(key)) false else { seen[key] = true; true }
    }

    private fun addMessage(m: ChatMsg) {
        var added = false
        messages.update { list ->
            if (list.any { it.id == m.id }) { added = false; list } else { added = true; list + m }
        }
        if (!added) return
        storage.saveMessages(messages.value)
        if (!m.mine) {
            if (currentConvo != m.convo) {
                unread.update { it + (m.convo to ((it[m.convo] ?: 0) + 1)) }
            }
            onIncoming?.invoke(m)
        }
    }

    // ---------- Store-and-Forward ----------
    private fun storeAdd(p: Packet) {
        synchronized(store) {
            store[p.id.toHex()] = StoredPacket(p.id.toHex(), now(), p.encode())
            while (store.size > STORE_MAX) {
                val first = store.keys.iterator().next()
                store.remove(first)
            }
        }
        persistStore()
    }

    private fun persistStore() {
        val snap = synchronized(store) { store.values.toList() }
        storage.saveStore(snap)
    }

    private fun storeSnapshot(): List<ByteArray> = synchronized(store) {
        val cutoff = now() - STORE_TTL_MS
        store.values.removeAll { it.time < cutoff }
        store.values.map { it.bytes }
    }

    private fun flushTo(c: Conn) {
        storeSnapshot().forEach { c.queue.add(it) }
        pump(c)
    }

    private fun flushToSub(d: BluetoothDevice) {
        storeSnapshot().forEach { notifyQueue.add(d to it) }
        pumpNotify()
    }

    // ---------- الإرسال عبر BLE ----------
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

    // ---------- callbacks ----------
    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { status.value = "Advertise failed: $errorCode" }
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device.address)
                updateLinks()
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
                if (on) {
                    subscribers[device.address] = device
                    handler.postDelayed({ announce(); flushToSub(device) }, 500)
                } else {
                    subscribers.remove(device.address)
                }
                updateLinks()
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, st: Int) {
            notifyBusy.set(false)
            pumpNotify()
        }
    }

    private fun onCandidate(result: ScanResult, verify: Boolean) {
        if (verify) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (uuids.none { it.uuid == SERVICE_UUID }) return
        }
        val addr = result.device.address
        if (clients.containsKey(addr) || !connecting.add(addr)) return
        status.value = "Found $addr, connecting…"
        result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val scanCbFiltered = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onCandidate(result, false)
        override fun onScanFailed(errorCode: Int) { status.value = "Scan failed: $errorCode" }
    }

    private val scanCbOpen = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onCandidate(result, true)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                status.value = "Connected $addr, discovering…"
                g.requestMtu(247)
                handler.postDelayed({
                    if (connecting.contains(addr) && discovering.add(addr)) g.discoverServices()
                }, 1500)
            } else {
                if (st != BluetoothGatt.GATT_SUCCESS) status.value = "Connection to $addr failed ($st)"
                g.close()
                clients.remove(addr)
                connecting.remove(addr)
                discovering.remove(addr)
                updateLinks()
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
            val conn = Conn(g, ch)
            clients[g.device.address] = conn
            status.value = "Linked with ${g.device.address}"
            updateLinks()
            handler.postDelayed({ announce(); flushTo(conn) }, 500)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            clients[g.device.address]?.let { it.busy.set(false); pump(it) }
        }
    }
}
