package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailEvent
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesEvent
import io.github.milanhorvatovic.reducible.cookbook.session.Account
import io.github.milanhorvatovic.reducible.cookbook.session.ShellAction
import io.github.milanhorvatovic.reducible.cookbook.session.ShellState
import io.github.milanhorvatovic.reducible.cookbook.session.SignInEvent
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsScreenEvent
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEvent
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.ViewStore
import kotlinx.coroutines.flow.Flow

/**
 * Not a [io.github.milanhorvatovic.reducible.android.StoreViewModel]: the shell derives
 * everything from the app-scoped session on start, so a restored snapshot would only be
 * overwritten a moment later.
 */
class ShellViewModel(
    stores: CookbookStores,
) : ViewModel() {
    private val store = stores.shellStore(StoreScope.Inherited(viewModelScope))
    val view: ViewStore<ShellState, ShellAction.Ui> = stores.shellView(store)
}

/** The app root: which flow is on screen follows the shell's view of the session. */
@Composable
fun AppRoot() {
    val stores = LocalContext.current.cookbookStores
    val shell: ShellViewModel = viewModel(initializer = { ShellViewModel(stores) })
    val state by shell.view.stateFlow.collectAsStateWithLifecycle()
    when (val current = state) {
        ShellState.Starting -> Centered(content = { CircularProgressIndicator() })
        ShellState.SignedOut -> SignedOutFlow(stores)
        is ShellState.SignedIn -> SignedInFlow(stores, current.account, shell.view)
    }
}

// Screens never navigate: a screen asks its store, the store emits an event, and the host that
// owns the NavController collects it here. A destination collects only while it is composed,
// which is exactly when its screen can be asking.
@Composable
private fun SignedOutFlow(stores: CookbookStores) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "signIn",
        builder = {
            composable(
                route = "signIn",
                content = { backStackEntry ->
                    val viewModel: SignInViewModel =
                        viewModel(initializer = { SignInViewModel(createSavedStateHandle(), stores) })
                    OnEvents(
                        events = stores.signInEvents(viewModel.store),
                        handle = { event ->
                            when (event) {
                                SignInEvent.OpenDebug -> navController.navigate("debug")
                            }
                        },
                    )
                    SignInRoute(viewModel, debugAvailable = stores.diagnostics)
                },
            )
            composable(route = "debug", content = { backStackEntry -> DebugDestination(stores, navController) })
        },
    )
}

@Composable
private fun SignedInFlow(
    stores: CookbookStores,
    account: Account,
    shell: ViewStore<ShellState, ShellAction.Ui>,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "recipes",
        builder = {
            composable(
                route = "recipes",
                content = { backStackEntry ->
                    val viewModel: RecipesViewModel =
                        viewModel(initializer = { RecipesViewModel(createSavedStateHandle(), stores) })
                    OnEvents(
                        events = stores.recipesEvents(viewModel.store),
                        handle = { event ->
                            when (event) {
                                is RecipesEvent.OpenRecipe -> navController.navigate("recipe/${event.id}")
                                RecipesEvent.OpenSettings -> navController.navigate("settings")
                                RecipesEvent.OpenDebug -> navController.navigate("debug")
                                RecipesEvent.SignOut -> shell.send(ShellAction.SignOut)
                            }
                        },
                    )
                    RecipesRoute(viewModel, account, debugAvailable = stores.diagnostics)
                },
            )
            composable(
                route = "settings",
                content = { backStackEntry ->
                    val viewModel: SettingsViewModel = viewModel(initializer = { SettingsViewModel(stores) })
                    OnEvents(
                        events = stores.settingsEvents(viewModel.store),
                        handle = { event ->
                            when (event) {
                                SettingsScreenEvent.Close -> navController.popBackStack()
                            }
                        },
                    )
                    SettingsRoute(viewModel)
                },
            )
            composable(
                route = "recipe/{id}",
                arguments = listOf(navArgument(name = "id", builder = { type = NavType.StringType })),
                content = { backStackEntry ->
                    val recipeId = requireNotNull(backStackEntry.arguments?.getString("id"))
                    val viewModel: RecipeDetailViewModel =
                        viewModel(initializer = { RecipeDetailViewModel(createSavedStateHandle(), stores, recipeId) })
                    OnEvents(
                        events = stores.recipeDetailEvents(viewModel.store),
                        handle = { event ->
                            when (event) {
                                RecipeDetailEvent.Close -> navController.popBackStack()
                            }
                        },
                    )
                    RecipeDetailRoute(viewModel)
                },
            )
            composable(route = "debug", content = { backStackEntry -> DebugDestination(stores, navController) })
        },
    )
}

/** The debug screen sits in both flows; with diagnostics off no screen offers a way in. */
@Composable
private fun DebugDestination(
    stores: CookbookStores,
    navController: NavController,
) {
    val viewModel: DebugViewModel = viewModel(initializer = { DebugViewModel(stores) })
    OnEvents(
        events = stores.debugEvents(viewModel.store),
        handle = { event ->
            when (event) {
                DebugEvent.Close -> navController.popBackStack()
            }
        },
    )
    DebugRoute(viewModel)
}

/**
 * Collects a store's events for as long as the destination is composed. Keyed on nothing: the
 * store outlives every recomposition, so the flow taken at the first one stays right, and the
 * handler is read fresh so it never goes stale.
 */
@Composable
private fun <E : Any> OnEvents(
    events: Flow<E>,
    handle: (E) -> Unit,
) {
    val current by rememberUpdatedState(handle)
    LaunchedEffect(key1 = Unit, block = { events.collect { event -> current(event) } })
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = { content() })
}
