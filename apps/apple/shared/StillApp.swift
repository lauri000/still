import SwiftUI

@main
struct StillApp: App {
    @StateObject private var library: LibraryStore

    init() {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--ui-testing") {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("StillUITests")
            if arguments.contains("--reset-library") {
                try? FileManager.default.removeItem(at: root)
                UserDefaults.standard.set(arguments.contains("--require-review"), forKey: "pauseAtUncheckedPages")
                UserDefaults.standard.set(false, forKey: "focusLetter")
            }
            _library = StateObject(wrappedValue: LibraryStore(repository: BookRepository(root: root), includeSample: !arguments.contains("--empty-library")))
        } else { _library = StateObject(wrappedValue: LibraryStore()) }
    }

    var body: some Scene {
        WindowGroup {
            LibraryView().environmentObject(library).tint(Palette.forest)
                .task { await library.load() }
        }
        #if os(macOS)
        .defaultSize(width: 1250, height: 850)
        .commands {
            CommandGroup(replacing: .newItem) { }
        }
        #endif
    }
}
