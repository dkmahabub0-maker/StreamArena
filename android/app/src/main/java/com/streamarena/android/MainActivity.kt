package com.streamarena.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * First native Android host prototype.
 *
 * Uses the same Supabase Auth + livekit-token endpoint as the website.
 * Credentials and session tokens remain in memory and are never written to disk.
 */
class MainActivity : ComponentActivity() {
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var status: TextView
    private lateinit var signInButton: Button
    private lateinit var startLiveButton: Button
    private lateinit var shareButton: Button
    private lateinit var stopShareButton: Button
    private lateinit var endLiveButton: Button

    private var accessToken: String? = null
    private var userId: String? = null
    private var room: Room? = null
    private var sharing = false
    private var activeStreamId: String? = null
    private var heartbeatJob: Job? = null

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[android.Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val microphoneGranted = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && microphoneGranted) startLive() else setStatus("Camera and microphone permissions are required to start a Live.")
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            setStatus("Screen sharing was cancelled.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                val activeRoom = room ?: error("Start your Live first.")
                val started = activeRoom.localParticipant.setScreenShareEnabled(
                    true,
                    ScreenCaptureParams(
                        mediaProjectionPermissionResultData = data,
                        onStop = {
                            sharing = false
                            runOnUiThread {
                                updateControls()
                                setStatus("Android stopped screen capture. Your Live can continue.")
                            }
                        }
                    )
                )
                sharing = started
                updateControls()
                setStatus(if (started) "Your phone screen is now being shared." else "LiveKit could not start screen sharing.")
            } catch (error: Exception) {
                sharing = false
                updateControls()
                setStatus("Screen share failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateControls()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
        }
        fun add(view: android.view.View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                bottomMargin = 14
            })
        }
        add(TextView(this).apply {
            text = "StreamArena — Android Host"
            textSize = 24f
        })
        add(TextView(this).apply {
            text = "Sign in, start your Live, then share your phone screen. Only the authenticated host can publish."
            textSize = 14f
        })
        titleInput = EditText(this).apply { hint = "Live title (at least 3 characters)" }
        add(titleInput)
        emailInput = EditText(this).apply {
            hint = "StreamArena account email"
            inputType = 33
        }
        add(emailInput)
        passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = 129
        }
        add(passwordInput)
        signInButton = Button(this).apply {
            text = "Sign in"
            setOnClickListener { signIn() }
        }
        add(signInButton)
        startLiveButton = Button(this).apply {
            text = "Start Live"
            setOnClickListener { startLive() }
        }
        add(startLiveButton)
        shareButton = Button(this).apply {
            text = "Share phone screen"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
        add(shareButton)
        stopShareButton = Button(this).apply {
            text = "Stop screen share"
            setOnClickListener { stopScreenShare() }
        }
        add(stopShareButton)
        endLiveButton = Button(this).apply {
            text = "End Live"
            setOnClickListener { endLive() }
        }
        add(endLiveButton)
        status = TextView(this).apply {
            text = "Not signed in."
            textSize = 14f
        }
        add(status)
        setContentView(root)
    }

    private fun signIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            setStatus("Enter your account email and password.")
            return
        }
        signInButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { requestPasswordSignIn(email, password) }
                accessToken = auth.getString("access_token")
                userId = auth.getJSONObject("user").getString("id")
                passwordInput.text.clear()
                setStatus("Signed in. You can start your Live.")
            } catch (error: Exception) {
                accessToken = null
                userId = null
                setStatus("Sign in failed: ${error.message ?: "unknown error"}")
            } finally {
                signInButton.isEnabled = true
                updateControls()
            }
        }
    }

    private fun startLive() {
        val missingPermissions = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            mediaPermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        val token = accessToken ?: run {
            setStatus("Sign in before starting a Live.")
            return
        }
        val id = userId ?: return
        startLiveButton.isEnabled = false
        val title = titleInput.text.toString().trim()
        if (title.length < 3) {
            setStatus("Enter a Live title with at least 3 characters.")
            startLiveButton.isEnabled = true
            return
        }
        lifecycleScope.launch {
            var createdStreamId: String? = null
            try {
                val streamId = withContext(Dispatchers.IO) { createStreamRow(token, id, title) }
                createdStreamId = streamId
                activeStreamId = streamId
                // The deployed publisher-token endpoint expects the stream to be marked live first.
                withContext(Dispatchers.IO) { updateStreamRow(token, streamId, id, true) }
                val credentials = withContext(Dispatchers.IO) { requestPublisherToken(token, id) }
                val liveRoom = LiveKit.create(applicationContext)
                liveRoom.connect(credentials.getString("serverUrl"), credentials.getString("token"))
                room = liveRoom
                liveRoom.localParticipant.setMicrophoneEnabled(true)
                liveRoom.localParticipant.setCameraEnabled(true)
                heartbeatJob?.cancel()
                heartbeatJob = lifecycleScope.launch {
                    while (activeStreamId != null) {
                        delay(25_000)
                        val streamId = activeStreamId ?: break
                        runCatching {
                            withContext(Dispatchers.IO) { updateStreamRow(token, streamId, id, true) }
                        }.onFailure { setStatus("Live is running, but its heartbeat failed: ${it.message}") }
                    }
                }
                setStatus("You are live. Tap Share phone screen when ready.")
            } catch (error: Exception) {
                runCatching { room?.disconnect() }
                room = null
                createdStreamId?.let { streamId ->
                    runCatching { withContext(Dispatchers.IO) { updateStreamRow(token, streamId, id, false) } }
                }
                activeStreamId = null
                setStatus("Could not start Live: ${error.message ?: "unknown error"}")
            } finally {
                startLiveButton.isEnabled = true
                updateControls()
            }
        }
    }

    private fun stopScreenShare() {
        lifecycleScope.launch {
            try {
                room?.localParticipant?.setScreenShareEnabled(false)
                sharing = false
                setStatus("Screen sharing stopped. Your Live is still active.")
            } catch (error: Exception) {
                setStatus("Could not stop screen sharing: ${error.message ?: "unknown error"}")
            } finally {
                updateControls()
            }
        }
    }

    private fun endLive() {
        lifecycleScope.launch {
            var cleanupWarning: String? = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            try {
                if (sharing) room?.localParticipant?.setScreenShareEnabled(false)
            } catch (error: Exception) {
                cleanupWarning = "Screen-share stop issue: ${error.message ?: "unknown error"}"
            }
            sharing = false
            val streamId = activeStreamId
            val token = accessToken
            val id = userId
            if (streamId != null && token != null && id != null) {
                try {
                    withContext(Dispatchers.IO) { updateStreamRow(token, streamId, id, false) }
                } catch (error: Exception) {
                    cleanupWarning = "Live status update failed: ${error.message ?: "unknown error"}"
                }
            }
            try {
                room?.disconnect()
            } catch (error: Exception) {
                cleanupWarning = cleanupWarning ?: "Room disconnect issue: ${error.message ?: "unknown error"}"
            }
            room = null
            activeStreamId = null
            setStatus(cleanupWarning ?: "Live ended.")
            updateControls()
        }
    }

    private fun updateControls() {
        val signedIn = accessToken != null
        val live = room != null
        startLiveButton.isEnabled = signedIn && !live
        shareButton.isEnabled = live && !sharing
        stopShareButton.isEnabled = live && sharing
        endLiveButton.isEnabled = live
        emailInput.isEnabled = !signedIn
        passwordInput.isEnabled = !signedIn
        signInButton.isEnabled = !signedIn
    }

    private fun setStatus(message: String) {
        status.text = message
    }

    private fun requestPasswordSignIn(email: String, password: String): JSONObject {
        val body = JSONObject().put("email", email).put("password", password)
        return postJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/auth/v1/token?grant_type=password",
            "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
            null,
            body
        )
    }

    private fun createStreamRow(session: String, id: String, title: String): String {
        val row = JSONObject()
            .put("user_id", id)
            .put("title", title)
            .put("caption", "")
            .put("thumbnail_url", JSONObject.NULL)
            .put("tournament_id", JSONObject.NULL)
            .put("is_live", false)
        val response = postRestJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/streams?select=id",
            session,
            row
        )
        return response.getJSONObject(0).get("id").toString()
    }

    private fun updateStreamRow(session: String, streamId: String, id: String, live: Boolean) {
        val body = JSONObject().put("is_live", live)
        if (live) body.put("last_heartbeat_at", java.time.Instant.now().toString())
        patchRestJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/streams?id=eq.$streamId&user_id=eq.$id",
            session,
            body
        )
    }

    private fun postRestJson(endpoint: String, bearer: String, body: JSONObject): org.json.JSONArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2")
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Prefer", "return=representation")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Stream creation failed (HTTP $code): $response")
            return org.json.JSONArray(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun patchRestJson(endpoint: String, bearer: String, body: JSONObject) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", "sb_publishable_0SdYbLy1WwRGMc5sxjmIEg_XmDD9Ds2")
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Prefer", "return=minimal")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("Stream update failed (HTTP $code): $response")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestPublisherToken(session: String, id: String): JSONObject {
        val body = JSONObject().put("roomName", "stream-$id").put("role", "publisher")
        return postJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/functions/v1/livekit-token",
            "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
            session,
            body
        )
    }

    private fun postJson(endpoint: String, apiKey: String, bearer: String?, body: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", apiKey)
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("msg").ifBlank { JSONObject(response).optString("error") } }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Server returned HTTP $code" })
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The app does not persist credentials. Explicit Live teardown is handled by End Live.
        // Avoid blocking lifecycle teardown with a network disconnect call.
        room = null
        accessToken = null
        userId = null
    }
}
