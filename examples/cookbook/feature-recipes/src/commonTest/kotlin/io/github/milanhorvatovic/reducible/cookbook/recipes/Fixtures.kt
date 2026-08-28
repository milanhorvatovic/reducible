package io.github.milanhorvatovic.reducible.cookbook.recipes

internal val soup =
    Recipe(
        id = "soup",
        title = "Tomato soup",
        summary = "Quick weeknight soup",
        servings = 4,
        minutes = 30,
        ingredients = listOf(Ingredient("Tomatoes", 800.0, Measure.Gram)),
        steps = listOf(Step("Simmer", timerSeconds = 900)),
    )

internal val pancakes =
    Recipe(
        id = "pancakes",
        title = "Pancakes",
        summary = "Sunday breakfast",
        servings = 2,
        minutes = 20,
        ingredients = listOf(Ingredient("Flour", 200.0, Measure.Gram)),
        steps = listOf(Step("Fry")),
    )

internal val focaccia =
    Recipe(
        id = "focaccia",
        title = "Focaccia",
        summary = "Olive oil bread",
        servings = 6,
        minutes = 120,
        ingredients = listOf(Ingredient("Flour", 500.0, Measure.Gram)),
        steps = listOf(Step("Proof", timerSeconds = 3600)),
    )
