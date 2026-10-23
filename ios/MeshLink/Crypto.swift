import Foundation
import CryptoKit

/// X25519 + HKDF-SHA256 + ChaCha20-Poly1305 (متوافق مع BouncyCastle في أندرويد)
enum MCrypto {
    static let salt = Data("MeshLink-v1".utf8)
    static let info = Data("MeshLink private message".utf8)

    struct KeyPair { let priv: Data; let pub: Data }

    static func generate() -> KeyPair {
        let k = Curve25519.KeyAgreement.PrivateKey()
        return KeyPair(priv: k.rawRepresentation, pub: k.publicKey.rawRepresentation)
    }

    static func fromPriv(_ d: Data) -> KeyPair? {
        guard let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: d) else { return nil }
        return KeyPair(priv: d, pub: k.publicKey.rawRepresentation)
    }

    static func sharedKey(priv: Data, theirPub: Data) -> SymmetricKey? {
        guard let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv),
              let pk = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPub),
              let secret = try? k.sharedSecretFromKeyAgreement(with: pk) else { return nil }
        return secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: info, outputByteCount: 32)
    }

    /// الناتج: nonce(12) + ciphertext + tag(16)
    static func seal(key: SymmetricKey, plain: Data, aad: Data) -> Data? {
        let nonceBytes = Data((0..<12).map { _ in UInt8.random(in: 0...255) })
        guard let nonce = try? ChaChaPoly.Nonce(data: nonceBytes),
              let box = try? ChaChaPoly.seal(plain, using: key, nonce: nonce, authenticating: aad) else { return nil }
        return nonceBytes + box.ciphertext + box.tag
    }

    static func open(key: SymmetricKey, data: Data, aad: Data) -> Data? {
        guard data.count >= 28 else { return nil }
        let b = [UInt8](data)
        let nonceD = Data(b[0..<12])
        let ct = Data(b[12..<(b.count - 16)])
        let tag = Data(b[(b.count - 16)...])
        guard let nonce = try? ChaChaPoly.Nonce(data: nonceD),
              let box = try? ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ct, tag: tag),
              let plain = try? ChaChaPoly.open(box, using: key, authenticating: aad) else { return nil }
        return plain
    }
}
