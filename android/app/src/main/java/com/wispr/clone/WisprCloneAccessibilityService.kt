package com.wispr.clone

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
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

class WisprCloneAccessibilityService : AccessibilityService() {

    private var currentFocusedNode: AccessibilityNodeInfo? = null
    private var focusedAppPackage: String? = null

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var micCard: MaterialCardView? = null
    private var micIcon: ImageView? = null
    private var recordingPulse: View? = null
    private var polishingProgress: ProgressBar? = null

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
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source
            if (source != null && source.className == "android.widget.EditText") {
                currentFocusedNode = source
                focusedAppPackage = event.packageName?.toString()
                showFloatingMic()
            } else {
                // If focus moved away from EditText, hide the overlay after a brief delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (currentFocusedNode?.isFocused != true) {
                        hideFloatingMic()
                    }
                }, 1000)
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Keep track of package name
            focusedAppPackage = event.packageName?.toString()
        }
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
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_mic_layout, null)

        micCard = floatingView?.findViewById(R.id.micCard)
        micIcon = floatingView?.findViewById(R.id.micIcon)
        recordingPulse = floatingView?.findViewById(R.id.recordingPulse)
        polishingProgress = floatingView?.findViewById(R.id.polishingProgress)

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
            x = 100
            y = 500
        }

        // Setup drag and click gestures on the floating mic card
        micCard?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var touchDownTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        touchDownTime = System.currentTimeMillis()
                        
                        // Start recording on long hold (200ms delay to differentiate from simple drag)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isDragging && event.action != MotionEvent.ACTION_UP && !isRecording) {
                                startRecording()
                            }
                        }, 250)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        // Trigger drag only if moved more than 10 pixels
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            if (floatingView != null) {
                                windowManager.updateViewLayout(floatingView, params)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchDownTime
                        if (isRecording) {
                            stopRecordingAndProcess()
                        } else if (!isDragging && duration < 250) {
                            // Tap to toggle recording (fallback if long-press is not preferred)
                            toggleRecording()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun hideFloatingMic() {
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                // Ignore
            }
            floatingView = null
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecordingAndProcess()
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
            recordingPulse?.visibility = View.VISIBLE
            micIcon?.setImageResource(android.R.drawable.presence_video_busy) // Show red active status
            Toast.makeText(this, "🎙️ Aufnahme gestartet...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler beim Starten der Aufnahme: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            isRecording = false
            recordingPulse?.visibility = View.GONE
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
            recordingPulse?.visibility = View.GONE
            micIcon?.setImageResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    private fun stopRecordingAndProcess() {
        stopRecording()
        
        val file = audioFile
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Keine Audio-Daten aufgenommen", Toast.LENGTH_SHORT).show()
            return
        }

        // Show polishing/loading animation
        micIcon?.visibility = View.GONE
        polishingProgress?.visibility = View.VISIBLE

        val prefs = getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseText = uploadAudioToBackend(file, backendUrl, focusedAppPackage ?: "")
                
                withContext(Dispatchers.Main) {
                    injectText(responseText)
                    resetOverlayUI()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WisprCloneAccessibilityService, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    resetOverlayUI()
                }
            }
        }
    }

    private fun resetOverlayUI() {
        polishingProgress?.visibility = View.GONE
        micIcon?.visibility = View.VISIBLE
        micIcon?.setImageResource(android.R.drawable.ic_btn_speak_now)
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
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, polishedText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Toast.makeText(this, "Text eingefügt!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Kein aktives Textfeld gefunden!", Toast.LENGTH_LONG).show()
        }
    }
}
