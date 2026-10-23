import Foundation

enum PType {
    static let publicMsg: UInt8 = 0   // رسالة عامة (نص غير مشفر)
    static let ack: UInt8 = 1         // تأكيد استلام
    static let announce: UInt8 = 2    // الاسم + المفتاح العام
    static let privateMsg: UInt8 = 3  // رسالة خاصة مشفرة
}

/// نفس شكل الرسالة في docs/PROTOCOL.md (الترويسة 45 بايت)
struct Packet {
    static let header = 45
    static let broadcast = Data(repeating: 0xFF, count: 8)

    var type: UInt8
    var ttl: UInt8
    let id: Data         // 16
    let sender: Data     // 8
    let recipient: Data  // 8
    let timestamp: UInt64
    let payload: Data

    func encode() -> Data {
        var d = Data([1, type, ttl])
        d.append(id); d.append(sender); d.append(recipient)
        withUnsafeBytes(of: timestamp.bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt16(payload.count).bigEndian) { d.append(contentsOf: $0) }
        d.append(payload)
        return d
    }

    static func decode(_ data: Data) -> Packet? {
        let b = [UInt8](data)
        guard b.count >= header, b[0] == 1 else { return nil }
        let ts = b[35..<43].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let len = Int(b[43]) << 8 | Int(b[44])
        guard b.count >= header + len else { return nil }
        return Packet(type: b[1], ttl: b[2],
                      id: Data(b[3..<19]), sender: Data(b[19..<27]), recipient: Data(b[27..<35]),
                      timestamp: ts, payload: Data(b[45..<(45 + len)]))
    }
}
