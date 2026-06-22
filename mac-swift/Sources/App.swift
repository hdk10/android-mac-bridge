import SwiftUI
import AppKit

@main
struct MacBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var core = BridgeCore.shared

    var body: some Scene {
        MenuBarExtra {
            ContentView().environmentObject(core)
        } label: {
            Image(systemName: "bolt.horizontal.fill")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)          // menu-bar only, no Dock icon
        Task { @MainActor in BridgeCore.shared.start() }
    }
}
