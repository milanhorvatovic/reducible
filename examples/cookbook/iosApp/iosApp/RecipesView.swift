import Shared
import SwiftUI

enum SignedInRoute: Hashable {
    case debug
    case settings
    case recipe(String)
}

/// The list rendered from Swift values: one conversion per reduction, nothing below reads
/// Kotlin while rendering or scrolling.
enum RecipesUI {
    case loading
    case failed(String)
    case content(RecipesContentUI)
}

struct RecipesContentUI {
    let rows: [RecipeRowUI]
    let query: String
    let refreshing: Bool
    let failure: String?
    let narrowed: Bool
}

struct RecipeRowUI: Identifiable {
    let id: String
    let title: String
    let summary: String
    let minutes: Int
    let servings: Int
    let favorite: Bool
    let download: DownloadUI
}

enum DownloadUI {
    case remote
    case downloading(Int)
    case offline
    case failed(String)
}

func recipesUI(_ state: RecipesViewState) -> RecipesUI {
    switch onEnum(of: state) {
    case .loading:
        return .loading
    case .failed(let failed):
        return .failed(label(for: failed.error))
    case .content(let content):
        return .content(
            RecipesContentUI(
                rows: content.rows.map { row in
                    RecipeRowUI(
                        id: row.id,
                        title: row.title,
                        summary: row.summary,
                        minutes: Int(row.minutes),
                        servings: Int(row.servings),
                        favorite: row.favorite,
                        download: downloadUI(row.download)
                    )
                },
                query: content.query,
                refreshing: content.refreshing,
                failure: content.failure.map { failure in label(for: failure) },
                narrowed: content.narrowed
            )
        )
    }
}

private func downloadUI(_ status: DownloadStatus) -> DownloadUI {
    switch onEnum(of: status) {
    case .remote:
        return .remote
    case .downloading(let downloading):
        return .downloading(Int(downloading.percent))
    case .offline:
        return .offline
    case .failed(let failed):
        return .failed(label(for: failed.error))
    }
}

/// The screen never navigates: it sends the intent to its store, and this host acts on the
/// event the store publishes — the path for its own routes, the shell for signing out.
struct RecipesFlow: View {
    let stores: CookbookStores
    let account: Account
    let shell: ViewStoreModel<ShellState, ShellActionUi>
    @StateObject private var models: RecipesModels
    @State private var path: [SignedInRoute] = []

    init(stores: CookbookStores, account: Account, shell: ViewStoreModel<ShellState, ShellActionUi>) {
        self.stores = stores
        self.account = account
        self.shell = shell
        _models = StateObject(wrappedValue: makeRecipesModels(stores))
    }

    var body: some View {
        NavigationStack(path: self.$path, root: {
            RecipesList(model: self.models.model)
                .navigationTitle("Cookbook")
                .toolbar(content: {
                    ToolbarItem(placement: .topBarLeading, content: {
                        Text(self.account.displayName).font(.footnote).foregroundStyle(.secondary)
                    })
                    ToolbarItemGroup(placement: .topBarTrailing, content: {
                        Button("Settings", action: {
                            self.models.model.send(RecipesActionSettingsClicked())
                        })
                        if self.stores.diagnostics {
                            Button("Debug", action: {
                                self.models.model.send(RecipesActionDebugClicked())
                            })
                        }
                        Button("Sign out", action: {
                            self.models.model.send(RecipesActionSignOutClicked())
                        })
                    })
                })
                .navigationDestination(for: SignedInRoute.self, destination: { route in
                    switch route {
                    case .debug:
                        DebugView(stores: self.stores)
                    case .settings:
                        SettingsView(stores: self.stores)
                    case .recipe(let id):
                        RecipeDetailView(stores: self.stores, recipeId: id)
                    }
                })
        })
        .task {
            self.models.model.activate()
        }
        .task {
            for await event in self.models.events {
                switch onEnum(of: event) {
                case .openRecipe(let open):
                    self.path.append(.recipe(open.id))
                case .openSettings:
                    self.path.append(.settings)
                case .openDebug:
                    self.path.append(.debug)
                case .signOut:
                    self.shell.send(ShellActionSignOut())
                }
            }
        }
    }
}

private struct RecipesList: View {
    let model: ProjectedModel<RecipesViewState, RecipesActionUi, RecipesUI>

    var body: some View {
        switch self.model.ui {
        case .loading:
            ProgressView()

        case .failed(let message):
            VStack(spacing: 12, content: {
                Text(message)
                Button("Retry", action: {
                    self.model.send(RecipesActionRetry())
                })
            })

        case .content(let content):
            self.list(content)
        }
    }

    private func list(_ content: RecipesContentUI) -> some View {
        List(content: {
            if let failure = content.failure {
                Section(content: {
                    Text(failure).font(.footnote).foregroundStyle(.red)
                })
            }
            if content.rows.isEmpty {
                Text(content.narrowed ? "No recipes match." : "No recipes yet.")
                    .foregroundStyle(.secondary)
            }
            ForEach(content.rows, content: { row in
                RecipeRowView(row: row, send: { action in
                    self.model.send(RecipesKt.recipeRowAction(id: row.id, action: action))
                })
                .contentShape(Rectangle())
                .onTapGesture(perform: {
                    self.model.send(RecipesActionRecipeClicked(id: row.id))
                })
                .swipeActions(content: {
                    Button("Hide", role: .destructive, action: {
                        self.model.send(RecipesActionDismissed(id: row.id))
                    })
                })
            })
        })
        .searchable(
            text: self.model.binding(
                get: { ui in
                    if case .content(let content) = ui {
                        return content.query
                    }
                    return ""
                },
                send: { text in RecipesActionQueryChanged(text: text) }
            ),
            prompt: "Search recipes"
        )
        .refreshable(action: {
            self.model.send(RecipesActionRefresh())
        })
    }
}

private struct RecipeRowView: View {
    let row: RecipeRowUI
    let send: (RecipeRowActionUi) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6, content: {
            HStack(alignment: .top, content: {
                VStack(alignment: .leading, spacing: 2, content: {
                    Text(self.row.title).font(.headline)
                    Text(self.row.summary).font(.subheadline).foregroundStyle(.secondary)
                    Text("\(self.row.minutes) min · serves \(self.row.servings)").font(.caption)
                })
                Spacer()
                Button(
                    action: {
                        self.send(RecipeRowActionFavoriteToggled())
                    },
                    label: {
                        Image(systemName: self.row.favorite ? "star.fill" : "star")
                    }
                )
            })
            HStack(content: {
                switch self.row.download {
                case .remote:
                    Button("Download", action: {
                        self.send(RecipeRowActionDownloadClicked())
                    })

                case .downloading(let percent):
                    ProgressView(value: Double(percent), total: 100)
                    Button("Cancel \(percent)%", action: {
                        self.send(RecipeRowActionCancelClicked())
                    })

                case .offline:
                    Text("Available offline").font(.caption).foregroundStyle(.teal)
                    Button("Remove", action: {
                        self.send(RecipeRowActionRemoveOfflineClicked())
                    })

                case .failed(let message):
                    Text(message).font(.caption).foregroundStyle(.red)
                    Button("Retry", action: {
                        self.send(RecipeRowActionDownloadClicked())
                    })
                }
            })
        })
        .buttonStyle(.borderless)
    }
}

func label(for error: RecipesError) -> String {
    switch onEnum(of: error) {
    case .offline:
        return "You appear to be offline."
    case .unexpected(let unexpected):
        return "Something went wrong: \(unexpected.message)"
    }
}
