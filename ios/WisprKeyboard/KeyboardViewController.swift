import UIKit
import AVFoundation

class KeyboardViewController: UIInputViewController {

    private var recordButton: UIButton!
    private var nextKeyboardButton: UIButton!
    private var activityIndicator: UIActivityIndicatorView!
    
    private var audioRecorder: AVAudioRecorder?
    private var recordingSession: AVAudioSession?
    private var audioFilename: URL?
    
    override func updateViewConstraints() {
        super.updateViewConstraints()
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        setupSession()
        setupUI()
    }
    
    private func setupSession() {
        recordingSession = AVAudioSession.sharedInstance()
        do {
            try recordingSession?.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try recordingSession?.setActive(true)
        } catch {
            print("Failed to set up recording session: \(error)")
        }
    }
    
    private func setupUI() {
        // Setup Next Keyboard Button (Required by Apple Guidelines)
        nextKeyboardButton = UIButton(type: .system)
        nextKeyboardButton.setTitle("🌐", for: .normal)
        nextKeyboardButton.titleLabel?.font = UIFont.systemFont(ofSize: 22)
        nextKeyboardButton.translatesAutoresizingMaskIntoConstraints = false
        nextKeyboardButton.addTarget(self, action: #selector(handleNextKeyboard), for: .touchUpInside)
        view.addSubview(nextKeyboardButton)
        
        // Setup Record Button
        recordButton = UIButton(type: .custom)
        recordButton.setTitle("🎙️ Halten zum Sprechen", for: .normal)
        recordButton.titleLabel?.font = UIFont.boldSystemFont(ofSize: 16)
        recordButton.backgroundColor = UIColor(red: 99/255, green: 102/255, blue: 241/255, alpha: 1.0) // Indigo 500
        recordButton.setTitleColor(.white, for: .normal)
        recordButton.layer.cornerRadius = 15
        recordButton.translatesAutoresizingMaskIntoConstraints = false
        
        // Gestures for Hold-to-Talk
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.2
        recordButton.addGestureRecognizer(longPress)
        
        // Tap gesture fallback
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        recordButton.addGestureRecognizer(tap)
        
        view.addSubview(recordButton)
        
        // Setup Activity Indicator
        activityIndicator = UIActivityIndicatorView(style: .medium)
        activityIndicator.color = .white
        activityIndicator.hidesWhenStopped = true
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(activityIndicator)
        
        // Layout Constraints
        NSLayoutConstraint.activate([
            nextKeyboardButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            nextKeyboardButton.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -12),
            nextKeyboardButton.widthAnchor.constraint(equalToConstant: 44),
            nextKeyboardButton.heightAnchor.constraint(equalToConstant: 44),
            
            recordButton.leadingAnchor.constraint(equalTo: nextKeyboardButton.trailingAnchor, constant: 12),
            recordButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            recordButton.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            recordButton.heightAnchor.constraint(equalToConstant: 50),
            
            activityIndicator.centerXAnchor.constraint(equalTo: recordButton.centerXAnchor),
            activityIndicator.centerYAnchor.constraint(equalTo: recordButton.centerYAnchor)
        ])
    }
    
    @objc private func handleNextKeyboard() {
        self.advanceToNextInputMode()
    }
    
    @objc private func handleTap() {
        // Prompt user that they should hold the button instead of tapping
        let originalText = recordButton.title(for: .normal)
        recordButton.setTitle("⚠️ Halten zum Sprechen!", for: .normal)
        recordButton.backgroundColor = UIColor(red: 239/255, green: 68/255, blue: 68/255, alpha: 1.0) // Red
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.recordButton.setTitle(originalText, for: .normal)
            self?.recordButton.backgroundColor = UIColor(red: 99/255, green: 102/255, blue: 241/255, alpha: 1.0)
        }
    }
    
    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            startRecording()
        case .ended, .cancelled:
            stopRecordingAndUpload()
        default:
            break
        }
    }
    
    private func startRecording() {
        // Request mic permissions if not already granted
        recordingSession?.requestRecordPermission { [weak self] granted in
            guard let self = self, granted else { return }
            
            DispatchQueue.main.async {
                self.recordButton.setTitle("🎙️ Sprechen...", for: .normal)
                self.recordButton.backgroundColor = UIColor(red: 239/255, green: 68/255, blue: 68/255, alpha: 1.0) // Red
                
                let paths = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
                self.audioFilename = paths[0].appendingPathComponent("wispr_recording.m4a")
                
                let settings = [
                    AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                    AVSampleRateKey: 12000,
                    AVNumberOfChannelsKey: 1,
                    AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
                ]
                
                do {
                    self.audioRecorder = try AVAudioRecorder(url: self.audioFilename!, settings: settings)
                    self.audioRecorder?.record()
                } catch {
                    print("Could not start recording: \(error)")
                }
            }
        }
    }
    
    private func stopRecordingAndUpload() {
        audioRecorder?.stop()
        audioRecorder = nil
        
        recordButton.setTitle("", for: .normal)
        recordButton.isEnabled = false
        activityIndicator.startAnimating()
        
        guard let fileUrl = audioFilename, FileManager.default.fileExists(atPath: fileUrl.path) else {
            resetButton()
            return
        }
        
        // Retrieve backend URL from shared app group defaults or fallbacks
        let defaults = UserDefaults(suiteName: "group.com.wispr.clone")
        let backendUrlString = defaults?.string(forKey: "backend_url") ?? "http://localhost:8000"
        
        uploadAudio(fileUrl: fileUrl, backendUrlString: backendUrlString)
    }
    
    private func uploadAudio(fileUrl: URL, backendUrlString: String) {
        let transcribeUrlString = backendUrlString.appending(backendUrlString.hasSuffix("/") ? "transcribe" : "/transcribe")
        guard let url = URL(string: transcribeUrlString) else {
            resetButton()
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        let fileData: Data
        do {
            fileData = try Data(contentsOf: fileUrl)
        } catch {
            print("Failed to read audio file data: \(error)")
            resetButton()
            return
        }
        
        var body = Data()
        
        // File part
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"wispr_recording.m4a\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/mp4\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n".data(using: .utf8)!)
        
        // App name part
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"app_name\"\r\n\r\n".data(using: .utf8)!)
        body.append("iOS Custom Keyboard\r\n".data(using: .utf8)!)
        
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        
        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            
            defer {
                DispatchQueue.main.async {
                    self.resetButton()
                }
            }
            
            if let error = error {
                print("Network request failed: \(error)")
                return
            }
            
            guard let data = data else { return }
            
            do {
                if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                   let polishedText = json["polished_text"] as? String {
                    DispatchQueue.main.async {
                        self.textDocumentProxy.insertText(polishedText)
                    }
                }
            } catch {
                print("Failed to parse JSON response: \(error)")
            }
        }
        task.resume()
    }
    
    private func resetButton() {
        activityIndicator.stopAnimating()
        recordButton.isEnabled = true
        recordButton.setTitle("🎙️ Halten zum Sprechen", for: .normal)
        recordButton.backgroundColor = UIColor(red: 99/255, green: 102/255, blue: 241/255, alpha: 1.0)
    }
}
