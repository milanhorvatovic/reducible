import Combine
import Shared

/// Screen wiring only: each factory creates the screen's store — already started, since the
/// Kotlin factory sends the unconditional start action — hands its lifetime to a `StoreModel`,
/// and returns the screen's view over it — the only object the screen holds. Screens call these from `@StateObject`'s
/// autoclosure, which runs once per view identity; a `@State` set in `init` would run them,
/// and start a store, on every re-initialisation of the view struct.
@MainActor
func makeShellModel(_ stores: CookbookStores) -> ViewStoreModel<ShellState, ShellActionUi> {
    let store = stores.shellStore()
    let owner = StoreModel(store: store)
    return owner.view(stores.shellView(store: store))
}

/// The sign-in screen's model plus the events its host acts on, over one store. A class only
/// so `@StateObject` creates the pair once per view identity; it publishes nothing itself.
@MainActor
final class SignInModels: ObservableObject {
    let model: ViewStoreModel<SignInViewState, SignInActionUi>
    let events: SkieSwiftFlow<SignInEvent>

    init(model: ViewStoreModel<SignInViewState, SignInActionUi>, events: SkieSwiftFlow<SignInEvent>) {
        self.model = model
        self.events = events
    }
}

@MainActor
func makeSignInModels(_ stores: CookbookStores) -> SignInModels {
    let store = stores.signInStore(restored: nil)
    let owner = StoreModel(store: store)
    return SignInModels(model: owner.view(stores.signInView(store: store)), events: stores.signInEvents(store: store))
}

@MainActor
func makeDebugModel(_ stores: CookbookStores) -> ViewStoreModel<DebugState, DebugActionUi> {
    let store = stores.debugStore()
    let owner = StoreModel(store: store)
    return owner.view(stores.debugView(store: store))
}

/// The recipes screen's model plus the events its host acts on, over one store; see `SignInModels`.
@MainActor
final class RecipesModels: ObservableObject {
    let model: ProjectedModel<RecipesViewState, RecipesActionUi, RecipesUI>
    let events: SkieSwiftFlow<RecipesEvent>

    init(
        model: ProjectedModel<RecipesViewState, RecipesActionUi, RecipesUI>,
        events: SkieSwiftFlow<RecipesEvent>
    ) {
        self.model = model
        self.events = events
    }
}

@MainActor
func makeRecipesModels(_ stores: CookbookStores) -> RecipesModels {
    let store = stores.recipesStore(restored: nil)
    let owner = StoreModel(store: store)
    // The list renders from Swift values: rows, strings, and phases are converted once per
    // reduction instead of on every body evaluation and scroll.
    return RecipesModels(
        model: owner.project(stores.recipesView(store: store), recipesUI),
        events: stores.recipesEvents(store: store)
    )
}

/// The detail screen's four models over one store, all retaining the same owner, so the store
/// closes once the last tab model is gone. A class conforming to `ObservableObject` only so the
/// screen can hold it in `@StateObject` and create it once; it publishes nothing itself.
@MainActor
final class RecipeDetailModels: ObservableObject {
    let detail: ViewStoreModel<RecipeDetailViewState, RecipeDetailActionUi>
    let ingredients: ViewStoreModel<IngredientsViewState, RecipeDetailActionServings>
    let steps: ProjectedModel<StepsViewState, RecipeDetailActionUi, [StepUI]>
    let notes: ViewStoreModel<NotesViewState, NotesActionUi>

    init(
        detail: ViewStoreModel<RecipeDetailViewState, RecipeDetailActionUi>,
        ingredients: ViewStoreModel<IngredientsViewState, RecipeDetailActionServings>,
        steps: ProjectedModel<StepsViewState, RecipeDetailActionUi, [StepUI]>,
        notes: ViewStoreModel<NotesViewState, NotesActionUi>
    ) {
        self.detail = detail
        self.ingredients = ingredients
        self.steps = steps
        self.notes = notes
    }
}

@MainActor
func makeRecipeDetailModels(_ stores: CookbookStores, recipeId: String) -> RecipeDetailModels {
    let store = stores.recipeDetailStore(recipeId: recipeId, restored: nil)
    let owner = StoreModel(store: store)
    let views = stores.recipeDetailViews(store: store)
    return RecipeDetailModels(
        detail: owner.view(views.detail),
        ingredients: owner.view(views.ingredients),
        // Timers tick once a second; the steps tab renders from Swift values so a tick costs
        // one conversion, not a boundary read per step.
        steps: owner.project(views.steps, stepsUI),
        notes: owner.view(views.notes)
    )
}
