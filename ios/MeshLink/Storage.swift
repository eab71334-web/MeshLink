import Foundation

/// تخزين بسيط (UserDefaults + JSON). ملاحظة: المفتاح الخاص هنا غير محمي بـ Keychain بعد.
final class Storage {
    private let d = UserDefaults.standard

    var privKey: Data? {
        get { d.data(forKey: "priv") }
        set { d.set(newValue, forKey: "priv") }
    }
    var nickname: String? {
        get { d.string(forKey: "nick") }
        set { d.set(newValue, forKey: "nick") }
    }
    var onboarded: Bool {
        get { d.bool(forKey: "onboarded") }
        set { d.set(newValue, forKey: "onboarded") }
    }

    func saveMessages(_ l: [ChatMsg]) {
        if let data = try? JSONEncoder().encode(Array(l.suffix(400))) { d.set(data, forKey: "messages") }
    }
    func loadMessages() -> [ChatMsg] {
        guard let data = d.data(forKey: "messages") else { return [] }
        return (try? JSONDecoder().decode([ChatMsg].self, from: data)) ?? []
    }

    func savePeers(_ l: [Peer]) {
        if let data = try? JSONEncoder().encode(l) { d.set(data, forKey: "peers") }
    }
    func loadPeers() -> [Peer] {
        guard let data = d.data(forKey: "peers") else { return [] }
        return (try? JSONDecoder().decode([Peer].self, from: data)) ?? []
    }

    func saveStore(_ l: [StoredPacket]) {
        if let data = try? JSONEncoder().encode(l) { d.set(data, forKey: "store") }
    }
    func loadStore() -> [StoredPacket] {
        guard let data = d.data(forKey: "store") else { return [] }
        return (try? JSONDecoder().decode([StoredPacket].self, from: data)) ?? []
    }
}
