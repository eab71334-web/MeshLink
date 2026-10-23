package com.meshlink

const val PUBLIC = "public"

data class ChatMsg(
    val id: String,
    val convo: String,   // "public" أو معرّف الشخص (hex)
    val nick: String,
    val text: String,
    val mine: Boolean,
    val time: Long,
    val delivered: Boolean = false
)

class Peer(
    val id: String,       // hex لأول 8 بايت من المفتاح العام
    val nick: String,
    val key: ByteArray,   // المفتاح العام X25519 (32 بايت)
    val lastSeen: Long
)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
