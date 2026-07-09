import Cocoa
import SwiftUI

class SettingsWindowController: NSWindowController, NSWindowDelegate {

    static let shared = SettingsWindowController()

    private init() {
        // Create the window
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 350),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Wispr - Einstellungen"
        window.contentViewController = NSHostingController(rootView: SettingsView())
        window.center()
        
        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func showSettings() {
        // Temporarily change activation policy to accessory so the window can receive focus and appear on screen
        NSApp.setActivationPolicy(.accessory)
        
        self.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    
    func windowWillClose(_ notification: Notification) {
        // Revert activation policy to prohibited to keep the app running strictly in the background
        NSApp.setActivationPolicy(.prohibited)
    }
}
