package com.streamarena.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.net.Uri
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    private lateinit var welcomeScreen: LinearLayout
    private lateinit var liveScreen: LinearLayout
    private lateinit var signInButton: Button
    private var isSignUpMode = false
    private lateinit var startLiveButton: Button
    private lateinit var shareButton: Button
    private lateinit var stopShareButton: Button
    private lateinit var endLiveButton: Button
    private lateinit var plusNavButton: Button
    private var keepScreenAwakeDuringLive = true
    private var selectedGameCategory = "ACTION"
    private lateinit var otpInput: EditText
    private lateinit var otpVerifyButton: Button
    private lateinit var liveCard: LinearLayout
    private lateinit var cameraPreviewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingSignupEmail: String? = null
    private var selectedLiveMode: String = "camera"

    private var accessToken: String? = null
    private var userId: String? = null
    private var room: Room? = null
    private var viewerRoom: Room? = null
    private var viewerEventJob: Job? = null
    private var viewerConnectJob: Job? = null
    private var liveChatRefreshJob: Job? = null
    private var communityRefreshJob: Job? = null
    private var streamChatLoadJob: Job? = null
    private var communityMessageLoadJob: Job? = null
    private var supportTicketRepliesLoadJob: Job? = null
    private var supportTicketRefreshJob: Job? = null
    private var activeSupportTicketId: String? = null
    private var communityMessageSending = false
    private var communitySendButton: Button? = null
    private var communityFamiliesLoadJob: Job? = null
    private var viewerVideoTrack: VideoTrack? = null
    private var viewerRenderer: SurfaceViewRenderer? = null
    private var sharing = false
    private var activeStreamId: String? = null
    private var heartbeatJob: Job? = null
    private lateinit var liveStreamsContainer: LinearLayout
    private lateinit var gamesContainer: LinearLayout
    private lateinit var communityFamiliesContainer: LinearLayout
    private lateinit var communityChatContainer: LinearLayout
    private lateinit var communityMessageInput: EditText
    private var activeCommunityFamilyId: String? = null
    private var profileAvatarBitmap: Bitmap? = null
    private var profileAvatarDataUrl: String? = null
    private var profileAvatarStoredUrl: String? = null
    private var profileAvatarView: ImageView? = null

    private val profileImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) lifecycleScope.launch {
            try {
                val resized = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        ?: error("Could not inspect the selected image.")
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("The selected file is not a supported image.")
                    var sampleSize = 1
                    while (bounds.outWidth / (sampleSize * 2) >= 384 &&
                        bounds.outHeight / (sampleSize * 2) >= 384) {
                        sampleSize *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val decoded = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Could not decode the selected image.")
                    val scale = minOf(384f / decoded.width, 384f / decoded.height, 1f)
                    val output = if (scale < 1f) Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    ) else decoded
                    if (output !== decoded) decoded.recycle()
                    output
                }
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 72, out)
                    out.toByteArray()
                }
                profileAvatarBitmap?.takeIf { it !== resized }?.recycle()
                profileAvatarBitmap = resized
                profileAvatarDataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                profileAvatarView?.setImageBitmap(resized)
                profileStatus.text = "Photo selected. Tap Save to update your profile picture."
            } catch (error: Exception) {
                profileStatus.text = "Could not use this image: ${error.message ?: "unsupported image"}"
            }
        }
    }

    private val cameraPreviewPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && selectedLiveMode == "camera" && ::liveCard.isInitialized && liveCard.visibility == android.view.View.VISIBLE) showCameraPreview() else if (!granted) setStatus("Camera permission is needed to show the camera preview. Screen Share remains available.")
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = selectedLiveMode == "screen" ||
            grants[android.Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val microphoneGranted = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && microphoneGranted) startLive() else {
            setStatus(if (selectedLiveMode == "screen") "Microphone permission is required to start a Screen Live."
                else "Camera and microphone permissions are required to start a Camera Live.")
        }
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
        keepScreenAwakeDuringLive = getSharedPreferences("streamarena_settings", MODE_PRIVATE)
            .getBoolean("keep_screen_awake_during_live", true)
        buildUi()
        updateControls()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(5, 7, 19)
        window.navigationBarColor = Color.rgb(5, 7, 19)
        buildAuthScreen()
        buildLiveScreen()
        buildWelcomeScreen()
        setContentView(welcomeScreen)
    }


    private fun buildWelcomeScreen() {
        welcomeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(30), dp(28), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7,12,43), Color.rgb(25,8,72), Color.rgb(4,12,35)))
        }
        welcomeScreen.addView(ImageView(this).apply { setImageResource(R.drawable.ic_streamarena) }, LinearLayout.LayoutParams(dp(116),dp(116)).apply { bottomMargin=dp(16) })
        welcomeScreen.addView(TextView(this).apply { text="StreamArena"; textSize=34f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
        welcomeScreen.addView(TextView(this).apply { text="Live Together • Play Together"; textSize=16f; gravity=Gravity.CENTER; setTextColor(Color.LTGRAY) }, bottomMargin(dp(30)))
        welcomeScreen.addView(TextView(this).apply {
            text="🎮          ⚔️          🎮"; textSize=42f; gravity=Gravity.CENTER
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(77,20,166),Color.rgb(16,32,90))).apply { cornerRadius=dp(28).toFloat() }
        }, LinearLayout.LayoutParams(-1,dp(250)).apply { bottomMargin=dp(28) })
        welcomeScreen.addView(makeButton("Get Started  →",true) { setContentView(authScreen) }, LinearLayout.LayoutParams(-1,dp(56)))
        welcomeScreen.addView(TextView(this).apply { text="Version 1.0.0"; textSize=11f; gravity=Gravity.CENTER; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(18) })
    }

    private fun buildAuthScreen() {
        authScreen=ScrollView(this).apply { isFillViewport=true; background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(4,9,27),Color.rgb(26,8,63),Color.rgb(5,9,28))) }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(24),dp(40),dp(24),dp(28)) }
        authScreen.addView(root)
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_streamarena) },LinearLayout.LayoutParams(dp(88),dp(88)).apply { bottomMargin=dp(8) })
        root.addView(TextView(this).apply { text="StreamArena"; textSize=30f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },bottomMargin(dp(22)))
        root.addView(TextView(this).apply { text="Welcome Back!"; textSize=19f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text="Sign in to continue"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Color.LTGRAY) },bottomMargin(dp(24)))
        val googleButton = Button(this).apply {
            text = "🌐   Continue with Google"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.rgb(24, 24, 32))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }
            elevation = dp(2).toFloat()
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Google sign-in is not configured in this Android client yet.")
                    .setPositiveButton("OK", null).show()
            }
        }
        root.addView(googleButton, fieldLayout(dp(52), dp(10)))
        root.addView(makeButton("✉   Login with Email",true) { emailInput.requestFocus() },fieldLayout(dp(52),dp(18)))
        root.addView(TextView(this).apply { text="──────────────  or  ──────────────"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Color.LTGRAY) },bottomMargin(dp(14)))
        emailInput=EditText(this).apply { hint="Email Address"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine(true); textSize=15f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); background=inputBackground(); setPadding(dp(16),0,dp(16),0) }
        root.addView(emailInput,fieldLayout(dp(54),dp(12)))
        passwordInput=EditText(this).apply { hint="Password"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true); textSize=15f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); background=inputBackground(); setPadding(dp(16),0,dp(16),0) }
        root.addView(passwordInput,fieldLayout(dp(54),dp(14)))
        signInButton=makeButton("Login",true) { if(isSignUpMode) signUp() else signIn() }
        root.addView(signInButton,fieldLayout(dp(54),dp(8)))
        loginTabButton=makeButton("Log in",false) { setAuthMode(false) }
        signUpTabButton=makeButton("Sign up",false) { setAuthMode(true) }
        status=TextView(this).apply { text=""; textSize=13f; gravity=Gravity.CENTER; setTextColor(Color.rgb(255,145,160)); setPadding(dp(8),dp(6),dp(8),dp(6)) }
        root.addView(status,LinearLayout.LayoutParams(-1,-2))
        otpInput=EditText(this).apply { hint="6-digit email code"; inputType=InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); background=inputBackground(); setPadding(dp(16),0,dp(16),0); visibility=android.view.View.GONE }
        root.addView(otpInput,fieldLayout(dp(52),dp(8)))
        otpVerifyButton=makeButton("Verify code",true) { verifySignupOtp() }.apply { visibility=android.view.View.GONE }
        root.addView(otpVerifyButton,fieldLayout(dp(50),dp(8)))
        val links=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        links.addView(TextView(this).apply { text="Create Account"; setTextColor(Color.WHITE); setOnClickListener { setAuthMode(true) } },LinearLayout.LayoutParams(0,dp(42),1f))
        links.addView(TextView(this).apply { text="Forgot Password?"; gravity=Gravity.END; setTextColor(Color.WHITE); setOnClickListener { requestPasswordReset() } },LinearLayout.LayoutParams(0,dp(42),1f))
        root.addView(links)
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
        liveScreen=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(5,7,19)) }
        val scroll=ScrollView(this).apply { isFillViewport=true }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(14)) }
        scroll.addView(root); liveScreen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val menu=makeButton("☰",false) { showDashboardMenu(menuAnchor) }
        menuAnchor=menu
        header.addView(menu,LinearLayout.LayoutParams(dp(42),dp(48)))
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_streamarena) },LinearLayout.LayoutParams(dp(34),dp(34)))
        header.addView(TextView(this).apply { text="StreamArena"; textSize=21f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) })
        header.addView(makeButton("♧",false) { setStatus("No new notifications.") })
        root.addView(header,bottomMargin(dp(12)))
        val hero=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM; setPadding(dp(16),dp(16),dp(16),dp(16)); background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(118,16,151),Color.rgb(26,38,111),Color.rgb(7,16,45))).apply { cornerRadius=dp(20).toFloat() } }
        hero.addView(TextView(this).apply { text="🔴 LIVE GAMING"; textSize=13f; setTextColor(Color.WHITE) },bottomMargin(dp(42)))
        hero.addView(TextView(this).apply { text="Find Your Next Stream"; textSize=27f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
        hero.addView(makeButton("Explore Live Streams  ▶",true) { loadLiveStreams(); setStatus("Refreshing live streams…") },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(8) })
        root.addView(hero,LinearLayout.LayoutParams(-1,dp(190)).apply { bottomMargin=dp(12) })
        root.addView(makeButton("✦ EXPLORE GAMES                         See All  ›",true) { showExploreScreen() },fieldLayout(dp(48),dp(16)))
        addSectionTitle(root,"Popular Games","See All →") { showExploreScreen() }
        gamesContainer=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        root.addView(gamesContainer,bottomMargin(dp(18)))
        loadGames()
        addSectionTitle(root,"Live Streams","See All →") { loadLiveStreams() }
        liveStreamsContainer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(liveStreamsContainer,bottomMargin(dp(18)))
        loadLiveStreams()
        liveCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=panelBackground(); setPadding(dp(16),dp(16),dp(16),dp(16)); visibility=android.view.View.GONE }
        liveCard.addView(TextView(this).apply { text="Go Live"; textSize=20f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },bottomMargin(dp(12)))
        cameraPreviewView=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(Color.BLACK) }
        liveCard.addView(cameraPreviewView,LinearLayout.LayoutParams(-1,dp(190)).apply { bottomMargin=dp(12) })
        val modes=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        modes.addView(makeButton("Camera",true) { selectedLiveMode="camera"; showCameraPreview() },LinearLayout.LayoutParams(0,dp(44),1f))
        modes.addView(makeButton("Screen Share",false) { selectedLiveMode="screen"; stopCameraPreview() },LinearLayout.LayoutParams(0,dp(44),1f))
        liveCard.addView(modes,bottomMargin(dp(12)))
        titleInput=EditText(this).apply { hint="Gaming Live 🔥"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); background=inputBackground(); setPadding(dp(12),0,dp(12),0) }
        liveCard.addView(titleInput,fieldLayout(dp(50),dp(10)))
        startLiveButton=makeButton("Start Live  ▶",true) { startLive() }; liveCard.addView(startLiveButton,fieldLayout(dp(52),dp(8)))
        shareButton=makeButton("Start Sharing",false) { val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; screenCaptureLauncher.launch(mgr.createScreenCaptureIntent()) }; liveCard.addView(shareButton,fieldLayout(dp(48),dp(6)))
        stopShareButton=makeButton("Stop Sharing",false) { stopScreenShare() }; liveCard.addView(stopShareButton,fieldLayout(dp(48),dp(6)))
        endLiveButton=makeButton("End Live",false) { endLive() }; liveCard.addView(endLiveButton,fieldLayout(dp(48),dp(6)))
        liveStatus=TextView(this).apply { text="Ready to stream"; textSize=12f; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER }; liveCard.addView(liveStatus)
        root.addView(liveCard,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        addBottomNav()
    }

    private lateinit var menuAnchor: android.view.View
    private fun addSectionTitle(root:LinearLayout,title:String,action:String,click:()->Unit) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text=title; textSize=17f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,dp(38),1f))
        row.addView(TextView(this).apply { text=action; textSize=12f; setTextColor(Color.MAGENTA); setOnClickListener { click() } }); root.addView(row)
    }
    private fun addBottomNav(activeIndex: Int = 0) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(4), dp(3), dp(4))
            setBackgroundColor(Color.rgb(10, 9, 24))
        }
        val items = listOf(
            Pair("⌂", "Home"), Pair("◎", "Explore"), Pair("⊕", "Create"),
            Pair("♧", "Community"), Pair("♙", "Profile")
        )
        items.forEachIndexed { index, item ->
            val selected = index == activeIndex
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(0, dp(3), 0, dp(2))
                setOnClickListener {
                    when (index) {
                        0 -> { stopCameraPreview(); buildLiveScreen(); setContentView(liveScreen) }
                        1 -> showExploreScreen()
                        2 -> {
                            buildLiveScreen()
                            setContentView(liveScreen)
                            val homeScroll = liveScreen.getChildAt(0) as? ScrollView
                            if (homeScroll != null) openLiveSetup("camera", homeScroll)
                        }
                        3 -> showCommunityScreen()
                        4 -> showProfileScreen()
                    }
                }
            }
            val tint = if (selected) Color.rgb(224, 0, 239) else Color.WHITE
            cell.addView(TextView(this).apply {
                text = item.first
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(tint)
            })
            cell.addView(TextView(this).apply {
                text = item.second
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(tint)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            })
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        liveScreen.addView(nav)
    }

        private fun showCreateScreen() {
        liveScreen.removeAllViews()
        val sc = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        sc.addView(root)
        liveScreen.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "‹      Go Live"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { buildLiveScreen(); setContentView(liveScreen) }
        }, bottomMargin(dp(12)))

        val preview = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(93, 0, 180), Color.rgb(12, 28, 91), Color.rgb(5, 9, 27))).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        cameraPreviewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        preview.addView(cameraPreviewView, FrameLayout.LayoutParams(-1, dp(188)))
        val screenShareHint = TextView(this).apply {
            text = "▣\nShare Your Screen"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            visibility = android.view.View.GONE
        }
        preview.addView(screenShareHint, FrameLayout.LayoutParams(-1, -1))
        preview.addView(TextView(this).apply {
            text = "🔴 LIVE"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.rgb(235, 15, 54)); cornerRadius = dp(8).toFloat()
            }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(188)).apply { bottomMargin = dp(14) })

        fun fieldLabel(label: String) {
            root.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }, bottomMargin(dp(6)))
        }
        fieldLabel("Stream Title")
        titleInput = EditText(this).apply {
            hint = "Gaming Live 🔥"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(titleInput, fieldLayout(dp(48), dp(12)))
        fieldLabel("Select Category")
        lateinit var categoryButton: Button
        categoryButton = makeButton("🎮   $selectedGameCategory     ▾", false) {
            categoryButton.isEnabled = false
            categoryButton.text = "Loading games…"
            lifecycleScope.launch {
                val choices = try {
                    withContext(Dispatchers.IO) {
                        val rows = getRestJson(
                            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/games?select=name&order=name.asc&limit=100",
                            accessToken
                        )
                        (0 until rows.length()).mapNotNull { index ->
                            rows.getJSONObject(index).optString("name").trim().takeIf { it.isNotEmpty() }
                        }.distinct().toTypedArray()
                    }
                } catch (_: Exception) {
                    arrayOf("FREE FIRE", "PUBG MOBILE", "BGMI", "CALL OF DUTY", "OTHER")
                }
                categoryButton.isEnabled = true
                categoryButton.text = "🎮   $selectedGameCategory     ▾"
                val options = if (choices.isNotEmpty()) choices else arrayOf("OTHER")
                AlertDialog.Builder(this@MainActivity).setTitle("Select Game")
                    .setItems(options) { _, which ->
                        selectedGameCategory = options[which].uppercase()
                        categoryButton.text = "🎮   $selectedGameCategory     ▾"
                    }.show()
            }
        }
        root.addView(categoryButton, fieldLayout(dp(48), dp(10)))

        var screenToggle: CheckBox? = null
        val cameraToggle = CheckBox(this).apply {
            text = "Enable Camera"
            isChecked = selectedLiveMode == "camera"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(176, 0, 255))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedLiveMode = "camera"
                    screenToggle?.isChecked = false
                    cameraPreviewView.visibility = android.view.View.VISIBLE
                    screenShareHint.visibility = android.view.View.GONE
                    if (::startLiveButton.isInitialized) startLiveButton.text = "Start Live  ▶"
                    showCameraPreview()
                } else if (selectedLiveMode == "camera") stopCameraPreview()
            }
        }
        root.addView(cameraToggle, fieldLayout(dp(42), dp(2)))
        screenToggle = CheckBox(this).apply {
            text = "Enable Screen Share"
            isChecked = selectedLiveMode == "screen"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(176, 0, 255))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedLiveMode = "screen"
                    cameraToggle.isChecked = false
                    stopCameraPreview()
                    cameraPreviewView.visibility = android.view.View.GONE
                    screenShareHint.visibility = android.view.View.VISIBLE
                    if (::startLiveButton.isInitialized) startLiveButton.text = "Continue to Screen Share  ▶"
                } else if (selectedLiveMode == "screen") {
                    selectedLiveMode = "camera"
                    cameraPreviewView.visibility = android.view.View.VISIBLE
                    screenShareHint.visibility = android.view.View.GONE
                    if (::startLiveButton.isInitialized) startLiveButton.text = "Start Live  ▶"
                }
            }
        }
        root.addView(screenToggle, fieldLayout(dp(42), dp(2)))
        startLiveButton = makeButton(
            if (selectedLiveMode == "screen") "Continue to Screen Share  ▶" else "Start Live  ▶", true
        ) {
            if (selectedLiveMode == "screen") showScreenShareIntro() else startLive()
        }
        root.addView(startLiveButton, fieldLayout(dp(52), dp(14)))
        liveStatus = TextView(this).apply {
            text = "Ready to stream"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        root.addView(liveStatus, bottomMargin(dp(6)))
        addBottomNav(2)
        if (selectedLiveMode == "camera") {
            cameraPreviewView.visibility = android.view.View.VISIBLE
            screenShareHint.visibility = android.view.View.GONE
            showCameraPreview()
        } else {
            stopCameraPreview()
            cameraPreviewView.visibility = android.view.View.GONE
            screenShareHint.visibility = android.view.View.VISIBLE
        }
    }

    private fun showScreenShareIntro() {
        stopCameraPreview()
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 8, 25), Color.rgb(24, 18, 53), Color.rgb(8, 10, 29)))
        }
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply {
            text = "▣"
            textSize = 76f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 151, 255))
        }, LinearLayout.LayoutParams(-1, dp(150)))
        root.addView(TextView(this).apply {
            text = "Share Your Screen"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(12)))
        root.addView(TextView(this).apply {
            text = "Allow screen sharing to show your gameplay or app. Android will ask you to confirm before capture starts."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        root.addView(makeButton("Start Sharing", true) {
            selectedLiveMode = "screen"
            startLive()
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        root.addView(makeButton("Cancel", false) {
            showCreateScreen()
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun showExploreScreen() {
        liveScreen.removeAllViews()
        val sc = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(18))
        }
        sc.addView(root)
        liveScreen.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Explore"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(4)))
        root.addView(TextView(this).apply {
            text = "Discover games and choose what you want to stream"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }, bottomMargin(dp(16)))
        val games = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(games, bottomMargin(dp(14)))
        games.addView(TextView(this).apply {
            text = "Popular Games"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(8)))
        games.addView(TextView(this).apply {
            text = "Loading games…"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    getRestJson("https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/games?select=id,name&order=name.asc&limit=50", accessToken)
                }
                games.removeAllViews()
                games.addView(TextView(this@MainActivity).apply {
                    text = "Popular Games"
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                }, bottomMargin(dp(8)))
                if (rows.length() == 0) {
                    games.addView(TextView(this@MainActivity).apply {
                        text = "No games have been added yet."
                        setTextColor(Color.LTGRAY)
                        textSize = 13f
                    })
                }
                for (index in 0 until rows.length()) {
                    val name = rows.getJSONObject(index).optString("name", "Game")
                    val item = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = inputBackground()
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            selectedGameCategory = name.uppercase()
                            showCreateScreen()
                        }
                    }
                    item.addView(TextView(this@MainActivity).apply {
                        text = "🎮"
                        textSize = 22f
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(44), dp(52)))
                    item.addView(TextView(this@MainActivity).apply {
                        text = name
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                    }, LinearLayout.LayoutParams(0, dp(52), 1f))
                    item.addView(TextView(this@MainActivity).apply {
                        text = "›"
                        textSize = 24f
                        setTextColor(Color.LTGRAY)
                    })
                    games.addView(item, LinearLayout.LayoutParams(-1, dp(56)).apply {
                        bottomMargin = dp(6)
                    })
                }
            } catch (error: Exception) {
                games.removeAllViews()
                games.addView(TextView(this@MainActivity).apply {
                    text = "Games could not load: " + (error.message ?: "network error")
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                })
            }
        }
        root.addView(makeButton("Go Live", true) { showCreateScreen() },
            fieldLayout(dp(50), dp(8)))
        addBottomNav(1)
    }

        private fun closeLiveViewer() {
        liveChatRefreshJob?.cancel()
        liveChatRefreshJob = null
        streamChatLoadJob?.cancel()
        streamChatLoadJob = null
        viewerEventJob?.cancel()
        viewerEventJob = null
        viewerConnectJob?.cancel()
        viewerConnectJob = null
        viewerVideoTrack?.let { track -> viewerRenderer?.let { renderer -> runCatching { track.removeRenderer(renderer) } } }
        viewerVideoTrack = null
        viewerRenderer?.let { renderer -> runCatching { renderer.release() } }
        viewerRenderer = null
        val activeViewer = viewerRoom
        viewerRoom = null
        if (activeViewer != null) lifecycleScope.launch { runCatching { activeViewer.disconnect() } }
    }

    private fun showLivePlayer(streamId: String, streamTitle: String) {
        recordWatchedStream(streamId, streamTitle)
        closeLiveViewer()
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            setBackgroundColor(Color.rgb(5, 7, 19))
        }
        root.addView(makeButton("LIVE   " + streamTitle + "     ✕", false) {
            closeLiveViewer()
            buildLiveScreen()
            setContentView(liveScreen)
        }, fieldLayout(dp(48), dp(8)))

        val renderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.BLACK) }
        viewerRenderer = renderer
        root.addView(renderer, LinearLayout.LayoutParams(-1, dp(220)))
        val playbackStatus = TextView(this).apply {
            text = "Connecting to live stream…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(playbackStatus, LinearLayout.LayoutParams(-1, dp(34)))

        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(this)
        chatScroll.addView(chat)
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Type a message..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), 0, dp(12), 0)
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f))
        var chatSending = false
        lateinit var sendButton: Button
        sendButton = makeButton("Send", true) {
            if (chatSending) return@makeButton
            val message = input.text.toString().trim()
            val uid = userId
            val token = accessToken
            if (message.isEmpty()) return@makeButton
            if (uid == null || token == null) {
                setStatus("Sign in again before sending chat messages.", true)
                return@makeButton
            }
            // Disable the button until the request completes to prevent duplicate posts.
            chatSending = true
            sendButton.isEnabled = false
            input.isEnabled = false
            input.text.clear()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        postRestJson(
                            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/chat_messages?select=id",
                            token,
                            JSONObject().put("stream_id", streamId).put("user_id", uid).put("message", message)
                        )
                    }
                    if (viewerRenderer === renderer && chat.isAttachedToWindow) loadStreamChat(streamId, chat)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (viewerRenderer === renderer && chat.isAttachedToWindow) {
                        if (input.text.isNullOrEmpty()) input.setText(message)
                        else input.setText(message + "\n" + input.text.toString())
                        input.setSelection(input.text.length)
                        setStatus("Chat send failed: " + (error.message ?: "unknown error"), true)
                    }
                } finally {
                    chatSending = false
                    if (viewerRenderer === renderer && chat.isAttachedToWindow) {
                        input.isEnabled = true
                        sendButton.isEnabled = true
                    }
                }
            }
        }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(78), dp(50)).apply { leftMargin = dp(6) })
        root.addView(composer, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, -1))
        loadStreamChat(streamId, chat)
        liveChatRefreshJob = lifecycleScope.launch {
            while (viewerRenderer === renderer) {
                delay(6000)
                if (viewerRenderer === renderer) loadStreamChat(streamId, chat)
            }
        }

        viewerConnectJob = lifecycleScope.launch {
            var connectingRoom: Room? = null
            try {
                val token = accessToken ?: error("Sign in to watch live streams.")
                val credentials = withContext(Dispatchers.IO) { requestViewerToken(token, streamId) }
                if (viewerRenderer !== renderer || !renderer.isAttachedToWindow) return@launch
                val liveRoom = LiveKit.create(applicationContext)
                connectingRoom = liveRoom
                viewerRoom = liveRoom
                liveRoom.initVideoRenderer(renderer)
                viewerEventJob = lifecycleScope.launch {
                    liveRoom.events.collect { event ->
                        when (event) {
                            is RoomEvent.TrackSubscribed -> {
                                val video = event.track as? VideoTrack
                                if (video != null) {
                                    viewerVideoTrack?.let { previous -> previous.removeRenderer(renderer) }
                                    viewerVideoTrack = video
                                    video.addRenderer(renderer)
                                    playbackStatus.text = "LIVE • Video connected"
                                }
                            }
                            is RoomEvent.TrackUnsubscribed -> {
                                val video = event.track as? VideoTrack
                                if (video != null && viewerVideoTrack === video) {
                                    video.removeRenderer(renderer)
                                    viewerVideoTrack = null
                                    playbackStatus.text = "Video track disconnected. Waiting for stream…"
                                }
                            }
                            is RoomEvent.Disconnected -> playbackStatus.text = "Disconnected from live stream."
                            else -> Unit
                        }
                    }
                }
                liveRoom.connect(credentials.getString("serverUrl"), credentials.getString("token"))
                if (viewerRenderer !== renderer || viewerRoom !== liveRoom) {
                    runCatching { liveRoom.disconnect() }
                    return@launch
                }
                playbackStatus.text = "Connected • Waiting for video…"
            } catch (error: kotlinx.coroutines.CancellationException) {
                connectingRoom?.let { staleRoom ->
                    runCatching { staleRoom.disconnect() }
                    if (viewerRoom === staleRoom) viewerRoom = null
                }
                throw error
            } catch (error: Exception) {
                if (viewerRenderer === renderer) {
                    playbackStatus.text = "Playback unavailable: " + (error.message ?: "connection failed")
                    closeLiveViewer()
                } else {
                    connectingRoom?.let { staleRoom -> runCatching { staleRoom.disconnect() } }
                }
            }
        }
    }

    private fun loadStreamChat(streamId: String, target: LinearLayout) {
        streamChatLoadJob?.cancel()
        streamChatLoadJob = lifecycleScope.launch {
            try {
                val endpoint = "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/chat_messages?select=id,user_id,message,created_at&stream_id=eq." + streamId + "&order=created_at.asc&limit=80"
                val rows = withContext(Dispatchers.IO) { getRestJson(endpoint, accessToken) }
                if (viewerRenderer == null || !target.isAttachedToWindow) return@launch
                val scroll = target.parent as? ScrollView
                val previousScrollY = scroll?.scrollY ?: 0
                val wasAtBottom = scroll != null && scroll.height > 0 &&
                    scroll.scrollY + scroll.height >= target.height - dp(24)
                target.removeAllViews()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val label = if (row.optString("user_id") == userId) "You" else "Player"
                    target.addView(TextView(this@MainActivity).apply {
                        text = label + ": " + row.optString("message")
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = panelBackground()
                    }, bottomMargin(dp(6)))
                }
                if (rows.length() == 0) target.addView(TextView(this@MainActivity).apply {
                    text = "Be the first to chat."
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, dp(20))
                })
                scroll?.post {
                    if (!target.isAttachedToWindow || viewerRenderer == null) return@post
                    if (wasAtBottom || rows.length() <= 1) {
                        scroll.fullScroll(ScrollView.FOCUS_DOWN)
                    } else {
                        scroll.scrollTo(0, previousScrollY)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (viewerRenderer != null && target.isAttachedToWindow) {
                    target.removeAllViews()
                    target.addView(TextView(this@MainActivity).apply {
                        text = "Chat unavailable: " + (error.message ?: "network error")
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    })
                }
            }
        }
    }

    private fun showCommunityScreen() {
        liveScreen.removeAllViews()
        val sc = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(16))
        }
        sc.addView(root)
        liveScreen.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "Community"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(makeButton("Refresh", false) { loadCommunityFamilies() })
        root.addView(heading, bottomMargin(dp(12)))
        val communityHero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(48, 9, 104), Color.rgb(91, 0, 151), Color.rgb(31, 16, 62))).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(102, 35, 157))
            }
        }
        communityHero.addView(TextView(this).apply {
            text = "♟"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        communityHero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Your Gaming Squad"
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Chat, connect and play together"
                textSize = 11f
                setTextColor(Color.LTGRAY)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        communityHero.addView(makeButton("Refresh", true) { loadCommunityFamilies() },
            LinearLayout.LayoutParams(dp(92), dp(42)))
        root.addView(communityHero, bottomMargin(dp(16)))
        root.addView(TextView(this).apply {
            text = "Your communities"
            textSize = 15f
            setTextColor(Color.LTGRAY)
        }, bottomMargin(dp(8)))
        communityFamiliesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(communityFamiliesContainer, bottomMargin(dp(14)))
        root.addView(TextView(this).apply {
            text = "Community chat"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(8)))
        communityChatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(this).apply {
            background = panelBackground()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatScroll.addView(communityChatContainer)
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(260)).apply { bottomMargin = dp(8) })
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        communityMessageInput = EditText(this).apply {
            hint = "Type a community message..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), 0, dp(12), 0)
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            isEnabled = false
        }
        composer.addView(communityMessageInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        val sendCommunityButton = makeButton("Send", true) { sendCommunityMessage() }.apply {
            isEnabled = false
        }
        communitySendButton = sendCommunityButton
        composer.addView(sendCommunityButton, LinearLayout.LayoutParams(dp(78), dp(50)).apply { leftMargin = dp(6) })
        root.addView(composer, bottomMargin(dp(10)))
        addBottomNav(3)
        loadCommunityFamilies()
    }

    private fun loadCommunityFamilies() {
        if (!::communityFamiliesContainer.isInitialized) return
        val targetFamilies = communityFamiliesContainer
        val targetChat = communityChatContainer
        val session = accessToken
        val uid = userId
        communityFamiliesLoadJob?.cancel()
        communityFamiliesLoadJob = null
        targetFamilies.removeAllViews()
        targetChat.removeAllViews()
        communityMessageInput.isEnabled = false
        communitySendButton?.isEnabled = false
        communityRefreshJob?.cancel()
        communityRefreshJob = null
        communityMessageLoadJob?.cancel()
        communityMessageLoadJob = null
        activeCommunityFamilyId = null
        if (session == null || uid == null) {
            targetFamilies.addView(TextView(this).apply {
                text = "Sign in to view your communities."
                setTextColor(Color.LTGRAY)
            })
            return
        }
        targetFamilies.addView(TextView(this).apply {
            text = "Loading communities…"
            setTextColor(Color.LTGRAY)
        })
        communityFamiliesLoadJob = lifecycleScope.launch {
            try {
                val memberships = withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/family_members?select=family_id&user_id=eq.$uid",
                        session
                    )
                }
                val memberIds = HashSet<String>()
                for (i in 0 until memberships.length()) {
                    memberIds.add(memberships.getJSONObject(i).optString("family_id"))
                }
                val families = withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/families?select=id,name&order=created_at.desc&limit=50",
                        session
                    )
                }
                if (communityFamiliesContainer !== targetFamilies || !targetFamilies.isAttachedToWindow) return@launch
                targetFamilies.removeAllViews()
                var shown = 0
                for (i in 0 until families.length()) {
                    val family = families.getJSONObject(i)
                    val familyId = family.optString("id")
                    if (familyId !in memberIds) continue
                    shown++
                    val familyName = family.optString("name", "Community")
                    targetFamilies.addView(
                        makeButton("👥   $familyName   ›", shown == 1) {
                            openCommunityFamily(familyId, familyName)
                        },
                        fieldLayout(dp(50), dp(6))
                    )
                }
                if (shown == 0) {
                    targetFamilies.addView(TextView(this@MainActivity).apply {
                        text = "You have not joined a community yet. Ask a community owner to invite your account."
                        textSize = 13f
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    })
                    targetChat.addView(TextView(this@MainActivity).apply {
                        text = "Community messages will appear here after you join."
                        setTextColor(Color.LTGRAY)
                    })
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (communityFamiliesContainer !== targetFamilies || !targetFamilies.isAttachedToWindow) return@launch
                targetFamilies.removeAllViews()
                targetFamilies.addView(TextView(this@MainActivity).apply {
                    text = "Communities could not load: " + (error.message ?: "network error")
                    setTextColor(Color.rgb(255, 140, 140))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
            }
        }
    }

    private fun openCommunityFamily(familyId: String, familyName: String) {
        communityRefreshJob?.cancel()
        communityMessageLoadJob?.cancel()
        activeCommunityFamilyId = familyId
        communityMessageInput.isEnabled = true
        communitySendButton?.isEnabled = !communityMessageSending
        communityChatContainer.removeAllViews()
        communityChatContainer.addView(TextView(this).apply {
            text = "Loading $familyName chat…"
            setTextColor(Color.LTGRAY)
        })
        loadCommunityMessages(familyId)
        communityRefreshJob = lifecycleScope.launch {
            while (activeCommunityFamilyId == familyId) {
                delay(6000)
                if (activeCommunityFamilyId == familyId) loadCommunityMessages(familyId)
            }
        }
    }

    private fun loadCommunityMessages(familyId: String) {
        val session = accessToken ?: return
        val targetChat = communityChatContainer
        communityMessageLoadJob?.cancel()
        communityMessageLoadJob = lifecycleScope.launch {
            try {
                val messages = withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/family_messages?select=id,user_id,message,created_at&family_id=eq.$familyId&order=created_at.asc&limit=100",
                        session
                    )
                }
                if (activeCommunityFamilyId != familyId || communityChatContainer !== targetChat || !targetChat.isAttachedToWindow) return@launch
                val scroll = targetChat.parent as? ScrollView
                val previousScrollY = scroll?.scrollY ?: 0
                val wasAtBottom = scroll != null && scroll.height > 0 &&
                    scroll.scrollY + scroll.height >= targetChat.height - dp(24)
                targetChat.removeAllViews()
                if (messages.length() == 0) {
                    targetChat.addView(TextView(this@MainActivity).apply {
                        text = "No messages yet. Start the conversation."
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                }
                for (i in 0 until messages.length()) {
                    val row = messages.getJSONObject(i)
                    val sender = if (row.optString("user_id") == userId) "You" else "Member"
                    targetChat.addView(TextView(this@MainActivity).apply {
                        text = "$sender: " + row.optString("message")
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = panelBackground()
                    }, bottomMargin(dp(6)))
                }
                scroll?.post {
                    if (!targetChat.isAttachedToWindow || communityChatContainer !== targetChat ||
                        activeCommunityFamilyId != familyId) return@post
                    if (wasAtBottom || messages.length() <= 1) {
                        scroll.fullScroll(ScrollView.FOCUS_DOWN)
                    } else {
                        scroll.scrollTo(0, previousScrollY)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeCommunityFamilyId != familyId || communityChatContainer !== targetChat || !targetChat.isAttachedToWindow) return@launch
                if ((0 until targetChat.childCount).none { targetChat.getChildAt(it).tag == "community-chat-load-error" }) {
                    targetChat.addView(TextView(this@MainActivity).apply {
                        tag = "community-chat-load-error"
                        text = "Community chat could not refresh: " + (error.message ?: "network error")
                        setTextColor(Color.rgb(255, 140, 140))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    })
                }
            }
        }
    }

    private fun sendCommunityMessage() {
        val familyId = activeCommunityFamilyId ?: run {
            setStatus("Select a community before sending a message.")
            return
        }
        if (communityMessageSending) return
        val uid = userId ?: return
        val session = accessToken ?: return
        val message = communityMessageInput.text.toString().trim()
        if (message.isEmpty()) return
        if (message.length > 1000) {
            setStatus("Community messages must be 1000 characters or fewer.", true)
            return
        }
        communityMessageSending = true
        communityMessageInput.isEnabled = false
        communityMessageInput.text.clear()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    postRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/family_messages?select=id",
                        session,
                        JSONObject().put("family_id", familyId).put("user_id", uid).put("message", message)
                    )
                }
                if (activeCommunityFamilyId == familyId) loadCommunityMessages(familyId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeCommunityFamilyId == familyId && communityMessageInput.text.isNullOrEmpty()) {
                    communityMessageInput.setText(message)
                    communityMessageInput.setSelection(communityMessageInput.text.length)
                }
                if (activeCommunityFamilyId == familyId) {
                    setStatus("Message was not sent: " + (error.message ?: "database policy or network error"), true)
                }
            } finally {
                communityMessageSending = false
                if (activeCommunityFamilyId != null && ::communityMessageInput.isInitialized) {
                    communityMessageInput.isEnabled = true
                    communitySendButton?.isEnabled = true
                }
            }
        }
    }

    private fun showProfileScreen() {
        liveScreen.removeAllViews()
        val sc = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        sc.addView(root)
        liveScreen.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        val profileHero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 8, 67), Color.rgb(53, 12, 104), Color.rgb(18, 15, 43))).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(91, 35, 145))
            }
        }
        profileAvatarView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(111, 0, 220), Color.rgb(63, 0, 156))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(205, 105, 255))
            }
            clipToOutline = true
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setOnClickListener { openProfileEditor() }
        }
        profileHero.addView(profileAvatarView, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(10) })
        profileHero.addView(TextView(this).apply {
            text = "Your profile"; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        profileHero.addView(TextView(this).apply {
            text = "Set your username"; textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }, bottomMargin(dp(12)))
        profileHero.addView(makeButton("Change Photo", true) { openProfileEditor() },
            LinearLayout.LayoutParams(dp(190), dp(44)))
        root.addView(profileHero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = panelBackground()
        }
        fun menuRow(icon: String, title: String, action: () -> Unit, alert: String? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(36), dp(48)))
            row.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (alert != null) row.addView(TextView(this).apply {
                text = alert
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(238, 20, 58)); cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
            row.addView(TextView(this).apply { text = "›"; textSize = 25f; setTextColor(Color.LTGRAY) },
                LinearLayout.LayoutParams(dp(28), dp(48)).apply { gravity = Gravity.CENTER_VERTICAL })
            menuCard.addView(row)
            if (title != "About App") menuCard.addView(android.view.View(this).apply {
                setBackgroundColor(Color.rgb(54, 44, 78))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        menuRow("▣", "My Streams", { showMyStreams() })
        menuRow("◷", "Watch History", { showWatchHistory() })
        menuRow("⚙", "Settings", { showLiveSettings() })
        menuRow("♧", "Notifications", { showNotifications() })
        menuRow("?", "Help & Support", { showSupportTickets() })
        menuRow("ⓘ", "About App", {
            showUnavailableAccountFeature("About StreamArena", "StreamArena Android app.")
        })
        root.addView(menuCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        root.addView(makeButton("Edit Profile", false) { openProfileEditor() }, fieldLayout(dp(46), dp(4)))
        root.addView(makeButton("Logout", true) { signOut() }, fieldLayout(dp(50), dp(8)))
        loadProfileSummary()
        addBottomNav(4)
    }

        private fun showAccountList(title: String, emptyMessage: String, loader: suspend () -> JSONArray, render: (JSONArray, LinearLayout) -> Unit) {
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        root.addView(makeButton("‹  Back to Profile", false) { showProfileScreen() }, fieldLayout(dp(46), dp(8)))
        root.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(12)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this)
        sc.addView(list)
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, -1))
        list.addView(TextView(this).apply {
            text = "Loading…"
            setTextColor(Color.LTGRAY)
        })
        lifecycleScope.launch {
            try {
                val rows = loader()
                list.removeAllViews()
                if (rows.length() == 0) {
                    list.addView(TextView(this@MainActivity).apply {
                        text = emptyMessage
                        textSize = 14f
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), dp(16), dp(8), dp(16))
                    })
                } else {
                    render(rows, list)
                }
            } catch (error: Exception) {
                list.removeAllViews()
                list.addView(TextView(this@MainActivity).apply {
                    text = "Could not load $title: " + (error.message ?: "network error")
                    setTextColor(Color.rgb(255, 140, 140))
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                })
            }
        }
        addBottomNav()
    }

    private fun recordWatchedStream(streamId: String, streamTitle: String) {
        val prefs = getSharedPreferences("streamarena_watch_history", MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val updated = JSONArray()
        updated.put(JSONObject().put("id", streamId).put("title", streamTitle)
            .put("opened_at", java.time.Instant.now().toString()))
        for (i in 0 until previous.length()) {
            val item = previous.optJSONObject(i) ?: continue
            if (item.optString("id") != streamId && updated.length() < 30) updated.put(item)
        }
        prefs.edit().putString("items", updated.toString()).apply()
    }

    private fun showWatchHistory() {
        val prefs = getSharedPreferences("streamarena_watch_history", MODE_PRIVATE)
        val items = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Watch History"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(12)))
        root.addView(TextView(this).apply {
            text = "Streams you recently opened on this device"
            textSize = 12f; setTextColor(Color.LTGRAY)
        }, bottomMargin(dp(14)))
        if (items.length() == 0) {
            root.addView(TextView(this).apply {
                text = "No watch history yet. Open a live stream and it will appear here."
                textSize = 14f; setTextColor(Color.LTGRAY)
                setPadding(dp(4), dp(18), dp(4), dp(18))
            })
        } else {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title", "Live Stream")
                val opened = item.optString("opened_at")
                root.addView(makeButton("▶  $title\nOpened: $opened", false) {
                    showLivePlayer(id, title)
                }, fieldLayout(dp(64), dp(8)))
            }
            root.addView(makeButton("Clear Watch History", false) {
                prefs.edit().remove("items").apply()
                showWatchHistory()
            }, fieldLayout(dp(46), dp(10)))
        }
        root.addView(makeButton("Back to Profile", true) { showProfileScreen() },
            fieldLayout(dp(48), dp(8)))
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav(4)
    }

    private fun showUnavailableAccountFeature(title: String, reason: String) {
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, bottomMargin(dp(12)))
        root.addView(TextView(this).apply {
            text = reason
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }, bottomMargin(dp(20)))
        root.addView(makeButton("Back to Profile", true) { showProfileScreen() }, fieldLayout(dp(48), dp(8)))
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav()
    }

    private fun showNotifications() {
        val uid = userId ?: run {
            showUnavailableAccountFeature("Notifications", "Sign in to view invitations.")
            return
        }
        val token = accessToken ?: return
        showAccountList(
            "Notifications",
            "You have no pending invitations.",
            loader = {
                withContext(Dispatchers.IO) {
                    val family = getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/family_invites?select=id,status,created_at,families(name)&invitee_id=eq.$uid&status=eq.pending&order=created_at.desc&limit=50",
                        token
                    )
                    val tournaments = getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/tournament_invites?select=id,status,created_at,tournaments(title)&invited_user_id=eq.$uid&status=eq.pending&order=created_at.desc&limit=50",
                        token
                    )
                    val combined = JSONArray()
                    for (i in 0 until family.length()) {
                        combined.put(family.getJSONObject(i).apply { put("_kind", "Community invitation") })
                    }
                    for (i in 0 until tournaments.length()) {
                        combined.put(tournaments.getJSONObject(i).apply { put("_kind", "Tournament invitation") })
                    }
                    combined
                }
            },
            render = { rows, list ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val kind = row.optString("_kind")
                    val related = if (kind == "Community invitation") row.optJSONObject("families") else row.optJSONObject("tournaments")
                    val field = if (kind == "Community invitation") "name" else "title"
                    val name = related?.optString(field)?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Invitation"
                    val date = row.optString("created_at").take(10)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = panelBackground()
                    }
                    card.addView(TextView(this).apply {
                        text = kind + "\n" + name + "\nPending · " + date
                        textSize = 14f
                        setTextColor(Color.WHITE)
                    })
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                    }
                    actions.addView(makeButton("Decline", false) {
                        respondToInvitation(row.optString("id"), kind, false)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
                    actions.addView(makeButton("Accept", true) {
                        respondToInvitation(row.optString("id"), kind, true)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
                    card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                    list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        )
    }

    private fun respondToInvitation(inviteId: String, kind: String, accept: Boolean) {
        if (inviteId.isBlank()) return
        val token = accessToken ?: return
        val rpc = if (kind == "Community invitation") "respond_family_invite" else "respond_tournament_invite"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val connection = (URL("https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/rpc/$rpc").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 20000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("apikey", "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2")
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        val body = JSONObject().put("p_invite_id", inviteId).put("p_accept", accept)
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val response = stream.bufferedReader().use { it.readText() }
                        if (code !in 200..299) {
                            val message = runCatching {
                                val json = JSONObject(response)
                                json.optString("message").ifBlank { json.optString("msg") }.ifBlank { json.optString("error") }
                            }.getOrDefault("")
                            throw IllegalStateException(message.ifBlank { "Server returned HTTP $code" })
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                setStatus(if (accept) "Invitation accepted." else "Invitation declined.")
                showNotifications()
            } catch (error: Exception) {
                setStatus("Could not respond: " + (error.message ?: "network error"))
            }
        }
    }

    private fun showMyStreams() {
        val uid = userId ?: run {
            showUnavailableAccountFeature("My Streams", "Sign in to view your streams.")
            return
        }
        val token = accessToken ?: return
        showAccountList(
            "My Streams",
            "You have not created any streams yet.",
            loader = {
                withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/streams?select=id,title,caption,is_live,created_at,last_heartbeat_at&user_id=eq.$uid&order=created_at.desc&limit=50",
                        token
                    )
                }
            },
            render = { rows, list ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val streamId = row.optString("id")
                    val title = row.optString("title", "Untitled stream")
                    val isLive = row.optBoolean("is_live", false)
                    val heartbeat = row.optString("last_heartbeat_at")
                    val liveNow = isLive && heartbeat.isNotBlank() && heartbeat != "null" &&
                        runCatching {
                            java.time.Instant.parse(heartbeat).isAfter(java.time.Instant.now().minusSeconds(120))
                        }.getOrDefault(false)
                    val subtitle = if (liveNow) "LIVE" else "Offline"
                    list.addView(makeButton("$title\n$subtitle", liveNow) {
                        if (liveNow) showLivePlayer(streamId, title)
                        else setStatus("This stream is currently offline.")
                    }, fieldLayout(dp(60), dp(8)))
                }
            }
        )
    }

    private fun showMyTournamentRegistrations() {
        val uid = userId ?: run {
            showUnavailableAccountFeature("Tournament Registrations", "Sign in to view your registrations.")
            return
        }
        val token = accessToken ?: return
        showAccountList(
            "Tournament Registrations",
            "You have no tournament registrations.",
            loader = {
                withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/tournament_registrations?select=id,created_at,tournament_id,tournaments(title,status,starts_at,entry_type,entry_fee,prize_pool)&user_id=eq.$uid&order=created_at.desc&limit=50",
                        token
                    )
                }
            },
            render = { rows, list ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val tournament = row.optJSONObject("tournaments")
                    val title = tournament?.optString("title")?.takeIf { !it.isNullOrBlank() && it != "null" }
                        ?: "Tournament"
                    val status = tournament?.optString("status")?.takeIf { !it.isNullOrBlank() && it != "null" }
                        ?: "Status unavailable"
                    val type = tournament?.optString("entry_type")?.takeIf { !it.isNullOrBlank() && it != "null" }
                        ?: "Entry type unavailable"
                    list.addView(TextView(this@MainActivity).apply {
                        text = "$title\n$status • $type"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = panelBackground()
                    }, bottomMargin(dp(8)))
                }
            }
        )
    }

    private fun showCreateSupportTicket() {
        if (accessToken == null || userId == null) {
            showUnavailableAccountFeature("Contact Support", "Sign in to create a support request.")
            return
        }
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        root.addView(makeButton("‹  Back to Profile", false) { showProfileScreen() }, fieldLayout(dp(46), dp(10)))
        root.addView(TextView(this).apply {
            text = "New Support Request"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(14)))
        val subjectInput = EditText(this).apply {
            hint = "Subject (up to 160 characters)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(subjectInput, fieldLayout(dp(52), dp(10)))
        val messageInput = EditText(this).apply {
            hint = "Describe your issue (up to 5000 characters)"
            minLines = 5
            gravity = Gravity.TOP
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(messageInput, fieldLayout(dp(150), dp(10)))
        val statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        root.addView(statusText, bottomMargin(dp(8)))
        var ticketSending = false
        lateinit var submitTicketButton: Button
        submitTicketButton = makeButton("Submit Request", true) {
            if (ticketSending) return@makeButton
            val subject = subjectInput.text.toString().trim()
            val message = messageInput.text.toString().trim()
            val uid = userId ?: return@makeButton
            val token = accessToken ?: return@makeButton
            if (subject.isEmpty() || subject.length > 160 || message.isEmpty() || message.length > 5000) {
                statusText.text = "Enter a subject (1–160 chars) and message (1–5000 chars)."
                return@makeButton
            }
            ticketSending = true
            subjectInput.isEnabled = false
            messageInput.isEnabled = false
            submitTicketButton.isEnabled = false
            submitTicketButton.text = "Submitting…"
            statusText.text = "Submitting…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        postRestJson(
                            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/support_tickets?select=id",
                            token,
                            JSONObject().put("user_id", uid).put("subject", subject).put("message", message)
                        )
                    }
                    if (root.isAttachedToWindow) {
                        statusText.text = "Support request submitted."
                        subjectInput.text.clear()
                        messageInput.text.clear()
                        showSupportTickets()
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (root.isAttachedToWindow) {
                        statusText.text = "Request failed: " + (error.message ?: "network or access policy error")
                    }
                } finally {
                    ticketSending = false
                    subjectInput.isEnabled = true
                    messageInput.isEnabled = true
                    submitTicketButton.isEnabled = true
                    submitTicketButton.text = "Submit Request"
                }
            }
        }
        root.addView(submitTicketButton, fieldLayout(dp(50), dp(8)))
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav()
    }

    private fun showSupportTickets() {
        activeSupportTicketId = null
        supportTicketRefreshJob?.cancel()
        supportTicketRepliesLoadJob?.cancel()
        val uid = userId ?: run {
            showUnavailableAccountFeature("Help & Support", "Sign in to view support requests.")
            return
        }
        val token = accessToken ?: return
        showAccountList(
            "Help & Support",
            "No support requests found.",
            loader = {
                withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/support_tickets?select=id,subject,message,status,created_at,updated_at&user_id=eq.$uid&order=created_at.desc&limit=50",
                        token
                    )
                }
            },
            render = { rows, list ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val ticketId = row.optString("id")
                    val subject = row.optString("subject")
                    val state = row.optString("status")
                    list.addView(makeButton(subject + "\n" + state + " • " + row.optString("created_at") + "\nView conversation ›", false) {
                        showSupportTicketConversation(ticketId, subject, state)
                    }, fieldLayout(dp(76), dp(8)))
                }
            }
        )
    }

    private fun showSupportTicketConversation(ticketId: String, subject: String, ticketStatus: String) {
        val token = accessToken ?: return
        activeSupportTicketId = ticketId
        supportTicketRefreshJob?.cancel()
        supportTicketRepliesLoadJob?.cancel()
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(12))
        }
        root.addView(makeButton("‹  Back to Support", false) { showSupportTickets() }, fieldLayout(dp(44), dp(8)))
        root.addView(TextView(this).apply {
            text = subject
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(4)))
        root.addView(TextView(this).apply {
            text = "Status: $ticketStatus"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }, bottomMargin(dp(8)))
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = EditText(this).apply {
            hint = "Write a reply (up to 5000 characters)"
            minLines = 2
            maxLines = 4
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = inputBackground()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(composer, fieldLayout(dp(90), dp(8)))
        var replySending = false
        lateinit var sendReplyButton: Button
        sendReplyButton = makeButton("Send Reply", true) {
            if (replySending) return@makeButton
            val message = composer.text.toString().trim()
            val uid = userId ?: return@makeButton
            if (message.isEmpty() || message.length > 5000) {
                setStatus("Reply must contain 1–5000 characters.", true)
                return@makeButton
            }
            replySending = true
            composer.isEnabled = false
            sendReplyButton.isEnabled = false
            sendReplyButton.text = "Sending…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        postRestJson(
                            "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/support_replies?select=id",
                            token,
                            JSONObject().put("ticket_id", ticketId).put("sender_id", uid).put("message", message)
                        )
                    }
                    if (activeSupportTicketId == ticketId && messages.isAttachedToWindow) {
                        if (composer.text.toString().trim() == message) composer.text.clear()
                        loadSupportTicketReplies(ticketId, messages)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (activeSupportTicketId == ticketId && messages.isAttachedToWindow) {
                        setStatus("Reply failed: " + (error.message ?: "network or access policy error"), true)
                    }
                } finally {
                    replySending = false
                    composer.isEnabled = true
                    sendReplyButton.isEnabled = true
                    sendReplyButton.text = "Send Reply"
                }
            }
        }
        root.addView(sendReplyButton, fieldLayout(dp(48), dp(4)))
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomNav()
        loadSupportTicketReplies(ticketId, messages)
        supportTicketRefreshJob = lifecycleScope.launch {
            while (activeSupportTicketId == ticketId && messages.isAttachedToWindow) {
                delay(8000)
                if (activeSupportTicketId == ticketId && messages.isAttachedToWindow &&
                    supportTicketRepliesLoadJob?.isActive != true) {
                    loadSupportTicketReplies(ticketId, messages)
                }
            }
        }
    }

    private fun loadSupportTicketReplies(ticketId: String, target: LinearLayout) {
        val token = accessToken ?: return
        supportTicketRepliesLoadJob?.cancel()
        supportTicketRepliesLoadJob = lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/support_replies?select=id,sender_id,message,created_at&ticket_id=eq.$ticketId&order=created_at.asc&limit=100",
                        token
                    )
                }
                if (activeSupportTicketId != ticketId || !target.isAttachedToWindow) return@launch
                val parentScroll = target.parent as? ScrollView
                val previousScrollY = parentScroll?.scrollY ?: 0
                val wasAtBottom = parentScroll?.let { scrollView ->
                    val contentHeight = target.height
                    contentHeight <= scrollView.height + scrollView.scrollY + dp(24)
                } ?: true
                target.removeAllViews()
                if (rows.length() == 0) {
                    target.addView(TextView(this@MainActivity).apply {
                        text = "No replies yet. Send a message to support."
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                }
                val uid = userId
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val mine = row.optString("sender_id") == uid
                    target.addView(TextView(this@MainActivity).apply {
                        text = (if (mine) "You" else "Support") + "\n" + row.optString("message") +
                            "\n" + row.optString("created_at")
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = panelBackground()
                    }, bottomMargin(dp(8)))
                }
                parentScroll?.post {
                    if (!target.isAttachedToWindow || activeSupportTicketId != ticketId) return@post
                    if (wasAtBottom) {
                        parentScroll.fullScroll(ScrollView.FOCUS_DOWN)
                    } else {
                        parentScroll.scrollTo(0, previousScrollY)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeSupportTicketId == ticketId && target.isAttachedToWindow &&
                    (0 until target.childCount).none { target.getChildAt(it).tag == "support-replies-load-error" }) {
                    target.addView(TextView(this@MainActivity).apply {
                        tag = "support-replies-load-error"
                        text = "Replies could not refresh: " + (error.message ?: "network error")
                        setTextColor(Color.rgb(255, 140, 140))
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                }
            }
        }
    }

    private lateinit var profileNameInput: EditText
    private lateinit var profileUsernameInput: EditText
    private lateinit var profileStatus: TextView

    private fun loadProfileSummary() {
        val session = accessToken ?: return
        val uid = userId ?: return
        val targetAvatarView = profileAvatarView
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    getRestJson("https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/profiles?select=full_name,username,avatar_url&id=eq.$uid&limit=1", session)
                }
                if (accessToken != session || userId != uid || profileAvatarView !== targetAvatarView ||
                    targetAvatarView?.isAttachedToWindow != true) return@launch
                if (rows.length() == 0) return@launch
                val row = rows.getJSONObject(0)
                val name = row.optString("full_name").takeUnless { it == "null" } ?: ""
                val username = row.optString("username").takeUnless { it == "null" } ?: ""
                val avatar = row.optString("avatar_url").takeUnless { it == "null" || it.isBlank() }
                profileAvatarStoredUrl = avatar
                profileHeroNameUpdate(name, username)
                if (avatar != null && avatar.startsWith("data:image/")) {
                    val bytes = Base64.decode(avatar.substringAfter(",", ""), Base64.DEFAULT)
                    profileAvatarBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (accessToken == session && userId == uid && profileAvatarView === targetAvatarView &&
                        targetAvatarView?.isAttachedToWindow == true) targetAvatarView.setImageBitmap(profileAvatarBitmap)
                } else if (avatar != null && (avatar.startsWith("https://") || avatar.startsWith("http://"))) {
                    val bitmap = withContext(Dispatchers.IO) {
                        (URL(avatar).openConnection() as HttpURLConnection).run {
                            connectTimeout = 10000; readTimeout = 10000
                            try { inputStream.use { BitmapFactory.decodeStream(it) } } finally { disconnect() }
                        }
                    }
                    if (accessToken == session && userId == uid && profileAvatarView === targetAvatarView &&
                        targetAvatarView?.isAttachedToWindow == true) {
                        profileAvatarBitmap = bitmap
                        targetAvatarView.setImageBitmap(bitmap)
                    } else {
                        bitmap?.recycle()
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) { }
        }
    }

    private fun openProfileEditor() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        profileNameInput = EditText(this).apply {
            hint = "Display name"; setSingleLine(true); setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY); background = inputBackground(); setPadding(dp(12), 0, dp(12), 0)
        }
        profileUsernameInput = EditText(this).apply {
            hint = "Username (optional)"; setSingleLine(true); setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY); background = inputBackground(); setPadding(dp(12), 0, dp(12), 0)
        }
        form.addView(profileNameInput, fieldLayout(dp(50), dp(8)))
        form.addView(profileUsernameInput, fieldLayout(dp(50), dp(8)))
        form.addView(makeButton("Choose photo from gallery", false) {
            profileImagePicker.launch("image/*")
        }, fieldLayout(dp(46), dp(8)))
        profileStatus = TextView(this).apply {
            text = "Choose a display name and optional profile photo."
            setTextColor(Color.LTGRAY); textSize = 12f
        }
        form.addView(profileStatus)
        val dialog = AlertDialog.Builder(this).setTitle("Customize Profile").setView(form)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { saveProfile() }
        }
        dialog.show()
        loadProfile()
    }

    private fun profileHeroNameUpdate(name: String, username: String) {
        val hero = (liveScreen.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        (hero.getChildAt(1) as? TextView)?.text = name.ifBlank { "Your profile" }
        (hero.getChildAt(2) as? TextView)?.text = if (username.isNotBlank()) "@$username" else "Set your username"
    }

    private fun loadProfile() {
        val session = accessToken
        val uid = userId
        if (session == null || uid == null) {
            profileStatus.text = "Sign in to view your profile."
            profileNameInput.setText("")
            profileUsernameInput.setText("")
            return
        }
        profileStatus.text = "Loading profile…"
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/profiles?select=id,full_name,username,avatar_url,created_at&id=eq.$uid&limit=1",
                        session
                    )
                }
                if (rows.length() == 0) {
                    profileStatus.text = "No profile row exists for this account yet. Profile cannot be saved until a row is created."
                    return@launch
                }
                val row = rows.getJSONObject(0)
                val savedName = row.optString("full_name").takeUnless { it == "null" } ?: ""
                val savedUsername = row.optString("username").takeUnless { it == "null" } ?: ""
                val avatarUrl = row.optString("avatar_url").takeUnless { it == "null" || it.isBlank() }
                profileAvatarStoredUrl = avatarUrl
                profileNameInput.setText(savedName)
                profileUsernameInput.setText(savedUsername)
                if (avatarUrl != null && avatarUrl.startsWith("data:image/")) {
                    val bytes = Base64.decode(avatarUrl.substringAfter(",", ""), Base64.DEFAULT)
                    profileAvatarBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    profileAvatarView?.setImageBitmap(profileAvatarBitmap)
                } else if (avatarUrl != null && (avatarUrl.startsWith("https://") || avatarUrl.startsWith("http://"))) {
                    val bitmap = withContext(Dispatchers.IO) {
                        (URL(avatarUrl).openConnection() as HttpURLConnection).run {
                            connectTimeout = 10000; readTimeout = 10000
                            try { inputStream.use { BitmapFactory.decodeStream(it) } } finally { disconnect() }
                        }
                    }
                    profileAvatarBitmap = bitmap
                    profileAvatarView?.setImageBitmap(bitmap)
                }
                profileHeroNameUpdate(savedName, savedUsername)
                profileStatus.text = "Profile loaded."
            } catch (error: Exception) {
                profileStatus.text = "Profile could not load: " + (error.message ?: "network error")
            }
        }
    }

    private fun uploadProfileAvatar(session: String, uid: String, dataUrl: String): String {
        val encodedImage = dataUrl.substringAfter(",", "")
        if (encodedImage.isBlank()) error("Selected profile image is empty.")
        val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
        if (imageBytes.size > 5 * 1024 * 1024) error("Profile image exceeds the 5 MB storage limit.")
        val objectName = "$uid/profile-avatar-${java.util.UUID.randomUUID()}.jpg"
        val endpoint = "https://ovoufvyevnixpmswfrgl.supabase.co/storage/v1/object/stream-thumbnails/$objectName"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("apikey", "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2")
            setRequestProperty("Authorization", "Bearer $session")
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Cache-Control", "3600")
            setRequestProperty("x-upsert", "false")
        }
        try {
            connection.outputStream.use { it.write(imageBytes) }
            val code = connection.responseCode
            val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val json = JSONObject(response)
                    json.optString("message").ifBlank { json.optString("error") }
                }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Avatar upload failed (HTTP $code)."})
            }
        } finally {
            connection.disconnect()
        }
        return "https://ovoufvyevnixpmswfrgl.supabase.co/storage/v1/object/public/stream-thumbnails/$objectName"
    }

    private fun saveProfile() {
        val session = accessToken ?: run {
            profileStatus.text = "Sign in to update your profile."
            return
        }
        val uid = userId ?: return
        val fullName = profileNameInput.text.toString().trim()
        val username = profileUsernameInput.text.toString().trim()
        val pendingAvatar = profileAvatarDataUrl
        if (fullName.length > 80 || username.length > 30) {
            profileStatus.text = "Name must be 80 characters or fewer; username 30 or fewer."
            return
        }
        if (username.isNotEmpty() && !username.matches(Regex("[A-Za-z0-9_]+"))) {
            profileStatus.text = "Username can contain only letters, numbers, and underscores."
            return
        }
        profileStatus.text = if (pendingAvatar != null) "Uploading profile photo and saving…" else "Saving profile…"
        lifecycleScope.launch {
            try {
                val savedAvatar = withContext(Dispatchers.IO) {
                    val avatarUrl = pendingAvatar?.let { uploadProfileAvatar(session, uid, it) }
                        ?: profileAvatarStoredUrl
                    patchRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/profiles?id=eq.$uid",
                        session,
                        JSONObject()
                            .put("full_name", fullName.ifEmpty { JSONObject.NULL })
                            .put("username", username.ifEmpty { JSONObject.NULL })
                            .put("avatar_url", avatarUrl ?: JSONObject.NULL)
                    )
                    val verified = getRestJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/profiles?select=id,full_name,username,avatar_url&id=eq.$uid&limit=1",
                        session
                    )
                    if (verified.length() != 1) error("No profile row was updated for this account.")
                    val saved = verified.getJSONObject(0)
                    val savedName = saved.optString("full_name").takeUnless { it == "null" } ?: ""
                    val savedUsername = saved.optString("username").takeUnless { it == "null" } ?: ""
                    val verifiedAvatar = saved.optString("avatar_url").takeUnless { it == "null" }
                    if (savedName != fullName || savedUsername != username || verifiedAvatar != avatarUrl) {
                        error("The saved profile values could not be verified.")
                    }
                    verifiedAvatar
                }
                profileHeroNameUpdate(fullName, username)
                profileAvatarStoredUrl = savedAvatar
                profileAvatarDataUrl = null
                profileStatus.text = "Profile name and photo saved."
            } catch (error: Exception) {
                profileStatus.text = "Profile save failed: " + (error.message ?: "database policy or network error")
            }
        }
    }

    private fun showDashboardMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "⚙  Settings")
        popup.menu.add(0, 2, 1, "↪  Sign out")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showLiveSettings()
                2 -> signOut()
            }
            true
        }
        popup.show()
    }

    private fun showLiveSettings() {
        val keepAwake = CheckBox(this).apply {
            text = "Keep screen awake while Live is running"
            setTextColor(Color.WHITE)
            isChecked = keepScreenAwakeDuringLive
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("StreamArena settings")
            .setView(keepAwake)
            .setPositiveButton("Save") { _, _ ->
                keepScreenAwakeDuringLive = keepAwake.isChecked
                getSharedPreferences("streamarena_settings", MODE_PRIVATE).edit()
                    .putBoolean("keep_screen_awake_during_live", keepScreenAwakeDuringLive)
                    .apply()
                if (room != null && keepScreenAwakeDuringLive) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                setStatus("Settings saved.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatorMenu(anchor: android.view.View, scroll: ScrollView) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 10, 0, "🎥  Go Live — Camera")
        popup.menu.add(0, 11, 1, "📱  Share Phone Screen")
        val games = popup.menu.addSubMenu(0, 12, 2, "🎮  Choose Game Category")
        listOf("ACTION", "BATTLE", "SPORTS", "RACING").forEachIndexed { index, category ->
            games.add(1, 20 + index, index, category)
        }
        popup.setOnDismissListener {
            anchor.animate().rotation(0f).setDuration(140).start()
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> openLiveSetup("camera", scroll)
                11 -> openLiveSetup("screen", scroll)
                20, 21, 22, 23 -> {
                    selectedGameCategory = listOf("ACTION", "BATTLE", "SPORTS", "RACING")[item.itemId - 20]
                    setStatus("Selected game category: $selectedGameCategory")
                }
            }
            true
        }
        popup.show()
    }

    private fun openLiveSetup(mode: String, scroll: ScrollView) {
        selectedLiveMode = mode
        startLiveButton.text = if (mode == "screen") "Go Live with Screen Share" else "Go Live"
        liveCard.visibility = android.view.View.VISIBLE
        if (mode == "camera") showCameraPreview() else stopCameraPreview()
        scroll.post { scroll.smoothScrollTo(0, liveCard.top) }
        setStatus(if (mode == "screen") {
            "Screen Share selected. Enter a title, tap Go Live and approve Android's screen-capture prompt."
        } else {
            "Camera preview opened. Enter a title and tap Go Live only when you want to broadcast."
        })
    }

    private fun showCameraPreview() {
        if (!::cameraPreviewView.isInitialized || room != null) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPreviewPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(cameraPreviewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                cameraProvider = provider
                cameraPreviewView.visibility = android.view.View.VISIBLE
            } catch (error: Exception) {
                setStatus("Could not open camera preview: ${error.message ?: "unknown error"}", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraPreview() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (::cameraPreviewView.isInitialized) cameraPreviewView.visibility = android.view.View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(22, 18, 43))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.rgb(65, 39, 102))
    }

    private fun inputBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(31, 27, 57))
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), Color.rgb(70, 51, 111))
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
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (primary) intArrayOf(Color.rgb(139, 0, 255), Color.rgb(202, 0, 224), Color.rgb(118, 0, 255))
                else intArrayOf(Color.rgb(34, 27, 61), Color.rgb(27, 24, 48))
            ).apply {
                cornerRadius = dp(18).toFloat()
                if (!primary) setStroke(dp(1), Color.rgb(75, 55, 116))
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
                    pendingSignupEmail = email
                    passwordInput.visibility = android.view.View.GONE
                    otpInput.visibility = android.view.View.VISIBLE
                    otpVerifyButton.visibility = android.view.View.VISIBLE
                    authCardShowOtpLabel()
                    signInButton.visibility = android.view.View.GONE
                    loginTabButton.visibility = android.view.View.GONE
                    signUpTabButton.visibility = android.view.View.GONE
                    setStatus("A confirmation code was sent to your email. Enter it below.")
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

    private fun authCardShowOtpLabel(show: Boolean = true) {
        // Reveal the confirmation label added immediately before the OTP field.
        val parent = otpInput.parent as? LinearLayout ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is TextView && child.tag == "otp_label") {
                child.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun verifySignupOtp() {
        val email = pendingSignupEmail ?: emailInput.text.toString().trim()
        val code = otpInput.text.toString().trim()
        if (email.isBlank() || code.length !in 6..8) {
            setStatus("Enter the confirmation code from your email.", true)
            return
        }
        otpVerifyButton.isEnabled = false
        otpVerifyButton.text = "Verifying…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    postJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/auth/v1/verify",
                        "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
                        null,
                        JSONObject().put("type", "signup").put("email", email).put("token", code)
                    )
                }
                accessToken = result.getString("access_token")
                userId = result.optJSONObject("user")?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Verified, but the user session was not returned. Please log in.")
                pendingSignupEmail = null
                passwordInput.text.clear()
                otpInput.text.clear()
                setContentView(liveScreen)
                loadLiveStreams()
                setStatus("Email confirmed. Welcome to StreamArena.")
            } catch (error: Exception) {
                setStatus("Code verification failed: ${error.message ?: "unknown error"}", true)
            } finally {
                otpVerifyButton.isEnabled = true
                otpVerifyButton.text = "Verify code"
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
                loadLiveStreams()
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
        stopCameraPreview()
        heartbeatJob?.cancel()
        heartbeatJob = null
        lifecycleScope.launch {
            // Mark the user's active stream offline before discarding the session token.
            val streamId = activeStreamId
            val session = accessToken
            val ownerId = userId
            if (streamId != null && session != null && ownerId != null) {
                runCatching {
                    withContext(Dispatchers.IO) { updateStreamRow(session, streamId, ownerId, false) }
                }.onFailure {
                    setStatus("Signed out locally, but the stream status could not be updated: ${it.message}")
                }
            }
            runCatching { room?.localParticipant?.setScreenShareEnabled(false) }
            runCatching { room?.disconnect() }
            room = null
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activeStreamId = null
            sharing = false
            accessToken = null
            userId = null
            profileAvatarBitmap = null
            profileAvatarDataUrl = null
            profileAvatarStoredUrl = null
            profileAvatarView?.setImageDrawable(null)
            profileAvatarView = null
            emailInput.text.clear()
            passwordInput.text.clear()
            titleInput.text.clear()
            otpInput.text.clear()
            otpInput.visibility = android.view.View.GONE
            otpVerifyButton.visibility = android.view.View.GONE
            authCardShowOtpLabel(false)
            passwordInput.visibility = android.view.View.VISIBLE
            signInButton.visibility = android.view.View.VISIBLE
            loginTabButton.visibility = android.view.View.VISIBLE
            signUpTabButton.visibility = android.view.View.VISIBLE
            pendingSignupEmail = null
            setAuthMode(false)
            setContentView(authScreen)
            updateControls()
        }
    }

    private fun startLive() {
        val requiredPermissions = if (selectedLiveMode == "screen") {
            listOf(android.Manifest.permission.RECORD_AUDIO)
        } else {
            listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
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
        stopCameraPreview()
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
                if (keepScreenAwakeDuringLive) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                liveRoom.localParticipant.setMicrophoneEnabled(true)
                // Screen Live publishes the captured display, not the front-facing camera.
                liveRoom.localParticipant.setCameraEnabled(selectedLiveMode == "camera")
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
                if (selectedLiveMode == "screen") {
                    setStatus("Live started. Approve Android's prompt to share your phone screen.")
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                } else {
                    setStatus("You are live with your camera and microphone.")
                }
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
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activeStreamId = null
            if (selectedLiveMode == "camera" && ::liveCard.isInitialized && liveCard.visibility == android.view.View.VISIBLE) showCameraPreview()
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

    private fun requestPasswordReset() {
        val email = emailInput.text.toString().trim()
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            setStatus("Enter a valid email address first.", true)
            emailInput.requestFocus()
            return
        }
        setStatus("Requesting password reset…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    postJson(
                        "https://ovoufvyevnixpmswfrgl.supabase.co/auth/v1/recover",
                        "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
                        null,
                        JSONObject().put("email", email)
                    )
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Check your email")
                    .setMessage("If an account exists for $email, Supabase will send a password recovery email. Open the link in that email and follow the website instructions to choose a new password.")
                    .setPositiveButton("OK", null)
                    .show()
                setStatus("Password recovery request submitted.")
            } catch (error: Exception) {
                setStatus("Password recovery request failed: " + (error.message ?: "network error"), true)
            }
        }
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

    private fun getRestJson(endpoint: String, bearer: String?): org.json.JSONArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Database request failed (HTTP $code): $response")
            return org.json.JSONArray(response)
        } finally { connection.disconnect() }
    }

    private fun loadGames() {
        if (!::gamesContainer.isInitialized) return
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    getRestJson("https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/games?select=id,name&order=name.asc&limit=12", accessToken)
                }
                gamesContainer.removeAllViews()
                if (rows.length() == 0) {
                    gamesContainer.addView(TextView(this@MainActivity).apply {
                        text = "No games have been added yet."
                        setTextColor(Color.LTGRAY)
                    })
                }
                for (i in 0 until rows.length()) {
                    val name = rows.getJSONObject(i).optString("name", "Game")
                    gamesContainer.addView(TextView(this@MainActivity).apply {
                        text = name
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.rgb(112,25,155), Color.rgb(20,52,104))).apply {
                            cornerRadius = dp(14).toFloat()
                        }
                        setOnClickListener {
                            selectedGameCategory = name.uppercase()
                            setStatus("$name selected")
                        }
                    }, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
            } catch (error: Exception) {
                gamesContainer.removeAllViews()
                gamesContainer.addView(TextView(this@MainActivity).apply {
                    text = "Games could not load: " + (error.message ?: "network error")
                    setTextColor(Color.LTGRAY)
                })
            }
        }
    }

    private fun loadLiveStreams() {
        if (!::liveStreamsContainer.isInitialized) return
        lifecycleScope.launch {
            try {
                val freshAfter = java.time.Instant.now().minusSeconds(120).toString()
                val encoded = java.net.URLEncoder.encode(freshAfter, "UTF-8")
                val endpoint = "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/streams?select=id,title,caption,user_id&is_live=eq.true&last_heartbeat_at=gt." + encoded + "&order=last_heartbeat_at.desc&limit=8"
                val rows = withContext(Dispatchers.IO) { getRestJson(endpoint, accessToken) }
                liveStreamsContainer.removeAllViews()
                if (rows.length() == 0) {
                    liveStreamsContainer.addView(TextView(this@MainActivity).apply {
                        text = "No live streams right now."
                        textSize = 13f
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                }
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val streamId = row.getString("id")
                    val streamTitle = row.optString("title", "Live Stream")
                    val caption = row.optString("caption", "")
                    liveStreamsContainer.addView(
                        makeButton("🔴 LIVE   " + streamTitle + "\n" + caption + "\nWatch Live  ›", false) {
                            showLivePlayer(streamId, streamTitle)
                        },
                        fieldLayout(dp(76), dp(8))
                    )
                }
            } catch (error: Exception) {
                liveStreamsContainer.removeAllViews()
                liveStreamsContainer.addView(TextView(this@MainActivity).apply {
                    text = "Live streams could not load: " + (error.message ?: "network error")
                    textSize = 12f
                    setTextColor(Color.rgb(255, 140, 140))
                })
            }
        }
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
            if (code !in 200..299) throw IllegalStateException("Request failed (HTTP $code): $response")
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

    private fun requestViewerToken(session: String, streamId: String): JSONObject {
        val body = JSONObject().put("roomName", "stream-$streamId").put("role", "viewer")
        return postJson(
            "https://ovoufvyevnixpmswfrgl.supabase.co/functions/v1/livekit-token",
            "sb_publishable_0SdYbLyW1wRGMc5sxjmIEg_XmDD9Ds2",
            session,
            body
        )
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
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
