import SwiftUI
import Carbon

struct SettingsView: View {
    @AppStorage("backend_url") private var backendUrl = "http://localhost:8000"
    @AppStorage("recording_mode") private var recordingMode = "toggle" // "toggle" or "hold"
    @AppStorage("hotkey_keycode") private var hotkeyCode = 9 // Default 'V'
    @AppStorage("hotkey_modifiers") private var hotkeyModifiers = 2304 // Default: cmdKey (256) | optionKey (2048)
    
    @State private var isRecordingHotkey = false
    @State private var hotkeyMonitor: Any? = nil
    
    var body: some View {
        Form {
            Section(header: Text("Server-Konfiguration").font(.headline)) {
                TextField("Backend URL", text: $backendUrl)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .frame(width: 350)
                Text("Trage hier die Adresse deines Python-Backends ein.")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            
            Divider()
                .padding(.vertical, 10)
            
            Section(header: Text("Aufnahme-Verhalten").font(.headline)) {
                Picker("Diktier-Modus", selection: $recordingMode) {
                    Text("Einmal drücken zum Starten / Stoppen (Toggle)").tag("toggle")
                    Text("Gedrückt halten zum Sprechen (Loslassen zum Stoppen)").tag("hold")
                }
                .pickerStyle(RadioGroupPickerStyle())
                .onChange(of: recordingMode) { _ in
                    // Notify MenuBarController about mode change
                    NotificationCenter.default.post(name: NSNotification.Name("WisprSettingsChanged"), object: nil)
                }
            }
            
            Divider()
                .padding(.vertical, 10)
            
            Section(header: Text("Tastenkombination (Hotkey)").font(.headline)) {
                HStack {
                    Text("Aktivierungs-Hotkey:")
                    
                    Button(action: startRecordingHotkey) {
                        Text(isRecordingHotkey ? "Drücke Tasten-Kombi..." : getHotkeyDescription())
                            .frame(minWidth: 150)
                            .font(.system(.body, design: .monospaced))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(isRecordingHotkey ? .red : .blue)
                    
                    if isRecordingHotkey {
                        Button("Abbrechen") {
                            stopRecordingHotkey()
                        }
                    }
                }
                Text("Standard: Option (⌥) + Command (⌘) + V. Klicke auf den Button, um eine neue Kombination einzugeben.")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
        }
        .padding(20)
        .frame(width: 480, height: 350)
    }
    
    private func startRecordingHotkey() {
        isRecordingHotkey = true
        
        // Install local monitor to capture the next key down event
        hotkeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            // Extract keycode and modifiers
            let code = Int(event.keyCode)
            let flags = event.modifierFlags
            
            // Map Cocoa modifiers to Carbon modifiers
            var carbonModifiers = 0
            if flags.contains(.command) { carbonModifiers |= cmdKey }
            if flags.contains(.option) { carbonModifiers |= optionKey }
            if flags.contains(.shift) { carbonModifiers |= shiftKey }
            if flags.contains(.control) { carbonModifiers |= controlKey }
            
            // Save to AppStorage
            self.hotkeyCode = code
            self.hotkeyModifiers = carbonModifiers
            
            self.stopRecordingHotkey()
            
            // Notify MenuBarController to re-register the hotkey
            NotificationCenter.default.post(name: NSNotification.Name("WisprSettingsChanged"), object: nil)
            
            // Consume the event so it doesn't propagate
            return nil
        }
    }
    
    private func stopRecordingHotkey() {
        if let monitor = hotkeyMonitor {
            NSEvent.removeMonitor(monitor)
            hotkeyMonitor = nil
        }
        isRecordingHotkey = false
    }
    
    private func getHotkeyDescription() -> String {
        var desc = ""
        
        // Add modifier symbols
        if (hotkeyModifiers & controlKey) != 0 { desc += "⌃ " }
        if (hotkeyModifiers & shiftKey) != 0 { desc += "⇧ " }
        if (hotkeyModifiers & optionKey) != 0 { desc += "⌥ " }
        if (hotkeyModifiers & cmdKey) != 0 { desc += "⌘ " }
        
        // Map keycode to string character
        desc += keycodeToString(keyCode: UInt16(hotkeyCode))
        
        return desc.isEmpty ? "Keine" : desc
    }
    
    private func keycodeToString(keyCode: UInt16) -> String {
        // Simple mapping for common keys
        switch keyCode {
        case 0: return "A"
        case 1: return "S"
        case 2: return "D"
        case 3: return "F"
        case 4: return "H"
        case 5: return "G"
        case 6: return "Z"
        case 7: return "X"
        case 8: return "C"
        case 9: return "V"
        case 11: return "B"
        case 12: return "Q"
        case 13: return "W"
        case 14: return "E"
        case 15: return "R"
        case 16: return "Y"
        case 17: return "T"
        case 31: return "O"
        case 32: return "U"
        case 34: return "I"
        case 35: return "P"
        case 36: return "↩" // Enter
        case 37: return "L"
        case 38: return "J"
        case 40: return "K"
        case 45: return "N"
        case 46: return "M"
        case 49: return "␣" // Space
        default:
            return "Taste \(keyCode)"
        }
    }
}
