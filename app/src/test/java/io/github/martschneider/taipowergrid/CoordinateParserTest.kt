package io.github.martschneider.taipowergrid

import io.github.martschneider.taipowergrid.utils.CoordinateParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CoordinateParser
 * 
 * Tests the parsing and OCR correction logic for Taiwan Power Company coordinates.
 */
class CoordinateParserTest {
    
    private lateinit var parser: CoordinateParser
    
    @Before
    fun setUp() {
        parser = CoordinateParser()
    }
    
    @Test
    fun testValidCoordinateParsing() {
        val testCases = listOf(
            "G8152 FC56",
            "A1234 BC78",
            "E9863 DE60",
            "Z9999 ZZ99"
        )
        
        testCases.forEach { input ->
            val coordinates = parser.parseText(input)
            assertEquals("Should parse exactly one coordinate for '$input'", 1, coordinates.size)
            
            val coordinate = coordinates.first()
            assertTrue("Parsed coordinate should be valid", coordinate.isValid())
            assertEquals("Should preserve original format", input, coordinate.rawCoordinate)
        }
    }
    
    @Test
    fun testMultiLineTextParsing() {
        val multiLineText = """
            G7825
            FB24
            221
        """.trimIndent()
        
        val coordinates = parser.parseText(multiLineText)
        assertEquals("Should parse one coordinate from multi-line text", 1, coordinates.size)
        
        val coordinate = coordinates.first()
        assertEquals("Should parse sector correctly", "G", coordinate.sector)
        assertEquals("Should parse zone correctly", "7825", coordinate.zone)
        assertEquals("Should parse block correctly", "FB", coordinate.block)
        assertEquals("Should parse precision correctly", "24", coordinate.precision)
    }
    
    @Test
    fun testOCRErrorCorrection() {
        val testCases = mapOf(
            // OCR input to expected result
            "G7825 F824" to "G7825 FB24", // 8 -> B in block position
            "G78Z5 FB24" to "G7825 FB24", // Z -> 2 in zone position  
            "G7825 FBO4" to "G7825 FB04", // O -> 0 in precision position
            "G78I5 FB24" to "G7815 FB24", // I -> 1 in zone position
            "G7825 F5Z4" to "G7825 FS24"  // 5 -> S, Z -> 2 corrections
        )
        
        testCases.forEach { (input, expected) ->
            val coordinates = parser.parseText(input)
            if (coordinates.isNotEmpty()) {
                val coordinate = coordinates.first()
                val result = coordinate.formatted
                assertEquals("OCR correction failed for '$input'", expected, result)
            } else {
                fail("Failed to parse coordinate with OCR errors: '$input'")
            }
        }
    }
    
    @Test
    fun testInvalidCoordinates() {
        val invalidInputs = listOf(
            "I8152 FC56", // Invalid sector (I not allowed)
            "G815 FC56",  // Zone too short
            "G8152 F56",  // Block too short  
            "G8152 FC5",  // Precision too short
            "G8152 FC567", // Precision too long
            "1234 ABCD",  // No sector
            "ABCD 1234",  // Invalid format
            "Random text", // Not a coordinate
            ""            // Empty string
        )
        
        invalidInputs.forEach { input ->
            val coordinates = parser.parseText(input)
            assertEquals("Should not parse invalid input '$input'", 0, coordinates.size)
        }
    }

    @Test
    fun testNoisyText() {
        val noisyInputs = listOf(
            "Power Pole G8152 FC56 Taiwan",  // Coordinate embedded in text
            "[G8152] (FC56)",                // With brackets and parentheses
            "G8152\nFC56\n2021",            // Multi-line with extra numbers
            "Location: G8152 FC56 End"       // With prefix and suffix
        )
        
        noisyInputs.forEach { input ->
            val coordinates = parser.parseText(input)
            assertTrue("Should parse coordinate from noisy text '$input'", coordinates.isNotEmpty())
            
            if (coordinates.isNotEmpty()) {
                val coordinate = coordinates.first()
                assertTrue("Parsed coordinate should be valid", coordinate.isValid())
            }
        }
    }
    
    @Test
    fun testSingleCoordinateStringParsing() {
        val validCoord = "G8152 FC56"
        val coordinate = parser.parseCoordinate(validCoord)
        
        assertNotNull("Should parse single coordinate", coordinate)
        assertEquals("Should match expected format", validCoord, coordinate!!.rawCoordinate)
    }
    
    @Test
    fun testValidationMethod() {
        val validFormats = listOf(
            "G8152 FC56",
            "A1234 BC78",
            "Z9999 ZZ99"
        )
        
        val invalidFormats = listOf(
            "I8152 FC56", // Invalid sector
            "G815 FC56",  // Invalid zone
            "Random text"
        )
        
        validFormats.forEach { format ->
            assertTrue("Should validate '$format' as valid", parser.isValidFormat(format))
        }
        
        invalidFormats.forEach { format ->
            assertFalse("Should validate '$format' as invalid", parser.isValidFormat(format))
        }
    }
    
    @Test
    fun testEdgeCases() {
        val edgeCases = listOf(
            "A0000 AA00",  // Minimum values
            "Z9999 ZZ99",  // Maximum values  
            "G0001 AA01",  // Small increments
            "G9998 ZZ98"   // Near maximum
        )
        
        edgeCases.forEach { input ->
            val coordinates = parser.parseText(input)
            assertTrue("Should handle edge case '$input'", coordinates.isNotEmpty())
            
            if (coordinates.isNotEmpty()) {
                val coordinate = coordinates.first()
                assertTrue("Edge case coordinate should be valid", coordinate.isValid())
            }
        }
    }
}