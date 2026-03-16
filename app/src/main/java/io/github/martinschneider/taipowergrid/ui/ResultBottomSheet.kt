package io.github.martinschneider.taipowergrid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.martinschneider.taipowergrid.model.TaipowerCoordinate

/**
 * Bottom sheet shown after OCR or when the user taps "Manual entry".
 *
 * Lets the user review / correct the detected coordinate and then
 * launch a maps app.
 *
 * @param initialText  Pre-filled text (detected coordinate or partial OCR text).
 * @param onShowOnMap  Called with the current text when the user taps "Show on Map".
 * @param onDismiss    Called when the sheet should close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultBottomSheet(
    initialText: String?,
    onShowOnMap: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tfv by remember(initialText) {
        val t = initialText.orEmpty()
        mutableStateOf(TextFieldValue(text = t, selection = TextRange(t.length)))
    }
    val parsed = remember(tfv.text) { TaipowerCoordinate.parse(tfv.text.trim()) }
    val isValid = parsed != null
    val isEmpty = tfv.text.isBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "Taiwan Power Coordinates",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = tfv,
                onValueChange = { newValue ->
                    // Uppercase + filter, then auto-insert a space after the 5th char.
                    // Cursor position is mapped from the raw input so it never jumps.
                    val rawCursor = newValue.selection.end.coerceIn(0, newValue.text.length)
                    val clean = newValue.text
                        .replace(" ", "")
                        .uppercase()
                        .filter { it.isLetterOrDigit() }
                    val formatted = if (clean.length > 5) "${clean.take(5)} ${clean.drop(5)}" else clean
                    val cleanBeforeCursor = newValue.text.take(rawCursor)
                        .replace(" ", "").uppercase().filter { it.isLetterOrDigit() }.length
                        .coerceAtMost(clean.length)
                    val newCursor = if (cleanBeforeCursor > 5) cleanBeforeCursor + 1 else cleanBeforeCursor
                    tfv = TextFieldValue(
                        text = formatted,
                        selection = TextRange(newCursor.coerceIn(0, formatted.length)),
                    )
                },
                label = { Text("Coordinate  e.g. G8152 FC56") },
                isError = !isEmpty && !isValid,
                supportingText = {
                    if (!isEmpty && !isValid) {
                        Text("Use format: G8152 FC56")
                    } else if (isValid) {
                        Text("✓ Valid coordinate")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onShowOnMap(tfv.text.trim()) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Show on Map")
            }
        }
    }
}

