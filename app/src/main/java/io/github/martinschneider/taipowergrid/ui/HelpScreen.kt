package io.github.martinschneider.taipowergrid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val uriHandler = LocalUriHandler.current

    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider()

            // Scrollable content
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                BodyText(
                    "Taiwan Power Company (Taipower) marks every electricity pole and " +
                    "box with a unique coordinate code, making them a useful offline " +
                    "positioning system throughout Taiwan — even where GPS is unreliable."
                )

                BodyText(
                    "Point the camera at a blue plaque on a pole or the front of an " +
                    "electricity box, then tap the shutter button. The app reads the " +
                    "coordinate and converts it to GPS so you can open it in any maps app."
                )

                SectionTitle("Coordinate format")
                BodyText(
                    "Coordinates look like G8152 FC56:\n" +
                    "  G    — 80 × 50 km sector (A–W, X–Y for Penghu, Z for Jinmen, S for Matsu)\n" +
                    "  8152 — zone (columns & rows of 800 m × 500 m cells)\n" +
                    "  FC   — 100-metre block inside the zone\n" +
                    "  56   — 10-metre precision offset"
                )

                SectionTitle("Accuracy")
                BodyText(
                    "Results are typically accurate to 10–100 metres depending on the " +
                    "precision digits printed on the sign. Always cross-check with " +
                    "nearby landmarks."
                )

                SectionTitle("Coverage")
                BodyText(
                    "Taiwan mainland (sectors A–W), Penghu (X, Y), " +
                    "Kinmen/Jinmen (Z), and Matsu (S) are all supported."
                )

                SectionTitle("More information")
                val linkStyle = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
                TextButton(
                    onClick = { uriHandler.openUri("https://www.jidanni.org/geo/taipower/") },
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(linkStyle) { append("Dan Jacobson's Taipower reference") }
                        }
                    )
                }
                TextButton(
                    onClick = { uriHandler.openUri("https://wiki.osgeo.org/wiki/Taiwan_Power_Company_grid") },
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(linkStyle) { append("OSGeo wiki — coordinate system details") }
                        }
                    )
                }

                HorizontalDivider()

                Text(
                    "This is a hobby project and is not affiliated with Taiwan Power Company. " +
                    "Source code released under the MIT licence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                Button(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Close")
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
