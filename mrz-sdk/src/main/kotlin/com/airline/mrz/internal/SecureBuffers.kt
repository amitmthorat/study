package com.airline.mrz.internal

import java.nio.ByteBuffer

/** Helpers to make sure image and text buffers do not linger in memory after a session. */
internal object SecureBuffers {
    fun zero(buffer: ByteArray) = java.util.Arrays.fill(buffer, 0)

    fun zero(chars: CharArray) = java.util.Arrays.fill(chars, '\u0000')

    fun zero(buffer: ByteBuffer) {
        buffer.rewind()
        while (buffer.hasRemaining()) buffer.put(0)
        buffer.rewind()
    }
}
