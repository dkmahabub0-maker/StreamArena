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
        otpInput = EditText(this).apply {
            hint = "6-digit email code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 126, 153))
            background = inputBackground()
            setPadding(dp(15), 0, dp(15), 0)
            visibility = android.view.View.GONE
        }
        authCard.addView(fieldLabel("Email confirmation code").apply {
            visibility = android.view.View.GONE
            tag = "otp_label"
        }, bottomMargin(dp(8)))
        authCard.addView(otpInput, fieldLayout(dp(54), dp(10)).apply { })
        otpVerifyButton = makeButton("Verify code", true) { verifySignupOtp() }.apply {
            visibility = android.view.View.GONE
        }
        authCard.addView(otpVerifyButton, fieldLayout(dp(52), dp(10)))
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
        liveScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 29))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(root)
        liveScreen.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

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
        lateinit var menuButton: Button
        menuButton = makeButton("⋮", false) { showDashboardMenu(menuButton) }.apply {
            textSize = 32f
            contentDescription = "More options"
            setPadding(0, 0, 0, 0)
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(52), dp(46)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(30)
        })
        val topNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(6), dp(5), dp(6))
            setBackgroundColor(Color.rgb(13, 21, 38))
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 21, 38))
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(35, 53, 82))
            }
        }
        fun navItem(label: String, action: () -> Unit) {
            val button = makeButton(label, false, action).apply {
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(1), 0, dp(1), 0)
                isAllCaps = false
                maxLines = 2
            }
            topNav.addView(button, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        navItem("⌂\nHome") { scroll.smoothScrollTo(0, 0) }
        plusNavButton = makeButton("＋", true) {
            openLiveSetup("camera", scroll)
        }.apply {
            textSize = 29f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            contentDescription = "Create live or screen share"
        }
        topNav.addView(plusNavButton, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        navItem("♧\nNotifications") { setStatus("Notifications will appear here when the notification service is connected.") }
        navItem("◎\nCommunity") { setStatus("Community feed is not connected yet.") }
        // Bottom navigation is attached below the scroll area; the overflow menu stays in the header.

        root.addView(TextView(this).apply {
            text = "FEATURED GAMES"
            textSize = 13f
            setTextColor(Color.rgb(128, 190, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, bottomMargin(dp(10)))

        val featuredGamesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val featuredGames = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val featured = listOf(
            Triple("ACTION GAMES", "Fast-paced action picks", intArrayOf(Color.rgb(20, 105, 145), Color.rgb(30, 54, 105))),
            Triple("BATTLE GAMES", "Competitive battle zone", intArrayOf(Color.rgb(132, 48, 62), Color.rgb(65, 35, 83))),
            Triple("SPORTS GAMES", "Sports and tournaments", intArrayOf(Color.rgb(24, 119, 91), Color.rgb(23, 62, 84))),
            Triple("RACING GAMES", "Speed and racing picks", intArrayOf(Color.rgb(170, 91, 30), Color.rgb(88, 43, 75)))
        )
        featured.forEach { item ->
            val gameBanner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, item.third).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.argb(90, 190, 215, 255))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedGameCategory = item.first.substringBefore(' ').uppercase()
                    setStatus("${item.first} selected.")
                }
            }
            gameBanner.addView(TextView(this).apply {
                text = "▶"
                textSize = 25f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }, bottomMargin(dp(8)))
            gameBanner.addView(TextView(this).apply {
                text = item.first
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            gameBanner.addView(TextView(this).apply {
                text = item.second
                textSize = 11f
                setTextColor(Color.rgb(226, 235, 248))
            })
            featuredGames.addView(gameBanner, LinearLayout.LayoutParams(dp(270), dp(158)).apply {
                rightMargin = dp(10)
            })
        }
        featuredGamesScroll.addView(featuredGames)
        root.addView(featuredGamesScroll, bottomMargin(dp(14)))

        // Reserved, intentionally empty ad slot for a future horizontal mobile banner.
        val adSlot = TextView(this).apply {
            text = "AD SPACE"
            textSize = 9f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(92, 107, 132))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(12, 19, 33))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(35, 48, 70))
            }
        }
        root.addView(adSlot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { bottomMargin = dp(22) })

        root.addView(TextView(this).apply {
            text = "GAME CATEGORIES"
            textSize = 13f
            setTextColor(Color.rgb(128, 190, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, bottomMargin(dp(10)))

        val categoryRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ACTION", "BATTLE").forEach { category ->
            categoryRowOne.addView(makeButton(category, selectedGameCategory == category) {
                selectedGameCategory = category
                setStatus("$category category selected.")
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(3), 0, dp(3), dp(8))
            })
        }
        root.addView(categoryRowOne)
        val categoryRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("SPORTS", "RACING").forEach { category ->
            categoryRowTwo.addView(makeButton(category, selectedGameCategory == category) {
                selectedGameCategory = category
                setStatus("$category category selected.")
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(3), 0, dp(3), dp(8))
            })
        }
        root.addView(categoryRowTwo, bottomMargin(dp(20)))

        root.addView(TextView(this).apply {
            text = "Welcome to your creator space."
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Manage your broadcast, screen sharing and creator tools from one place."
            textSize = 14f
            setTextColor(Color.rgb(166, 178, 202))
        }, bottomMargin(dp(22)))

        liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = android.view.View.GONE
        }
        liveCard.addView(TextView(this).apply {
            text = "CREATE A LIVE"
            textSize = 12f
            setTextColor(Color.rgb(115, 205, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, bottomMargin(dp(14)))
        liveCard.addView(TextView(this).apply {
            text = "Choose how you want to broadcast"
            textSize = 14f
            setTextColor(Color.rgb(197, 207, 225))
            typeface = Typeface.DEFAULT_BOLD
        }, bottomMargin(dp(10)))
        cameraPreviewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "Front camera preview"
        }
        liveCard.addView(cameraPreviewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(210)
        ).apply { bottomMargin = dp(14) })
        val liveModes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        liveModes.addView(makeButton("Camera", true) {
            selectedLiveMode = "camera"
            startLiveButton.text = "Go Live"
            showCameraPreview()
            setStatus("Camera selected. Preview is local; tap Go Live when ready to broadcast.")
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        liveModes.addView(makeButton("Screen Share", false) {
            selectedLiveMode = "screen"
            startLiveButton.text = "Go Live with Screen Share"
            stopCameraPreview()
            setStatus("Screen Share selected. Tap Go Live and approve Android's screen-capture prompt.")
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        liveCard.addView(liveModes, bottomMargin(dp(16)))
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
        startLiveButton = makeButton("Go Live", true) { startLive() }
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

        val bottomBanner = TextView(this).apply {
            text = "READY WHEN YOU ARE  •  Set your title, start your stream and connect with your viewers."
            textSize = 12f
            setTextColor(Color.rgb(215, 228, 248))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 42, 70))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(48, 75, 112))
            }
        }
        root.addView(bottomBanner, bottomMargin(dp(14)))
        root.addView(TextView(this).apply {
            text = "Screen sharing starts only after you approve Android's system prompt."
            textSize = 12f
            setTextColor(Color.rgb(120, 133, 158))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        // Keep navigation fixed at the bottom while dashboard content scrolls above it.
        liveScreen.addView(topNav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Lift the bottom navigation slightly without changing icon or button sizes.
            bottomMargin = dp(8)
        })
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
