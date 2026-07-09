import Cocoa
import AVFoundation
import Carbon

class MenuBarController: NSObject {

    static var shared: MenuBarController?

    private var statusItem: NSStatusItem!
    private var isRecording = false
    private var isUploading = false
    
    private var audioRecorder: AVAudioRecorder?
    private var audioFilename: URL!
    
    // Carbon hotkey references
    private var hotKeyRef: EventHotKeyRef?
    
    // Key release monitors for "Hold to Speak" mode
    private var localReleaseMonitor: Any?
    private var globalReleaseMonitor: Any?
    
    override init() {
        super.init()
        print("MenuBarController: init called!")
        MenuBarController.shared = self
        setupStatusItem()
        setupAudio()
        registerGlobalHotkey()
        
        // Listen to settings changes from the SwiftUI View
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applySettings),
            name: NSNotification.Name("WisprSettingsChanged"),
            object: nil
        )
    }
    
    deinit {
        stopKeyReleaseMonitor()
        if let ref = hotKeyRef {
            UnregisterEventHotKey(ref)
        }
    }
    
    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        updateStatusItemUI()
        
        let menu = NSMenu()
        menu.addItem(NSMenuItem(title: "🎙️ Aufnehmen", action: #selector(toggleRecording), keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "⚙️ Einstellungen...", action: #selector(openSettingsWindow), keyEquivalent: ","))
        menu.addItem(NSMenuItem(title: "Status: Bereit", action: nil, keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Beenden", action: #selector(quitApp), keyEquivalent: "q"))
        
        // Connect actions to target
        for item in menu.items {
            item.target = self
        }
        
        statusItem.menu = menu
        
        // Single click on status item will trigger recording toggle!
        if let button = statusItem.button {
            button.action = #selector(statusItemClicked(_:))
            button.target = self
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
    }
    
    @objc private func statusItemClicked(_ sender: NSStatusBarButton) {
        let event = NSApp.currentEvent
        if event?.type == .rightMouseUp {
            // Show menu on right click
            statusItem.menu?.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
        } else {
            // Toggle recording on left click
            toggleRecording()
        }
    }
    
    private func updateStatusItemUI() {
        guard let button = statusItem.button else { return }
        
        if isUploading {
            button.title = "⌛ Polieren..."
        } else if isRecording {
            button.title = "🔴 Aufnahme läuft..."
        } else {
            button.title = "🎙️ Wispr"
        }
    }
    
    private func setupAudio() {
        let tempDir = FileManager.default.temporaryDirectory
        audioFilename = tempDir.appendingPathComponent("wispr_mac_recording.m4a")
    }
    
    @objc func applySettings() {
        // Re-register hotkey when settings change
        registerGlobalHotkey()
    }
    
    @objc private func openSettingsWindow() {
        SettingsWindowController.shared.showSettings()
    }
    
    @objc func toggleRecording() {
        if isUploading { return }
        
        if isRecording {
            stopRecordingAndUpload()
            stopKeyReleaseMonitor()
        } else {
            startRecording()
        }
    }
    
    @objc func handleHotkeyTrigger() {
        if isUploading { return }
        
        let recordingMode = UserDefaults.standard.string(forKey: "recording_mode") ?? "toggle"
        
        if recordingMode == "hold" {
            // In "Hold to Speak" mode, start recording on hotkey down
            if !isRecording {
                startRecording()
                startKeyReleaseMonitor()
            }
        } else {
            // In "Toggle" mode, normal click behavior
            toggleRecording()
        }
    }
    
    private func startRecording() {
        // Dismiss settings window if open so focus goes back to the target app
        DispatchQueue.main.async {
            if SettingsWindowController.shared.window?.isVisible == true {
                SettingsWindowController.shared.window?.close()
            }
        }
        
        do {
            let settings = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 16000,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
            ]
            
            audioRecorder = try AVAudioRecorder(url: audioFilename, settings: settings)
            audioRecorder?.record()
            
            isRecording = true
            updateStatusItemUI()
            
            // Post notification / sound play
            NSSound.beep()
        } catch {
            showErrorAlert(message: "Fehler beim Starten der Aufnahme: \(error.localizedDescription)")
        }
    }
    
    private func stopRecordingAndUpload() {
        audioRecorder?.stop()
        audioRecorder = nil
        isRecording = false
        isUploading = true
        updateStatusItemUI()
        
        // Trigger upload
        let backendUrl = UserDefaults.standard.string(forKey: "backend_url") ?? "https://wispr-deploy.onrender.com"
        uploadAudio(fileUrl: audioFilename, backendUrlString: backendUrl)
    }
    
    private func uploadAudio(fileUrl: URL, backendUrlString: String) {
        let transcribeUrlString = backendUrlString.appending(backendUrlString.hasSuffix("/") ? "transcribe" : "/transcribe")
        guard let url = URL(string: transcribeUrlString) else {
            showErrorAlert(message: "Ungültige Backend URL: \(transcribeUrlString)")
            resetState()
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        guard let fileData = try? Data(contentsOf: fileUrl) else {
            resetState()
            return
        }
        
        var body = Data()
        // File part
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"wispr_mac_recording.m4a\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/mp4\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n".data(using: .utf8)!)
        
        // Get frontmost application name to send as context for AI formatting
        let activeAppName = NSWorkspace.shared.frontmostApplication?.localizedName ?? "macOS Desktop App"
        
        // App context part
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"app_name\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(activeAppName)\r\n".data(using: .utf8)!)
        
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        
        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            
            defer {
                DispatchQueue.main.async {
                    self.resetState()
                }
            }
            
            if let error = error {
                DispatchQueue.main.async {
                    self.showErrorAlert(message: "Netzwerkfehler: \(error.localizedDescription)")
                }
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
                var errorMessage = "Server-Fehler: Status \(httpResponse.statusCode)"
                if let data = data,
                   let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                   let detail = json["detail"] as? String {
                    errorMessage = "Server-Fehler: \(detail)"
                }
                DispatchQueue.main.async {
                    self.showErrorAlert(message: errorMessage)
                }
                return
            }
            
            guard let data = data else { return }
            
            do {
                if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                   let polishedText = json["polished_text"] as? String {
                    DispatchQueue.main.async {
                        self.injectTextGlobally(text: polishedText)
                    }
                } else {
                    print("Invalid backend response JSON")
                }
            } catch {
                print("Failed to parse JSON: \(error)")
            }
        }
        task.resume()
    }
    
    private func resetState() {
        isUploading = false
        isRecording = false
        updateStatusItemUI()
    }
    
    private func logToFile(_ message: String) {
        let logMessage = "[\(Date())] \(message)\n"
        let logURL = URL(fileURLWithPath: "/Users/lenz/Documents/Apps/wispr/mac_debug.log")
        if let data = logMessage.data(using: .utf8) {
            if FileManager.default.fileExists(atPath: logURL.path) {
                if let fileHandle = try? FileHandle(forWritingTo: logURL) {
                    fileHandle.seekToEndOfFile()
                    fileHandle.write(data)
                    fileHandle.closeFile()
                }
            } else {
                try? data.write(to: logURL)
            }
        }
    }

    private func injectTextGlobally(text: String) {
        // 1. Set new text to clipboard
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.declareTypes([.string], owner: nil)
        pasteboard.setString(text, forType: .string)
        logToFile("Text copied to clipboard: \(text)")
        
        // Save to 24-hour dictation history
        HistoryManager.shared.saveItem(text: text)
        
        // Wait 0.2 seconds for the user to fully release the hotkey modifier keys (Command/Option)
        // so that they do not interfere with the simulated Cmd+V keystroke.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            // Check Accessibility trust
            let isTrusted = AXIsProcessTrusted()
            self.logToFile("AXIsProcessTrusted: \(isTrusted)")
            
            if let activeApp = NSWorkspace.shared.frontmostApplication {
                self.logToFile("Frontmost Application: \(activeApp.localizedName ?? "nil") (\(activeApp.bundleIdentifier ?? "nil"))")
            } else {
                self.logToFile("Frontmost Application: None")
            }
            
            // Attempt AppleScript paste (highly reliable system integration)
            let appleScript = """
            tell application "System Events"
                keystroke "v" using command down
            end tell
            """
            
            self.logToFile("Executing AppleScript paste...")
            if let scriptObject = NSAppleScript(source: appleScript) {
                var errorDict: NSDictionary?
                scriptObject.executeAndReturnError(&errorDict)
                if let error = errorDict {
                    self.logToFile("AppleScript paste failed: \(error), falling back to CGEvent")
                    self.simulatePasteViaCGEvent()
                } else {
                    self.logToFile("AppleScript paste executed successfully")
                }
            } else {
                self.logToFile("Could not initialize AppleScript, falling back to CGEvent")
                self.simulatePasteViaCGEvent()
            }
        }
    }
    
    private func simulatePasteViaCGEvent() {
        self.logToFile("Fallback to CGEvent paste simulation...")
        let src = CGEventSource(stateID: .hidSystemState)
        let loc = CGEventTapLocation.cghidEventTap
        
        // 1. Command Key Down
        let cmdDown = CGEvent(keyboardEventSource: src, virtualKey: 0x37, keyDown: true)
        cmdDown?.flags = .maskCommand
        cmdDown?.post(tap: loc)
        usleep(10000) // 10ms delay to let the OS register the modifier key down state
        
        // 2. 'V' Key Down
        let vDown = CGEvent(keyboardEventSource: src, virtualKey: 0x09, keyDown: true)
        vDown?.flags = .maskCommand
        vDown?.post(tap: loc)
        usleep(10000) // 10ms delay
        
        // 3. 'V' Key Up
        let vUp = CGEvent(keyboardEventSource: src, virtualKey: 0x09, keyDown: false)
        vUp?.flags = .maskCommand
        vUp?.post(tap: loc)
        usleep(10000) // 10ms delay
        
        // 4. Command Key Up
        let cmdUp = CGEvent(keyboardEventSource: src, virtualKey: 0x37, keyDown: false)
        cmdUp?.post(tap: loc)
        self.logToFile("CGEvent paste simulation posted successfully")
    }
    
    private func showErrorAlert(message: String) {
        let alert = NSAlert()
        alert.messageText = "Wispr Fehler"
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }
    
    @objc private func quitApp() {
        NSApplication.shared.terminate(nil)
    }
    
    // MARK: - Global Carbon Hotkey Registration
    
    private func registerGlobalHotkey() {
        // Unregister old hotkey first
        if let ref = hotKeyRef {
            UnregisterEventHotKey(ref)
            hotKeyRef = nil
        }
        
        // Read keys from UserDefaults with default fallbacks
        let targetKeyCode = UserDefaults.standard.integer(forKey: "hotkey_keycode") == 0 ? 9 : UserDefaults.standard.integer(forKey: "hotkey_keycode")
        let targetModifiers = UserDefaults.standard.integer(forKey: "hotkey_modifiers") == 0 ? (cmdKey | optionKey) : UserDefaults.standard.integer(forKey: "hotkey_modifiers")
        
        let hotKeyID = EventHotKeyID(signature: 0x57737072, id: 1) // 'Wspr'
        
        var eventType = EventTypeSpec()
        eventType.eventClass = OSType(kEventClassKeyboard)
        eventType.eventKind = OSType(kEventHotKeyPressed)
        
        let target = GetApplicationEventTarget()
        
        let handlerCallback: EventHandlerUPP = { (nextHandler, event, userData) -> OSStatus in
            // Trigger hotkey down
            DispatchQueue.main.async {
                MenuBarController.shared?.handleHotkeyTrigger()
            }
            return noErr
        }
        
        InstallEventHandler(target, handlerCallback, 1, &eventType, nil, nil)
        
        let status = RegisterEventHotKey(
            UInt32(targetKeyCode),
            UInt32(targetModifiers),
            hotKeyID,
            target,
            0,
            &hotKeyRef
        )
        
        if status != noErr {
            print("Failed to register Carbon global hotkey: \(status)")
        }
    }
    
    // MARK: - Key Release Monitoring (for Hold to Speak)
    
    private func startKeyReleaseMonitor() {
        let targetKeyCode = UserDefaults.standard.integer(forKey: "hotkey_keycode") == 0 ? 9 : UserDefaults.standard.integer(forKey: "hotkey_keycode")
        let targetModifiers = UserDefaults.standard.integer(forKey: "hotkey_modifiers") == 0 ? (cmdKey | optionKey) : UserDefaults.standard.integer(forKey: "hotkey_modifiers")
        
        let eventHandler: (NSEvent) -> NSEvent? = { [weak self] event in
            guard let self = self else { return event }
            
            var shouldStop = false
            
            if event.type == .keyUp && Int(event.keyCode) == targetKeyCode {
                // Main key released
                shouldStop = true
            } else if event.type == .flagsChanged {
                let flags = event.modifierFlags
                var currentModifiers = 0
                if flags.contains(.command) { currentModifiers |= cmdKey }
                if flags.contains(.option) { currentModifiers |= optionKey }
                if flags.contains(.shift) { currentModifiers |= shiftKey }
                if flags.contains(.control) { currentModifiers |= controlKey }
                
                // If any of the required modifier keys has been released, stop recording
                if (currentModifiers & targetModifiers) != targetModifiers {
                    shouldStop = true
                }
            }
            
            if shouldStop && self.isRecording {
                DispatchQueue.main.async {
                    self.stopRecordingAndUpload()
                    self.stopKeyReleaseMonitor()
                }
            }
            
            return event
        }
        
        // Register local and global monitors
        localReleaseMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyUp, .flagsChanged], handler: eventHandler)
        globalReleaseMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.keyUp, .flagsChanged]) { event in
            _ = eventHandler(event)
        }
    }
    
    private func stopKeyReleaseMonitor() {
        if let monitor = localReleaseMonitor {
            NSEvent.removeMonitor(monitor)
            localReleaseMonitor = nil
        }
        if let monitor = globalReleaseMonitor {
            NSEvent.removeMonitor(monitor)
            globalReleaseMonitor = nil
        }
    }
}
