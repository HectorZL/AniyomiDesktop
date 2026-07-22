package eu.kanade.presentation.entries.anime.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.components.material.TextButton

@Composable
fun TrackingSection(
    trackItems: List<AnimeTrackItem>,
    onTrackingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = stringResource(MR.strings.manga_tracking_tab),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        trackItems.forEach { item ->
            Text(text = item.tracker.name)
        }
        TextButton(onClick = onTrackingClicked) {
            Text(text = stringResource(MR.strings.action_edit))
        }
    }
}
