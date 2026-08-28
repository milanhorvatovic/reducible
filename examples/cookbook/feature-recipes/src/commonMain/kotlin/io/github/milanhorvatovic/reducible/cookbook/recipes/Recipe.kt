package io.github.milanhorvatovic.reducible.cookbook.recipes

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
public data class Recipe(
    public val id: String,
    public val title: String,
    public val summary: String,
    public val servings: Int,
    public val minutes: Int,
    public val ingredients: List<Ingredient>,
    public val steps: List<Step>,
)

@Serializable
public data class Ingredient(
    public val name: String,
    public val quantity: Double,
    public val measure: Measure,
)

/** Metric base measures; screens convert for imperial settings. */
@Serializable
public enum class Measure {
    Gram,
    Milliliter,
    Piece,
    Teaspoon,
    Tablespoon,
}

@Serializable
public data class Step(
    public val text: String,
    public val timerSeconds: Int? = null,
)

@Serializable
public sealed interface RecipesError {
    @Serializable
    public data object Offline : RecipesError

    @Serializable
    public data class Unexpected(
        public val message: String,
    ) : RecipesError
}

public class RecipesException(
    public val error: RecipesError,
) : Exception("Recipes failed: $error")

/** The feature's data boundary; implementations are wired by the consuming app. */
public interface RecipesRepository {
    /** @throws RecipesException for expected failures. */
    public suspend fun loadRecipes(): List<Recipe>

    /** @throws RecipesException for expected failures, an unknown id included. */
    public suspend fun recipe(id: String): Recipe

    /**
     * Ids of the recipes matching [query], as a server-side search would answer.
     * @throws RecipesException for expected failures.
     */
    public suspend fun search(query: String): Set<String>
}

/** Fetches one recipe for offline use, reporting progress in percent. */
public interface RecipeDownloads {
    /** @throws RecipesException for expected failures. */
    public suspend fun download(
        recipeId: String,
        onProgress: (percent: Int) -> Unit,
    )
}
