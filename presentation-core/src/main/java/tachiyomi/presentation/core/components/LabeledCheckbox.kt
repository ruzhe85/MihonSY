package tachiyomi.presentation.core.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.material.padding

// SY -->
/** Alpha of a label whose row is disabled, matching what Material uses for a disabled control. */
private const val DisabledLabelAlpha = 0.38f
// SY <--

@Composable
fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                // SY -->
                // Passed on so a disabled row loses its ripple and its clickable semantics too
                role = Role.Checkbox,
                enabled = enabled,
                onClick = {
                    onCheckedChange(!checked)
                },
                // SY <--
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )

        // SY -->
        Text(
            text = label,
            color = if (enabled) {
                LocalContentColor.current
            } else {
                LocalContentColor.current.copy(alpha = DisabledLabelAlpha)
            },
        )
        // SY <--
    }
}
