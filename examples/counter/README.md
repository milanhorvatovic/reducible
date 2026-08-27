# Counter

The smallest complete feature, in two files and two tests.

- `Counter.kt` — the state as two phases (`Idle`, `Ticking`), the actions with a `Ui` subset a screen may send, the effect as a value, the reducer, and the handler with its one suspension injected.
- `CounterStore.kt` — the factory a platform holder calls (`counterStore(scope)`) and the screen's projection (`counterView(store)`).
- `CounterReducerTest.kt` — pure reductions through the `given / on / expect` DSL.
- `CounterStoreTest.kt` — the store under virtual time with `testStore`: ticks land once per second, stopping cancels the tick in flight, and `finish()` proves nothing is left.

The point to notice: stopping needs no bookkeeping. The tick effect is requested on entering `Ticking`, and leaving `Ticking` is what cancels it — the runtime scopes state-scoped effects to the state class that asked for them. The reducer still handles a `Ticked` that arrives in `Idle`, because an effect that had already sent before its cancellation still delivers.

```sh
./gradlew :examples:counter:allTests -PskipIosTests
```

Holding this store on a platform is shown in the root [README](../../README.md#holding-a-store).
