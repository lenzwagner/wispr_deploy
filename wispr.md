```markdown
# Dokumentation & Technische Anleitung: Nachbau der Wispr Flow Funktionalität für Android & iOS

Dieses Dokument beschreibt umfassend, was **Wispr Flow** ist, wie die zugrundeliegende Architektur aufgebaut ist und wie die Kernfunktionalitäten für die mobilen Betriebssysteme Android und iOS implementiert werden können.

---

## 1. Was ist Wispr Flow?

**Wispr Flow** ist kein herkömmlicher Sprachrekorder und auch kein einfaches Diktierwerkzeug, wie man es von Standard-Tastaturen kennt. Es ist ein **systemweiter, KI-gestützter Schreibassistent (Voice-First Productivity Tool)**. 

Das Tool löst die drei größten Probleme traditioneller Spracherkennung (Speech-to-Text):

1. **Das „Ähm“-Problem (Füllwörter):** Menschen sprechen nicht so, wie sie schreiben. Reine Transkriptionstools schreiben jedes *„äh“*, *„öhm“*, Husten oder doppelte Wörter stumpf mit. Wispr Flow filtert diese durch ein nachgeschaltetes Sprachmodell (LLM) komplett heraus.
2. **Selbstkorrekturen im Redefluss:** Wenn ein Nutzer spricht: *„Lass uns am Dienstag um... nein, warte, Dienstag kann ich nicht, lieber am Donnerstag um 14 Uhr treffen“*, versteht die KI den Kontext und die Absicht. Sie gibt präzise aus: *„Lass uns am Donnerstag um 14 Uhr treffen.“*
3. **Globale Barrierefreiheit:** Über tief ins Betriebssystem integrierte Schnittstellen funktioniert die App über eine einzige Geste oder Taste in **jeder beliebigen Anwendung** (Slack, WhatsApp, Gmail, Notion etc.), ohne dass Text manuell kopiert und eingefügt werden muss.

Darüber hinaus passt sich das System dem Kontext an (schreibt beispielsweise formeller in Outlook und lockerer in WhatsApp) und erlaubt die Formatierung durch direkte Sprachbefehle (z. B. *„Mach daraus eine Bulletpoint-Liste“*).

---

## 2. Der Entwicklungs-Fahrplan (MVP-Ansatz)

Um nicht in der Komplexität zu versinken, sollte das Projekt in dieser logischen Reihenfolge aufgeteilt und aufgebaut werden:

| Phase | Fokus | Technologie / Aufgabe |
| :--- | :--- | :--- |
| **Phase 1** | **Backend & KI-Pipeline** | Erstellen der API, die Audio empfängt, transkribiert und optimiert. |
| **Phase 2** | **OS-Integration** | Implementierung der System-Schnittstellen (Android Service / iOS Keyboard). |
| **Phase 3** | **Verbindung & UX** | Streaming des Audios von den Clients zum Backend und nahtlose Textinjektion. |

---

## 3. System-Integration: Textinjektion in Drittanbieter-Apps

Die größte technische Hürde beim Nachbau ist das globale Einfügen des Textes. Da Android und iOS strikte Sandbox-Sicherheitsmodelle nutzen, unterscheidet sich die Implementierung grundlegend.

### 🇦 Android-Architektur: Der `AccessibilityService`-Ansatz

Unter Android ist der beste Weg die Nutzung eines Barrierefreiheitsdienstes (`AccessibilityService`). Dieser hat das Recht, das aktive Fenster zu inspizieren und Text direkt in das fokussierte Eingabefeld zu injizieren.

#### Schritt 1: Konfiguration (`accessibility_service_config.xml`)
Der Dienst muss so konfiguriert werden, dass er Interaktionen mit Eingabefeldern überwacht:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeViewFocused|typeWindowStateChanged"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true" />

```

#### Schritt 2: Dienst-Implementierung (Kotlin)

Hier ist der minimalistische Code, um den optimierten KI-Text in das gerade aktive Textfeld zu injizieren:

```kotlin
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WisprCloneAccessibilityService : AccessibilityService() {

    private var currentFocusedNode: AccessibilityNodeInfo? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Verfolge das aktuell fokussierte Eingabefeld
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source
            if (source != null && source.className == "android.widget.EditText") {
                currentFocusedNode = source
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * Diese Methode wird aufgerufen, sobald die KI-Pipeline 
     * den fertigen, bereinigten Text liefert.
     */
    fun injectTextIntoActiveField(polishedText: String) {
        val node = currentFocusedNode ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (node != null) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, 
                    polishedText
                )
            }
            // Text direkt in das Feld schreiben
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }
}

```

*Hinweis zu Android:* Um ein schwebendes Overlay (z. B. ein Mikrofon-Icon über anderen Apps) anzuzeigen, nutzt man zusätzlich die `WindowManager`-API mit dem Typ `TYPE_APPLICATION_OVERLAY`.

---

### 🍎 iOS-Architektur: Der `Custom Keyboard Extension`-Ansatz

Aus Sicherheitsgründen erlaubt iOS keinen globalen Zugriff via Accessibility für Texterstellungen in Drittanbieter-Apps. Die einzige native und zuverlässige Möglichkeit ist das Erstellen einer eigenen Systemtastatur.

#### Schritt 1: Custom Keyboard Target erstellen

Füge deinem Xcode-Projekt ein neues Target vom Typ **Custom Keyboard Extension** hinzu.

#### Schritt 2: Implementierung des Keyboards (Swift)

Statt Tasten für Buchstaben anzuzeigen, rendert dein `UIInputViewController` primär eine große Aufnahmetaste. Das Einfügen erfolgt über den `textDocumentProxy`.

```swift
import UIKit

class KeyboardViewController: UIInputViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        setupRecordButton()
    }
    
    private func setupRecordButton() {
        let recordButton = UIButton(type: .system)
        recordButton.setTitle("🎙️ Halten zum Sprechen", for: .normal)
        recordButton.titleLabel?.font = UIFont.boldSystemFont(ofSize: 18)
        recordButton.backgroundColor = .systemBlue
        recordButton.setTitleColor(.white, for: .normal)
        recordButton.layer.cornerRadius = 12
        
        // Gesten für "Hold-to-Talk" hinzufügen
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        recordButton.addGestureRecognizer(longPress)
        
        recordButton.frame = CGRect(x: 20, y: 10, width: self.view.frame.width - 40, height: 50)
        self.view.addSubview(recordButton)
    }
    
    @objc func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        if gesture.state == .began {
            // 1. Audio-Aufnahme über AVAudioEngine starten
            startAudioRecording()
        } else if gesture.state == .ended {
            // 2. Aufnahme stoppen
            stopAudioRecording { [weak self] audioUrl in
                // 3. An Backend senden & Text erhalten
                self?.sendAudioToBackend(url: audioUrl) { polishedText in
                    // 4. Text global in die aktive App einfügen
                    DispatchQueue.main.async {
                        self?.textDocumentProxy.insertText(polishedText)
                    }
                }
            }
        }
    }
    
    private func startAudioRecording() { /* Implementierung via AVAudioEngine */ }
    private func stopAudioRecording(completion: @escaping (URL) -> Void) { /* ... */ }
    private func sendAudioToBackend(url: URL, completion: @escaping (String) -> Void) { /* ... */ }
}

```

---

## 4. Die Backend-KI-Pipeline (Das Herzstück)

Um die extreme Geschwindigkeit und geringe Latenz von Wispr Flow zu erreichen, muss die Verarbeitungskette (Transkription + LLM) optimiert sein. Nimm das Audio auf den Geräten im komprimierten Format (z. B. Opus oder AAC) auf, um Bandbreite zu sparen.

```
[Audio-Stream / Datei] ──> [Whisper API] ──> [LLM (AI Polish)] ──> [Bereinigter Text zurück an App]

```

### 1. Transkription mit hohem Tempo

Nutze eine API, die Whisper-Modelle auf spezialisierter Hardware ausführt (z. B. **Groq** mit `whisper-large-v3` oder **Deepgram**). Die Verarbeitungszeit liegt dort meist unter 300 Millisekunden für kurze Sprachnachrichten.

* *Tipp für Eigennamen:* Übergib beim Whisper-API-Aufruf den Parameter `prompt` mit einem benutzerdefinierten Wörterbuch deines Nutzers. Dadurch werden Namen direkt fehlerfrei transkribiert.

### 2. Der Optimierungsprompt (Der „AI Polish Layer“)

Das transkribierte Ergebnis wird sofort an ein schnelles, kostengünstiges LLM weitergeleitet (z. B. `gpt-4o-mini` oder `Claude 3.5 Haiku`). Der System Prompt entscheidet darüber, ob das Ergebnis wie ein holpriges Diktat oder wie natürlich geschriebener Text wirkt:

```text
Du bist das Text-Optimierungs-Modul einer professionellen Diktier-App. 
Deine Aufgabe ist es, das rohe, gesprochene Transkript in perfekten, geschriebenen Text zu verwandeln.

REGELN:
1. Entferne absolut alle Füllwörter (ähm, ah, wie gesagt, öh, sozusagen).
2. Bereinige Selbstkorrekturen intelligent. (Beispiel: "Wir sehen uns um 5... ah nee, um 6" wird zu "Wir sehen uns um 6 Uhr.")
3. Füge sinnvolle Satzzeichen (Punkte, Kommas, Fragezeichen) basierend auf dem Kontext ein.
4. Falls der Nutzer Formatierungsanweisungen gibt (z.B. "Mach daraus eine Liste: Punkt eins... Punkt zwei..."), wende Markdown-Formatierung an.
5. Verändere niemals den inhaltlichen Kern oder den Sinn der Aussage.
6. Füge keinerlei Metatext oder KI-Floskeln hinzu (Antworte NIEMALS mit "Hier ist dein bereinigter Text:"). Gib AUSSCHLIESSLICH den finalen Text zurück.

```

---

## 5. Fortgeschrittene Features nachbauen

### A. Kontextbezogene Schreibstile (Styles)

Wispr Flow passt den Ton an, je nachdem wo du schreibst.

* **Umsetzung:** Übergib dem LLM-Prompt die Information, in welcher App der Nutzer sich gerade befindet. Auf Android ist dies über den *Package Name* der aktiven App ermittelbar (z. B. `com.slack` oder `com.whatsapp`).
* **Logik im Prompt:** `Erweitere den System Prompt: WENN App == 'Slack', antworte prägnanter und lockerer. WENN App == 'Gmail', nutze eine formelle Briefstruktur.`

### B. Snippets (Sprach-Shortcuts)

Der Nutzer sagt ein Schlüsselwort und ein vordefinierter Text wird eingefügt (z. B. *"Einfügen Signatur"*).

* **Umsetzung:** Lege eine lokale Datenbank mit Key-Value-Paaren an (`"meine signatur"` -> `"Mit freundlichen Grüßen, Max Mustermann..."`). Bevor du den Text an das LLM schickst oder injizierst, prüfst du via RegEx, ob das Transkript ein registriertes Snippet-Shortcut enthält, und ersetzt es direkt lokal.

---

## 6. Wichtige Fallstricke & Store-Richtlinien

Wer eine App dieser Art in die Stores bringen will, stößt auf regulatorische Hürden der Plattform-Betreiber:

1. **Android Play Store Review:** Google prüft Apps, die den `AccessibilityService` nutzen, extrem streng, da dieser oft von Schadsoftware missbraucht wird. Im Review-Formular muss detailliert (meist via Video-Nachweis) begründet werden, dass der Dienst zwingend für die Kernfunktion der Barrierefreiheit (Sprache-zu-Text für motorisch eingeschränkte Menschen) benötigt wird.
2. **iOS Network Access:** Standardmäßig haben iOS Keyboard Extensions *keinen* Netzwerkzugriff. Um Audio-Daten an dein Backend zu senden, musst du in der `Info.plist` der Extension den Wert `RequestsOpenAccess` auf `YES` setzen. Auch dies erfordert beim App-Store-Review eine plausible Begründung für Apple.

```

```