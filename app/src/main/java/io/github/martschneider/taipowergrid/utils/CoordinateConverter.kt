package io.github.martschneider.taipowergrid.utils

import io.github.martschneider.taipowergrid.model.TaipowerCoordinate
import io.github.martschneider.taipowergrid.model.GPSCoordinate
import kotlin.math.*

/**
 * Taiwan Power coordinate converter based on Dan Jacobson's official implementation
 * Source: http://jidanni.org/geo/taipower/
 * 
 * Converts between Taiwan Power Company grid coordinates and X,Y meter coordinates.
 * This is the precise, deterministic algorithm used by the Taiwan Power Company.
 */
class CoordinateConverter {
    
    companion object {
        // Grid dimensions
        private const val D_EW = 80000.0        // East-West dimension (80km)
        private const val D_NS = 50000.0        // North-South dimension (50km)
        
        // Taiwan mainland reference points
        private const val TAIWAN_LEFT = 90000.0
        private const val TAIWAN_TOP = 2800000.0
        
        // Outlying islands reference points
        private const val PENGHU_LEFT = 275000.0
        private const val PENGHU_BOTTOM = 2564000.0
        private const val JINMEN_LEFT = 10000.0
        private const val JINMEN_RIGHT = 170000.0  // Jinmen has two Z sectors side by side
        private const val JINMEN_BOTTOM = 2675800.0
        private const val JINMEN_TOP = 2725800.0
        private const val MAZU_LEFT = 10000.0
        private const val MAZU_BOTTOM = 2894000.0
        private const val MAZU_TOP = 2944000.0
        
        // Taiwan grid layout map (as defined in original Perl)
        // Row layout: _ABC, _DEF, _GH_, JKL_, MNO_, PQR_, _TU_, _VW
        private val TAIWAN_ROWS = listOf(
            "_ABC",
            "_DEF", 
            "_GH_",
            "JKL_",
            "MNO_",
            "PQR_",
            "_TU_",
            "_VW"
        )
        
        // Baseline coordinates for each sector
        private val baselines = mutableMapOf<Char, Pair<Double, Double>>()
        
        init {
            // Initialize outlying islands baselines
            baselines['S'] = Pair(MAZU_LEFT, MAZU_BOTTOM)
            baselines['Y'] = Pair(PENGHU_LEFT, PENGHU_BOTTOM)
            baselines['X'] = Pair(PENGHU_LEFT, PENGHU_BOTTOM + D_NS)
            baselines['Z'] = Pair(JINMEN_LEFT, JINMEN_BOTTOM)
            
            // Initialize Taiwan mainland baselines from grid map (matching Perl exactly)
            // H0000 AA00 should give (250000, 2650000) and G0000 AA00 should give (170000, 2650000)
            var taiwanBottom = TAIWAN_TOP  // Start at 2800000
            
            for (rowString in TAIWAN_ROWS) {
                var leftEdge = TAIWAN_LEFT  // Start at 90000 for first column
                taiwanBottom -= D_NS  // Move down 50000 for each row
                
                for (char in rowString) {
                    if (char.isLetter() && char != '_') {
                        baselines[char] = Pair(leftEdge, taiwanBottom)
                    }
                    leftEdge += D_EW  // Increment AFTER assigning baseline
                }
            }
        }
    }
    
    /**
     * Convert Taipower coordinate to X,Y meter coordinates
     */
    fun convertToXY(coordinate: TaipowerCoordinate): Pair<Double, Double> {
        if (!coordinate.isValid()) {
            throw IllegalArgumentException("Invalid Taipower coordinate: ${coordinate.rawCoordinate}")
        }
        
        val areaLetter = coordinate.sector[0]
        
        // Parse according to Perl regex: G7825 FB24
        // zone = "7825" -> zoneX=78, zoneY=25  
        // block = "FB" -> blockX=F, blockY=B
        // precision = "24" -> precisionX=2, precisionY=4
        var zoneX = coordinate.zone.substring(0, 2).toInt()  // First 2 digits
        var zoneY = coordinate.zone.substring(2, 4).toInt()  // Last 2 digits
        
        // Special handling for Jinmen (Z sector)
        if (areaLetter == 'Z') {
            zoneX = 50 + (zoneX + 50) % 100
        }
        
        // Parse block coordinates (letters)
        val blockX = coordinate.block[0] - 'A'
        val blockY = coordinate.block[1] - 'A'
        
        // Parse precision coordinates (individual digits)
        val precisionX = coordinate.precision.substring(0, 1).toInt()  // First digit
        val precisionY = coordinate.precision.substring(1, 2).toInt()  // Second digit
        
        // Sub-precision if available (4-digit precision)
        val subPrecisionX = if (coordinate.precision.length >= 3) coordinate.precision.substring(2, 3).toInt() else 0
        val subPrecisionY = if (coordinate.precision.length >= 4) coordinate.precision.substring(3, 4).toInt() else 0
        
        // Calculate local coordinates within the 80x50km sector (matching Perl algorithm)
        val localX = 800.0 * zoneX + 100.0 * blockX + 10.0 * precisionX + subPrecisionX
        val localY = 500.0 * zoneY + 100.0 * blockY + 10.0 * precisionY + subPrecisionY
        
        // Add baseline offset for the sector
        val baseline = baselines[areaLetter] 
            ?: throw IllegalArgumentException("Unknown sector: $areaLetter")
        
        val absoluteX = localX + baseline.first
        val absoluteY = localY + baseline.second
        
        return Pair(absoluteX, absoluteY)
    }
    
    /**
     * Convert X,Y meter coordinates to Taipower coordinate
     */
    fun convertFromXY(x: Double, y: Double, isPenghu: Boolean = false): TaipowerCoordinate {
        // Determine area letter
        val areaLetter = determineAreaLetter(x, y, isPenghu)
        
        // Subtract baseline offset
        val baseline = baselines[areaLetter]!!
        var localX = x - baseline.first
        var localY = y - baseline.second
        
        // Special handling for Jinmen (Z sector)
        if (areaLetter == 'Z') {
            localX = localX % D_EW
        }
        
        // Convert to zone coordinates
        val zoneX = (localX / 800).toInt()
        val zoneY = (localY / 500).toInt()
        
        // Convert to block coordinates
        val blockX = ((localX % 800) / 100).toInt()
        val blockY = ((localY % 500) / 100).toInt()
        
        // Convert to precision coordinates
        val precisionX = ((localX % 100) / 10).toInt()
        val precisionY = ((localY % 100) / 10).toInt()
        val subPrecisionX = (localX % 10).toInt()
        val subPrecisionY = (localY % 10).toInt()
        
        // Format coordinate string
        val sector = areaLetter.toString()
        val zone = String.format("%02d%02d", zoneX, zoneY)
        val block = "${('A' + blockX)}${('A' + blockY)}"
        val precision = String.format("%d%d%d%d", precisionX, precisionY, subPrecisionX, subPrecisionY)
        
        val rawCoordinate = "$sector$zone $block$precision"
        
        return TaipowerCoordinate.parse(rawCoordinate)
            ?: throw IllegalArgumentException("Failed to create coordinate from X=$x, Y=$y")
    }
    
    /**
     * Determine area letter based on X,Y coordinates
     */
    private fun determineAreaLetter(x: Double, y: Double, isPenghu: Boolean): Char {
        if (isPenghu) {
            if (x < PENGHU_LEFT) throw IllegalArgumentException("X coordinate too small for Penghu")
            if (x >= PENGHU_LEFT + D_EW) throw IllegalArgumentException("X coordinate too large for Penghu")
            if (y < PENGHU_BOTTOM) throw IllegalArgumentException("Y coordinate too small for Penghu")
            
            return when {
                y < PENGHU_BOTTOM + D_NS -> 'Y'
                y < PENGHU_BOTTOM + 2 * D_NS -> 'X'
                else -> throw IllegalArgumentException("Y coordinate too large for Penghu")
            }
        }
        
        // Check Mazu
        if (y >= MAZU_BOTTOM && y < MAZU_TOP) {
            if (x < MAZU_LEFT) throw IllegalArgumentException("X coordinate too small for Mazu")
            if (x >= MAZU_LEFT + D_EW) throw IllegalArgumentException("X coordinate too large for Mazu")
            return 'S'
        }
        
        // Check Jinmen (has two Z sectors side by side)
        if (x >= JINMEN_LEFT && x < JINMEN_RIGHT && y >= JINMEN_BOTTOM && y < JINMEN_TOP) {
            return 'Z'
        }
        
        // Taiwan mainland
        if (y >= TAIWAN_TOP) throw IllegalArgumentException("Y coordinate too large for Taiwan")
        
        val taiwanBottom = TAIWAN_TOP - 8 * D_NS // 8 rows in grid
        if (y < taiwanBottom) throw IllegalArgumentException("Y coordinate too small for Taiwan")
        
        val row = ((TAIWAN_TOP - y - 1) / D_NS).toInt()
        val col = ((x - TAIWAN_LEFT) / D_EW).toInt()
        
        if (row >= TAIWAN_ROWS.size) throw IllegalArgumentException("Position outside Taiwan grid")
        if (col >= TAIWAN_ROWS[row].length) throw IllegalArgumentException("Position outside Taiwan grid")
        
        val char = TAIWAN_ROWS[row][col]
        if (char == '_') throw IllegalArgumentException("Position in invalid grid cell")
        
        return char
    }
    
    /**
     * Convert Taipower coordinate to WGS84 GPS coordinates
     * Uses appropriate projection for each region
     */
    fun convertToWGS84(coordinate: TaipowerCoordinate): GPSCoordinate {
        val (x, y) = convertToXY(coordinate)
        
        // Determine which projection to use based on sector
        val (lat, lon) = when (coordinate.sector[0]) {
            'Y', 'X' -> xyToWgs84Penghu(x, y)      // Penghu projection
            'Z' -> xyToWgs84Jinmen(x, y)           // Jinmen projection  
            'S' -> xyToWgs84Mazu(x, y)             // Mazu projection
            else -> xyToWgs84Taiwan(x, y)          // Taiwan mainland projection
        }
        
        return GPSCoordinate(lat, lon, getAccuracyEstimate(coordinate))
    }
    
    /**
     * Convert XY meter coordinates to WGS84 latitude/longitude for Taiwan mainland
     * Uses the exact same projection parameters as the reference cs2cs command:
     * cs2cs +proj=tmerc +lon_0=121 +k=0.9999 +x_0=249172 +y_0=207
     */
    fun xyToWgs84Taiwan(x: Double, y: Double): Pair<Double, Double> {
        // Projection parameters from reference implementation
        // cs2cs +proj=tmerc +lon_0=121 +k=0.9999 +x_0=249172 +y_0=207
        val centralMeridian = 121.0      // Central meridian (degrees)
        val falseEasting = 249172.0      // False easting: 250000-828 = 249172
        val falseNorthing = 207.0        // False northing (meters)
        val scaleFactor = 0.9999         // Scale factor
        val latitudeOfOrigin = 0.0       // Latitude of origin (degrees)
        
        // WGS84 ellipsoid parameters
        val a = 6378137.0            // Semi-major axis (meters)
        val f = 1.0 / 298.257223563  // Flattening
        val e2 = 2 * f - f * f       // First eccentricity squared
        val e = sqrt(e2)             // First eccentricity
        
        // Convert to radians
        val lon0 = Math.toRadians(centralMeridian)
        
        // Adjust coordinates
        val dx = x - falseEasting
        val dy = y - falseNorthing
        
        // Calculate meridional arc
        val m = dy / scaleFactor
        
        // Calculate footprint latitude using series expansion
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val mu = m / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256))
        
        val j1 = 3*e1/2 - 27*e1*e1*e1/32
        val j2 = 21*e1*e1/16 - 55*e1*e1*e1*e1/32
        val j3 = 151*e1*e1*e1/96
        val j4 = 1097*e1*e1*e1*e1/512
        
        val fp = mu + j1*sin(2*mu) + j2*sin(4*mu) + j3*sin(6*mu) + j4*sin(8*mu)
        
        // Calculate latitude and longitude
        val sinFp = sin(fp)
        val cosFp = cos(fp)
        val tanFp = tan(fp)
        
        val c1 = e2 * cosFp * cosFp / (1 - e2)
        val t1 = tanFp * tanFp
        val r1 = a * (1 - e2) / Math.pow(1 - e2 * sinFp * sinFp, 1.5)
        val n1 = a / sqrt(1 - e2 * sinFp * sinFp)
        val d = dx / (n1 * scaleFactor)
        
        // Latitude calculation
        val q1 = n1 * tanFp / r1
        val q2 = d * d / 2
        val q3 = (5 + 3*t1 + 10*c1 - 4*c1*c1 - 9*e2) * d*d*d*d / 24
        val q4 = (61 + 90*t1 + 298*c1 + 45*t1*t1 - 1.6*e2 - 37*e2*c1) * d*d*d*d*d*d / 720
        
        val lat = fp - q1 * (q2 - q3 + q4)
        
        // Longitude calculation  
        val q5 = d
        val q6 = (1 + 2*t1 + c1) * d*d*d / 6
        val q7 = (5 - 2*c1 + 28*t1 - 3*c1*c1 + 8*e2 + 24*t1*t1) * d*d*d*d*d / 120
        
        val lon = lon0 + (q5 - q6 + q7) / cosFp
        
        // Convert to degrees
        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }
    
    /**
     * Convert XY meter coordinates to WGS84 latitude/longitude for Penghu
     * Penghu uses 119° E longitude as x=250000m, unlike Taiwan (121° E)
     * cs2cs -f %.7f +proj=tmerc +lon_0=119 +k=0.9999 +x_0=250000-828 +y_0=207
     */
    fun xyToWgs84Penghu(x: Double, y: Double): Pair<Double, Double> {
        return xyToWgs84WithParams(
            x, y,
            centralMeridian = 119.0,
            falseEasting = 250000.0 - 828.0,
            falseNorthing = 207.0,
            scaleFactor = 0.9999
        )
    }
    
    /**
     * Convert XY meter coordinates to WGS84 latitude/longitude for Jinmen
     * Uses EPSG 3829 with +zone=50 (+lon_0=117)
     * Parameters from 2015/12 Taipower contractor conversations
     * cs2cs -f %.7f +proj=tmerc +lon_0=117 +ellps=intl +x_0=-42160 +y_0=-205 +k=0.9996 +towgs84=-637,-549,-203
     * 
     * Example: Z0054 EC0222 -> 118.3157370 24.4349832
     * Jinmen has two Z sectors side by side (10000-170000, 2675800-2725800)
     */
    fun xyToWgs84Jinmen(x: Double, y: Double): Pair<Double, Double> {
        return xyToWgs84WithParams(
            x, y,
            centralMeridian = 117.0,
            falseEasting = -42160.0,
            falseNorthing = -205.0,
            scaleFactor = 0.9996,
            useInternationalEllipsoid = true,
            datumShift = Triple(-637.0, -549.0, -203.0)
        )
    }
    
    /**
     * Convert XY meter coordinates to WGS84 latitude/longitude for Mazu
     * cs2cs +proj=tmerc +lon_0=117 +ellps=intl +x_0=-279825 +y_0=20830 +k=0.9996 +towgs84=-637,-549,-203
     */
    fun xyToWgs84Mazu(x: Double, y: Double): Pair<Double, Double> {
        return xyToWgs84WithParams(
            x, y,
            centralMeridian = 117.0,
            falseEasting = -279825.0,
            falseNorthing = 20830.0,
            scaleFactor = 0.9996,
            useInternationalEllipsoid = true,
            datumShift = Triple(-637.0, -549.0, -203.0)
        )
    }
    
    /**
     * Generic projection conversion with configurable parameters
     */
    private fun xyToWgs84WithParams(
        x: Double, 
        y: Double,
        centralMeridian: Double,
        falseEasting: Double,
        falseNorthing: Double,
        scaleFactor: Double,
        useInternationalEllipsoid: Boolean = false,
        datumShift: Triple<Double, Double, Double>? = null
    ): Pair<Double, Double> {
        
        // Ellipsoid parameters
        val (a, f) = if (useInternationalEllipsoid) {
            // International 1924 ellipsoid
            Pair(6378388.0, 1.0 / 297.0)
        } else {
            // WGS84 ellipsoid
            Pair(6378137.0, 1.0 / 298.257223563)
        }
        
        val e2 = 2 * f - f * f       // First eccentricity squared
        val e = sqrt(e2)             // First eccentricity
        
        // Convert to radians
        val lon0 = Math.toRadians(centralMeridian)
        
        // Adjust coordinates
        val dx = x - falseEasting
        val dy = y - falseNorthing
        
        // Calculate meridional arc
        val m = dy / scaleFactor
        
        // Calculate footprint latitude using series expansion
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val mu = m / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256))
        
        val j1 = 3*e1/2 - 27*e1*e1*e1/32
        val j2 = 21*e1*e1/16 - 55*e1*e1*e1*e1/32
        val j3 = 151*e1*e1*e1/96
        val j4 = 1097*e1*e1*e1*e1/512
        
        val fp = mu + j1*sin(2*mu) + j2*sin(4*mu) + j3*sin(6*mu) + j4*sin(8*mu)
        
        // Calculate latitude and longitude
        val sinFp = sin(fp)
        val cosFp = cos(fp)
        val tanFp = tan(fp)
        
        val c1 = e2 * cosFp * cosFp / (1 - e2)
        val t1 = tanFp * tanFp
        val r1 = a * (1 - e2) / Math.pow(1 - e2 * sinFp * sinFp, 1.5)
        val n1 = a / sqrt(1 - e2 * sinFp * sinFp)
        val d = dx / (n1 * scaleFactor)
        
        // Latitude calculation
        val q1 = n1 * tanFp / r1
        val q2 = d * d / 2
        val q3 = (5 + 3*t1 + 10*c1 - 4*c1*c1 - 9*e2) * d*d*d*d / 24
        val q4 = (61 + 90*t1 + 298*c1 + 45*t1*t1 - 1.6*e2 - 37*e2*c1) * d*d*d*d*d*d / 720
        
        val lat = fp - q1 * (q2 - q3 + q4)
        
        // Longitude calculation  
        val q5 = d
        val q6 = (1 + 2*t1 + c1) * d*d*d / 6
        val q7 = (5 - 2*c1 + 28*t1 - 3*c1*c1 + 8*e2 + 24*t1*t1) * d*d*d*d*d / 120
        
        val lon = lon0 + (q5 - q6 + q7) / cosFp
        
        // Convert to degrees
        var latDeg = Math.toDegrees(lat)
        var lonDeg = Math.toDegrees(lon)
        
        // Apply datum shift if specified (Molodensky transformation)
        datumShift?.let { (dx, dy, dz) ->
            // Convert to Earth-Centered Earth-Fixed (ECEF) coordinates
            val latRad = Math.toRadians(latDeg)
            val lonRad = Math.toRadians(lonDeg)
            val h = 0.0 // Assume sea level
            
            val sinLat = sin(latRad)
            val cosLat = cos(latRad)
            val sinLon = sin(lonRad)
            val cosLon = cos(lonRad)
            
            val n = a / sqrt(1 - e2 * sinLat * sinLat)
            
            // ECEF coordinates
            val x_ecef = (n + h) * cosLat * cosLon
            val y_ecef = (n + h) * cosLat * sinLon
            val z_ecef = (n * (1 - e2) + h) * sinLat
            
            // Apply 3-parameter datum shift
            val x_new = x_ecef + dx
            val y_new = y_ecef + dy
            val z_new = z_ecef + dz
            
            // Convert back to geodetic coordinates (simplified)
            val p = sqrt(x_new * x_new + y_new * y_new)
            val theta = atan2(z_new * a, p * a * (1 - e2))
            
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            
            latDeg = Math.toDegrees(atan2(z_new + e2 * a * sinTheta * sinTheta * sinTheta, 
                                         p - e2 * a * cosTheta * cosTheta * cosTheta))
            lonDeg = Math.toDegrees(atan2(y_new, x_new))
        }
        
        return Pair(latDeg, lonDeg)
    }
    
    /**
     * Estimate conversion accuracy
     */
    fun getAccuracyEstimate(coordinate: TaipowerCoordinate): Double {
        // Based on precision level of the coordinate
        return when (coordinate.precision.length) {
            4 -> 1.0   // Full precision: ~1m
            2 -> 10.0  // Reduced precision: ~10m
            else -> 100.0  // Block level: ~100m
        }
    }
    
    /**
     * Validate conversion result
     */
    fun validateConversion(coordinate: TaipowerCoordinate, result: GPSCoordinate): Boolean {
        if (!result.isValid()) return false
        
        // Different validation ranges for different regions
        return when (coordinate.sector[0]) {
            'Y', 'X' -> {
                // Penghu islands
                result.latitude in 23.0..24.0 && result.longitude in 119.0..120.0
            }
            'Z' -> {
                // Jinmen (Kinmen) - based on your example: 118.3157370 24.4349832
                result.latitude in 24.3..24.6 && result.longitude in 118.2..118.5
            }
            'S' -> {
                // Mazu (Matsu) - actual coordinates
                result.latitude in 26.1..26.4 && result.longitude in 119.9..120.5
            }
            else -> {
                // Taiwan mainland
                result.latitude in 21.0..26.0 && result.longitude in 120.0..122.0
            }
        }
    }
}