package io.github.martschneider.taipowergrid.model

/**
 * Data class representing a GPS coordinate in WGS84 format
 */
data class GPSCoordinate(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double = 0.0
) {
    
    /**
     * Format latitude for display
     */
    fun getFormattedLatitude(precision: Int = 6): String {
        return String.format("%.${precision}f", latitude)
    }
    
    /**
     * Format longitude for display
     */
    fun getFormattedLongitude(precision: Int = 6): String {
        return String.format("%.${precision}f", longitude)
    }
    
    /**
     * Get formatted coordinate string
     */
    fun getFormattedCoordinate(precision: Int = 6): String {
        return "${getFormattedLatitude(precision)}, ${getFormattedLongitude(precision)}"
    }
    
    /**
     * Check if coordinate is within Taiwan bounds
     */
    fun isInTaiwan(): Boolean {
        return latitude in 21.5..25.5 && longitude in 119.0..122.5
    }
    
    /**
     * Check if coordinate is valid
     */
    fun isValid(): Boolean {
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
    
    /**
     * Calculate distance to another coordinate in meters
     */
    fun distanceTo(other: GPSCoordinate): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        
        val lat1Rad = Math.toRadians(latitude)
        val lat2Rad = Math.toRadians(other.latitude)
        val deltaLatRad = Math.toRadians(other.latitude - latitude)
        val deltaLonRad = Math.toRadians(other.longitude - longitude)
        
        val a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return earthRadius * c
    }
    
    companion object {
        
        /**
         * Create GPSCoordinate from string representation
         */
        fun parse(coordinateString: String): GPSCoordinate? {
            return try {
                val parts = coordinateString.split(",")
                if (parts.size >= 2) {
                    val lat = parts[0].trim().toDouble()
                    val lon = parts[1].trim().toDouble()
                    val accuracy = if (parts.size > 2) parts[2].trim().toDouble() else 0.0
                    
                    val coordinate = GPSCoordinate(lat, lon, accuracy)
                    if (coordinate.isValid()) coordinate else null
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Taiwan center coordinate for reference
         */
        val TAIWAN_CENTER = GPSCoordinate(23.6978, 120.9605)
        
        /**
         * Taiwan bounds
         */
        val TAIWAN_BOUNDS = mapOf(
            "north" to 25.5,
            "south" to 21.5,
            "east" to 122.5,
            "west" to 119.0
        )
    }
}