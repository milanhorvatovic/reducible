package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.milanhorvatovic.reducible.android.StoreViewModel
import io.github.milanhorvatovic.reducible.cookbook.recipes.DownloadStatus
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeRowAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeRowViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesError
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipeRowAction
import io.github.milanhorvatovic.reducible.cookbook.session.Account
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.runtime.ViewStore

/** Saved state keeps the search and each row's phase across process death; Started re-converges them. */
class RecipesViewModel(
    savedStateHandle: SavedStateHandle,
    stores: CookbookStores,
) : StoreViewModel<RecipesState, RecipesAction>(
        savedStateHandle = savedStateHandle,
        serializer = RecipesState.serializer(),
        createStore = { restored, scope -> stores.recipesStore(restored, scope) },
    ) {
    val view: ViewStore<RecipesViewState, RecipesAction.Ui> = stores.recipesView(store)
}

/** [debugAvailable] hides the entry with diagnostics off, when the debug screen would be empty. */
@Composable
fun RecipesRoute(
    viewModel: RecipesViewModel,
    account: Account,
    debugAvailable: Boolean,
) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    RecipesScreen(state = state, account = account, send = viewModel.view::send, debugAvailable = debugAvailable)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    state: RecipesViewState,
    account: Account,
    send: (RecipesAction.Ui) -> Unit,
    debugAvailable: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        content = {
                            Text("Cookbook")
                            Text(account.displayName, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                },
                actions = {
                    TextButton(onClick = { send(RecipesAction.SettingsClicked) }, content = { Text("Settings") })
                    if (debugAvailable) {
                        TextButton(onClick = { send(RecipesAction.DebugClicked) }, content = { Text("Debug") })
                    }
                    TextButton(onClick = { send(RecipesAction.SignOutClicked) }, content = { Text("Sign out") })
                },
            )
        },
        content = { padding ->
            when (state) {
                RecipesViewState.Loading -> {
                    Centered(content = { CircularProgressIndicator() })
                }

                is RecipesViewState.Failed -> {
                    Centered(
                        content = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                content = {
                                    Text(state.error.label(), style = MaterialTheme.typography.bodyLarge)
                                    Button(onClick = { send(RecipesAction.Retry) }, content = { Text("Retry") })
                                },
                            )
                        },
                    )
                }

                is RecipesViewState.Content -> {
                    RecipesContent(state, send, Modifier.padding(padding))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipesContent(
    state: RecipesViewState.Content,
    send: (RecipesAction.Ui) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        content = {
            OutlinedTextField(
                value = state.query,
                onValueChange = { text -> send(RecipesAction.QueryChanged(text)) },
                label = { Text("Search recipes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.failure?.let { failure ->
                Text(
                    failure.label(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { send(RecipesAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
                content = {
                    if (state.rows.isEmpty()) {
                        Centered(
                            content = {
                                Text(
                                    if (state.narrowed) {
                                        "No recipes match."
                                    } else {
                                        "No recipes yet."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            content = {
                                items(
                                    items = state.rows,
                                    key = { row -> row.id },
                                    itemContent = { row ->
                                        RecipeRow(
                                            row = row,
                                            send = { action -> send(recipeRowAction(row.id, action)) },
                                            onOpen = { send(RecipesAction.RecipeClicked(row.id)) },
                                            onHide = { send(RecipesAction.Dismissed(row.id)) },
                                        )
                                        HorizontalDivider()
                                    },
                                )
                            },
                        )
                    }
                },
            )
        },
    )
}

@Composable
private fun RecipeRow(
    row: RecipeRowViewState,
    send: (RecipeRowAction.Ui) -> Unit,
    onOpen: () -> Unit,
    onHide: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = {
                    Column(
                        modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                        content = {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                row.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("${row.minutes} min · serves ${row.servings}", style = MaterialTheme.typography.labelSmall)
                        },
                    )
                    IconButton(
                        onClick = { send(RecipeRowAction.FavoriteToggled) },
                        content = {
                            Text(
                                if (row.favorite) {
                                    "★"
                                } else {
                                    "☆"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                    )
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = {
                    when (val download = row.download) {
                        DownloadStatus.Remote -> {
                            TextButton(onClick = { send(RecipeRowAction.DownloadClicked) }, content = { Text("Download") })
                        }

                        is DownloadStatus.Downloading -> {
                            LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.width(120.dp))
                            TextButton(
                                onClick = { send(RecipeRowAction.CancelClicked) },
                                content = { Text("Cancel ${download.percent}%") },
                            )
                        }

                        DownloadStatus.Offline -> {
                            Text(
                                "Available offline",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            TextButton(onClick = { send(RecipeRowAction.RemoveOfflineClicked) }, content = { Text("Remove") })
                        }

                        is DownloadStatus.Failed -> {
                            Text(
                                download.error.label(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { send(RecipeRowAction.DownloadClicked) }, content = { Text("Retry") })
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onHide, content = { Text("Hide") })
                },
            )
        },
    )
}

fun RecipesError.label(): String =
    when (this) {
        RecipesError.Offline -> "You appear to be offline."
        is RecipesError.Unexpected -> "Something went wrong: $message"
    }
