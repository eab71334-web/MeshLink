import Foundation

let publicConvo = "public"

struct ChatMsg: Identifiable, Codable, Equatable {
    let id: String
    let convo: String   // "public" أو معرّف الشخص (hex)
    let nick: String
    let text: String
    let mine: Bool
    let time: Double    // ms
    var delivered: Bool
}

struct Peer: Identifiable, Codable, Equatable {
    let id: String
    var nick: String
    var key: Data       // المفتاح العام X25519 (32 بايت)
    var lastSeen: Double
}

struct StoredPacket: Codable {
    let id: String
    let time: Double
    let bytes: Data
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
