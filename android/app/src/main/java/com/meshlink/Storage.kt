package com.meshlink

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

class StoredPacket(val id: String, val time: Long, val bytes: ByteArray)

/** تخزين بسيط (SharedPreferences + JSON). ملاحظة: المفتاح الخاص هنا غير محمي بـ Keystore بعد. */
class Storage(ctx: Context) {
    private val sp = ctx.getSharedPreferences("meshlink", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    var privKey: String?
        get() = sp.getString("priv", null)
        set(v) { sp.edit().putString("priv", v).apply() }

    var nickname: String?
        get() = sp.getString("nick", null)
        set(v) { sp.edit().putString("nick", v).apply() }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }

    private fun putAsync(key: String, value: String) {
        io.execute { sp.edit().putString(key, value).apply() }
    }

    fun saveMessages(list: List<ChatMsg>) {
        val arr = JSONArray()
        list.takeLast(400).forEach {
            arr.put(
                JSONObject().put("id", it.id).put("c", it.convo).put("n", it.nick)
                    .put("t", it.text).put("m", it.mine).put("ts", it.time).put("d", it.delivered)
            )
        }
        putAsync("messages", arr.toString())
    }

    fun loadMessages(): List<ChatMsg> = runCatching {
        val arr = JSONArray(sp.getString("messages", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ChatMsg(
                o.getString("id"), o.getString("c"), o.getString("n"), o.getString("t"),
                o.getBoolean("m"), o.getLong("ts"), o.optBoolean("d", false)
            )
        }
    }.getOrDefault(emptyList())

    fun savePeers(list: List<Peer>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("n", it.nick).put("k", b64e(it.key)).put("ts", it.lastSeen))
        }
        putAsync("peers", arr.toString())
    }

    fun loadPeers(): List<Peer> = runCatching {
        val arr = JSONArray(sp.getString("peers", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Peer(o.getString("id"), o.getString("n"), b64d(o.getString("k")), o.getLong("ts"))
        }
    }.getOrDefault(emptyList())

    fun saveStore(list: List<StoredPacket>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("ts", it.time).put("b", b64e(it.bytes))) }
        putAsync("store", arr.toString())
    }

    fun loadStore(): List<StoredPacket> = runCatching {
        val arr = JSONArray(sp.getString("store", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StoredPacket(o.getString("id"), o.getLong("ts"), b64d(o.getString("b")))
        }
    }.getOrDefault(emptyList())
}
