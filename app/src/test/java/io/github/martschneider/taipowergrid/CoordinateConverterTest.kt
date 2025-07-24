package io.github.martschneider.taipowergrid

import io.github.martschneider.taipowergrid.model.TaipowerCoordinate
import io.github.martschneider.taipowergrid.utils.CoordinateConverter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CoordinateConverter
 * 
 * Tests the conversion from Taiwan Power Company grid coordinates to WGS84 GPS coordinates.
 * These tests help validate conversion accuracy and catch regressions.
 */
class CoordinateConverterTest {
    
    private lateinit var converter: CoordinateConverter
    
    @Before
    fun setUp() {
        converter = CoordinateConverter()
    }
    
    @Test
    fun testTaipowerToXYConversion() {
        // Test Phase 1: Taipower coordinate -> XY meter coordinates
        testCases.forEach { testCase ->
            val coordinate = TaipowerCoordinate.parse(testCase.taipower)
            assertNotNull("Should parse ${testCase.taipower}", coordinate)
            
            val (x, y) = converter.convertToXY(coordinate!!)
            
            // Verify exact match with reference XY coordinates
            assertEquals("${testCase.taipower} - X coordinate should match reference exactly", 
                testCase.expectedX, x, 0.1) // Allow 0.1m tolerance for rounding
            assertEquals("${testCase.taipower} - Y coordinate should match reference exactly", 
                testCase.expectedY, y, 0.1) // Allow 0.1m tolerance for rounding
        }
    }
    
    @Test
    fun testXYToWGS84Conversion() {
        // Test Phase 2: XY meter coordinates -> WGS84 GPS coordinates
        testCases.forEach { testCase ->
            val (lat, lon) = converter.xyToWgs84Taiwan(testCase.expectedX, testCase.expectedY)
            
            // Verify accuracy within 0.001° (~100m tolerance)
            val latDiff = kotlin.math.abs(lat - testCase.expectedLat)
            val lonDiff = kotlin.math.abs(lon - testCase.expectedLon)
            val tolerance = 0.001
            
            assertTrue("${testCase.taipower} - Latitude should be within ${tolerance}° of reference (${testCase.expectedLat}), but was ${lat} (diff: $latDiff)",
                latDiff < tolerance)
            assertTrue("${testCase.taipower} - Longitude should be within ${tolerance}° of reference (${testCase.expectedLon}), but was ${lon} (diff: $lonDiff)",
                lonDiff < tolerance)
        }
    }
    
    @Test
    fun testEndToEndConversion() {
        // Test complete pipeline: Taipower -> XY -> WGS84
        testCases.forEach { testCase ->
            val coordinate = TaipowerCoordinate.parse(testCase.taipower)
            val gpsCoordinate = converter.convertToWGS84(coordinate!!)
            
            // Verify accuracy within 0.001° (~100m tolerance)
            val latDiff = kotlin.math.abs(gpsCoordinate.latitude - testCase.expectedLat)
            val lonDiff = kotlin.math.abs(gpsCoordinate.longitude - testCase.expectedLon)
            val tolerance = 0.001
            
            assertTrue("${testCase.taipower} - Latitude should be within ${tolerance}° of reference (${testCase.expectedLat}), but was ${gpsCoordinate.latitude} (diff: $latDiff)",
                latDiff < tolerance)
            assertTrue("${testCase.taipower} - Longitude should be within ${tolerance}° of reference (${testCase.expectedLon}), but was ${gpsCoordinate.longitude} (diff: $lonDiff)",
                lonDiff < tolerance)
            
            // Verify GPS coordinate is valid
            assertTrue("Should be valid GPS coordinate: lat=${gpsCoordinate.latitude}, lon=${gpsCoordinate.longitude}", 
                gpsCoordinate.isValid())
            assertTrue("Should be in Taiwan: lat=${gpsCoordinate.latitude}, lon=${gpsCoordinate.longitude}", 
                gpsCoordinate.isInTaiwan())
        }
    }

    companion object {
        // Reference test cases with Taipower coordinate, XY coordinates, and GPS coordinates
        // XY values verified against taipowergrid program, GPS values against taipowergrid + cs2cs
        private val testCases = listOf(
            TestCase("H4292 BD23", 283720.0, 2696330.0, 24.3707230, 121.3405475),
            TestCase("G7353 DD67", 228760.0, 2676870.0, 24.1952640, 120.7990700),
            TestCase("G7825 FB24", 232920.0, 2662640.0, 24.0668243, 120.8401795),
            TestCase("G7353 DE81", 228780.0, 2676910.0, 24.1956254, 120.7992663)
        )
    }
    
    /**
     * Unified test case with all reference values
     */
    private data class TestCase(
        val taipower: String,
        val expectedX: Double,
        val expectedY: Double,
        val expectedLat: Double,
        val expectedLon: Double
    )
}