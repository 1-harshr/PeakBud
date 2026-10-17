package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.UprightFrame
import org.junit.Assert.*
import org.junit.Test

class UprightFrameTest {
    // Buffer 40×30 whose pixel value encodes its own coordinates.
    private fun pixel(x: Int, y: Int) = (0xff shl 24) or (x shl 8) or y

    @Test fun everyRotationProducesAnUprightSceneAndMapsBack() {
        // A scene point "top-left" must land at upright (0,0) for each sensor rotation.
        val expectedTopLeftInBuffer = mapOf(0 to (0 to 0), 90 to (0 to 29), 180 to (39 to 29), 270 to (39 to 0))
        for ((rotation, buffer) in expectedTopLeftInBuffer) {
            val frame = UprightFrame.sample(0, 0, 40, 30, rotation, 1000, ::pixel)
            assertEquals(if (rotation % 180 == 0) 40 else 30, frame.width)
            assertEquals(if (rotation % 180 == 0) 30 else 40, frame.height)
            assertEquals("rotation $rotation", pixel(buffer.first, buffer.second), frame.argb[0])
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val (bx, by) = frame.toBuffer(x.toDouble(), y.toDouble())
                assertEquals("rotation $rotation at $x,$y", pixel(bx.toInt(), by.toInt()), frame.argb[y * frame.width + x])
            }
        }
    }

    @Test fun cropAndDownsamplingStayInsideTheCropAndKeepPixelCentres() {
        val frame = UprightFrame.sample(10, 5, 80, 64, 90, 32, ::pixel)  // upright 64×80 -> 32×40, scale 2
        assertEquals(32, frame.width); assertEquals(40, frame.height)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val (bx, by) = frame.toBuffer(x.toDouble(), y.toDouble())
            assertTrue("$bx,$by", bx in 10.0..89.0 && by in 5.0..68.0)
        }
        // Upright pixel (0,0) covers upright full-resolution pixels 0..1, centred at 0.5.
        val (bx, by) = frame.toBuffer(0.0, 0.0)
        assertEquals(10.5, bx, 1e-9); assertEquals(5 + 63 - .5, by, 1e-9)
    }
}
