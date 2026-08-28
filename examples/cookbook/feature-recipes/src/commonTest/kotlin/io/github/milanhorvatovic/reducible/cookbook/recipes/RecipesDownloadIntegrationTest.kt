package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private object TwoRecipes : RecipesRepository {
    override suspend fun loadRecipes(): List<Recipe> {
        delay(100)
        return listOf(soup, pancakes)
    }

    override suspend fun recipe(id: String): Recipe {
        delay(100)
        return listOf(soup, pancakes).first { recipe -> recipe.id == id }
    }

    override suspend fun search(query: String): Set<String> {
        delay(50)
        return listOf(soup, pancakes).filter { recipe -> query in recipe.title.lowercase() }.map { recipe -> recipe.id }.toSet()
    }
}

/** Two progress ticks, one every 100 ms; [failing] ids fail at the first tick. */
private class TickingDownloads(
    private val failing: Set<String> = emptySet(),
) : RecipeDownloads {
    val started = mutableListOf<String>()

    override suspend fun download(
        recipeId: String,
        onProgress: (percent: Int) -> Unit,
    ) {
        started += recipeId
        delay(100)
        if (recipeId in failing) {
            throw RecipesException(RecipesError.Offline)
        }
        onProgress(50)
        delay(100)
        onProgress(100)
    }
}

private fun row(
    id: String,
    action: RecipeRowAction,
): RecipesAction =
    RecipesAction.Row(
        io.github.milanhorvatovic.reducible
            .IdentifiedAction(id, action),
    )

// The identified-row machinery against the real reducer and handler under virtual time: two
// rows download at once under the same row-level key without cancelling each other, and
// dismissing one row cancels exactly that row's download.
class RecipesDownloadIntegrationTest {
    @Test
    fun rows_download_concurrently_under_their_own_keys() =
        runTest {
            val downloads = TickingDownloads()
            val store =
                testStore(RecipesState.Loading, recipesReducer, recipesEffectHandler(TwoRecipes, downloads, searchWindow = { delay(300) }))

            store.send(RecipesAction.Started)
            advanceUntilIdle()
            store.send(recipeRowAction("soup", RecipeRowAction.DownloadClicked))
            store.send(recipeRowAction("pancakes", RecipeRowAction.DownloadClicked))
            advanceUntilIdle()

            store
                .expectAction(RecipesAction.Started)
                .expectAction(RecipesAction.Loaded(listOf(soup, pancakes)))
                .expectAction(row("soup", RecipeRowAction.DownloadClicked))
                .expectAction(row("pancakes", RecipeRowAction.DownloadClicked))
                .expectAction(row("soup", RecipeRowAction.Progress(50)))
                .expectAction(row("pancakes", RecipeRowAction.Progress(50)))
                // Completed follows the last tick without suspending, so each row finishes in one go.
                .expectAction(row("soup", RecipeRowAction.Progress(100)))
                .expectAction(row("soup", RecipeRowAction.Completed))
                .expectAction(row("pancakes", RecipeRowAction.Progress(100)))
                .expectAction(
                    row("pancakes", RecipeRowAction.Completed),
                    resulting =
                        RecipesState.Loaded(
                            persistentListOf(
                                RecipeRowState.Offline(soup, favorite = false),
                                RecipeRowState.Offline(pancakes, favorite = false),
                            ),
                        ),
                )
            store.finish()
            assertEquals(listOf("soup", "pancakes"), downloads.started)
        }

    @Test
    fun dismissing_a_row_cancels_only_its_download() =
        runTest {
            val downloads = TickingDownloads()
            val store =
                testStore(RecipesState.Loading, recipesReducer, recipesEffectHandler(TwoRecipes, downloads, searchWindow = { delay(300) }))

            store.send(RecipesAction.Started)
            advanceUntilIdle()
            store.send(recipeRowAction("soup", RecipeRowAction.DownloadClicked))
            store.send(recipeRowAction("pancakes", RecipeRowAction.DownloadClicked))
            advanceTimeBy(150)
            store.send(RecipesAction.Dismissed("soup"))
            advanceUntilIdle()

            store
                .expectAction(RecipesAction.Started)
                .expectAction(RecipesAction.Loaded(listOf(soup, pancakes)))
                .expectAction(row("soup", RecipeRowAction.DownloadClicked))
                .expectAction(row("pancakes", RecipeRowAction.DownloadClicked))
                .expectAction(row("soup", RecipeRowAction.Progress(50)))
                .expectAction(row("pancakes", RecipeRowAction.Progress(50)))
                .expectAction(RecipesAction.Dismissed("soup"))
                // Nothing more from soup: its download died with its row. Pancakes runs to the end.
                .expectAction(row("pancakes", RecipeRowAction.Progress(100)))
                .expectAction(
                    row("pancakes", RecipeRowAction.Completed),
                    resulting =
                        RecipesState.Loaded(persistentListOf(RecipeRowState.Offline(pancakes, favorite = false)), hidden = setOf("soup")),
                )
            store.finish()
        }

    @Test
    fun a_failing_download_lands_in_the_failed_phase_of_its_row_only() =
        runTest {
            val downloads = TickingDownloads(failing = setOf("soup"))
            val store =
                testStore(RecipesState.Loading, recipesReducer, recipesEffectHandler(TwoRecipes, downloads, searchWindow = { delay(300) }))

            store.send(RecipesAction.Started)
            advanceUntilIdle()
            store.send(recipeRowAction("soup", RecipeRowAction.DownloadClicked))
            store.send(recipeRowAction("pancakes", RecipeRowAction.DownloadClicked))
            advanceUntilIdle()

            store
                .expectAction(RecipesAction.Started)
                .expectAction(RecipesAction.Loaded(listOf(soup, pancakes)))
                .expectAction(row("soup", RecipeRowAction.DownloadClicked))
                .expectAction(row("pancakes", RecipeRowAction.DownloadClicked))
                .expectAction(row("soup", RecipeRowAction.DownloadFailed(RecipesError.Offline)))
                .expectAction(row("pancakes", RecipeRowAction.Progress(50)))
                .expectAction(row("pancakes", RecipeRowAction.Progress(100)))
                .expectAction(
                    row("pancakes", RecipeRowAction.Completed),
                    resulting =
                        RecipesState.Loaded(
                            persistentListOf(
                                RecipeRowState.Failed(soup, favorite = false, RecipesError.Offline),
                                RecipeRowState.Offline(pancakes, favorite = false),
                            ),
                        ),
                )
            store.finish()
        }

    @Test
    fun search_waits_for_typing_to_settle_and_answers_the_last_query_only() =
        runTest {
            val store =
                testStore(
                    RecipesState.Loading,
                    recipesReducer,
                    recipesEffectHandler(TwoRecipes, TickingDownloads(), searchWindow = { delay(300) }),
                )

            store.send(RecipesAction.Started)
            advanceUntilIdle()
            store.send(RecipesAction.QueryChanged("p"))
            advanceTimeBy(100)
            store.send(RecipesAction.QueryChanged("pa"))
            advanceUntilIdle()

            store
                .expectAction(RecipesAction.Started)
                .expectAction(RecipesAction.Loaded(listOf(soup, pancakes)))
                .expectAction(RecipesAction.QueryChanged("p"))
                .expectAction(RecipesAction.QueryChanged("pa"))
                // One search: the keyed effect for "p" was replaced while it waited in its window.
                .expectAction(
                    RecipesAction.Searched("pa", setOf("pancakes")),
                    resulting =
                        RecipesState.Loaded(
                            persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Remote(pancakes)),
                            query = "pa",
                            matching = setOf("pancakes"),
                        ),
                )
            store.finish()
        }
}
