package com.meshlink

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/** X25519 + HKDF-SHA256 + ChaCha20-Poly1305 (متوافق مع CryptoKit في iOS). */
object Crypto {
    private val SALT = "MeshLink-v1".toByteArray()
    private val INFO = "MeshLink private message".toByteArray()
    private val rng = SecureRandom()

    class KeyPair(val priv: ByteArray, val pub: ByteArray)

    fun generate(): KeyPair {
        val p = X25519PrivateKeyParameters(rng)
        return KeyPair(p.encoded, p.generatePublicKey().encoded)
    }

    fun fromPriv(priv: ByteArray): KeyPair {
        val p = X25519PrivateKeyParameters(priv, 0)
        return KeyPair(priv, p.generatePublicKey().encoded)
    }

    fun sharedKey(myPriv: ByteArray, theirPub: ByteArray): ByteArray {
        val agr = X25519Agreement()
        agr.init(X25519PrivateKeyParameters(myPriv, 0))
        val secret = ByteArray(agr.agreementSize)
        agr.calculateAgreement(X25519PublicKeyParameters(theirPub, 0), secret, 0)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(secret, SALT, INFO))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, 32)
        return key
    }

    /** الناتج: nonce(12) + ciphertext + tag(16) */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        var n = c.processBytes(plain, 0, plain.size, out, 0)
        n += c.doFinal(out, n)
        return nonce + out.copyOf(n)
    }

    fun open(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray? {
        if (data.size < 12 + 16) return null
        return try {
            val nonce = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(ct.size))
            var n = c.processBytes(ct, 0, ct.size, out, 0)
            n += c.doFinal(out, n)
            out.copyOf(n)
        } catch (e: Exception) {
            null
        }
    }
}
