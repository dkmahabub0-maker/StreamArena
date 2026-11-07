package com.streamarena.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    private lateinit var liveStatus: TextView
    private lateinit var loginTabButton: Button
    private lateinit var signUpTabButton: Button
    private lateinit var authScreen: ScrollView
    private lateinit var liveScreen: ScrollView
    private lateinit var signInButton: Button
    private var isSignUpMode = false
    private lateinit var startLiveButton: Button
    private lateinit var shareButton: Button
    private lateinit var stopShareButton: Button
    private lateinit var endLiveButton: Button
    private lateinit var signOutButton: Button

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        buildUi()
        updateControls()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(9, 14, 29)
        window.navigationBarColor = Color.rgb(9, 14, 29)
        buildAuthScreen()
        buildLiveScreen()
        setContentView(authScreen)
    }

    private fun buildAuthScreen() {
        authScreen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(7, 12, 25))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(30))
        }
        authScreen.addView(root)

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_streamarena)
            contentDescription = "StreamArena"
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(76), dp(76)).apply {
            bottomMargin = dp(14)
        })
        root.addView(TextView(this).apply {
            text = "STREAMARENA"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Welcome back"
            textSize = 16f
            setTextColor(Color.rgb(151, 168, 194))
            gravity = Gravity.CENTER
        }, bottomMargin(dp(28)))

        val authCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(10, 17, 32))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        loginTabButton = makeButton("Log in", true) { setAuthMode(false) }
        signUpTabButton = makeButton("Sign up", false) { setAuthMode(true) }
        tabs.addView(loginTabButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            rightMargin = dp(4)
        })
        tabs.addView(signUpTabButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        authCard.addView(tabs, bottomMargin(dp(24)))

        authCard.addView(fieldLabel("Email"), bottomMargin(dp(8)))
        emailInput = EditText(this).apply {
            hint = "name@example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 126, 153))
            background = inputBackground()
            setPadding(dp(15), 0, dp(15), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        authCard.addView(emailInput, fieldLayout(dp(54), dp(18)))

        authCard.addView(fieldLabel("Password"), bottomMargin(dp(8)))
        passwordInput = EditText(this).apply {
            hint = "Enter password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 126, 153))
            background = inputBackground()
            setPadding(dp(15), 0, dp(15), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        authCard.addView(passwordInput, fieldLayout(dp(54), dp(20)))

        signInButton = makeButton("Log in", true) {
            if (isSignUpMode) signUp() else signIn()
        }
        authCard.addView(signInButton, fieldLayout(dp(54), dp(10)))

        status = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.rgb(166, 178, 202))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(2))
        }
        authCard.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(authCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setAuthMode(false)
    }

    private fun setAuthMode(signUp: Boolean) {
        isSignUpMode = signUp
        if (::signInButton.isInitialized) {
            signInButton.text = if (signUp) "Create account" else "Log in"
            signInButton.isEnabled = true
        }
        if (::loginTabButton.isInitialized) {
            loginTabButton.background = tabBackground(!signUp)
            loginTabButton.setTextColor(Color.WHITE)
        }
        if (::signUpTabButton.isInitialized) {
            signUpTabButton.background = tabBackground(signUp)
            signUpTabButton.setTextColor(Color.WHITE)
        }
        if (::status.isInitialized) {
            status.text = ""
            status.setTextColor(Color.rgb(166, 178, 202))
        }
    }

    private fun tabBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (selected) Color.rgb(35, 112, 211) else Color.rgb(27, 39, 61))
        cornerRadius = dp(11).toFloat()
    }

    private fun buildLiveScreen() {
        liveScreen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(9, 14, 29))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(34))
        }
        liveScreen.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_streamarena)
            contentDescription = "StreamArena logo"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(TextView(this).apply {
            text = "STREAMARENA\nCREATOR DASHBOARD"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        signOutButton = makeButton("Sign out", false) { signOut() }
        header.addView(signOutButton, LinearLayout.LayoutParams(dp(90), dp(42)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(30)
        })
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(24, 92, 166), Color.rgb(91, 51, 151), Color.rgb(25, 37, 73))
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        banner.addView(TextView(this).apply {
            text = "YOUR CHANNEL. YOUR MOMENT."
            textSize = 10f
            setTextColor(Color.rgb(210, 227, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, bottomMargin(dp(10)))
        banner.addView(TextView(this).apply {
            text = "Go live.\nBring your community together."
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(8)))
        banner.addView(TextView(this).apply {
            text = "Create a broadcast and share your phone screen with viewers."
            textSize = 13f
            setTextColor(Color.rgb(225, 232, 249))
        })
        root.addView(banner, bottomMargin(dp(22)))
        root.addView(TextView(this).apply {
            text = "CREATOR TOOLS"
            textSize = 12f
            setTextColor(Color.rgb(128, 190, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
        }, bottomMargin(dp(12)))
        val quickTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickTools.addView(makeButton("Go Live", true) { titleInput.requestFocus() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        quickTools.addView(makeButton("Screen Share", false) {
            if (room == null) setStatus("Start your Live first, then share your phone screen.")
            else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(quickTools, bottomMargin(dp(20)))
        root.addView(TextView(this).apply {
            text = "You're signed in."
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Set up your stream and go live from your phone."
            textSize = 14f
            setTextColor(Color.rgb(166, 178, 202))
        }, bottomMargin(dp(22)))

        val liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        liveCard.addView(TextView(this).apply {
            text = "LIVE CONTROLS"
            textSize = 12f
            setTextColor(Color.rgb(115, 205, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, bottomMargin(dp(14)))
        liveCard.addView(fieldLabel("Stream title"), bottomMargin(dp(7)))
        titleInput = EditText(this).apply {
            hint = "Enter your live title"
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 126, 153))
            background = inputBackground()
            setPadding(dp(15), 0, dp(15), 0)
        }
        liveCard.addView(titleInput, fieldLayout(dp(54), dp(14)))
        startLiveButton = makeButton("Start Live", true) { startLive() }
        liveCard.addView(startLiveButton, fieldLayout(dp(52), dp(10)))
        shareButton = makeButton("Share phone screen", false) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }
        liveCard.addView(shareButton, fieldLayout(dp(50), dp(8)))
        stopShareButton = makeButton("Stop screen share", false) { stopScreenShare() }
        liveCard.addView(stopShareButton, fieldLayout(dp(50), dp(8)))
        endLiveButton = makeButton("End Live", false) { endLive() }
        liveCard.addView(endLiveButton, fieldLayout(dp(50), dp(12)))
        liveStatus = TextView(this).apply {
            text = "You are signed in. Set a title to start your Live."
            textSize = 13f
            setTextColor(Color.rgb(166, 178, 202))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        liveCard.addView(liveStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(liveCard)

        root.addView(TextView(this).apply {
            text = "Screen sharing starts only after you approve Android's system prompt."
            textSize = 12f
            setTextColor(Color.rgb(120, 133, 158))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(19, 28, 49))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.rgb(37, 51, 78))
    }

    private fun inputBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(11, 18, 34))
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), Color.rgb(48, 65, 96))
    }

    private fun fieldLabel(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(197, 207, 225))
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun bottomMargin(value: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = value
        }

    private fun fieldLayout(height: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            bottomMargin = bottom
        }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) Color.rgb(31, 123, 222) else Color.rgb(34, 48, 73))
                cornerRadius = dp(14).toFloat()
                if (!primary) setStroke(dp(1), Color.rgb(58, 77, 108))
            }
            stateListAnimator = null
            setOnClickListener { action() }
        }


    private fun signUp() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            setStatus("Email and password are required.", true)
            return
        }
        if (password.length < 6) {
            setStatus("Password must be at least 6 characters.", true)
            return
        }
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(passwordInput.windowToken, 0)
        signInButton.isEnabled = false
        signInButton.text = "Creating account…"
        setStatus("Creating your StreamArena account…")
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { requestSignUp(email, password) }
                val token = response.optString("access_token")
                val user = response.optJSONObject("user")
                if (token.isNotBlank() && user != null) {
                    accessToken = token
                    userId = user.getString("id")
                    passwordInput.text.clear()
                    setContentView(liveScreen)
                    setStatus("Account created. You are signed in.")
                } else {
                    isSignUpMode = false
                    signInButton.text = "Sign in to StreamArena"
                    setStatus("Account created. Check your email to confirm, then sign in.")
                }
            } catch (error: Exception) {
                setStatus("Account creation failed: " + (error.message ?: "unknown error"), true)
            } finally {
                if (accessToken == null) {
                    signInButton.text = if (isSignUpMode) "Create StreamArena account" else "Sign in to StreamArena"
                }
                updateControls()
            }
        }
    }

    private fun requestSignUp(email: String, password: String): JSONObject {
        val body = JSONObject().put("email", email).put("password", password)
        return postJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/auth/v1/signup",
            "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
            null,
            body
        )
    }

    private fun signIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            setStatus("Email and password are required.", true)
            return
        }
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(passwordInput.windowToken, 0)
        signInButton.isEnabled = false
        signInButton.text = "Signing in…"
        setStatus("Connecting securely to your account…")
        lifecycleScope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { requestPasswordSignIn(email, password) }
                accessToken = auth.getString("access_token")
                userId = auth.getJSONObject("user").getString("id")
                passwordInput.text.clear()
                setContentView(liveScreen)
                setStatus("Signed in successfully. You can start your Live.")
            } catch (error: Exception) {
                accessToken = null
                userId = null
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "Unknown connection error"
                setStatus("Sign in failed: $detail", true)
            } finally {
                signInButton.text = "Sign in to StreamArena"
                updateControls()
            }
        }
    }

    private fun signOut() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        lifecycleScope.launch {
            runCatching { room?.disconnect() }
            room = null
            activeStreamId = null
            sharing = false
            accessToken = null
            userId = null
            emailInput.text.clear()
            passwordInput.text.clear()
            titleInput.text.clear()
            setAuthMode(false)
            setContentView(authScreen)
            updateControls()
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
        loginTabButton.isEnabled = !signedIn
        signUpTabButton.isEnabled = !signedIn
    }

    private fun setStatus(message: String, isError: Boolean = false) {
        val color = if (isError) Color.rgb(255, 125, 125) else Color.rgb(166, 207, 231)
        if (::status.isInitialized) { status.text = message; status.setTextColor(color) }
        if (::liveStatus.isInitialized) { liveStatus.text = message; liveStatus.setTextColor(color) }
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
                val message = runCatching {
                    val json = JSONObject(response)
                    json.optString("msg")
                        .ifBlank { json.optString("error_description") }
                        .ifBlank { json.optString("message") }
                        .ifBlank { json.optString("error") }
                }.getOrDefault("")
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
