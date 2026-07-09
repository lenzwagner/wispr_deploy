import Foundation

struct HistoryItem: Identifiable, Codable {
    let id: UUID
    let text: String
    let timestamp: Date
}

class HistoryManager {
    static let shared = HistoryManager()
    
    private let key = "dictation_history"
    
    func saveItem(text: String) {
        var items = getHistory()
        let newItem = HistoryItem(id: UUID(), text: text, timestamp: Date())
        items.insert(newItem, at: 0) // Newest first
        
        // Keep only items from the last 24 hours (86400 seconds)
        let cutoff = Date().addingTimeInterval(-86400)
        items = items.filter { $0.timestamp > cutoff }
        
        if let encoded = try? JSONEncoder().encode(items) {
            UserDefaults.standard.set(encoded, forKey: key)
        }
        
        // Notify SettingsView to refresh
        NotificationCenter.default.post(name: NSNotification.Name("WisprHistoryChanged"), object: nil)
    }
    
    func getHistory() -> [HistoryItem] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        if let decoded = try? JSONDecoder().decode([HistoryItem].self, from: data) {
            let cutoff = Date().addingTimeInterval(-86400)
            return decoded.filter { $0.timestamp > cutoff }
        }
        return []
    }
    
    func clearHistory() {
        UserDefaults.standard.removeObject(forKey: key)
        NotificationCenter.default.post(name: NSNotification.Name("WisprHistoryChanged"), object: nil)
    }
}
