package io.github.martschneider.taipowergrid.utils

import io.github.martschneider.taipowergrid.model.TaipowerCoordinate

/**
 * Utility class for parsing Taiwan Power Company coordinates from OCR text
 * 
 * Strategy:
 * 1. Remove all whitespace and newlines
 * 2. Apply position-based OCR corrections 
 * 3. Extract 9-character coordinate pattern
 */
class CoordinateParser {
    
    companion object {
        // OCR corrections: digit -> letter (for block positions)
        private val DIGIT_TO_LETTER = mapOf(
            '0' to 'O',
            '1' to 'I',
            '2' to 'Z',
            '5' to 'S', 
            '6' to 'G',
            '8' to 'B'
        )
        
        // OCR corrections: letter -> digit (for zone/precision positions)
        private val LETTER_TO_DIGIT = mapOf(
            'O' to '0',
            'I' to '1',
            'Z' to '2',
            'S' to '5',
            'G' to '6', 
            'B' to '8',
            'Q' to '0'
        )
    }
    
    /**
     * Parse text and extract Taiwan Power Company coordinates
     */
    fun parseText(text: String): List<TaipowerCoordinate> {
        // Step 1: Clean text - remove all non-alphanumeric characters except spaces
        val cleaned = text.replace(Regex("[^A-Za-z0-9\\s]"), "").uppercase()
        
        // Step 2: Try to find coordinate patterns using different approaches
        val coordinates = mutableListOf<TaipowerCoordinate>()
        
        // Approach 1: Look for spaced format like "G8152 FC56"
        val spacedPattern = Regex("([A-Z])([0-9]{4})\\s+([A-Z]{2})([0-9]{2})\\b")
        spacedPattern.findAll(cleaned).forEach { match ->
            val candidate = match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + match.groupValues[4]
            val corrected = applyCorrections(candidate)
            val coordinate = parseCandidate(corrected)
            if (coordinate != null && coordinate.isValid()) {
                coordinates.add(coordinate)
            }
        }
        
        // Approach 2: Look for continuous 9-character patterns (only if no spaced pattern found)
        if (coordinates.isEmpty()) {
            val compactPattern = Regex("([A-Z])([0-9]{4})([A-Z]{2})([0-9]{2})\\b")
            compactPattern.findAll(cleaned.replace(Regex("\\s+"), "")).forEach { match ->
                val candidate = match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + match.groupValues[4]
                val corrected = applyCorrections(candidate)
                val coordinate = parseCandidate(corrected)
                if (coordinate != null && coordinate.isValid()) {
                    coordinates.add(coordinate)
                }
            }
        }
        
        // Approach 3: Lenient parsing for OCR error correction (only if still no coordinates found)
        if (coordinates.isEmpty()) {
            val lenientPattern = Regex("([A-Z])([0-9A-Z]{4})\\s+([A-Z0-9]{2})([0-9A-Z]{2})\\b")
            lenientPattern.findAll(cleaned).forEach { match ->
                val candidate = match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + match.groupValues[4]
                val corrected = applyCorrections(candidate)
                val coordinate = parseCandidate(corrected)
                if (coordinate != null && coordinate.isValid()) {
                    coordinates.add(coordinate)
                }
            }
        }
        
        return coordinates
    }
    
    /**
     * Apply position-based OCR corrections to a 9-character candidate
     */
    private fun applyCorrections(candidate: String): String {
        if (candidate.length != 9) return candidate
        
        val corrected = StringBuilder(candidate)
        
        // Position 0: Sector (letter) - already validated
        
        // Positions 1-4: Zone (should be digits)
        for (i in 1..4) {
            if (i < corrected.length) {
                val char = corrected[i]
                if (char.isLetter() && LETTER_TO_DIGIT.containsKey(char)) {
                    corrected[i] = LETTER_TO_DIGIT[char]!!
                }
            }
        }
        
        // Positions 5-6: Block (should be letters)
        for (i in 5..6) {
            if (i < corrected.length) {
                val char = corrected[i]
                if (char.isDigit() && DIGIT_TO_LETTER.containsKey(char)) {
                    corrected[i] = DIGIT_TO_LETTER[char]!!
                }
            }
        }
        
        // Positions 7-8: Precision (should be digits)
        for (i in 7..8) {
            if (i < corrected.length) {
                val char = corrected[i]
                if (char.isLetter() && LETTER_TO_DIGIT.containsKey(char)) {
                    corrected[i] = LETTER_TO_DIGIT[char]!!
                }
            }
        }
        
        return corrected.toString()
    }
    
    /**
     * Parse a 9-character candidate into TaipowerCoordinate
     */
    private fun parseCandidate(candidate: String): TaipowerCoordinate? {
        if (candidate.length != 9) return null
        
        try {
            val sector = candidate.substring(0, 1)
            val zone = candidate.substring(1, 5)
            val block = candidate.substring(5, 7)
            val precision = candidate.substring(7, 9)
            
            
            // Validate format
            if (!isValidSector(sector)) return null
            if (!isValidZone(zone)) return null
            if (!isValidBlock(block)) return null
            if (!isValidPrecision(precision)) return null
            
            val rawCoordinate = "$sector$zone $block$precision"
            return TaipowerCoordinate(
                rawCoordinate = rawCoordinate,
                sector = sector,
                zone = zone,
                block = block,
                precision = precision
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Validate sector (A-Z except I)
     */
    private fun isValidSector(sector: String): Boolean {
        return sector.length == 1 && 
               sector[0].isLetter() && 
               sector[0] in 'A'..'Z' && 
               sector[0] != 'I'
    }
    
    /**
     * Validate zone (4 digits)
     */
    private fun isValidZone(zone: String): Boolean {
        return zone.length == 4 && zone.all { it.isDigit() }
    }
    
    /**
     * Validate block (2 letters)
     */
    private fun isValidBlock(block: String): Boolean {
        return block.length == 2 && block.all { it.isLetter() }
    }
    
    /**
     * Validate precision (2 digits)
     */
    private fun isValidPrecision(precision: String): Boolean {
        return precision.length == 2 && precision.all { it.isDigit() }
    }
    
    /**
     * Parse a single coordinate string
     */
    fun parseCoordinate(coordinateString: String): TaipowerCoordinate? {
        val coordinates = parseText(coordinateString)
        return coordinates.firstOrNull()
    }
    
    /**
     * Validate if a string could be a Taipower coordinate
     */
    fun isValidFormat(text: String): Boolean {
        val coordinates = parseText(text)
        return coordinates.isNotEmpty()
    }
}