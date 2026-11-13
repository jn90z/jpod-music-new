package com.music.spotui.soulseek

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException

/**
 * Little-endian wire primitives for the Soulseek protocol.
 *
 * Every integer is little-endian; a string is `<uint32 length><utf8 bytes>`; a
 * bool is a single byte. Message framing differs by connection:
 *   - Server & Peer ('P') messages: `<uint32 length><uint32 code><payload>`
 *   - Peer-init messages:           `<uint32 length><uint8 code><payload>`
 * ([SlskWriter.frameServer] / [frameInit] build these; the length counts the
 * code byte(s) + payload.)
 */
internal class SlskWriter {
    private val buf = ByteArrayOutputStream()

    fun int(v: Int) = apply {
        buf.write(v and 0xFF); buf.write((v ushr 8) and 0xFF)
        buf.write((v ushr 16) and 0xFF); buf.write((v ushr 24) and 0xFF)
    }

    fun long(v: Long) = apply {
        for (i in 0 until 8) buf.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    fun byte(v: Int) = apply { buf.write(v and 0xFF) }

    fun bool(v: Boolean) = apply { buf.write(if (v) 1 else 0) }

    fun str(s: String) = apply {
        val bytes = s.toByteArray(Charsets.UTF_8)
        int(bytes.size); buf.write(bytes)
    }

    fun raw(bytes: ByteArray) = apply { buf.write(bytes) }

    private fun body(): ByteArray = buf.toByteArray()

    /** Frame with a uint32 message code (server / peer 'P' messages). */
    fun frameServer(code: Int): ByteArray = frame(SlskWriter().int(code).body(), body())

    /** Frame with a uint8 message code (peer-init messages). */
    fun frameInit(code: Int): ByteArray = frame(SlskWriter().byte(code).body(), body())

    private fun frame(codeBytes: ByteArray, payload: ByteArray): ByteArray {
        val len = codeBytes.size + payload.size
        val out = ByteArrayOutputStream(len + 4)
        out.write(SlskWriter().int(len).body())
        out.write(codeBytes)
        out.write(payload)
        return out.toByteArray()
    }
}

/** Reader over a message payload (framing/length already stripped). */
internal class SlskReader(private val data: ByteArray) {
    var pos = 0
        private set

    val remaining get() = data.size - pos

    fun int(): Int {
        require(pos + 4 <= data.size) { "int underflow" }
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun long(): Long {
        require(pos + 8 <= data.size) { "long underflow" }
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (i * 8))
        pos += 8
        return v
    }

    fun byte(): Int {
        require(pos + 1 <= data.size) { "byte underflow" }
        return data[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = byte() != 0

    fun str(): String {
        val len = int()
        require(len >= 0 && pos + len <= data.size) { "string underflow (len=$len)" }
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }
}

/**
 * Read one length-prefixed frame `<uint32 length><body>` from a stream and return
 * the body bytes (code + payload). Returns null on clean EOF.
 */
internal fun DataInputStream.readFrame(maxLen: Int = 16 * 1024 * 1024): ByteArray? {
    val lenBytes = ByteArray(4)
    var read = 0
    while (read < 4) {
        val r = read(lenBytes, read, 4 - read)
        if (r < 0) return if (read == 0) null else throw EOFException("short frame length")
        read += r
    }
    val len = (lenBytes[0].toInt() and 0xFF) or
        ((lenBytes[1].toInt() and 0xFF) shl 8) or
        ((lenBytes[2].toInt() and 0xFF) shl 16) or
        ((lenBytes[3].toInt() and 0xFF) shl 24)
    if (len <= 0 || len > maxLen) throw EOFException("bad frame length $len")
    val body = ByteArray(len)
    readFully(body)
    return body
}
