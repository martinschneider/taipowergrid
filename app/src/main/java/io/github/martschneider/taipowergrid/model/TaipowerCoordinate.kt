package io.github.martschneider.taipowergrid.model

/**
 * Data class representing a Taiwan Power Company grid coordinate
 * 
 * Format examples:
 * - G8152 FC56 (9 characters with space, 2-digit precision)
 * - G8152 FC5678 (11 characters with space, 4-digit precision)
 * - E9863DE60 (9 characters without space, 2-digit precision)
 * - E9863DE6078 (11 characters without space, 4-digit precision)
 * 
 * Structure:
 * - Sector: First letter (A-Z, excluding I) - 80x50km area
 * - Zone: Next 4 digits - Zone coordinate origin point
 * - Block: Next 2 letters - 100-meter block identifier
 * - Precision: Last 2 or 4 digits - 1-10 meter or sub-meter precision
 */
data class TaipowerCoordinate(
    val rawCoordinate: String,
    val sector: String,
    val zone: String,
    val block: String,
    val precision: String
) {
    
    /**
     * Get the normalized coordinate string (without spaces)
     */
    val normalized: String
        get() = rawCoordinate.replace(" ", "").uppercase()
    
    /**
     * Get the formatted coordinate string (with space for readability)
     */
    val formatted: String
        get() = "${sector}${zone} ${block}${precision}"
    
    /**
     * Validate if the coordinate format is correct
     */
    fun isValid(): Boolean {
        return try {
            // Check total length (should be 9 or 11 characters when normalized)
            if (normalized.length != 9 && normalized.length != 11) return false
            
            // Check sector (should be A-Z excluding I)
            if (!sector.matches(Regex("[A-HJ-Z]"))) return false
            
            // Check zone (should be 4 digits)
            if (!zone.matches(Regex("\\d{4}"))) return false
            
            // Check block (should be 2 letters)
            if (!block.matches(Regex("[A-Z]{2}"))) return false
            
            // Check precision (should be 2 or 4 digits)
            if (!precision.matches(Regex("\\d{2,4}"))) return false
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get zone coordinates as integers
     */
    fun getZoneCoordinates(): Pair<Int, Int> {
        val zoneInt = zone.toIntOrNull() ?: 0
        val zoneX = zoneInt / 100  // First 2 digits
        val zoneY = zoneInt % 100  // Last 2 digits
        return Pair(zoneX, zoneY)
    }
    
    /**
     * Get block coordinates as integers (A=0, B=1, ..., Z=25)
     */
    fun getBlockCoordinates(): Pair<Int, Int> {
        val blockX = block.getOrNull(0)?.let { it.code - 'A'.code } ?: 0
        val blockY = block.getOrNull(1)?.let { it.code - 'A'.code } ?: 0
        return Pair(blockX, blockY)
    }
    
    /**
     * Get precision coordinates as integers
     */
    fun getPrecisionCoordinates(): Pair<Int, Int> {
        val precisionInt = precision.toIntOrNull() ?: 0
        return when (precision.length) {
            2 -> {
                // 2-digit precision: split into X and Y
                val precisionX = precisionInt / 10  // First digit
                val precisionY = precisionInt % 10  // Last digit
                Pair(precisionX, precisionY)
            }
            4 -> {
                // 4-digit precision: first 2 digits for X, last 2 for Y
                val precisionX = precisionInt / 100  // First 2 digits
                val precisionY = precisionInt % 100  // Last 2 digits
                Pair(precisionX, precisionY)
            }
            else -> Pair(0, 0)
        }
    }
    
    companion object {
        
        /**
         * Parse a coordinate string into TaipowerCoordinate
         */
        fun parse(coordinateString: String): TaipowerCoordinate? {
            return try {
                val normalized = coordinateString.replace(" ", "").uppercase()
                
                // Support both 9-character (2-digit precision) and 11-character (4-digit precision) formats
                if (normalized.length != 9 && normalized.length != 11) return null
                
                val sector = normalized.substring(0, 1)
                val zone = normalized.substring(1, 5)
                val block = normalized.substring(5, 7)
                val precision = when (normalized.length) {
                    9 -> normalized.substring(7, 9)   // 2-digit precision
                    11 -> normalized.substring(7, 11)  // 4-digit precision
                    else -> return null
                }
                
                val coordinate = TaipowerCoordinate(
                    rawCoordinate = coordinateString,
                    sector = sector,
                    zone = zone,
                    block = block,
                    precision = precision
                )
                
                if (coordinate.isValid()) coordinate else null
                
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Create a TaipowerCoordinate from individual components
         */
        fun create(sector: String, zone: String, block: String, precision: String): TaipowerCoordinate? {
            val rawCoordinate = "$sector$zone $block$precision"
            return parse(rawCoordinate)
        }
    }
}