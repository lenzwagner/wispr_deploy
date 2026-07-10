package com.wispr

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.graphics.Color
import com.github.dhaval2404.colorpicker.MaterialColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape
import android.content.res.ColorStateList

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_REQUEST_CODE = 101

    private lateinit var urlInput: EditText
    private lateinit var saveUrlButton: Button
    private lateinit var colorBackgroundInput: EditText
    private lateinit var colorActionInput: EditText
    private lateinit var colorIconInput: EditText
    private lateinit var saveColorsButton: Button
    
    private lateinit var colorBackgroundPreview: View
    private lateinit var colorActionPreview: View
    private lateinit var colorIconPreview: View
    private lateinit var btnPickBackgroundColor: Button
    private lateinit var btnPickActionColor: Button
    private lateinit var btnPickIconColor: Button

    private lateinit var masterEnabledSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAudioPermission: Button
    private lateinit var accessibilityStatusIcon: ImageView
    private lateinit var overlayStatusIcon: ImageView
    private lateinit var audioStatusIcon: ImageView
    
    private lateinit var historyContainer: LinearLayout
    private lateinit var emptyHistoryText: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var historyManager: HistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        urlInput = findViewById(R.id.urlInput)
        saveUrlButton = findViewById(R.id.saveUrlButton)
        colorBackgroundInput = findViewById(R.id.colorBackgroundInput)
        colorActionInput = findViewById(R.id.colorActionInput)
        colorIconInput = findViewById(R.id.colorIconInput)
        saveColorsButton = findViewById(R.id.saveColorsButton)
        
        colorBackgroundPreview = findViewById(R.id.colorBackgroundPreview)
        colorActionPreview = findViewById(R.id.colorActionPreview)
        colorIconPreview = findViewById(R.id.colorIconPreview)
        btnPickBackgroundColor = findViewById(R.id.btnPickBackgroundColor)
        btnPickActionColor = findViewById(R.id.btnPickActionColor)
        btnPickIconColor = findViewById(R.id.btnPickIconColor)

        masterEnabledSwitch = findViewById(R.id.masterEnabledSwitch)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAudioPermission = findViewById(R.id.btnAudioPermission)
        accessibilityStatusIcon = findViewById(R.id.accessibilityStatusIcon)
        overlayStatusIcon = findViewById(R.id.overlayStatusIcon)
        audioStatusIcon = findViewById(R.id.audioStatusIcon)
        
        historyContainer = findViewById(R.id.historyContainer)
        emptyHistoryText = findViewById(R.id.emptyHistoryText)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyManager = HistoryManager(this)

        setupSharedPreferences()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        updateHistoryUI()
    }

    private fun setupSharedPreferences() {
        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("backend_url", "https://wispr-deploy.onrender.com")
        urlInput.setText(savedUrl)
        
        val isMasterEnabled = prefs.getBoolean("master_enabled", true)
        masterEnabledSwitch.isChecked = isMasterEnabled

        val bgHex = prefs.getString("color_bg", "#F5F3FF")!!
        val actionHex = prefs.getString("color_action", "#6366F1")!!
        val iconHex = prefs.getString("color_icon", "#4F46E5")!!

        colorBackgroundInput.setText(bgHex)
        colorActionInput.setText(actionHex)
        colorIconInput.setText(iconHex)
        
        updateColorPreviews(bgHex, actionHex, iconHex)
    }

    private fun updateColorPreviews(bg: String, action: String, icon: String) {
        try {
            colorBackgroundPreview.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
            colorActionPreview.backgroundTintList = ColorStateList.valueOf(Color.parseColor(action))
            colorIconPreview.backgroundTintList = ColorStateList.valueOf(Color.parseColor(icon))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        masterEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("Wispr", "Master Switch changed to: $isChecked")
            val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putBoolean("master_enabled", isChecked)
            editor.apply()
            
            if (isChecked) {
                Toast.makeText(this, "Wispr aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Wispr deaktiviert", Toast.LENGTH_SHORT).show()
            }
        }

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

        saveColorsButton.setOnClickListener {
            saveColors()
        }

        btnPickBackgroundColor.setOnClickListener {
            showColorPicker(colorBackgroundInput)
        }

        btnPickActionColor.setOnClickListener {
            showColorPicker(colorActionInput)
        }

        btnPickIconColor.setOnClickListener {
            showColorPicker(colorIconInput)
        }

        btnAccessibility.setOnClickListener {
            Log.d("Wispr", "Opening Accessibility Settings")
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

        btnClearHistory.setOnClickListener {
            historyManager.clearHistory()
            updateHistoryUI()
        }
    }

    private fun showColorPicker(input: EditText) {
        val currentColor = try {
            Color.parseColor(input.text.toString())
        } catch (e: Exception) {
            Color.BLUE
        }

        MaterialColorPickerDialog
            .Builder(this)
            .setTitle("Farbe wählen")
            .setColorShape(ColorShape.CIRCLE)
            .setDefaultColor(currentColor)
            .setColorListener { color, colorHex ->
                input.setText(colorHex)
                saveColors()
            }
            .show()
    }

    private fun saveColors() {
        val bg = colorBackgroundInput.text.toString().trim()
        val action = colorActionInput.text.toString().trim()
        val icon = colorIconInput.text.toString().trim()
        
        getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("color_bg", bg)
            .putString("color_action", action)
            .putString("color_icon", icon)
            .apply()
        
        updateColorPreviews(bg, action, icon)
        Toast.makeText(this, "Farben aktualisiert!", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusIndicators() {
        // 1. Accessibility Service check
        val accessibilityEnabled = isAccessibilityServiceEnabled(WisprAccessibilityService::class.java)
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

    private fun updateHistoryUI() {
        historyContainer.removeAllViews()
        val items = historyManager.getHistory()
        
        if (items.isEmpty()) {
            emptyHistoryText.visibility = android.view.View.VISIBLE
            btnClearHistory.visibility = android.view.View.GONE
        } else {
            emptyHistoryText.visibility = android.view.View.GONE
            btnClearHistory.visibility = android.view.View.VISIBLE
            
            val inflater = LayoutInflater.from(this)
            for (item in items) {
                val itemView = inflater.inflate(R.layout.item_history, historyContainer, false)
                
                val timeTextView = itemView.findViewById<TextView>(R.id.historyItemTime)
                val contentTextView = itemView.findViewById<TextView>(R.id.historyItemText)
                val btnCopy = itemView.findViewById<ImageButton>(R.id.btnCopyHistory)
                
                timeTextView.text = formatTime(item.timestamp)
                contentTextView.text = item.text
                
                btnCopy.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Wispr Dictation", item.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Text kopiert!", Toast.LENGTH_SHORT).show()
                }
                
                historyContainer.addView(itemView)
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val date = java.util.Date(timestamp)
        
        val now = java.util.Calendar.getInstance()
        val itemCal = java.util.Calendar.getInstance().apply { time = date }
        
        return if (now.get(java.util.Calendar.YEAR) == itemCal.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == itemCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            formatter.format(date)
        } else {
            "Gestern, " + formatter.format(date)
        }
    }
}
