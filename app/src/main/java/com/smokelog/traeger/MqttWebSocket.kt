package com.smokelog.traeger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/**
 * Just enough MQTT 3.1.1 over WebSocket for AWS IoT: CONNECT, SUBSCRIBE, receive PUBLISH
 * (QoS 0/1, with PUBACK) and PINGREQ. Same approach as the iPhone app.
 *
 * Used instead of Paho because Paho's WebSocket handshake sends `Host: name:443`, which
 * breaks the signature on AWS IoT's pre-signed URL, so the server refuses the upgrade.
 * OkHttp sends the plain host name.
 */
class MqttWebSocket(
    private val http: OkHttpClient,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    private var ws: WebSocket? = null
    private val buffer = ByteArrayOutputStream()
    private var connAck = CompletableDeferred<Int>()
    private var subAck = CompletableDeferred<Int>()
    @Volatile private var closed = false

    /** Opens the socket, sends CONNECT and waits for CONNACK. */
    suspend fun connect(url: String, clientId: String, keepAliveSec: Int = 60) {
        val opened = CompletableDeferred<Unit>()
        val req = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { receive(bytes.toByteArray()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val err = if (response != null) Exception("Traeger live feed refused: HTTP ${response.code} ${response.message}".trim(), t) else t
                opened.completeExceptionally(err); connAck.completeExceptionally(err); subAck.completeExceptionally(err)
                finish(err)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val err = Exception("Live feed closed ($code${if (reason.isNotEmpty()) " $reason" else ""})")
                connAck.completeExceptionally(err); subAck.completeExceptionally(err)
                finish(err)
            }
        })
        withTimeout(30_000) { opened.await() }

        val body = ByteArrayOutputStream().apply {
            write(str("MQTT")); write(4)                       // protocol level 4 = 3.1.1
            write(0x02)                                        // clean session
            write(keepAliveSec shr 8); write(keepAliveSec and 0xFF)
            write(str(clientId))
        }.toByteArray()
        send(0x10, body)
        val code = withTimeout(30_000) { connAck.await() }
        if (code != 0) throw Exception("Traeger live feed refused the connection (code $code)")
    }

    suspend fun subscribe(topic: String, qos: Int = 1) {
        val body = ByteArrayOutputStream().apply { write(0); write(1); write(str(topic)); write(qos) }.toByteArray()
        send(0x82, body)
        val granted = withTimeout(30_000) { subAck.await() }
        if (granted == 0x80) throw Exception("Traeger live feed refused the subscription")
    }

    fun ping() { send(0xC0, ByteArray(0)) }

    fun close() {
        closed = true
        runCatching { send(0xE0, ByteArray(0)) }                 // DISCONNECT
        ws?.close(1000, null)
    }

    private fun finish(t: Throwable?) {
        if (closed) return
        closed = true
        onClosed(t)
    }

    private fun send(type: Int, body: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(type)
        var len = body.size
        do {
            var b = len % 128; len /= 128
            if (len > 0) b = b or 0x80
            out.write(b)
        } while (len > 0)
        out.write(body)
        ws?.send(out.toByteArray().toByteString())
    }

    @Synchronized private fun receive(bytes: ByteArray) {
        buffer.write(bytes)
        while (true) {
            val data = buffer.toByteArray()
            val pkt = parse(data) ?: return
            buffer.reset()
            buffer.write(data, pkt.consumed, data.size - pkt.consumed)
            handle(pkt.type, pkt.body)
        }
    }

    private fun handle(type: Int, body: ByteArray) {
        when (type and 0xF0) {
            0x20 -> connAck.complete(if (body.size >= 2) body[1].toInt() and 0xFF else -1)
            0x90 -> subAck.complete(if (body.size >= 3) body[2].toInt() and 0xFF else -1)
            0x30 -> {
                val qos = (type shr 1) and 0x03
                val tLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                val topic = String(body, 2, tLen, Charsets.UTF_8)
                var i = 2 + tLen
                if (qos > 0) {
                    send(0x40, byteArrayOf(body[i], body[i + 1]))       // PUBACK
                    i += 2
                }
                onMessage(topic, body.copyOfRange(i, body.size))
            }
            // 0xD0 PINGRESP and anything else: nothing to do
        }
    }

    private class Packet(val type: Int, val body: ByteArray, val consumed: Int)

    companion object {
        private fun str(s: String): ByteArray {
            val u = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf((u.size shr 8).toByte(), (u.size and 0xFF).toByte()) + u
        }

        /** One complete packet from the start of [d], or null if more bytes are needed. */
        private fun parse(d: ByteArray): Packet? {
            if (d.size < 2) return null
            var len = 0; var mult = 1; var i = 1
            while (true) {
                if (i >= d.size) return null
                val b = d[i].toInt() and 0xFF
                len += (b and 0x7F) * mult; mult *= 128; i++
                if (b and 0x80 == 0) break
                if (i > 4) throw IllegalStateException("Bad MQTT packet length")
            }
            if (d.size < i + len) return null
            return Packet(d[0].toInt() and 0xFF, d.copyOfRange(i, i + len), i + len)
        }
    }
}
