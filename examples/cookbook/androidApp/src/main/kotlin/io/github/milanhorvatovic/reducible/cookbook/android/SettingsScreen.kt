package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsScreenAction
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsViewState
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.ViewStore

/** No process-death restore: the screen derives everything from the app-scoped settings on start. */
class SettingsViewModel(
    stores: CookbookStores,
) : ViewModel() {
    val store = stores.settingsScreenStore(StoreScope.Inherited(viewModelScope))
    val view: ViewStore<SettingsViewState, SettingsScreenAction.Ui> = stores.settingsView(store)
}

@Composable
fun SettingsRoute(viewModel: SettingsViewModel) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    SettingsScreen(state = state, send = viewModel.view::send)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsViewState,
    send: (SettingsScreenAction.Ui) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = { send(SettingsScreenAction.BackClicked) }, content = { Text("Back") })
                },
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                content = {
                    Text(
                        "Units",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Units.entries.forEach { units ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = state.units == units,
                                        enabled = state.ready,
                                        onClick = { send(SettingsScreenAction.UnitsSelected(units)) },
                                    ).padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = {
                                RadioButton(selected = state.units == units, onClick = null, enabled = state.ready)
                                Text(units.label(), modifier = Modifier.padding(start = 8.dp))
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = {
                            Column(
                                modifier = Modifier.weight(1f),
                                content = {
                                    Text("Failure injection", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Makes the fake repositories fail: loads, searches, downloads, and notes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            Switch(
                                checked = state.failureInjection,
                                onCheckedChange = { checked -> send(SettingsScreenAction.FailureInjectionToggled) },
                                enabled = state.ready,
                            )
                        },
                    )
                },
            )
        },
    )
}

private fun Units.label(): String =
    when (this) {
        Units.Metric -> "Metric (g, ml)"
        Units.Imperial -> "Imperial (oz, lb, cups)"
    }
