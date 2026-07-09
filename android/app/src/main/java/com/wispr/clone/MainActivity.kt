package com.wispr.clone

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_REQUEST_CODE = 101

    private lateinit var urlInput: EditText
    private lateinit var saveUrlButton: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAudioPermission: Button
    private lateinit var accessibilityStatusIcon: ImageView
    private lateinit var overlayStatusIcon: ImageView
    private lateinit var audioStatusIcon: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        urlInput = findViewById(R.id.urlInput)
        saveUrlButton = findViewById(R.id.saveUrlButton)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAudioPermission = findViewById(R.id.btnAudioPermission)
        accessibilityStatusIcon = findViewById(R.id.accessibilityStatusIcon)
        overlayStatusIcon = findViewById(R.id.overlayStatusIcon)
        audioStatusIcon = findViewById(R.id.audioStatusIcon)

        setupSharedPreferences()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }

    private fun setupSharedPreferences() {
        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("backend_url", "http://10.0.2.2:8000")
        urlInput.setText(savedUrl)
    }

    private fun setupListeners() {
        saveUrlButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("backend_url", url)
                    .apply()
                Toast.makeText(this, "Backend URL gespeichert!", Toast.LENGTH_SHORT).show()
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay-Berechtigung bereits erteilt!", Toast.LENGTH_SHORT).show()
            }
        }

        btnAudioPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_REQUEST_CODE
                )
            } else {
                Toast.makeText(this, "Mikrofon-Berechtigung bereits erteilt!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatusIndicators() {
        // 1. Accessibility Service check
        val accessibilityEnabled = isAccessibilityServiceEnabled(WisprCloneAccessibilityService::class.java)
        if (accessibilityEnabled) {
            accessibilityStatusIcon.setImageResource(android.R.drawable.presence_online)
            accessibilityStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            btnAccessibility.text = "Aktiviert"
            btnAccessibility.isEnabled = false
        } else {
            accessibilityStatusIcon.setImageResource(android.R.drawable.presence_offline)
            accessibilityStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.recording_red))
            btnAccessibility.text = "Aktivieren"
            btnAccessibility.isEnabled = true
        }

        // 2. Overlay permission check
        val canDrawOverlays = Settings.canDrawOverlays(this)
        if (canDrawOverlays) {
            overlayStatusIcon.setImageResource(android.R.drawable.presence_online)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            btnOverlay.text = "Aktiviert"
            btnOverlay.isEnabled = false
        } else {
            overlayStatusIcon.setImageResource(android.R.drawable.presence_offline)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.recording_red))
            btnOverlay.text = "Aktivieren"
            btnOverlay.isEnabled = true
        }

        // 3. Audio recording permission check
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            audioStatusIcon.setImageResource(android.R.drawable.presence_online)
            audioStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            btnAudioPermission.text = "Erteilt"
            btnAudioPermission.isEnabled = false
        } else {
            audioStatusIcon.setImageResource(android.R.drawable.presence_offline)
            audioStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.recording_red))
            btnAudioPermission.text = "Zulassen"
            btnAudioPermission.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(serviceClass: Class<*>): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (enabledService in enabledServices) {
            val enabledServiceInfo = enabledService.resolveInfo.serviceInfo
            if (enabledServiceInfo.packageName == packageName && enabledServiceInfo.name == serviceClass.name) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Mikrofon-Berechtigung erteilt!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Mikrofon-Berechtigung verweigert!", Toast.LENGTH_SHORT).show()
            }
            updateStatusIndicators()
        }
    }
}
