import Shared
import SwiftUI

struct DebugView: View {
    let stores: CookbookStores
    @StateObject private var model: ViewStoreModel<DebugState, DebugActionUi>

    init(stores: CookbookStores) {
        self.stores = stores
        _model = StateObject(wrappedValue: makeDebugModel(stores))
    }

    var body: some View {
        let state = self.model.state
        List(content: {
            Section(content: {
                HStack(content: {
                    Button("Verify replay", action: {
                        self.model.send(DebugActionVerifyReplay())
                    })
                    Spacer()
                    Button("Throw in effect", action: {
                        self.model.send(DebugActionThrowInEffect())
                    })
                    Spacer()
                    Button("Throw in reducer", action: {
                        self.model.send(DebugActionThrowInReducer())
                    })
                })
                .buttonStyle(.bordered)
                if let verdict = state.replay {
                    Text(label(for: verdict)).font(.footnote)
                }
            })
            Section("Newest first", content: {
                ForEach(state.entries.reversed(), id: \.sequence, content: { entry in
                    DebugEntryRow(entry: entry)
                })
            })
        })
        .navigationTitle("Action log")
        .toolbar(content: {
            // Kotlin objects released by Swift stay allocated until the next Kotlin/Native
            // collection; force one before reading a memory graph, or garbage reads as retention.
            // Two passes: the first frees Kotlin objects, and their ObjC wrappers are released by
            // the finalizer on a later run-loop turn, so a second pass a moment later is what
            // makes them leave the graph.
            Button("Collect garbage", action: {
                self.stores.debugLog.audit(event: DiagnosticsKt.collectGarbage())
                Task(operation: { @MainActor in
                    try? await Task.sleep(for: .seconds(1))
                    self.stores.debugLog.audit(event: DiagnosticsKt.collectGarbage())
                })
            })
            Button("Clear", action: {
                self.model.send(DebugActionClear())
            })
        })
        .task {
            self.model.activate()
        }
    }
}

private struct DebugEntryRow: View {
    let entry: DebugEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2, content: {
            switch onEnum(of: self.entry) {
            case .reduced(let reduced):
                Text("#\(reduced.sequence) \(reduced.store) · \(reduced.action)")
                    .font(.caption.weight(.medium))
                Text(reduced.state)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)

            case .audit(let audit):
                Text("#\(audit.sequence) audit · \(audit.event)")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.teal)

            case .defect(let defect):
                Text("#\(defect.sequence) \(defect.store) · defect")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.red)
                Text(defect.message)
                    .font(.caption2)
                    .foregroundStyle(.red)

            case .effect(let effect):
                Text(
                    "#\(effect.sequence) \(effect.store) · effect \(effect.phase) · \(effect.effect)"
                        + (effect.key.map { key in " · key \(key)" } ?? "")
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            case .warning(let warning):
                Text("#\(warning.sequence) \(warning.store) · warning")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.orange)
                Text(warning.message)
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        })
    }
}

private func label(for verdict: ReplayVerdict) -> String {
    switch onEnum(of: verdict) {
    case .nothingRecorded:
        return "Nothing recorded yet: sign in first."
    case .reproduced(let reproduced):
        return "Replaying \(reproduced.actions) recorded session action(s) over the pure reducer reproduces the live state."
    case .diverged(let diverged):
        return "Replay diverged after \(diverged.actions) action(s).\nreplayed: \(diverged.replayed)\nlive: \(diverged.live)"
    }
}
