package io.github.martinschneider.taipowergrid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.martinschneider.taipowergrid.R

@Composable
fun HomeScreen(
    onCameraClick: () -> Unit,
    onManualEntryClick: () -> Unit,
    onHelpClick: () -> Unit,
    onFileBug: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        // Background image
        Image(
            painter = painterResource(R.drawable.background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Dark scrim for readability
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Top-right overflow menu
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("About") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { menuExpanded = false; onHelpClick() },
                )
                DropdownMenuItem(
                    text = { Text("File a bug") },
                    leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    onClick = { menuExpanded = false; onFileBug() },
                )
            }
        }

        // Main content centred on screen
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
        ) {
            Text(
                text = "Taiwan Power\nGrid Scanner",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Scan power-pole coordinate plaques\nand convert them to GPS",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.80f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(64.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    onClick = onCameraClick,
                )
                ActionButton(
                    icon = Icons.Default.Edit,
                    label = "Manual",
                    onClick = onManualEntryClick,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        LargeFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    TaipowerTheme {
        HomeScreen(onCameraClick = {}, onManualEntryClick = {}, onHelpClick = {})
    }
}
