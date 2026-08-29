import Shared
import SwiftUI

private enum DetailTab: Hashable {
    case ingredients
    case steps
    case notes
}

/// Each tab reads its own nested model, so a timer tick invalidates the steps tab and nothing else.
struct RecipeDetailView: View {
    let stores: CookbookStores
    @StateObject private var models: RecipeDetailModels
    @State private var tab: DetailTab = .ingredients

    init(stores: CookbookStores, recipeId: String) {
        self.stores = stores
        _models = StateObject(wrappedValue: makeRecipeDetailModels(stores, recipeId: recipeId))
    }

    var body: some View {
        let phase = self.models.detail.state.phase
        VStack(spacing: 0, content: {
            switch onEnum(of: phase) {
            case .loading:
                ProgressView().padding()

            case .failed(let failed):
                VStack(spacing: 12, content: {
                    Text(label(for: failed.error))
                    Button("Retry", action: {
                        self.models.detail.send(RecipeDetailActionRetry())
                    })
                })
                .padding()

            case .ready(let ready):
                VStack(alignment: .leading, spacing: 4, content: {
                    Text(ready.summary).font(.subheadline)
                    Text("\(ready.minutes) min").font(.caption).foregroundStyle(.secondary)
                    if let failure = ready.failure {
                        Text(label(for: failure)).font(.footnote).foregroundStyle(.red)
                    }
                })
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
                Picker("Section", selection: self.$tab, content: {
                    Text("Ingredients").tag(DetailTab.ingredients)
                    Text("Steps").tag(DetailTab.steps)
                    Text("Notes").tag(DetailTab.notes)
                })
                .pickerStyle(.segmented)
                .padding()
                switch self.tab {
                case .ingredients:
                    IngredientsTab(model: self.models.ingredients)
                case .steps:
                    StepsTab(model: self.models.steps)
                case .notes:
                    NotesTab(model: self.models.notes)
                }
            }
        })
        .navigationTitle(self.title(for: phase))
        .task {
            self.models.detail.activate()
            self.models.ingredients.activate()
            self.models.steps.activate()
            self.models.notes.activate()
        }
    }

    private func title(for phase: DetailPhase) -> String {
        if case .ready(let ready) = onEnum(of: phase) {
            return ready.title
        }
        return "Recipe"
    }
}

private struct IngredientsTab: View {
    let model: ViewStoreModel<IngredientsViewState, RecipeDetailActionServings>

    var body: some View {
        let state = self.model.state
        List(content: {
            Section(content: {
                Stepper(
                    "Servings: \(state.servings)",
                    onIncrement: { self.model.send(RecipeDetailActionIncreased()) },
                    onDecrement: state.canDecrease ? { self.model.send(RecipeDetailActionDecreased()) } : nil
                )
            })
            Section(content: {
                ForEach(Array(state.lines.enumerated()), id: \.offset, content: { offset, line in
                    HStack(content: {
                        Text(line.name)
                        Spacer()
                        Text(line.amount).foregroundStyle(.secondary)
                    })
                })
            })
        })
    }
}

struct StepUI: Identifiable {
    let id: Int
    let text: String
    let timer: TimerUI?
}

enum TimerUI {
    case idle(total: Int)
    case running(remaining: Int)
    case paused(remaining: Int)
    case done
}

func stepsUI(_ state: StepsViewState) -> [StepUI] {
    state.lines.map { line in
        StepUI(id: Int(line.index), text: line.text, timer: line.timer.map(timerUI))
    }
}

private func timerUI(_ status: TimerStatus) -> TimerUI {
    switch onEnum(of: status) {
    case .idle(let idle):
        return .idle(total: Int(idle.totalSeconds))
    case .running(let running):
        return .running(remaining: Int(running.remainingSeconds))
    case .paused(let paused):
        return .paused(remaining: Int(paused.remainingSeconds))
    case .done:
        return .done
    }
}

private struct StepsTab: View {
    let model: ProjectedModel<StepsViewState, RecipeDetailActionUi, [StepUI]>

    var body: some View {
        List(content: {
            ForEach(self.model.ui, content: { step in
                VStack(alignment: .leading, spacing: 6, content: {
                    Text("\(step.id + 1). \(step.text)")
                    if let timer = step.timer {
                        TimerControls(timer: timer, send: { action in
                            self.model.send(RecipeDetailKt.stepAction(index: Int32(step.id), action: action))
                        })
                    }
                })
            })
        })
    }
}

private struct TimerControls: View {
    let timer: TimerUI
    let send: (StepActionUi) -> Void

    var body: some View {
        HStack(spacing: 12, content: {
            switch self.timer {
            case .idle(let total):
                Button("Start \(self.clock(total))", action: {
                    self.send(StepActionStart())
                })

            case .running(let remaining):
                Text(self.clock(remaining)).font(.headline).monospacedDigit()
                Button("Pause", action: {
                    self.send(StepActionPause())
                })
                Button("Reset", action: {
                    self.send(StepActionReset())
                })

            case .paused(let remaining):
                Text(self.clock(remaining)).font(.headline).monospacedDigit().foregroundStyle(.secondary)
                Button("Resume", action: {
                    self.send(StepActionStart())
                })
                Button("Reset", action: {
                    self.send(StepActionReset())
                })

            case .done:
                Text("Done").font(.headline).foregroundStyle(.teal)
                Button("Reset", action: {
                    self.send(StepActionReset())
                })
            }
        })
        .buttonStyle(.borderless)
    }

    private func clock(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// The notes feature's own UI, driven through the nested model: it never learns it lives inside a recipe.
private struct NotesTab: View {
    let model: ViewStoreModel<NotesViewState, NotesActionUi>

    var body: some View {
        switch onEnum(of: self.model.state) {
        case .busy(let busy):
            if busy.saving {
                ProgressView("Saving…")
            } else {
                ProgressView()
            }

        case .notes(let notes):
            List(content: {
                if let editor = notes.editor {
                    self.editorSection(editor)
                }
                ForEach(notes.notes, id: \.id, content: { note in
                    Text(note.text)
                })
            })
            .toolbar(content: {
                Button("Add note", action: {
                    self.model.send(NotesActionAddClicked())
                })
                .disabled(!notes.canAdd)
            })

        case .failed(let failed):
            VStack(spacing: 12, content: {
                Text(label(for: failed.error))
                Button("Retry", action: {
                    self.model.send(NotesActionRetry())
                })
            })
        }
    }

    private func editorSection(_ editor: EditorViewState) -> some View {
        Section("New note", content: {
            TextField(
                "Note",
                text: Binding(
                    get: { editor.draft },
                    set: { text in self.model.send(NotesActionEditor(action: EditorActionDraftChanged(text: text))) }
                )
            )
            if let error = editor.error {
                Text(label(for: error)).foregroundStyle(.red)
            }
            if let hint = editor.hint {
                Text(hint).font(.footnote).foregroundStyle(.secondary)
            }
            HStack(content: {
                Button("Save", action: {
                    self.model.send(NotesActionEditor(action: EditorActionSave()))
                })
                Button("Cancel", role: .cancel, action: {
                    self.model.send(NotesActionEditorDismissed())
                })
            })
            .buttonStyle(.borderless)
        })
    }
}

func label(for error: NotesError) -> String {
    switch onEnum(of: error) {
    case .offline:
        return "You appear to be offline."
    case .unexpected(let unexpected):
        return "Something went wrong: \(unexpected.message)"
    }
}
