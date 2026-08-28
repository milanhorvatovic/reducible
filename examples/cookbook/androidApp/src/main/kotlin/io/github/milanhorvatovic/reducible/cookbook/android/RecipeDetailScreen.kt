package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.milanhorvatovic.reducible.android.StoreViewModel
import io.github.milanhorvatovic.reducible.cookbook.notes.EditorAction
import io.github.milanhorvatovic.reducible.cookbook.notes.EditorViewState
import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesError
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.DetailPhase
import io.github.milanhorvatovic.reducible.cookbook.recipes.IngredientsViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailState
import io.github.milanhorvatovic.reducible.cookbook.recipes.StepAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.StepsViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.TimerStatus
import io.github.milanhorvatovic.reducible.cookbook.recipes.stepAction
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.cookbook.shared.RecipeDetailViews
import io.github.milanhorvatovic.reducible.runtime.ViewStore

/** Saved state keeps servings, timers, and the notes editor across process death; Started re-converges them. */
class RecipeDetailViewModel(
    savedStateHandle: SavedStateHandle,
    stores: CookbookStores,
    recipeId: String,
) : StoreViewModel<RecipeDetailState, RecipeDetailAction>(
        savedStateHandle = savedStateHandle,
        serializer = RecipeDetailState.serializer(),
        createStore = { restored, scope -> stores.recipeDetailStore(recipeId, restored, scope) },
    ) {
    val views: RecipeDetailViews = stores.recipeDetailViews(store)
}

@Composable
fun RecipeDetailRoute(viewModel: RecipeDetailViewModel) {
    val state by viewModel.views.detail.stateFlow
        .collectAsStateWithLifecycle()
    RecipeDetailScreen(phase = state.phase, views = viewModel.views, send = viewModel.views.detail::send)
}

private enum class DetailTab(
    val label: String,
) {
    Ingredients("Ingredients"),
    Steps("Steps"),
    Notes("Notes"),
}

/** Each tab collects its own nested view, so a timer tick re-renders the steps tab and nothing else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    phase: DetailPhase,
    views: RecipeDetailViews,
    send: (RecipeDetailAction.Ui) -> Unit,
) {
    var tab by rememberSaveable(init = { mutableStateOf(DetailTab.Ingredients) })
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((phase as? DetailPhase.Ready)?.title ?: "Recipe") },
                navigationIcon = {
                    TextButton(onClick = { send(RecipeDetailAction.BackClicked) }, content = { Text("Back") })
                },
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                content = {
                    when (phase) {
                        DetailPhase.Loading -> {
                            Centered(content = { CircularProgressIndicator() })
                        }

                        is DetailPhase.Failed -> {
                            Centered(
                                content = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        content = {
                                            Text(phase.error.label(), style = MaterialTheme.typography.bodyLarge)
                                            Button(onClick = { send(RecipeDetailAction.Retry) }, content = { Text("Retry") })
                                        },
                                    )
                                },
                            )
                        }

                        is DetailPhase.Ready -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                content = {
                                    Text(phase.summary, style = MaterialTheme.typography.bodyMedium)
                                    Text("${phase.minutes} min", style = MaterialTheme.typography.labelSmall)
                                    phase.failure?.let { failure ->
                                        Text(
                                            failure.label(),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                            )
                            TabRow(
                                selectedTabIndex = tab.ordinal,
                                tabs = {
                                    DetailTab.entries.forEach { candidate ->
                                        Tab(
                                            selected = tab == candidate,
                                            onClick = { tab = candidate },
                                            text = { Text(candidate.label) },
                                        )
                                    }
                                },
                            )
                            when (tab) {
                                DetailTab.Ingredients -> IngredientsTab(views.ingredients)
                                DetailTab.Steps -> StepsTab(views.steps)
                                DetailTab.Notes -> NotesTab(views.notes)
                            }
                        }
                    }
                },
            )
        },
    )
}

@Composable
private fun IngredientsTab(view: ViewStore<IngredientsViewState, RecipeDetailAction.Servings>) {
    val state by view.stateFlow.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        content = {
            item(
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = {
                            Text(
                                "Servings: ${state.servings}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { view.send(RecipeDetailAction.Decreased) },
                                enabled = state.canDecrease,
                                content = { Text("−") },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { view.send(RecipeDetailAction.Increased) }, content = { Text("+") })
                        },
                    )
                },
            )
            items(
                items = state.lines,
                itemContent = { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        content = {
                            Text(line.name, modifier = Modifier.weight(1f))
                            Text(line.amount, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                    HorizontalDivider()
                },
            )
        },
    )
}

@Composable
private fun StepsTab(view: ViewStore<StepsViewState, RecipeDetailAction.Ui>) {
    val state by view.stateFlow.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        content = {
            items(
                items = state.lines,
                key = { line -> line.index },
                itemContent = { line ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        content = {
                            Text("${line.index + 1}. ${line.text}", style = MaterialTheme.typography.bodyLarge)
                            line.timer?.let { timer ->
                                TimerControls(timer, send = { action -> view.send(stepAction(line.index, action)) })
                            }
                        },
                    )
                    HorizontalDivider()
                },
            )
        },
    )
}

@Composable
private fun TimerControls(
    timer: TimerStatus,
    send: (StepAction.Ui) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            when (timer) {
                is TimerStatus.Idle -> {
                    TextButton(onClick = { send(StepAction.Start) }, content = { Text("Start ${timer.totalSeconds.clock()}") })
                }

                is TimerStatus.Running -> {
                    Text(timer.remainingSeconds.clock(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { send(StepAction.Pause) }, content = { Text("Pause") })
                    TextButton(onClick = { send(StepAction.Reset) }, content = { Text("Reset") })
                }

                is TimerStatus.Paused -> {
                    Text(
                        timer.remainingSeconds.clock(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { send(StepAction.Start) }, content = { Text("Resume") })
                    TextButton(onClick = { send(StepAction.Reset) }, content = { Text("Reset") })
                }

                TimerStatus.Done -> {
                    Text("Done", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                    TextButton(onClick = { send(StepAction.Reset) }, content = { Text("Reset") })
                }
            }
        },
    )
}

private fun Int.clock(): String = "%d:%02d".format(this / 60, this % 60)

/** The notes feature's own UI, driven through the nested view: it never learns it lives inside a recipe. */
@Composable
private fun NotesTab(view: ViewStore<NotesViewState, NotesAction.Ui>) {
    val state by view.stateFlow.collectAsStateWithLifecycle()
    when (val notes = state) {
        is NotesViewState.Busy -> {
            Centered(content = { CircularProgressIndicator() })
        }

        is NotesViewState.Failed -> {
            Centered(
                content = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = {
                            Text(notes.error.label(), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { view.send(NotesAction.Retry) }, content = { Text("Retry") })
                        },
                    )
                },
            )
        }

        is NotesViewState.Notes -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            items(
                                items = notes.notes,
                                key = Note::id,
                                itemContent = { note ->
                                    Text(
                                        note.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    )
                                    HorizontalDivider()
                                },
                            )
                        },
                    )
                    if (notes.canAdd) {
                        FloatingActionButton(
                            onClick = { view.send(NotesAction.AddClicked) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            content = { Text("+") },
                        )
                    }
                    notes.editor?.let { editor -> EditorDialog(editor = editor, send = view::send) }
                },
            )
        }
    }
}

@Composable
private fun EditorDialog(
    editor: EditorViewState,
    send: (NotesAction.Ui) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { send(NotesAction.EditorDismissed) },
        title = { Text("New note") },
        text = {
            Column(
                content = {
                    OutlinedTextField(
                        value = editor.draft,
                        onValueChange = { text -> send(NotesAction.Editor(EditorAction.DraftChanged(text))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    editor.error?.let { error ->
                        Text(error.label(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    editor.hint?.let { hint ->
                        Text(hint, style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { send(NotesAction.Editor(EditorAction.Save)) }, content = { Text("Save") })
        },
        dismissButton = {
            TextButton(onClick = { send(NotesAction.EditorDismissed) }, content = { Text("Cancel") })
        },
    )
}

private fun NotesError.label(): String =
    when (this) {
        NotesError.Offline -> "You appear to be offline."
        is NotesError.Unexpected -> "Something went wrong: $message"
    }
