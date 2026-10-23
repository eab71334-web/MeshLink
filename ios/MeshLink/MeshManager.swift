import Foundation
import CoreBluetooth
import CryptoKit
import UserNotifications

private let serviceUUID = CBUUID(string: "6E4D4C01-0000-1000-8000-00805F9B34FB")
private let charUUID = CBUUID(string: "6E4D4C02-0000-1000-8000-00805F9B34FB")
private let maxPacket = 180
private let defaultTTL: UInt8 = 7
private let storeMax = 150
private let storeTTL: Double = 6 * 3600 * 1000
private let announceEvery: TimeInterval = 25

final class MeshManager: NSObject, ObservableObject,
                         CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {

    // MARK: حالة الواجهة
    @Published var messages: [ChatMsg] = []
    @Published var peers: [Peer] = []
    @Published var linkCount = 0
    @Published var status = "Starting…"
    @Published var nickname = ""
    @Published var unread: [String: Int] = [:]

    var currentConvo: String?
    var appActive = true
    var onboarded: Bool {
        get { storage.onboarded }
        set { storage.onboarded = newValue }
    }

    let identity: MCrypto.KeyPair
    let myId: Data
    var myIdHex: String { myId.hex }

    // MARK: داخلي
    private let storage: Storage
    private var central: CBCentralManager?
    private var peripheralMgr: CBPeripheralManager?
    private var known: [UUID: CBPeripheral] = [:]
    private var links: [UUID: (CBPeripheral, CBCharacteristic)] = [:]
    private var seen = Set<Data>()
    private var seenOrder: [Data] = []
    private var peerMap: [String: Peer] = [:]
    private var keyCache: [String: SymmetricKey] = [:]
    private var store: [StoredPacket] = []
    private var pendingPriv: [Packet] = []
    private var timer: Timer?
    private var started = false

    private var nowMs: Double { Date().timeIntervalSince1970 * 1000 }

    override init() {
        let st = Storage()
        let kp: MCrypto.KeyPair
        if let d = st.privKey, let k = MCrypto.fromPriv(d) {
            kp = k
        } else {
            kp = MCrypto.generate()
            st.privKey = kp.priv
        }
        storage = st
        identity = kp
        myId = Data(kp.pub.prefix(8))
        super.init()
        nickname = st.nickname ?? ("User-" + myId.prefix(2).hex)
        messages = st.loadMessages()
        for p in st.loadPeers() { peerMap[p.id] = p }
        publishPeers()
        store = st.loadStore()
    }

    // MARK: تشغيل
    func start() {
        guard !started else { return }
        started = true
        central = CBCentralManager(delegate: self, queue: nil,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "meshlink.central"])
        peripheralMgr = CBPeripheralManager(delegate: self, queue: nil)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        timer = Timer.scheduledTimer(withTimeInterval: announceEvery, repeats: true) { [weak self] _ in
            self?.announce()
        }
    }

    // MARK: الواجهة العامة
    func setNickname(_ name: String) {
        let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(16))
        guard !n.isEmpty else { return }
        nickname = n
        storage.nickname = n
        announce()
    }

    func markRead(_ convo: String) { unread[convo] = nil }

    func sendPublic(_ text: String) {
        let nb = Data(fit(nickname, 16).utf8)
        let t = fit(text.trimmingCharacters(in: .whitespacesAndNewlines), maxPacket - Packet.header - 1 - nb.count)
        guard !t.isEmpty else { return }
        var payload = Data([UInt8(nb.count)])
        payload.append(nb); payload.append(Data(t.utf8))
        let p = newPacket(PType.publicMsg, to: Packet.broadcast, payload: payload)
        add(ChatMsg(id: p.id.hex, convo: publicConvo, nick: nickname, text: t, mine: true, time: nowMs, delivered: false))
        storeAdd(p)
        forward(p.encode())
    }

    func sendPrivate(to peerHex: String, _ text: String) {
        guard let peer = peerMap[peerHex], let key = keyFor(peer) else { return }
        let t = fit(text.trimmingCharacters(in: .whitespacesAndNewlines), maxPacket - Packet.header - 28)
        guard !t.isEmpty else { return }
        let peerId = Data(peer.key.prefix(8))
        guard let payload = MCrypto.seal(key: key, plain: Data(t.utf8), aad: myId + peerId) else { return }
        let p = newPacket(PType.privateMsg, to: peerId, payload: payload)
        add(ChatMsg(id: p.id.hex, convo: peerHex, nick: nickname, text: t, mine: true, time: nowMs, delivered: false))
        storeAdd(p)
        forward(p.encode())
    }

    // MARK: بناء الرسائل
    private func fit(_ s: String, _ maxBytes: Int) -> String {
        var t = s
        while t.utf8.count > maxBytes && !t.isEmpty { t.removeLast() }
        return t
    }

    private func randomData(_ n: Int) -> Data { Data((0..<n).map { _ in UInt8.random(in: 0...255) }) }

    private func newPacket(_ type: UInt8, to recipient: Data, payload: Data) -> Packet {
        let p = Packet(type: type, ttl: defaultTTL, id: randomData(16), sender: myId,
                       recipient: recipient, timestamp: UInt64(nowMs), payload: payload)
        _ = markSeen(p.id)
        return p
    }

    private func keyFor(_ peer: Peer) -> SymmetricKey? {
        if let k = keyCache[peer.id] { return k }
        guard let k = MCrypto.sharedKey(priv: identity.priv, theirPub: peer.key) else { return nil }
        keyCache[peer.id] = k
        return k
    }

    func announce() {
        let nb = Data(fit(nickname, 16).utf8)
        var payload = Data([UInt8(nb.count)])
        payload.append(nb); payload.append(identity.pub)
        let p = newPacket(PType.announce, to: Packet.broadcast, payload: payload)
        forward(p.encode())
    }

    private func sendAck(_ orig: Packet) {
        let a = Packet(type: PType.ack, ttl: defaultTTL, id: randomData(16), sender: myId,
                       recipient: orig.sender, timestamp: UInt64(nowMs), payload: orig.id)
        _ = markSeen(a.id)
        forward(a.encode())
    }

    // MARK: الاستقبال
    private func handle(_ data: Data) {
        guard let p = Packet.decode(data), markSeen(p.id) else { return }
        if p.sender == myId { return }
        let forMe = p.recipient == Packet.broadcast || p.recipient == myId

        switch p.type {
        case PType.publicMsg: if forMe { handlePublic(p) }
        case PType.announce: handleAnnounce(p)
        case PType.privateMsg: if p.recipient == myId { processPrivate(p) }
        case PType.ack: handleAck(p)
        default: break
        }

        if p.ttl > 0, p.recipient != myId {
            var f = p
            f.ttl -= 1
            if p.type == PType.publicMsg || p.type == PType.privateMsg { storeAdd(f) }
            forward(f.encode())
        }
    }

    private func handlePublic(_ p: Packet) {
        let d = p.payload
        guard let first = d.first else { return }
        let nl = Int(first)
        guard d.count >= 1 + nl else { return }
        let n = String(decoding: d.subdata(in: 1..<(1 + nl)), as: UTF8.self)
        let t = String(decoding: d.subdata(in: (1 + nl)..<d.count), as: UTF8.self)
        add(ChatMsg(id: p.id.hex, convo: publicConvo, nick: n, text: t, mine: false, time: nowMs, delivered: false))
    }

    private func handleAnnounce(_ p: Packet) {
        let d = p.payload
        guard let first = d.first else { return }
        let nl = Int(first)
        guard d.count == 1 + nl + 32 else { return }
        let nick = String(decoding: d.subdata(in: 1..<(1 + nl)), as: UTF8.self)
        let key = d.subdata(in: (1 + nl)..<d.count)
        guard Data(key.prefix(8)) == p.sender else { return }
        let id = p.sender.hex
        let old = peerMap[id]
        peerMap[id] = Peer(id: id, nick: nick, key: key, lastSeen: nowMs)
        if old == nil || old?.nick != nick || old?.key != key {
            keyCache[id] = nil
            storage.savePeers(Array(peerMap.values))
        }
        publishPeers()
        let retry = pendingPriv.filter { $0.sender == p.sender }
        pendingPriv.removeAll { $0.sender == p.sender }
        for r in retry { processPrivate(r) }
    }

    private func processPrivate(_ p: Packet) {
        let sid = p.sender.hex
        guard let peer = peerMap[sid] else {
            if pendingPriv.count < 20 { pendingPriv.append(p) }
            return
        }
        guard let key = keyFor(peer),
              let plain = MCrypto.open(key: key, data: p.payload, aad: p.sender + p.recipient) else { return }
        add(ChatMsg(id: p.id.hex, convo: sid, nick: peer.nick,
                    text: String(decoding: plain, as: UTF8.self), mine: false, time: nowMs, delivered: false))
        sendAck(p)
    }

    private func handleAck(_ p: Packet) {
        guard p.payload.count == 16 else { return }
        let id = p.payload.hex
        store.removeAll { $0.id == id }
        storage.saveStore(store)
        if p.recipient == myId, let i = messages.firstIndex(where: { $0.id == id && $0.mine }) {
            messages[i].delivered = true
            storage.saveMessages(messages)
        }
    }

    private func markSeen(_ id: Data) -> Bool {
        if seen.contains(id) { return false }
        seen.insert(id)
        seenOrder.append(id)
        if seenOrder.count > 3000 { seen.remove(seenOrder.removeFirst()) }
        return true
    }

    private func publishPeers() {
        peers = peerMap.values.sorted { $0.lastSeen > $1.lastSeen }
    }

    private func add(_ m: ChatMsg) {
        guard !messages.contains(where: { $0.id == m.id }) else { return }
        messages.append(m)
        storage.saveMessages(messages)
        if !m.mine {
            if currentConvo != m.convo { unread[m.convo, default: 0] += 1 }
            if !appActive { notify(m) }
        }
    }

    private func notify(_ m: ChatMsg) {
        let c = UNMutableNotificationContent()
        c.title = m.convo == publicConvo ? "Public · \(m.nick)" : m.nick
        c.body = m.text
        c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: m.id, content: c, trigger: nil))
    }

    // MARK: Store-and-Forward
    private func storeAdd(_ p: Packet) {
        store.removeAll { $0.id == p.id.hex }
        store.append(StoredPacket(id: p.id.hex, time: nowMs, bytes: p.encode()))
        if store.count > storeMax { store.removeFirst(store.count - storeMax) }
        storage.saveStore(store)
    }

    private func flush(to link: (CBPeripheral, CBCharacteristic)) {
        let cutoff = nowMs - storeTTL
        store.removeAll { $0.time < cutoff }
        for s in store { link.0.writeValue(s.bytes, for: link.1, type: .withResponse) }
    }

    private func forward(_ data: Data) {
        for (_, l) in links { l.0.writeValue(data, for: l.1, type: .withResponse) }
    }

    // MARK: Central (البحث والاتصال بالجيران)
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn {
            c.scanForPeripherals(withServices: [serviceUUID])
            status = "Running as \(nickname)"
        } else {
            status = "Bluetooth unavailable"
        }
    }

    func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for p in ps {
                known[p.identifier] = p
                p.delegate = self
                if p.state == .connected { p.discoverServices([serviceUUID]) } else { c.connect(p) }
            }
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard known[p.identifier] == nil else { return }
        known[p.identifier] = p
        c.connect(p)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.delegate = self
        p.discoverServices([serviceUUID])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        known[p.identifier] = nil
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        links[p.identifier] = nil
        linkCount = links.count
        c.connect(p) // إعادة الاتصال تلقائياً
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == serviceUUID }) else { return }
        p.discoverCharacteristics([charUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard let ch = s.characteristics?.first(where: { $0.uuid == charUUID }) else { return }
        let link = (p, ch)
        links[p.identifier] = link
        linkCount = links.count
        p.setNotifyValue(true, for: ch)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.announce()
            self?.flush(to: link)
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        if let v = ch.value { handle(v) }
    }

    // MARK: Peripheral (الإعلان واستقبال الكتابة)
    func peripheralManagerDidUpdateState(_ m: CBPeripheralManager) {
        guard m.state == .poweredOn else { return }
        let ch = CBMutableCharacteristic(type: charUUID,
                                         properties: [.write, .writeWithoutResponse],
                                         value: nil, permissions: [.writeable])
        let svc = CBMutableService(type: serviceUUID, primary: true)
        svc.characteristics = [ch]
        m.add(svc)
    }

    func peripheralManager(_ m: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        m.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func peripheralManager(_ m: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests { if let v = r.value { handle(v) } }
        if let first = requests.first { m.respond(to: first, withResult: .success) }
    }
}
