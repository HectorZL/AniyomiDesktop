package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.Utils
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Offers to resume playback from a position saved on this device, or to start over.
 *
 * Shown once per episode load when there's a saved position worth resuming (see
 * [eu.kanade.tachiyomi.data.playback.PlaybackSessionValidator]).
 */
@Composable
fun ResumeDialog(
    positionMs: Long,
    onResume: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    PlayerDialog(
        title = stringResource(AYMR.strings.player_resume_dialog_title),
        onConfirmRequest = null,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(
                AYMR.strings.player_resume_dialog_message,
                Utils.prettyTime((positionMs / 1000L).toInt()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onStartFromBeginning) {
                Text(stringResource(AYMR.strings.player_resume_dialog_start_over))
            }

            TextButton(onClick = onResume) {
                Text(stringResource(AYMR.strings.player_resume_dialog_resume))
            }
        }
    }
}
