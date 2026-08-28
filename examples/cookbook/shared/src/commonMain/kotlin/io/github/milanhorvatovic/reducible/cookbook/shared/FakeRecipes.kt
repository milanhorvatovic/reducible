package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.recipes.Ingredient
import io.github.milanhorvatovic.reducible.cookbook.recipes.Measure
import io.github.milanhorvatovic.reducible.cookbook.recipes.Recipe
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDownloads
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesError
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesException
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesRepository
import io.github.milanhorvatovic.reducible.cookbook.recipes.Step
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demo-only recipes: a fixed seed with simulated latency. [failureInjected] is read on every
 * call, so flipping the setting fails the next load or search and the retry paths show.
 */
public class FakeRecipesRepository(
    private val failureInjected: () -> Boolean = { false },
    private val simulatedLatency: Duration = 600.milliseconds,
) : RecipesRepository {
    override suspend fun loadRecipes(): List<Recipe> {
        delay(simulatedLatency)
        if (failureInjected()) {
            throw RecipesException(RecipesError.Offline)
        }
        return seedRecipes
    }

    override suspend fun recipe(id: String): Recipe {
        delay(simulatedLatency)
        if (failureInjected()) {
            throw RecipesException(RecipesError.Offline)
        }
        return seedRecipes.firstOrNull { recipe -> recipe.id == id } ?: throw RecipesException(RecipesError.Unexpected("No recipe '$id'"))
    }

    override suspend fun search(query: String): Set<String> {
        delay(simulatedLatency)
        if (failureInjected()) {
            throw RecipesException(RecipesError.Offline)
        }
        val needle = query.trim()
        return seedRecipes
            .filter { recipe ->
                recipe.title.contains(needle, ignoreCase = true) ||
                    recipe.summary.contains(needle, ignoreCase = true) ||
                    recipe.ingredients.any { ingredient -> ingredient.name.contains(needle, ignoreCase = true) }
            }.map { recipe -> recipe.id }
            .toSet()
    }
}

/** Demo-only downloads: five progress ticks, failing at the third when [failureInjected] says so. */
public class FakeRecipeDownloads(
    private val failureInjected: () -> Boolean = { false },
    private val tick: Duration = 400.milliseconds,
) : RecipeDownloads {
    override suspend fun download(
        recipeId: String,
        onProgress: (percent: Int) -> Unit,
    ) {
        repeat(5) { step ->
            delay(tick)
            if (step == 2 && failureInjected()) {
                throw RecipesException(RecipesError.Offline)
            }
            onProgress((step + 1) * 20)
        }
    }
}

private val seedRecipes: List<Recipe> =
    listOf(
        Recipe(
            id = "tomato-soup",
            title = "Tomato soup",
            summary = "Quick weeknight soup with basil",
            servings = 4,
            minutes = 30,
            ingredients =
                listOf(
                    Ingredient("Ripe tomatoes", 800.0, Measure.Gram),
                    Ingredient("Onion", 1.0, Measure.Piece),
                    Ingredient("Olive oil", 2.0, Measure.Tablespoon),
                    Ingredient("Vegetable stock", 500.0, Measure.Milliliter),
                ),
            steps =
                listOf(
                    Step("Soften the onion in olive oil."),
                    Step("Add tomatoes and stock, then simmer.", timerSeconds = 900),
                    Step("Blend smooth and season."),
                ),
        ),
        Recipe(
            id = "pancakes",
            title = "Pancakes",
            summary = "Fluffy Sunday breakfast",
            servings = 2,
            minutes = 20,
            ingredients =
                listOf(
                    Ingredient("Flour", 200.0, Measure.Gram),
                    Ingredient("Milk", 300.0, Measure.Milliliter),
                    Ingredient("Egg", 1.0, Measure.Piece),
                    Ingredient("Baking powder", 2.0, Measure.Teaspoon),
                ),
            steps =
                listOf(
                    Step("Whisk everything into a smooth batter."),
                    Step("Rest the batter.", timerSeconds = 300),
                    Step("Fry ladles of batter until golden on both sides."),
                ),
        ),
        Recipe(
            id = "guacamole",
            title = "Guacamole",
            summary = "Chunky, limey, ready in ten minutes",
            servings = 4,
            minutes = 10,
            ingredients =
                listOf(
                    Ingredient("Avocado", 3.0, Measure.Piece),
                    Ingredient("Lime juice", 2.0, Measure.Tablespoon),
                    Ingredient("Red onion", 0.5, Measure.Piece),
                    Ingredient("Salt", 1.0, Measure.Teaspoon),
                ),
            steps =
                listOf(
                    Step("Mash the avocados coarsely."),
                    Step("Fold in onion, lime, and salt."),
                ),
        ),
        Recipe(
            id = "lentil-curry",
            title = "Lentil curry",
            summary = "Red lentils, coconut, warming spices",
            servings = 4,
            minutes = 40,
            ingredients =
                listOf(
                    Ingredient("Red lentils", 250.0, Measure.Gram),
                    Ingredient("Coconut milk", 400.0, Measure.Milliliter),
                    Ingredient("Curry paste", 2.0, Measure.Tablespoon),
                    Ingredient("Spinach", 100.0, Measure.Gram),
                ),
            steps =
                listOf(
                    Step("Fry the curry paste briefly."),
                    Step("Add lentils and coconut milk, then simmer.", timerSeconds = 1500),
                    Step("Stir in spinach until wilted."),
                ),
        ),
        Recipe(
            id = "focaccia",
            title = "Focaccia",
            summary = "Dimpled olive oil bread",
            servings = 8,
            minutes = 150,
            ingredients =
                listOf(
                    Ingredient("Bread flour", 500.0, Measure.Gram),
                    Ingredient("Water", 400.0, Measure.Milliliter),
                    Ingredient("Yeast", 1.0, Measure.Teaspoon),
                    Ingredient("Olive oil", 4.0, Measure.Tablespoon),
                ),
            steps =
                listOf(
                    Step("Mix into a wet dough."),
                    Step("Let it rise.", timerSeconds = 3600),
                    Step("Dimple, oil, and bake.", timerSeconds = 1500),
                ),
        ),
        Recipe(
            id = "miso-ramen",
            title = "Miso ramen",
            summary = "Weeknight bowl with a rich broth",
            servings = 2,
            minutes = 35,
            ingredients =
                listOf(
                    Ingredient("Ramen noodles", 200.0, Measure.Gram),
                    Ingredient("Miso paste", 3.0, Measure.Tablespoon),
                    Ingredient("Stock", 900.0, Measure.Milliliter),
                    Ingredient("Egg", 2.0, Measure.Piece),
                ),
            steps =
                listOf(
                    Step("Soft-boil the eggs.", timerSeconds = 420),
                    Step("Whisk miso into hot stock."),
                    Step("Cook the noodles and assemble."),
                ),
        ),
        Recipe(
            id = "greek-salad",
            title = "Greek salad",
            summary = "Tomatoes, cucumber, feta, no lettuce",
            servings = 4,
            minutes = 15,
            ingredients =
                listOf(
                    Ingredient("Tomatoes", 400.0, Measure.Gram),
                    Ingredient("Cucumber", 1.0, Measure.Piece),
                    Ingredient("Feta", 200.0, Measure.Gram),
                    Ingredient("Olive oil", 3.0, Measure.Tablespoon),
                ),
            steps =
                listOf(
                    Step("Chop everything chunky."),
                    Step("Dress with oil and oregano."),
                ),
        ),
        Recipe(
            id = "chocolate-mousse",
            title = "Chocolate mousse",
            summary = "Two ingredients, one hour to set",
            servings = 4,
            minutes = 75,
            ingredients =
                listOf(
                    Ingredient("Dark chocolate", 200.0, Measure.Gram),
                    Ingredient("Egg", 4.0, Measure.Piece),
                ),
            steps =
                listOf(
                    Step("Melt the chocolate gently."),
                    Step("Fold in whipped whites."),
                    Step("Chill until set.", timerSeconds = 3600),
                ),
        ),
    )
