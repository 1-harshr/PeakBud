package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.core.sky.Sun
import com.harsh.peakbud.core.sky.SunEvent
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SunTest {
    private val delhi = GeoPoint(28.6139, 77.2090)

    @Test fun flatHorizonSunsetMatchesPublishedTime() {
        // Published sunset in New Delhi on 21 June 2024: 19:22 IST (13:52 UTC).
        val event = Sun.nextTerrainEvent(delhi, Instant.parse("2024-06-21T08:00:00Z").toEpochMilli(), { 0.0 })!!
        assertEquals(SunEvent.Kind.SET, event.kind)
        assertEquals(Instant.parse("2024-06-21T13:52:00Z").toEpochMilli().toDouble(), event.atMs.toDouble(), 2 * 60_000.0)
    }

    @Test fun aRidgeInTheWestBringsSunsetForward() {
        val start = Instant.parse("2024-06-21T08:00:00Z").toEpochMilli()
        val flat = Sun.nextTerrainEvent(delhi, start, { 0.0 })!!.atMs
        val ridge = Sun.nextTerrainEvent(delhi, start, { b -> if (b in 240.0..320.0) 5.0 else 0.0 })!!.atMs
        assertTrue(flat - ridge in 15 * 60_000L..40 * 60_000L)
    }
}
