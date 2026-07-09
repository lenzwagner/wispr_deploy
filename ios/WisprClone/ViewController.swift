import UIKit

class ViewController: UIViewController {

    private let titleLabel = UILabel()
    private let descriptionLabel = UILabel()
    private let serverTitleLabel = UILabel()
    private let urlTextField = UITextField()
    private let saveButton = UIButton(type: .system)
    private let settingsButton = UIButton(type: .system)
    private let testTextField = UITextField()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 15/255, green: 23/255, blue: 42/255, alpha: 1.0) // Slate 900
        setupNavigation()
        setupUI()
        loadSavedUrl()
    }

    private func setupNavigation() {
        title = "Wispr Flow"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationController?.navigationBar.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.white]
        navigationController?.navigationBar.barTintColor = UIColor(red: 15/255, green: 23/255, blue: 42/255, alpha: 1.0)
    }

    private func setupUI() {
        // Description
        descriptionLabel.text = "Wispr Flow ist eine systemweite KI-gestützte Tastatur. Gehe in die iOS Einstellungen, um die Tastatur zu aktivieren und 'Vollen Zugriff' zu erlauben."
        descriptionLabel.textColor = UIColor(red: 148/255, green: 163/255, blue: 184/255, alpha: 1.0) // Slate 400
        descriptionLabel.font = UIFont.systemFont(ofSize: 15)
        descriptionLabel.numberOfLines = 0
        descriptionLabel.textAlignment = .center
        descriptionLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(descriptionLabel)

        // Settings Button
        settingsButton.setTitle("Tastatur-Einstellungen öffnen", for: .normal)
        settingsButton.titleLabel?.font = UIFont.boldSystemFont(ofSize: 16)
        settingsButton.backgroundColor = UIColor(red: 99/255, green: 102/255, blue: 241/255, alpha: 1.0) // Indigo 500
        settingsButton.setTitleColor(.white, for: .normal)
        settingsButton.layer.cornerRadius = 12
        settingsButton.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        settingsButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(settingsButton)

        // Server Section
        serverTitleLabel.text = "Server-Konfiguration:"
        serverTitleLabel.textColor = .white
        serverTitleLabel.font = UIFont.boldSystemFont(ofSize: 18)
        serverTitleLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(serverTitleLabel)

        urlTextField.placeholder = "http://localhost:8000"
        urlTextField.text = "http://localhost:8000"
        urlTextField.textColor = .white
        urlTextField.backgroundColor = UIColor(red: 30/255, green: 41/255, blue: 59/255, alpha: 1.0) // Slate 800
        urlTextField.borderStyle = .roundedRect
        urlTextField.keyboardType = .URL
        urlTextField.autocapitalizationType = .none
        urlTextField.autocorrectionType = .no
        urlTextField.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(urlTextField)

        saveButton.setTitle("URL Speichern", for: .normal)
        saveButton.titleLabel?.font = UIFont.boldSystemFont(ofSize: 16)
        saveButton.setTitleColor(UIColor(red: 16/255, green: 185/255, blue: 129/255, alpha: 1.0), for: .normal) // Emerald
        saveButton.addTarget(self, action: #selector(saveUrl), for: .touchUpInside)
        saveButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(saveButton)

        // Test field
        testTextField.placeholder = "Tippe hier zum Testen der Tastatur..."
        testTextField.textColor = .white
        testTextField.backgroundColor = UIColor(red: 30/255, green: 41/255, blue: 59/255, alpha: 1.0)
        testTextField.borderStyle = .roundedRect
        testTextField.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(testTextField)

        // Constraints
        NSLayoutConstraint.activate([
            descriptionLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            descriptionLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            descriptionLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),

            settingsButton.topAnchor.constraint(equalTo: descriptionLabel.bottomAnchor, constant: 24),
            settingsButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            settingsButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            settingsButton.heightAnchor.constraint(equalToConstant: 50),

            serverTitleLabel.topAnchor.constraint(equalTo: settingsButton.bottomAnchor, constant: 40),
            serverTitleLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),

            urlTextField.topAnchor.constraint(equalTo: serverTitleLabel.bottomAnchor, constant: 12),
            urlTextField.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            urlTextField.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            urlTextField.heightAnchor.constraint(equalToConstant: 44),

            saveButton.topAnchor.constraint(equalTo: urlTextField.bottomAnchor, constant: 8),
            saveButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),

            testTextField.topAnchor.constraint(equalTo: saveButton.bottomAnchor, constant: 40),
            testTextField.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            testTextField.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            testTextField.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    @objc private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    @objc private func saveUrl() {
        let url = urlTextField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !url.isEmpty {
            // Save to shared AppGroup user defaults so extension can read it
            let defaults = UserDefaults(suiteName: "group.com.wispr.clone")
            defaults?.set(url, forKey: "backend_url")
            defaults?.synchronize()
            
            // Also save locally
            UserDefaults.standard.set(url, forKey: "backend_url")
            
            let alert = UIAlertController(title: "Erfolg", message: "Backend URL gespeichert!", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
        }
    }

    private func loadSavedUrl() {
        let defaults = UserDefaults(suiteName: "group.com.wispr.clone")
        let url = defaults?.string(forKey: "backend_url") ?? UserDefaults.standard.string(forKey: "backend_url") ?? "http://localhost:8000"
        urlTextField.text = url
    }
}
