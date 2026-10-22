package com.meshlink

import java.nio.ByteBuffer

/** شكل الرسالة حسب docs/PROTOCOL.md (الترويسة 45 بايت). */
class Packet(
    val type: Int,
    val ttl: Int,
    val id: ByteArray,        // 16
    val sender: ByteArray,    // 8
    val recipient: ByteArray, // 8
    val timestamp: Long,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size)
        b.put(1)
        b.put(type.toByte())
        b.put(ttl.toByte())
        b.put(id)
        b.put(sender)
        b.put(recipient)
        b.putLong(timestamp)
        b.putShort(payload.size.toShort())
        b.put(payload)
        return b.array()
    }

    fun withTtl(newTtl: Int) = Packet(type, newTtl, id, sender, recipient, timestamp, payload)

    companion object {
        const val HEADER = 45
        val BROADCAST = ByteArray(8) { 0xFF.toByte() }

        fun decode(d: ByteArray): Packet? {
            if (d.size < HEADER || d[0].toInt() != 1) return null
            val b = ByteBuffer.wrap(d)
            b.get()
            val type = b.get().toInt() and 0xFF
            val ttl = b.get().toInt() and 0xFF
            val id = ByteArray(16).also { b.get(it) }
            val sender = ByteArray(8).also { b.get(it) }
            val recipient = ByteArray(8).also { b.get(it) }
            val ts = b.long
            val len = b.short.toInt() and 0xFFFF
            if (b.remaining() < len) return null
            val payload = ByteArray(len).also { b.get(it) }
            return Packet(type, ttl, id, sender, recipient, ts, payload)
        }
    }
}
