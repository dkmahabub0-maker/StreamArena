package com.streamarena.android

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.EditText
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
    private var sharing = false
    private var activeStreamId: String? = null
    private var heartbeatJob: Job? = null
    private lateinit var liveStreamsContainer: LinearLayout
    private lateinit var gamesContainer: LinearLayout

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
        buildUi()
        updateControls()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(9, 14, 29)
        window.navigationBarColor = Color.rgb(9, 14, 29)
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
        root.addView(makeButton("🌐   Continue with Google",false) { AlertDialog.Builder(this).setMessage("Google sign-in is not configured in this Android client yet.").setPositiveButton("OK",null).show() },fieldLayout(dp(52),dp(10)))
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
        links.addView(TextView(this).apply { text="Forgot Password?"; gravity=Gravity.END; setTextColor(Color.WHITE); setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Password reset is not available in this Android build yet.").setPositiveButton("OK",null).show() } },LinearLayout.LayoutParams(0,dp(42),1f))
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
        hero.addView(TextView(this).apply { text="🔴 LIVE     ◉ 12.5K"; textSize=13f; setTextColor(Color.WHITE) },bottomMargin(dp(42)))
        hero.addView(TextView(this).apply { text="FREE FIRE"; textSize=30f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
        hero.addView(makeButton("Play Now  ▶",true) { setStatus("Choose a live stream from the Live Streams list below.") },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(8) })
        root.addView(hero,LinearLayout.LayoutParams(-1,dp(190)).apply { bottomMargin=dp(12) })
        root.addView(makeButton("✦ ADS     Add System                         Go",true) { setStatus("Ad system is not connected yet.") },fieldLayout(dp(48),dp(16)))
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
    private fun addBottomNav() {
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(dp(3),dp(4),dp(3),dp(4)); setBackgroundColor(Color.rgb(13,12,29)) }
        listOf("⌂\nHome","◎\nExplore","⊕\nCreate","♧\nCommunity","♙\nProfile").forEachIndexed { i,label ->
            nav.addView(makeButton(label,i==0) { when(i) {
                0 -> { stopCameraPreview(); buildLiveScreen(); setContentView(liveScreen) }
                1 -> showExploreScreen()
                2 -> { val sc=liveScreen.getChildAt(0) as? ScrollView; if(sc!=null) openLiveSetup("camera",sc) }
                3 -> showCommunityScreen()
                4 -> showProfileScreen()
            } }.apply { textSize=11f; minWidth=0; minimumWidth=0; setPadding(0,0,0,0); isAllCaps=false },LinearLayout.LayoutParams(0,dp(54),1f))
        }
        liveScreen.addView(nav)
    }
    private fun showExploreScreen() {
        liveScreen.removeAllViews(); val sc=ScrollView(this); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(24),dp(18),dp(20)) }
        sc.addView(root); liveScreen.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply { text="Explore Games"; textSize=24f; setTextColor(Color.WHITE) },bottomMargin(dp(18)))
        listOf("Free Fire","PUBG MOBILE","BGMI","ACTION","BATTLE","SPORTS","RACING").forEach { game -> root.addView(makeButton("🎮   "+game,false) { selectedGameCategory=game.uppercase(); buildLiveScreen(); setContentView(liveScreen) },fieldLayout(dp(58),dp(8))) }
        addBottomNav()
    }
    private fun showLivePlayer(streamId: String, streamTitle: String) {
        liveScreen.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            setBackgroundColor(Color.rgb(5, 7, 19))
        }
        root.addView(makeButton("LIVE   " + streamTitle + "     ✕", false) {
            buildLiveScreen()
            setContentView(liveScreen)
        }, fieldLayout(dp(48), dp(8)))
        root.addView(TextView(this).apply {
            text = "Stream video player\n\nViewer playback renderer is not yet connected."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = panelBackground()
        }, LinearLayout.LayoutParams(-1, dp(210)))
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
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f))
        composer.addView(makeButton("Send", true) {
            val message = input.text.toString().trim()
            val uid = userId
            val token = accessToken
            if (message.isNotEmpty() && uid != null && token != null) {
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
                        loadStreamChat(streamId, chat)
                    } catch (error: Exception) {
                        setStatus("Chat send failed: " + (error.message ?: "unknown error"), true)
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(78), dp(50)).apply { leftMargin = dp(6) })
        root.addView(composer, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        liveScreen.addView(root, LinearLayout.LayoutParams(-1, -1))
        loadStreamChat(streamId, chat)
    }

    private fun loadStreamChat(streamId: String, target: LinearLayout) {
        lifecycleScope.launch {
            try {
                val endpoint = "https://ovoufvyevnixpmswfrgl.supabase.co/rest/v1/chat_messages?select=id,user_id,message,created_at&stream_id=eq." + streamId + "&order=created_at.asc&limit=80"
                val rows = withContext(Dispatchers.IO) { getRestJson(endpoint, accessToken) }
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
            } catch (error: Exception) {
                target.addView(TextView(this@MainActivity).apply {
                    text = "Chat unavailable: " + (error.message ?: "network error")
                    setTextColor(Color.LTGRAY)
                })
            }
        }
    }

    private fun showCommunityScreen() {
        liveScreen.removeAllViews(); val sc=ScrollView(this); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(22),dp(16),dp(16)) }
        sc.addView(root); liveScreen.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply { text="‹     Community"; textSize=21f; setTextColor(Color.WHITE) },bottomMargin(dp(18)))
        root.addView(makeButton("👥     Community Hub                      Join",true) { setStatus("Community join service is not connected yet.") },fieldLayout(dp(92),dp(12)))
        root.addView(TextView(this).apply { text="Chat                         Members"; textSize=16f; gravity=Gravity.CENTER; setTextColor(Color.MAGENTA) },bottomMargin(dp(12)))
        listOf("Gaming Lover|Welcome to StreamArena! 🎉|2m ago","Riya Islam|Great platform! ❤️|3m ago","Tanvir Ahmed|Let's grow together! 💪|5m ago").forEach { item ->
            val a=item.split("|"); root.addView(TextView(this).apply { text="👤   "+a[0]+"\n       "+a[1]+"\n       "+a[2]; textSize=14f; setTextColor(Color.WHITE); setPadding(dp(14),dp(14),dp(14),dp(14)); background=panelBackground() },bottomMargin(dp(8)))
        }
        root.addView(EditText(this).apply { hint="Type a message..."; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); background=inputBackground(); setPadding(dp(12),0,dp(12),0) },fieldLayout(dp(50),dp(8))); addBottomNav()
    }
    private fun showProfileScreen() {
        liveScreen.removeAllViews(); val sc=ScrollView(this); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(20),dp(16),dp(18)) }
        sc.addView(root); liveScreen.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply { text="🛒\nOrder"; textSize=25f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },bottomMargin(dp(8)))
        root.addView(TextView(this).apply { text="Your Order List & Purchase History"; textSize=13f; gravity=Gravity.CENTER; setTextColor(Color.LTGRAY) },bottomMargin(dp(14)))
        root.addView(makeButton("View Orders",true) { setStatus("Order history is not connected to the backend yet.") },fieldLayout(dp(48),dp(16)))
        listOf("My Streams","Watch History","Settings","Notifications","Help & Support","About App").forEach { item -> root.addView(makeButton("⚙   "+item+"      ›",false) { if(item=="Settings") showLiveSettings() else setStatus(item+" is not connected yet.") },fieldLayout(dp(48),dp(4))) }
        root.addView(makeButton("Logout",true) { signOut() },fieldLayout(dp(52),dp(16))); addBottomNav()
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
        stopCameraPreview()
        room = null
        accessToken = null
        userId = null
    }
}
