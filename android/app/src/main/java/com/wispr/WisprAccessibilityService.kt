package com.wispr

import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.transition.TransitionManager
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WisprAccessibilityService : AccessibilityService() {

    private var currentFocusedNode: AccessibilityNodeInfo? = null
    private var focusedAppPackage: String? = null

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var micCard: MaterialCardView? = null
    private var micIcon: ImageView? = null
    private var expandedControls: View? = null
    private var btnCancel: View? = null
    private var btnConfirm: View? = null
    private var recordingPulse: View? = null
    private var polishingProgress: ProgressBar? = null
    private var waveformAnim: ImageView? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Service starts tracking active editor focus
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Keep track of the package for the backend
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != "com.wispr" && !pkg.contains("inputmethod")) {
            focusedAppPackage = pkg
        }

        Log.d("Wispr", "Accessibility Event: ${AccessibilityEvent.eventTypeToString(event.eventType)} from $pkg")

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, 
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Robustness: If the event source is an editable node, use it directly
                val source = event.source
                if (source != null && source.isEditable) {
                    currentFocusedNode = source
                    showFloatingMic()
                } else {
                    checkAndManageFloatingMicVisibility()
                }
            }
        }
    }

    private fun isKeyboardVisible(): Boolean {
        try {
            val windowsList = windows
            for (window in windowsList) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun checkAndManageFloatingMicVisibility() {
        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        
        // Check Master Toggle
        val isMasterEnabled = prefs.getBoolean("master_enabled", true)
        if (!isMasterEnabled) {
            Log.d("Wispr", "Visibility check: Master toggle is OFF")
            hideFloatingMic()
            return
        }

        // Look for any editable node in the active window
        val root = rootInActiveWindow
        val activeFocusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        // Fallback: If findFocus fails, try a recursive search for an editable node that is focused
        var nodeToShow = activeFocusedNode ?: findFocusedEditableNode(root)

        Log.d("Wispr", "Visibility check: nodeToShow found: ${nodeToShow != null}, isEditable: ${nodeToShow?.isEditable == true}")

        if (nodeToShow != null && nodeToShow.isEditable) {
            currentFocusedNode = nodeToShow
            showFloatingMic()
        } else {
            // Check if root itself is editable (common in some apps)
            if (root != null && root.isEditable) {
                Log.d("Wispr", "Visibility check: Root is editable")
                currentFocusedNode = root
                showFloatingMic()
            } else {
                hideFloatingMic()
            }
        }
    }

    private fun updateFloatingMicAppearance() {
        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val bgColor = parseColorSafely(prefs.getString("color_bg", "#F5F3FF"), Color.parseColor("#F5F3FF"))
        val actionColor = parseColorSafely(prefs.getString("color_action", "#6366F1"), Color.parseColor("#6366F1"))
        val iconColor = parseColorSafely(prefs.getString("color_icon", "#4F46E5"), Color.parseColor("#4F46E5"))

        micCard?.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(bgColor))
        btnConfirm?.backgroundTintList = android.content.res.ColorStateList.valueOf(actionColor)
        
        micIcon?.setColorFilter(iconColor)
        (btnCancel as? ImageView)?.setColorFilter(iconColor)
        waveformAnim?.setColorFilter(iconColor)
        polishingProgress?.indeterminateTintList = android.content.res.ColorStateList.valueOf(iconColor)
    }

    private fun parseColorSafely(hex: String?, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            fallback
        }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        
        // Priority 1: Properly focused and editable
        if (root.isEditable && (root.isFocused || root.isAccessibilityFocused)) return root
        
        // Recursive search
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val found = findFocusedEditableNode(child)
            if (found != null) return found
        }

        // Priority 2: Visible to user and editable (e.g. search fields that don't report focus correctly)
        if (root.isEditable && root.isVisibleToUser) return root

        return null
    }

    override fun onInterrupt() {
        hideFloatingMic()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingMic()
        stopRecording()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingMic() {
        if (floatingView != null) {
            updateFloatingMicAppearance()
            return
        }
        if (!Settings.canDrawOverlays(this)) return

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_Wispr)
        val inflater = LayoutInflater.from(themedContext)
        floatingView = inflater.inflate(R.layout.floating_mic_layout, null)

        micCard = floatingView?.findViewById(R.id.micCard)
        micIcon = floatingView?.findViewById(R.id.micIcon)
        expandedControls = floatingView?.findViewById(R.id.expandedControls)
        btnCancel = floatingView?.findViewById(R.id.btnCancel)
        btnConfirm = floatingView?.findViewById(R.id.btnConfirm)
        recordingPulse = floatingView?.findViewById(R.id.recordingPulse)
        polishingProgress = floatingView?.findViewById(R.id.polishingProgress)
        waveformAnim = floatingView?.findViewById(R.id.waveformAnim)

        updateFloatingMicAppearance()

        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val lastX = prefs.getInt("bubble_x", 100)
        val lastY = prefs.getInt("bubble_y", 500)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        // Setup drag and click gestures on the floating mic card
        micCard?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentView = floatingView ?: return false
                val layoutParams = currentView.layoutParams as WindowManager.LayoutParams

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        val density = resources.displayMetrics.density
                        val dragThreshold = 5 * density
                        if (!isDragging && (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            windowManager.updateViewLayout(currentView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val viewWidth = currentView.width
                            
                            // Snap to nearest horizontal edge
                            // We use the center of the bubble to decide the side
                            if (layoutParams.x + viewWidth / 2 < screenWidth / 2) {
                                layoutParams.x = 0
                            } else {
                                // Right side: screen width minus the bubble width
                                layoutParams.x = screenWidth - viewWidth
                            }
                            
                            windowManager.updateViewLayout(currentView, layoutParams)

                            // Save position
                            getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("bubble_x", layoutParams.x)
                                .putInt("bubble_y", layoutParams.y)
                                .apply()
                        } else {
                            if (!isRecording) {
                                startRecording()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Setup buttons
        btnCancel?.setOnClickListener { cancelRecording() }
        btnConfirm?.setOnClickListener { stopRecordingAndProcess() }

        windowManager.addView(floatingView, params)
    }

    private fun cancelRecording() {
        stopRecording()
        resetOverlayUI()
    }

    private fun hideFloatingMic() {
        if (floatingView != null) {
            if (isRecording) {
                stopRecording()
            }
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                // Ignore
            }
            floatingView = null
        }
    }

    private fun startRecording() {
        try {
            audioFile = File(cacheDir, "wispr_recording.m4a")
            if (audioFile?.exists() == true) {
                audioFile?.delete()
            }

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            
            // Expand UI with animation
            floatingView?.let { view ->
                TransitionManager.beginDelayedTransition(view as ViewGroup)
                
                // Determine direction based on side of screen
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val container = view.findViewById<LinearLayout>(R.id.container)
                val layoutParams = container.layoutParams as FrameLayout.LayoutParams
                
                val currentX = (view.layoutParams as WindowManager.LayoutParams).x
                if (currentX < screenWidth / 2) {
                    // On left side, expand to the right
                    container.orientation = LinearLayout.HORIZONTAL
                    layoutParams.gravity = Gravity.START
                } else {
                    // On right side, expand to the left
                    container.orientation = LinearLayout.HORIZONTAL
                    layoutParams.gravity = Gravity.END
                    
                    // We need to reorder views so micIcon is on the right
                    val mic = view.findViewById<View>(R.id.micIcon)
                    val expanded = view.findViewById<View>(R.id.expandedControls)
                    container.removeView(mic)
                    container.removeView(expanded)
                    container.addView(expanded)
                    container.addView(mic)
                }
                container.layoutParams = layoutParams
            }
            micIcon?.visibility = View.GONE
            expandedControls?.visibility = View.VISIBLE
            
            recordingPulse?.visibility = View.VISIBLE
            val alphaAnim = AlphaAnimation(0.0f, 0.4f).apply {
                duration = 800
                repeatCount = Animation.INFINITE
                repeatMode = Animation.REVERSE
            }
            recordingPulse?.startAnimation(alphaAnim)

            Toast.makeText(this, "🎙️ Aufnahme gestartet...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler beim Starten der Aufnahme: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            isRecording = false
            resetOverlayUI()
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) {
                // ignore
            }
            mediaRecorder = null
            isRecording = false
            recordingPulse?.clearAnimation()
            recordingPulse?.visibility = View.GONE
        }
    }

    private fun stopRecordingAndProcess() {
        stopRecording()
        
        val file = audioFile
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Keine Audio-Daten aufgenommen", Toast.LENGTH_SHORT).show()
            resetOverlayUI()
            return
        }

        // Show polishing/loading animation
        waveformAnim?.visibility = View.GONE
        polishingProgress?.visibility = View.VISIBLE
        btnConfirm?.isEnabled = false
        btnCancel?.isEnabled = false

        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "https://wispr-deploy.onrender.com") ?: "https://wispr-deploy.onrender.com"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseText = uploadAudioToBackend(file, backendUrl, focusedAppPackage ?: "")
                
                withContext(Dispatchers.Main) {
                    HistoryManager(this@WisprAccessibilityService).saveItem(responseText)
                    injectText(responseText)
                    resetOverlayUI()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WisprAccessibilityService, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    resetOverlayUI()
                }
            }
        }
    }

    private fun resetOverlayUI() {
        floatingView?.let { view ->
            TransitionManager.beginDelayedTransition(view as ViewGroup)
            
            // Reset view order if it was changed
            val container = view.findViewById<LinearLayout>(R.id.container)
            val mic = view.findViewById<View>(R.id.micIcon)
            val expanded = view.findViewById<View>(R.id.expandedControls)
            if (container.indexOfChild(mic) > container.indexOfChild(expanded)) {
                container.removeView(mic)
                container.removeView(expanded)
                container.addView(mic)
                container.addView(expanded)
            }
        }
        polishingProgress?.visibility = View.GONE
        waveformAnim?.visibility = View.VISIBLE
        expandedControls?.visibility = View.GONE
        micIcon?.visibility = View.VISIBLE
        btnConfirm?.isEnabled = true
        btnCancel?.isEnabled = true
    }

    private fun uploadAudioToBackend(file: File, serverUrl: String, appName: String): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", 
                file.name, 
                file.asRequestBody("audio/mp4".toMediaTypeOrNull())
            )
            .addFormDataPart("app_name", appName)
            .build()

        val fullUrl = if (serverUrl.endsWith("/")) "${serverUrl}transcribe" else "$serverUrl/transcribe"

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unerwarteter Response-Code: ${response.code} - ${response.message}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response body")
            val json = JSONObject(body)
            return json.getString("polished_text")
        }
    }

    private fun injectText(polishedText: String) {
        val node = currentFocusedNode ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            val existingText = node.text?.toString() ?: ""
            val textToInject = if (existingText.isNotEmpty() && !existingText.endsWith(" ")) {
                "$existingText $polishedText"
            } else {
                "$existingText$polishedText"
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInject)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Toast.makeText(this, "Text eingefügt!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Kein aktives Textfeld gefunden!", Toast.LENGTH_LONG).show()
        }
    }
}
