package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugAction
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEntry
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugState
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.ReplayVerdict
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.ViewStore

/** No process-death restore: the log itself lives in the app-scoped [CookbookStores.debugLog]. */
class DebugViewModel(
    stores: CookbookStores,
) : ViewModel() {
    val store = stores.debugStore(StoreScope.Inherited(viewModelScope))
    val view: ViewStore<DebugState, DebugAction.Ui> = stores.debugView(store)
}

@Composable
fun DebugRoute(viewModel: DebugViewModel) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    DebugScreen(state = state, send = viewModel.view::send)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(
    state: DebugState,
    send: (DebugAction.Ui) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Action log") },
                navigationIcon = { TextButton(onClick = { send(DebugAction.BackClicked) }, content = { Text("Back") }) },
                actions = { TextButton(onClick = { send(DebugAction.Clear) }, content = { Text("Clear") }) },
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                content = {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = {
                            OutlinedButton(onClick = { send(DebugAction.VerifyReplay) }, content = { Text("Verify replay") })
                            OutlinedButton(onClick = { send(DebugAction.ThrowInEffect) }, content = { Text("Throw in effect") })
                            OutlinedButton(onClick = { send(DebugAction.ThrowInReducer) }, content = { Text("Throw in reducer") })
                        },
                    )
                    state.replay?.let { verdict ->
                        Text(
                            verdict.label(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            items(
                                items = state.entries.asReversed(),
                                key = DebugEntry::sequence,
                                itemContent = { entry ->
                                    DebugEntryRow(entry)
                                    HorizontalDivider()
                                },
                            )
                        },
                    )
                },
            )
        },
    )
}

@Composable
private fun DebugEntryRow(entry: DebugEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        content = {
            when (entry) {
                is DebugEntry.Reduced -> {
                    Text("#${entry.sequence} ${entry.store} · ${entry.action}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        entry.state,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is DebugEntry.Audit -> {
                    Text(
                        "#${entry.sequence} audit · ${entry.event}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                is DebugEntry.Defect -> {
                    Text(
                        "#${entry.sequence} ${entry.store} · defect",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(entry.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                is DebugEntry.Effect -> {
                    Text(
                        "#${entry.sequence} ${entry.store} · effect ${entry.phase} · ${entry.effect}" +
                            (entry.key?.let { key -> " · key $key" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is DebugEntry.Warning -> {
                    Text(
                        "#${entry.sequence} ${entry.store} · warning",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(entry.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
    )
}

private fun ReplayVerdict.label(): String =
    when (this) {
        ReplayVerdict.NothingRecorded -> "Nothing recorded yet: sign in first."
        is ReplayVerdict.Reproduced -> "Replaying $actions recorded session action(s) over the pure reducer reproduces the live state."
        is ReplayVerdict.Diverged -> "Replay diverged after $actions action(s).\nreplayed: $replayed\nlive: $live"
    }
