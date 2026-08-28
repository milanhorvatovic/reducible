package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.cookbook.notes.notesEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailEvent
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDownloads
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesEvent
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesRepository
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipesViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipeDetailEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipeDetailReducer
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipesEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipesReducer
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipesViewState
import io.github.milanhorvatovic.reducible.cookbook.session.AuditSink
import io.github.milanhorvatovic.reducible.cookbook.session.AuthGateway
import io.github.milanhorvatovic.reducible.cookbook.session.SessionAction
import io.github.milanhorvatovic.reducible.cookbook.session.SessionGateway
import io.github.milanhorvatovic.reducible.cookbook.session.SessionState
import io.github.milanhorvatovic.reducible.cookbook.session.ShellAction
import io.github.milanhorvatovic.reducible.cookbook.session.ShellState
import io.github.milanhorvatovic.reducible.cookbook.session.SignInAction
import io.github.milanhorvatovic.reducible.cookbook.session.SignInEvent
import io.github.milanhorvatovic.reducible.cookbook.session.SignInState
import io.github.milanhorvatovic.reducible.cookbook.session.SignInViewState
import io.github.milanhorvatovic.reducible.cookbook.session.sessionEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.session.sessionReducer
import io.github.milanhorvatovic.reducible.cookbook.session.shellEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.session.shellReducer
import io.github.milanhorvatovic.reducible.cookbook.session.signInEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.session.signInReducer
import io.github.milanhorvatovic.reducible.cookbook.session.signInViewState
import io.github.milanhorvatovic.reducible.cookbook.settings.Settings
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsAction
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsGateway
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsRepository
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsScreenAction
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsScreenEvent
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsScreenState
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsState
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsViewState
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.cookbook.settings.settingsEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.settings.settingsReducer
import io.github.milanhorvatovic.reducible.cookbook.settings.settingsScreenEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.settings.settingsScreenReducer
import io.github.milanhorvatovic.reducible.cookbook.settings.settingsViewState
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugAction
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEvent
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugLog
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugState
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.ReplayVerdict
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.debugEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.debugReducer
import io.github.milanhorvatovic.reducible.replay
import io.github.milanhorvatovic.reducible.runtime.ActionRecorder
import io.github.milanhorvatovic.reducible.runtime.DefectHandler
import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreObserver
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.ViewStore
import io.github.milanhorvatovic.reducible.runtime.events
import io.github.milanhorvatovic.reducible.runtime.feedInto
import io.github.milanhorvatovic.reducible.runtime.loggingDefectHandler
import io.github.milanhorvatovic.reducible.runtime.view
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.github.milanhorvatovic.reducible.runtime.plus as fanOut

/**
 * The demo app's composition root: the app-scoped stores, alive for the process, plus the
 * factories every screen store and its view are built from. Each factory passes the feature's
 * unconditional start action, so a holder on either platform only wires and closes. One instance per app; the
 * platform's application object owns it and calls [close] never in practice, since the
 * app-scoped stores live exactly as long as the process.
 *
 * [settingsScope] is the platform's [StoreScope.Dedicated] — a named single-thread
 * executor on Android, a serial GCD queue on iOS — so the settings store reduces on a thread
 * the platform can recognize in a stack trace. Screen stores reduce on [StoreScope.Main]
 * unless their holder passes a scope of its own — [StoreScope.Inherited] over an Android
 * ViewModel's, which then closes the store with the ViewModel.
 *
 * [diagnostics] attaches the debug log and the session recorder to every store. Off, no store
 * is observed and nothing is stringified per reduction — the largest avoidable cost in the
 * demo — and defects are printed instead of logged. Platforms pass their debug flag.
 *
 * Each screen factory comes with its view (`*View`) and, where the screen asks its holder for
 * something, its events (`*Events`): the store's [Store.events] narrowed to the screen's own
 * event type, for the navigation host to collect.
 */
@OptIn(ExperimentalAtomicApi::class)
public class CookbookStores(
    settingsScope: StoreScope,
    public val diagnostics: Boolean,
    auth: AuthGateway = FakeAuthGateway(),
    settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    recipesRepository: RecipesRepository? = null,
    recipeDownloads: RecipeDownloads? = null,
) {
    public val debugLog: DebugLog = DebugLog()

    // Session actions carry credentials and the account; the recording is bounded and dropped
    // the moment the session ends, so nothing of a signed-out user stays reachable.
    private val sessionRecorder = ActionRecorder<SessionState, SessionAction>(capacity = 100)

    /** Never observed by UI directly: screens follow it through [SessionGateway] effects. */
    public val session: Store<SessionState, SessionAction> =
        Store(
            initialState = SessionState.SignedOut(),
            reducer = reducerOf("session", sessionReducer),
            handler =
                sessionEffectHandler(
                    auth = auth,
                    audit = AuditSink { event -> debugLog.audit(event.toString()) },
                    expiry = { ttlMillis -> delay(ttlMillis) },
                ),
            defects = defectsOf("session"),
            scope = StoreScope.Background,
            // Up to three observers on one hook: the recorder feeds the replay check, the log
            // feeds the screen, and the last wipes everything user-scoped once the session ends.
            observer = observerOf<SessionState, SessionAction>("session", sessionRecorder) + atSessionEnd(::forgetUser),
        )

    public val settings: Store<SettingsState, SettingsAction> =
        Store(
            initialState = SettingsState.Loading,
            reducer = reducerOf("settings", settingsReducer),
            handler = settingsEffectHandler(settingsRepository, persistWindow = { delay(500) }),
            defects = defectsOf("settings"),
            scope = settingsScope,
            observer = observerOf("settings"),
            start = SettingsAction.Started,
        )

    // The fakes read the settings snapshot on every call; a cross-thread read of a StateFlow
    // value is safe, and the null defaults exist because the fakes need this closure.
    private val failureInjected: () -> Boolean = {
        (settings.state as? SettingsState.Ready)?.settings?.failureInjection == true
    }
    private val recipesRepository: RecipesRepository = recipesRepository ?: FakeRecipesRepository(failureInjected)
    private val recipeDownloads: RecipeDownloads = recipeDownloads ?: FakeRecipeDownloads(failureInjected)

    private val sessionGateway: SessionGateway = StoreSessionGateway(session)
    private val settingsGateway: SettingsGateway = StoreSettingsGateway(settings)

    // One notes repository per recipe, created on first visit and kept for the session, so
    // notes survive navigating away and back but not a sign-out. Screen stores create entries
    // on the main thread while the session observer wipes from the session's own thread.
    private val notesRepositories = AtomicReference<PersistentMap<String, InMemoryNotesRepository>>(persistentMapOf())

    public fun shellStore(scope: StoreScope = StoreScope.Main): Store<ShellState, ShellAction> =
        Store(
            initialState = ShellState.Starting,
            reducer = reducerOf("shell", shellReducer),
            handler = shellEffectHandler(sessionGateway),
            defects = defectsOf("shell"),
            scope = scope,
            observer = observerOf("shell"),
            start = ShellAction.Started,
        )

    public fun shellView(store: Store<ShellState, ShellAction>): ViewStore<ShellState, ShellAction.Ui> =
        store.view(state = { state ->
            state
        }, action = { action -> action })

    /** [restored] is the state a previous process saved, or null on a fresh start. */
    public fun signInStore(
        restored: SignInState? = null,
        scope: StoreScope = StoreScope.Main,
    ): Store<SignInState, SignInAction> =
        Store(
            initialState = restored ?: SignInState.Editing(),
            reducer = reducerOf("signIn", signInReducer),
            handler = signInEffectHandler(sessionGateway),
            defects = defectsOf("signIn"),
            scope = scope,
            observer = observerOf("signIn", roundTripOf("signIn", SignInState.serializer())),
            start = SignInAction.Started,
        )

    public fun signInView(store: Store<SignInState, SignInAction>): ViewStore<SignInViewState, SignInAction.Ui> =
        store.view(state = ::signInViewState, action = { action -> action })

    public fun signInEvents(store: Store<SignInState, SignInAction>): Flow<SignInEvent> = store.events { action -> action as? SignInEvent }

    /** [restored] is the state a previous process saved, or null on a fresh start. */
    public fun recipesStore(
        restored: RecipesState? = null,
        scope: StoreScope = StoreScope.Main,
    ): Store<RecipesState, RecipesAction> =
        Store(
            initialState = restored ?: RecipesState.Loading,
            reducer = reducerOf("recipes", recipesReducer),
            handler = recipesEffectHandler(recipesRepository, recipeDownloads, searchWindow = { delay(400) }),
            defects = defectsOf("recipes"),
            scope = scope,
            observer = observerOf("recipes", roundTripOf("recipes", RecipesState.serializer())),
            start = RecipesAction.Started,
        )

    public fun recipesView(store: Store<RecipesState, RecipesAction>): ViewStore<RecipesViewState, RecipesAction.Ui> =
        store.view(state = ::recipesViewState, action = { action -> action })

    public fun recipesEvents(store: Store<RecipesState, RecipesAction>): Flow<RecipesEvent> =
        store.events { action -> action as? RecipesEvent }

    /** [restored] is the state a previous process saved, or null on a fresh start. */
    public fun recipeDetailStore(
        recipeId: String,
        restored: RecipeDetailState? = null,
        scope: StoreScope = StoreScope.Main,
    ): Store<RecipeDetailState, RecipeDetailAction> =
        Store(
            initialState = restored ?: RecipeDetailState.Loading(recipeId),
            reducer = reducerOf("recipe:$recipeId", recipeDetailReducer),
            handler =
                recipeDetailEffectHandler(
                    repository = recipesRepository,
                    notesHandler =
                        notesEffectHandler(notesRepositoryFor(recipeId), hintDebounceWindow = { delay(400) }),
                    settings = settingsGateway,
                    tick = { delay(1_000) },
                ),
            defects = defectsOf("recipe:$recipeId"),
            scope = scope,
            observer = observerOf("recipe:$recipeId", roundTripOf("recipe:$recipeId", RecipeDetailState.serializer())),
            start = RecipeDetailAction.Started,
        )

    public fun recipeDetailViews(store: Store<RecipeDetailState, RecipeDetailAction>): RecipeDetailViews = RecipeDetailViews(store)

    public fun recipeDetailEvents(store: Store<RecipeDetailState, RecipeDetailAction>): Flow<RecipeDetailEvent> =
        store.events { action -> action as? RecipeDetailEvent }

    /** The settings screen store; a screen over the Dedicated store, never the store itself. */
    public fun settingsScreenStore(scope: StoreScope = StoreScope.Main): Store<SettingsScreenState, SettingsScreenAction> =
        Store(
            initialState = SettingsScreenState.Loading,
            reducer = reducerOf("settingsScreen", settingsScreenReducer),
            handler = settingsScreenEffectHandler(settingsGateway),
            defects = defectsOf("settingsScreen"),
            scope = scope,
            observer = observerOf("settingsScreen"),
            start = SettingsScreenAction.Started,
        )

    public fun settingsView(
        store: Store<SettingsScreenState, SettingsScreenAction>,
    ): ViewStore<SettingsViewState, SettingsScreenAction.Ui> = store.view(state = ::settingsViewState, action = { action -> action })

    public fun settingsEvents(store: Store<SettingsScreenState, SettingsScreenAction>): Flow<SettingsScreenEvent> =
        store.events { action -> action as? SettingsScreenEvent }

    // Deliberately unobserved by the log: each logged reduction would feed the screen a new
    // entry list, whose reduction would be logged, without end.
    public fun debugStore(scope: StoreScope = StoreScope.Main): Store<DebugState, DebugAction> =
        Store(
            initialState = DebugState(),
            reducer = debugReducer,
            handler = debugEffectHandler(debugLog, replayCheck = ::verifySessionReplay),
            defects = defectsOf("debug"),
            scope = scope,
            start = DebugAction.Started,
        )

    public fun debugView(store: Store<DebugState, DebugAction>): ViewStore<DebugState, DebugAction.Ui> =
        store.view(state = { state ->
            state
        }, action = { action -> action })

    public fun debugEvents(store: Store<DebugState, DebugAction>): Flow<DebugEvent> = store.events { action -> action as? DebugEvent }

    public fun close() {
        session.close()
        settings.close()
    }

    // Inside this class the nullable-receiver operator shadows the runtime's `plus` for
    // non-null receivers too, so `this + other` here would call itself; the alias names the
    // runtime's fan-out explicitly.
    private operator fun <S, A> StoreObserver<S, A>?.plus(other: StoreObserver<S, A>): StoreObserver<S, A> =
        if (this == null) {
            other
        } else {
            this.fanOut(other)
        }

    // With diagnostics off nothing observes a store, so nothing is stringified per reduction
    // and the recorder never fills; [extra] joins the log when both are wanted.
    private fun <S, A> observerOf(
        store: String,
        extra: StoreObserver<S, A>? = null,
    ): StoreObserver<S, A>? =
        when {
            !diagnostics -> null
            extra == null -> debugLog.observer(store)
            else -> extra + debugLog.observer(store)
        }

    // The restore contract, checked on every reduction while diagnostics are on: only the
    // stores a platform restores carry a serializer, so only they get the round trip.
    private fun <S, A> roundTripOf(
        store: String,
        serializer: KSerializer<S>,
    ): StoreObserver<S, A>? =
        if (diagnostics) {
            debugLog.roundTrip(store, serializer)
        } else {
            null
        }

    // Every reducer is pure by contract; diagnostics builds reduce twice to prove it.
    private fun <S, A, E> reducerOf(
        store: String,
        reducer: Reducer<S, A, E>,
    ): Reducer<S, A, E> =
        if (diagnostics) {
            debugLog.deterministic(store, reducer)
        } else {
            reducer
        }

    // Release keeps the app alive on a defect and says so on stderr; debug shows it on screen.
    private fun defectsOf(store: String): DefectHandler<Any?, Any?, Any?> =
        if (diagnostics) {
            debugLog.defects(store)
        } else {
            loggingDefectHandler { exception -> println("[$store] ${exception.message}: ${exception.cause}") }
        }

    private fun notesRepositoryFor(recipeId: String): InMemoryNotesRepository {
        while (true) {
            val current = notesRepositories.load()
            current[recipeId]?.let { repository -> return repository }
            val created = InMemoryNotesRepository(failureInjected = failureInjected)
            if (notesRepositories.compareAndSet(current, current.putting(recipeId, created))) {
                return created
            }
        }
    }

    /** Everything that belongs to the signed-in user: the action recording and the cooking notes. */
    private fun forgetUser() {
        sessionRecorder.clear()
        notesRepositories.store(persistentMapOf())
    }

    // The recording and the live state are two snapshots taken a moment apart; a session
    // reduction landing between them reads as a divergence, so verify while the session idles.
    private fun verifySessionReplay(): ReplayVerdict {
        val recording = sessionRecorder.recording ?: return ReplayVerdict.NothingRecorded
        val replayed = sessionReducer.replay(recording.initialState, recording.actions)
        val live = session.state
        return if (replayed == live) {
            ReplayVerdict.Reproduced(recording.actions.size)
        } else {
            ReplayVerdict.Diverged(recording.actions.size, replayed.toString(), live.toString())
        }
    }
}

private class StoreSessionGateway(
    private val store: Store<SessionState, SessionAction>,
) : SessionGateway {
    override suspend fun signIn(
        email: String,
        password: String,
    ) {
        store.send(SessionAction.SignIn(email, password))
    }

    override suspend fun signOut() {
        store.send(SessionAction.SignOut)
    }

    override suspend fun observe(onEach: (SessionState) -> Unit) {
        store.stateFlow.feedInto(onEach) { state -> state }
    }
}

private class StoreSettingsGateway(
    private val store: Store<SettingsState, SettingsAction>,
) : SettingsGateway {
    override suspend fun observe(onEach: (Settings) -> Unit) {
        store.stateFlow.mapNotNull { state -> (state as? SettingsState.Ready)?.settings }.feedInto(onEach) { settings -> settings }
    }

    override suspend fun changeUnits(units: Units) {
        store.send(SettingsAction.UnitsChanged(units))
    }

    override suspend fun toggleFailureInjection() {
        store.send(SettingsAction.FailureInjectionToggled)
    }
}

/** Runs [wipe] whenever the session lands in [SessionState.SignedOut]; order it after the recorder on the same hook. */
internal fun atSessionEnd(wipe: () -> Unit): StoreObserver<SessionState, SessionAction> =
    StoreObserver { _, _, next ->
        if (next is SessionState.SignedOut) {
            wipe()
        }
    }
