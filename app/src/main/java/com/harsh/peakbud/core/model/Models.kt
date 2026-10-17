package com.harsh.peakbud.core.model

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south >= -90 && north <= 90 && south <= north)
        require(west in -180.0..180.0 && east in -180.0..180.0)
    }
    fun contains(point: GeoPoint): Boolean = point.latitude in south..north &&
        if (west <= east) point.longitude in west..east else point.longitude >= west || point.longitude <= east
}

enum class VerticalDatum { EGM96, EGM2008, SYNTHETIC }

data class Observer(
    val point: GeoPoint,
    val groundHeightM: Double,
    val phoneHeightM: Double = 1.6,
    val datum: VerticalDatum = VerticalDatum.EGM96,
    val horizontalAccuracyM: Double = 0.0,
    val heightUncertaintyM: Double = 10.0,
) {
    init {
        require(groundHeightM.isFinite())
        require(phoneHeightM.isFinite() && phoneHeightM in 0.0..100.0)
        require(horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0)
        require(heightUncertaintyM.isFinite() && heightUncertaintyM >= 0)
    }
    val heightM: Double get() = groundHeightM + phoneHeightM
}

data class Peak(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val publishedHeightM: Double? = null,
    val source: String,
) {
    init { require(publishedHeightM == null || publishedHeightM.isFinite()) }
}

sealed interface TerrainSample {
    data class Valid(val heightM: Double) : TerrainSample {
        init { require(heightM.isFinite()) }
    }
    data object NoData : TerrainSample
    data object TileMissing : TerrainSample
    data object OutsideCoverage : TerrainSample
}

interface Terrain : AutoCloseable {
    val datum: VerticalDatum
    val resolutionM: Double
    val heightErrorM: Double
    fun sample(point: GeoPoint): TerrainSample
    override fun close() {}
}

enum class Visibility { TERRAIN_VISIBLE, OCCLUDED, UNCERTAIN, UNKNOWN }

enum class PeakReason { NO_SUMMIT_DATA, HEIGHT_MISMATCH, VISIBLE, OCCLUDED, BORDERLINE, MISSING_TERRAIN }

data class PeakView(
    val peak: Peak,
    val distanceM: Double,
    val bearingDeg: Double,
    val elevationDeg: Double?,
    val geometryHeightM: Double?,
    val visibility: Visibility,
    val clearanceDeg: Double? = null,
    val blockerDistanceM: Double? = null,
    val reason: PeakReason,
    val heightDifferenceM: Int? = null,
)

data class HorizonSample(val bearingDeg: Double, val elevationDeg: Double?, val distanceM: Double?, val complete: Boolean)
data class MountainScene(val observer: Observer, val horizon: List<HorizonSample>, val peaks: List<PeakView>, val radiusM: Double)

data class CalculationSettings(val radiusM: Double = 50_000.0, val angularStepDeg: Double = 0.5) {
    init {
        require(radiusM.isFinite() && radiusM in 100.0..300_000.0)
        require(angularStepDeg.isFinite() && angularStepDeg in 0.1..10.0)
    }
}
