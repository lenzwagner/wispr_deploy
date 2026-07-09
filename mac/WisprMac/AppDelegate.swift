import Cocoa
import AVFoundation

class AppDelegate: NSObject, NSApplicationDelegate {

    private var menuBarController: MenuBarController?

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        print("AppDelegate: applicationDidFinishLaunching called!")
        
        // Request Accessibility permissions to allow keyboard simulation (Cmd+V)
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
        _ = AXIsProcessTrustedWithOptions(options as CFDictionary)
        
        // Request Microphone permissions
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            print("AppDelegate: Microphone access granted: \(granted)")
        }
        
        // Initialize the status bar item controller
        menuBarController = MenuBarController()
    }

    func applicationWillTerminate(_ aNotification: Notification) {
        // Clean up
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        SettingsWindowController.shared.showSettings()
        return true
    }
}
