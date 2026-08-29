import Combine
import Shared
import SwiftUI

/// The one screen built on `ObservableObject` and Combine rather than `@Observable`, to show
/// the `statePublisher` bridge for code bases that are not on iOS 17 observation yet. The
/// model owns the store through a `StoreModel` and republishes the view's projection.
@MainActor
final class SettingsModel: ObservableObject {
    @Published private(set) var state: SettingsViewState

    private let owner: StoreModel<SettingsScreenState, SettingsScreenAction>
    private let view: ViewStore<SettingsViewState, SettingsScreenActionUi>
    private var subscription: AnyCancellable?

    init(stores: CookbookStores) {
        let store = stores.settingsScreenStore()
        self.owner = StoreModel(store: store)
        self.view = stores.settingsView(store: store)
        self.state = self.view.state
        self.subscription = statePublisher(of: self.view)
            .sink(receiveValue: { [weak self] state in self?.state = state })
    }

    func send(_ action: SettingsScreenActionUi) {
        self.view.send(action: action)
    }
}

struct SettingsView: View {
    @StateObject private var model: SettingsModel

    init(stores: CookbookStores) {
        _model = StateObject(wrappedValue: SettingsModel(stores: stores))
    }

    var body: some View {
        let state = self.model.state
        Form(content: {
            Section("Units", content: {
                Picker(
                    "Units",
                    selection: Binding(
                        get: { state.units },
                        set: { units in self.model.send(SettingsScreenActionUnitsSelected(units: units)) }
                    ),
                    content: {
                        Text("Metric (g, ml)").tag(Units.metric)
                        Text("Imperial (oz, lb, cups)").tag(Units.imperial)
                    }
                )
                .pickerStyle(.inline)
                .labelsHidden()
                .disabled(!state.ready)
            })
            Section(content: {
                Toggle(
                    isOn: Binding(
                        get: { state.failureInjection },
                        set: { enabled in self.model.send(SettingsScreenActionFailureInjectionToggled()) }
                    ),
                    label: {
                        VStack(alignment: .leading, spacing: 2, content: {
                            Text("Failure injection")
                            Text("Makes the fake repositories fail: loads, searches, downloads, and notes.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        })
                    }
                )
                .disabled(!state.ready)
            })
        })
        .navigationTitle("Settings")
    }
}
