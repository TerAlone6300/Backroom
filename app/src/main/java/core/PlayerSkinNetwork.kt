package com.backrooms.game

import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Skin state synchronized between network clients. Custom PNG bytes are cached by the relay server. */
data class RemotePlayerSkin(val isDefault: Boolean, val texture: SkinTexture? = null, val png: ByteArray? = null)

/** Internal text protocol / server cache shared by the desktop server and LAN host. */
internal class SkinAwareRelay(private val seed: Int) {
    private data class Peer(val id: Int, val socket: Socket, val writer: PrintWriter, val lock: Any = Any())
    private data class Upload(val playerId: Int, val expectedBytes: Int, val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), var nextChunk: Int = 0)

    private val peers = ConcurrentHashMap<Int, Peer>()
    private val skins = ConcurrentHashMap<Int, RemotePlayerSkin>()
    private val stateLock = Any()

    fun attach(socket: Socket, id: Int) {
        val peer = Peer(id, socket, PrintWriter(socket.getOutputStream(), true))
        synchronized(stateLock) {
            peers[id] = peer
            send(peer, "ID $id"); send(peer, "SEED $seed")
            skins.toSortedMap().forEach { (playerId, state) -> sendSkin(peer, playerId, state) }
        }
        Thread({ serve(peer) }, "backrooms-client-$id").apply { isDaemon = true; start() }
    }

    private fun serve(peer: Peer) {
        var upload: Upload? = null
        try {
            val reader = peer.socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > SkinWire.MAX_LINE_LENGTH) break
                val p = line.split(' ', limit = 5)
                when (p.firstOrNull()) {
                    "P" -> if (p.size == 5 && p[1].toIntOrNull() == peer.id) {
                        val x = p[2].toDoubleOrNull(); val y = p[3].toDoubleOrNull(); val level = p[4].toIntOrNull()
                        if (x != null && y != null && level != null && x.isFinite() && y.isFinite())
                            broadcast("P ${peer.id} $x $y $level", peer.id)
                    }
                    "SKIN_DEFAULT" -> if (p.size == 2 && p[1].toIntOrNull() == peer.id) {
                        upload = null
                        val state = RemotePlayerSkin(isDefault = true)
                        synchronized(stateLock) { skins[peer.id] = state; broadcastSkin(peer.id, state, exceptId = peer.id) }
                    }
                    "SKIN_BEGIN" -> if (p.size == 3 && p[1].toIntOrNull() == peer.id) {
                        val size = p[2].toIntOrNull()
                        upload = if (size != null && size in 33..SkinWire.MAX_SKIN_BYTES) Upload(peer.id, size) else null
                    }
                    "SKIN_CHUNK" -> {
                        val current = upload
                        if (p.size == 4 && current != null && p[1].toIntOrNull() == peer.id && current.playerId == peer.id) {
                            val seq = p[2].toIntOrNull(); val chunk = SkinWire.decodeBase64(p[3])
                            if (seq != current.nextChunk || chunk == null || current.bytes.size().toLong() + chunk.size > current.expectedBytes) upload = null
                            else { current.bytes.write(chunk); current.nextChunk++ }
                        } else upload = null
                    }
                    "SKIN_END" -> if (p.size == 2 && p[1].toIntOrNull() == peer.id) {
                        val current = upload; upload = null
                        if (current != null && current.bytes.size() == current.expectedBytes) {
                            val png = current.bytes.toByteArray()
                            val texture = runCatching { SkinTexture.fromPng(png) }.getOrNull()
                            if (texture != null) {
                                val state = RemotePlayerSkin(isDefault = false, texture = texture, png = png)
                                synchronized(stateLock) { skins[peer.id] = state; broadcastSkin(peer.id, state, exceptId = peer.id) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        finally {
            upload = null
            synchronized(stateLock) {
                peers.remove(peer.id, peer)
                skins.remove(peer.id)
                broadcast("Q ${peer.id}", peer.id)
            }
            runCatching { peer.socket.close() }
        }
    }

    private fun broadcastSkin(playerId: Int, state: RemotePlayerSkin, exceptId: Int) = peers.values.forEach { if (it.id != exceptId) sendSkin(it, playerId, state) }
    private fun sendSkin(peer: Peer, playerId: Int, state: RemotePlayerSkin) {
        val png = state.png
        if (state.isDefault || png == null) { send(peer, "SKIN_DEFAULT $playerId"); return }
        send(peer, "SKIN_BEGIN $playerId ${png.size}")
        var sequence = 0; var offset = 0
        while (offset < png.size) {
            val end = minOf(offset + SkinWire.CHUNK_BYTES, png.size)
            send(peer, "SKIN_CHUNK $playerId ${sequence++} ${SkinWire.encodeBase64(png, offset, end - offset)}")
            offset = end
        }
        send(peer, "SKIN_END $playerId")
    }

    private fun broadcast(line: String, exceptId: Int) = peers.values.forEach { if (it.id != exceptId) send(it, line) }
    private fun send(peer: Peer, line: String) = synchronized(peer.lock) { peer.writer.println(line) }
}

internal object SkinWire {
    const val MAX_SKIN_BYTES = 8 * 1024 * 1024
    const val CHUNK_BYTES = 12 * 1024
    const val MAX_LINE_LENGTH = 24 * 1024
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val decode = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encodeBase64(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val out = StringBuilder((length + 2) / 3 * 4)
        var i = offset; val end = offset + length
        while (i < end) {
            val a = bytes[i++].toInt() and 255
            val hasB = i < end; val b = if (hasB) bytes[i++].toInt() and 255 else 0
            val hasC = i < end; val c = if (hasC) bytes[i++].toInt() and 255 else 0
            out.append(ALPHABET[a ushr 2]); out.append(ALPHABET[((a and 3) shl 4) or (b ushr 4)])
            out.append(if (hasB) ALPHABET[((b and 15) shl 2) or (c ushr 6)] else '=')
            out.append(if (hasC) ALPHABET[c and 63] else '=')
        }
        return out.toString()
    }

    fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 4 != 0 || value.length > MAX_LINE_LENGTH) return null
        val padding = if (value.endsWith("==")) 2 else if (value.endsWith('=')) 1 else 0
        val result = ByteArray(value.length / 4 * 3 - padding)
        var out = 0; var i = 0
        while (i < value.length) {
            val final = i + 4 == value.length
            val c0 = value[i].code; val c1 = value[i + 1].code; val c2 = value[i + 2].code; val c3 = value[i + 3].code
            if (c0 >= decode.size || c1 >= decode.size || decode[c0] < 0 || decode[c1] < 0) return null
            val a = decode[c0]; val b = decode[c1]
            val c = if (c2 == '='.code && final && padding == 2) 0 else if (c2 < decode.size) decode[c2] else -1
            val d = if (c3 == '='.code && final && padding > 0) 0 else if (c3 < decode.size) decode[c3] else -1
            if (c < 0 || d < 0 || (!final && (c2 == '='.code || c3 == '='.code))) return null
            if (out < result.size) result[out++] = ((a shl 2) or (b ushr 4)).toByte()
            if (out < result.size) result[out++] = ((b shl 4) or (c ushr 2)).toByte()
            if (out < result.size) result[out++] = ((c shl 6) or d).toByte()
            i += 4
        }
        return if (out == result.size) result else null
    }
}
