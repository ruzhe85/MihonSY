package tachiyomi.presentation.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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

/**
 * A [RadioButton] with its label, laid out exactly like [LabeledCheckbox] so one settings card can
 * hold both kinds of row without them drifting apart.
 *
 * A radio states a selection rather than a switch: clicking the row selects it and nothing else, and
 * the only way back is selecting another option — which is why a group needs an explicit "nothing"
 * option instead of relying on unticking.
 */
@Composable
fun LabeledRadioButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                enabled = enabled,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )

        Text(
            text = label,
            color = if (enabled) {
                LocalContentColor.current
            } else {
                LocalContentColor.current.copy(alpha = DisabledLabelAlpha)
            },
        )
    }
}
// SY <--
