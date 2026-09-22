package com.example

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KissFrameBufferTest {

    @Test
    fun testKissFrameDefragmentationAndUnescaping() {
        val receivedFrames = mutableListOf<ByteArray>()
        val buffer = KissFrameBuffer { frame ->
            receivedFrames.add(frame)
        }

        // Test split chunks with escape sequence:
        // Raw packet has: FEND (0xC0), CMD_DATA (0x00), 0x01, 0x02, FESC (0xDB), TFEND (0xDC), 0x03, FEND (0xC0)
        val chunk1 = byteArrayOf(0xC0.toByte(), 0x00.toByte(), 0x01.toByte(), 0x02.toByte())
        val chunk2 = byteArrayOf(0xDB.toByte(), 0xDC.toByte(), 0x03.toByte(), 0xC0.toByte())

        buffer.feed(chunk1)
        assertEquals(0, receivedFrames.size)

        buffer.feed(chunk2)
        assertEquals(1, receivedFrames.size)

        // The assembled frame should be valid KISS frame with FENDs at both ends
        val frame = receivedFrames[0]
        assertEquals(0xC0.toByte(), frame.first())
        assertEquals(0xC0.toByte(), frame.last())
        assertEquals(0x00.toByte(), frame[1]) // CMD_DATA
    }

    @Test
    fun testMultipleFramesInSingleChunk() {
        val receivedFrames = mutableListOf<ByteArray>()
        val buffer = KissFrameBuffer { frame ->
            receivedFrames.add(frame)
        }

        val chunk = byteArrayOf(
            0xC0.toByte(), 0x00.toByte(), 0x10.toByte(), 0xC0.toByte(),
            0xC0.toByte(), 0x00.toByte(), 0x20.toByte(), 0xC0.toByte()
        )

        buffer.feed(chunk)
        assertEquals(2, receivedFrames.size)
    }

    @Test
    fun testRNodeInitSequenceFrames() {
        val frames = RNodeRadioConfig.buildInitFrames()
        assertTrue(frames.size >= 7)

        // Verify frequency command (0x01)
        val freqFrame = frames.first { it.size >= 6 && it[1] == 0x01.toByte() }
        assertEquals(0xC0.toByte(), freqFrame.first())
        assertEquals(0xC0.toByte(), freqFrame.last())

        // Verify bandwidth command (0x02)
        val bwFrame = frames.first { it.size >= 6 && it[1] == 0x02.toByte() }
        assertEquals(0xC0.toByte(), bwFrame.first())
        assertEquals(0xC0.toByte(), bwFrame.last())

        // Verify TX power command (0x03 with 7 dBm)
        val txPowerFrame = frames.first { it.size >= 3 && it[1] == 0x03.toByte() }
        assertEquals(0x07.toByte(), txPowerFrame[2])

        // Verify SF command (0x04 with SF8)
        val sfFrame = frames.first { it.size >= 3 && it[1] == 0x04.toByte() }
        assertEquals(0x08.toByte(), sfFrame[2])

        // Verify CR command (0x05 with 4/5)
        val crFrame = frames.first { it.size >= 3 && it[1] == 0x05.toByte() }
        assertEquals(0x05.toByte(), crFrame[2])

        // Verify radio state command (0x06 with RX mode 0x01)
        val stateFrame = frames.first { it.size >= 4 && it[1] == 0x06.toByte() }
        assertEquals(0x01.toByte(), stateFrame[2]) // RX Mode

        // Verify detection probe (0x0B)
        val detectFrame = frames.first { it[1] == 0x0B.toByte() }
        assertEquals(0xC0.toByte(), detectFrame.first())
        assertEquals(0xC0.toByte(), detectFrame.last())
    }
}
