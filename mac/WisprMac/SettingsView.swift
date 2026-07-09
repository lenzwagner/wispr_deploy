import SwiftUI
import Carbon

struct SettingsView: View {
    @AppStorage("backend_url") private var backendUrl = "https://wispr-deploy.onrender.com"
    @AppStorage("recording_mode") private var recordingMode = "toggle" // "toggle" or "hold"
    @AppStorage("hotkey_keycode") private var hotkeyCode = 9 // Default 'V'
    @AppStorage("hotkey_modifiers") private var hotkeyModifiers = 2304 // Default: cmdKey (256) | optionKey (2048)
    
    @State private var isRecordingHotkey = false
    @State private var hotkeyMonitor: Any? = nil
    @State private var historyItems: [HistoryItem] = []
    
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Section: Server Config
                VStack(alignment: .leading, spacing: 8) {
                    Text("Server-Konfiguration").font(.headline)
                    TextField("Backend URL", text: $backendUrl)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                    Text("Trage hier die Adresse deines Python-Backends ein.")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
                
                Divider()
                
                // Section: Aufnahme-Verhalten
                VStack(alignment: .leading, spacing: 8) {
                    Text("Aufnahme-Verhalten").font(.headline)
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
                
                // Section: Hotkey
                VStack(alignment: .leading, spacing: 8) {
                    Text("Tastenkombination (Hotkey)").font(.headline)
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
                
                Divider()
                
                // Section: 24h Dictation History
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Verlauf (letzte 24 Std.)")
                            .font(.headline)
                        Spacer()
                        if !historyItems.isEmpty {
                            Button(action: {
                                HistoryManager.shared.clearHistory()
                            }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "trash")
                                    Text("Verlauf leeren")
                                }
                                .font(.caption)
                                .foregroundColor(.red)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    
                    if historyItems.isEmpty {
                        Text("Keine Einträge vorhanden.")
                            .font(.callout)
                            .foregroundColor(.gray)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else {
                        VStack(spacing: 8) {
                            ForEach(historyItems) { item in
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(formatTime(item.timestamp))
                                            .font(.caption)
                                            .foregroundColor(.blue)
                                            .fontWeight(.semibold)
                                        Text(item.text)
                                            .font(.body)
                                            .multilineTextAlignment(.leading)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    
                                    Button(action: {
                                        let pb = NSPasteboard.general
                                        pb.clearContents()
                                        pb.setString(item.text, forType: .string)
                                    }) {
                                        Image(systemName: "doc.on.doc")
                                            .resizable()
                                            .scaledToFit()
                                            .frame(width: 14, height: 14)
                                            .foregroundColor(.gray)
                                    }
                                    .buttonStyle(.borderless)
                                    .help("Kopieren")
                                    .padding(.top, 2)
                                }
                                .padding(10)
                                .background(Color(NSColor.controlBackgroundColor))
                                .cornerRadius(8)
                            }
                        }
                    }
                }
            }
            .padding(25)
        }
        .frame(width: 500, height: 500)
        .onAppear {
            refreshHistory()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("WisprHistoryChanged"))) { _ in
            refreshHistory()
        }
    }
    
    private func refreshHistory() {
        historyItems = HistoryManager.shared.getHistory()
    }
    
    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        formatter.locale = Locale.current
        
        if Calendar.current.isDateInToday(date) {
            return formatter.string(from: date)
        } else {
            return "Gestern, \(formatter.string(from: date))"
        }
    }
    
    private func startRecordingHotkey() {
        isRecordingHotkey = true
        
        // Install local monitor to capture the next key down event
        hotkeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let code = Int(event.keyCode)
            let flags = event.modifierFlags
            
            var carbonModifiers = 0
            if flags.contains(.command) { carbonModifiers |= cmdKey }
            if flags.contains(.option) { carbonModifiers |= optionKey }
            if flags.contains(.shift) { carbonModifiers |= shiftKey }
            if flags.contains(.control) { carbonModifiers |= controlKey }
            
            self.hotkeyCode = code
            self.hotkeyModifiers = carbonModifiers
            
            self.stopRecordingHotkey()
            
            // Notify MenuBarController to re-register the hotkey
            NotificationCenter.default.post(name: NSNotification.Name("WisprSettingsChanged"), object: nil)
            
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
        
        if (hotkeyModifiers & controlKey) != 0 { desc += "⌃ " }
        if (hotkeyModifiers & shiftKey) != 0 { desc += "⇧ " }
        if (hotkeyModifiers & optionKey) != 0 { desc += "⌥ " }
        if (hotkeyModifiers & cmdKey) != 0 { desc += "⌘ " }
        
        desc += keycodeToString(keyCode: UInt16(hotkeyCode))
        
        return desc.isEmpty ? "Keine" : desc
    }
    
    private func keycodeToString(keyCode: UInt16) -> String {
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
