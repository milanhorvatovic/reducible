# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `reducible-core`: `Reducer` and `Reduced`, effect envelopes with `EffectScope`, `EffectKey`, and `KeyPolicy`, `EffectOwner`, optics (`Lens`, `Prism`, `Optional`, `casePrism`, `andThen`), composition (`combine`, `ifPresent`, `forEachIdentified`, `rowReduced`, `handle`), follow-up actions (`andSend`), `Event`, and `replay`.
- `reducible-runtime`: `Store` with confined reduction, state-scoped and keyed effect cancellation, follow-up queueing with cycle protection, and one-shot events; `StoreScope` (`Main`, `Background`, `Dedicated`, `Inherited`, `Testing`); `ViewStore` projections; `StoreObserver` with effect events and warnings; `DefectHandler` with rethrowing and logging policies; `ActionRecorder` and `RecordingStore`; `feedInto`; a GCD-backed `Dedicated` scope for iOS.
- `reducible-immutable`: `forEachIdentified` over `PersistentList` with structural sharing, and `PersistentListSerializer`.
- `reducible-optics-arrow`: `asCoreLens`, `asCorePrism`, `asCoreOptional` adapters from Arrow Optics.
- `reducible-test`: the `given / on / expect` reducer DSL, `assertLensLaws` / `assertPrismLaws` / `assertOptionalLaws`, and `TestStore` with an exhaustive `finish()`.
- `reducible-android`: `StoreViewModel` with process-death restore through `SavedStateHandle`.
- `swift/`: `StoreModel`, `ViewStoreModel`, `ProjectedModel`, and `statePublisher` for SwiftUI and Combine.
- Examples: `counter`, `koin`, and the Cookbook (feature modules, composition root, Compose and SwiftUI apps).
