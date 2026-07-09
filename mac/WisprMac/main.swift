import Cocoa

print("main.swift: App starting...")
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
print("main.swift: Delegate set, calling app.run()...")
app.run()
