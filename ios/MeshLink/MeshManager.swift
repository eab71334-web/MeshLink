import Foundation
import CoreBluetooth

struct ChatMessage: Identifiable {
    let id = UUID()
    let nick: String
    let text: String
    let mine: Bool
}

private let serviceUUID = CBUUID(string: "6E4D4C01-0000-1000-8000-00805F9B34FB")
private let charUUID = CBUUID(string: "6E4D4C02-0000-1000-8000-00805F9B34FB")
private let maxPacket = 180
private let defaultTTL: UInt8 = 7

final class MeshManager: NSObject, ObservableObject,
                         CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {

    @Published var messages: [ChatMessage] = []
    @Published var peerCount = 0
    @Published var status = "Starting…"

    let myId: Data
    let nick: String

    private var central: CBCentralManager!
    private var peripheralMgr: CBPeripheralManager!
    private var known: [UUID: CBPeripheral] = [:]
    private var links: [UUID: (CBPeripheral, CBCharacteristic)] = [:]
    private var seen = Set<Data>()
    private var seenOrder: [Data] = []

    override init() {
        let key = "meshlink.id"
        let id: Data
        if let saved = UserDefaults.standard.data(forKey: key) {
            id = saved
        } else {
            id = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
            UserDefaults.standard.set(id, forKey: key)
        }
        myId = id
        nick = "User-" + id.prefix(2).map { String(format: "%02x", $0) }.joined()
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
        peripheralMgr = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: send / receive
    func send(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let p = Packet(type: 0, ttl: defaultTTL,
                       id: Data((0..<16).map { _ in UInt8.random(in: 0...255) }),
                       sender: myId, recipient: Packet.broadcast,
                       timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                       payload: buildPayload(t))
        markSeen(p.id)
        messages.append(ChatMessage(nick: nick, text: t, mine: true))
        forward(p.encode())
    }

    private func handle(_ data: Data) {
        guard var p = Packet.decode(data), markSeen(p.id) else { return }
        let forMe = p.recipient == Packet.broadcast || p.recipient == myId
        if p.type == 0, forMe, let (n, t) = parsePayload(p.payload) {
            messages.append(ChatMessage(nick: n, text: t, mine: false))
        }
        if p.ttl > 0, p.recipient != myId {
            p.ttl -= 1
            forward(p.encode())
        }
    }

    @discardableResult
    private func markSeen(_ id: Data) -> Bool {
        if seen.contains(id) { return false }
        seen.insert(id); seenOrder.append(id)
        if seenOrder.count > 2000 { seen.remove(seenOrder.removeFirst()) }
        return true
    }

    private func forward(_ data: Data) {
        for (_, link) in links {
            link.0.writeValue(data, for: link.1, type: .withResponse)
        }
    }

    private func buildPayload(_ text: String) -> Data {
        let n = Data(nick.utf8.prefix(12))
        let maxText = maxPacket - Packet.header - 1 - n.count
        var t = text
        while t.utf8.count > maxText { t.removeLast() }
        var d = Data([UInt8(n.count)])
        d.append(n); d.append(Data(t.utf8))
        return d
    }

    private func parsePayload(_ d: Data) -> (String, String)? {
        guard let first = d.first else { return nil }
        let nl = Int(first)
        guard d.count >= 1 + nl else { return nil }
        let n = String(decoding: d.subdata(in: 1..<(1 + nl)), as: UTF8.self)
        let t = String(decoding: d.subdata(in: (1 + nl)..<d.count), as: UTF8.self)
        return (n, t)
    }

    // MARK: Central (البحث والاتصال بالجيران)
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn {
            c.scanForPeripherals(withServices: [serviceUUID])
            status = "Running as \(nick)"
        } else {
            status = "Bluetooth unavailable"
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
        peerCount = links.count
        c.connect(p) // إعادة الاتصال تلقائياً
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == serviceUUID }) else { return }
        p.discoverCharacteristics([charUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard let ch = s.characteristics?.first(where: { $0.uuid == charUUID }) else { return }
        links[p.identifier] = (p, ch)
        peerCount = links.count
        // لاستقبال الرسائل القادمة من الطرف الآخر عبر نفس الاتصال
        p.setNotifyValue(true, for: ch)
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
