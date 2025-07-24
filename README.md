# Taipower Power Coordinates

Android app that scans coordinates from Taiwan power pole signs and converts them to a GPS location.

## Setup

1. Install Android Studio
2. Start Android emulator or connect physical Android device
3. `gradlew installDebug`

## Coordinate Format

Taiwan Power Company uses a grid system with coordinates like:
- `G8152 FC56` (9 characters)
- `E9863 DE60` (9 characters)

Format breakdown:
- First letter: 80x50km sector (A-Z excluding I)
- Next 4 digits: Zone coordinate origin
- Next 2 letters: 100m block (AA-ZZ)
- Last 2 digits: 1-10m precision

## Regional Support

The app supports accurate coordinate conversion for all Taiwan regions:
- **Taiwan** (A-W sectors): Using Taiwan TM2 projection (121°E central meridian)
- **Penghu** (X-Y sectors): Using Penghu TM projection (119°E central meridian)
- **Kinmen** (Z sector): Using EPSG 3829 with International 1924 ellipsoid
- **Matsu** (S sector): Using modified projection with datum shift corrections

For more information, see https://wiki.osgeo.org/wiki/Taiwan_Power_Company_grid.

## Architecture

- **CameraActivity**: Main screen with camera interface and real-time text detection
- **CoordinateParser**: Validates and parses Taipower coordinate format with OCR error correction
- **CoordinateConverter**: Converts Taipower grid coordinates to WGS84 GPS using precise projection algorithms
- **TaipowerCoordinate**: Data model for Taipower grid coordinates
- **GPSCoordinate**: Data model for GPS coordinates with accuracy estimates

## Map Integration

The app integrates with external map applications through Android's geo intent system which opens coordinates in user's preferred map app.

## Disclaimer

This is a hobby project and in no way associated with Taipower.
