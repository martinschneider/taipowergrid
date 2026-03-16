package io.github.martinschneider.taipowergrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.martinschneider.taipowergrid.model.TaipowerCoordinate
import io.github.martinschneider.taipowergrid.utils.CoordinateConverter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for the full OCR → parse → GPS conversion pipeline.
 *
 * Reference coordinates are taken from the OSGeo wiki and cross-checked
 * against `taipowergrid` + `cs2cs` (see project README).
 *
 * Tolerance: 0.001° ≈ 100 m (limited by 2-digit precision on the sign).
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CoordinateConversionIntegrationTest {

    private lateinit var converter: CoordinateConverter

    @Before
    fun setUp() {
        converter = CoordinateConverter()
    }

    // ── Taiwan mainland ───────────────────────────────────────────────────────

    @Test
    fun testTaiwanMainland_Hualien() = assertConversion(
        input    = "H4292 BD23",
        expectLat = 24.3707,
        expectLon = 121.3405,
    )

    @Test
    fun testTaiwanMainland_Taichung_1() = assertConversion(
        input    = "G7353 DD67",
        expectLat = 24.1953,
        expectLon = 120.7991,
    )

    @Test
    fun testTaiwanMainland_Taichung_2() = assertConversion(
        input    = "G7825 FB24",
        expectLat = 24.0668,
        expectLon = 120.8402,
    )

    @Test
    fun testTaiwanMainland_Taichung_3() = assertConversion(
        input    = "G7353 DE81",
        expectLat = 24.1956,
        expectLon = 120.7993,
    )

    // ── Full pipeline: parse + convert + validate ─────────────────────────────

    @Test
    fun testFullPipeline_parseAndConvert() {
        val coord = requireNotNull(TaipowerCoordinate.parse("G7825 FB24"))
        val gps = converter.convertToWGS84(coord)

        assertTrue("GPS result should be valid", gps.isValid())
        assertTrue("GPS result should be in Taiwan", gps.isInTaiwan())
        assertTrue("Conversion result should validate", converter.validateConversion(coord, gps))
    }

    @Test
    fun testPrecision_10m_vs_100m() {
        // Same location at two precision levels should be within 100 m of each other
        val coarseCoord = requireNotNull(TaipowerCoordinate.parse("G7825 FB24"))
        val fineCoord   = requireNotNull(TaipowerCoordinate.parse("G7825 FB2400"))

        val coarse = converter.convertToWGS84(coarseCoord)
        val fine   = converter.convertToWGS84(fineCoord)

        val distMeters = coarse.distanceTo(fine)
        assertTrue(
            "10-m and coarser coord should be within 100 m; actual distance: $distMeters m",
            distMeters < 100.0,
        )
    }

    // ── Outlying islands ──────────────────────────────────────────────────────

    @Test
    fun testJinmen_Z_sector() {
        // Kinmen / Jinmen — uses UTM zone 50 with International 1924 ellipsoid
        // Reference from OSGeo wiki: Z0054 EC0222 → 118.3157°E, 24.4350°N
        val coord = requireNotNull(TaipowerCoordinate.parse("Z0054 EC0222"))
        val gps = converter.convertToWGS84(coord)
        assertTrue("Jinmen result should be valid", gps.isValid())
        assertTrue("Jinmen lat should be ~24.3–24.6", gps.latitude in 24.3..24.6)
        assertTrue("Jinmen lon should be ~118.2–118.5", gps.longitude in 118.2..118.5)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertConversion(
        input: String,
        expectLat: Double,
        expectLon: Double,
        tolerance: Double = 0.001,
    ) {
        val coord = requireNotNull(TaipowerCoordinate.parse(input)) {
            "Failed to parse coordinate: $input"
        }
        val gps = converter.convertToWGS84(coord)

        val latDiff = Math.abs(gps.latitude  - expectLat)
        val lonDiff = Math.abs(gps.longitude - expectLon)

        assertTrue(
            "$input: latitude ${gps.latitude} is ${latDiff}° away from expected $expectLat (tolerance $tolerance°)",
            latDiff < tolerance,
        )
        assertTrue(
            "$input: longitude ${gps.longitude} is ${lonDiff}° away from expected $expectLon (tolerance $tolerance°)",
            lonDiff < tolerance,
        )
    }
}
