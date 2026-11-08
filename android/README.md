# StreamArena Native Android App

This is a separate native Android client workstream. It does not replace the existing website or Chrome PWA.

## Current prototype

- Kotlin Android application module with a branded launcher icon, Android 12+ splash screen, a dedicated authentication screen, and an app-style creator dashboard.
- Authentication screen has explicit Log in and Sign up tabs. When Supabase email confirmation is required, the app accepts the email OTP and verifies it through the Supabase Auth verification endpoint before opening the dashboard.
- The Android UI now follows the supplied reference structure: welcome screen, email login/OTP, dark gaming home, live-player presentation, Go Live setup, screen-share permission flow, Community layout, and Profile/Orders layout. Bottom navigation includes Home, Explore, Create, Community, and Profile.
- Live setup allows Camera Live or Phone Screen Live selection. Camera Live requests camera and microphone access; Phone Screen Live requests microphone access plus Android's MediaProjection consent and publishes the captured display instead of the camera.
- Uses the existing Supabase Auth password sign-in, sign-up, and email verification endpoints.
- Requests a publisher token from the existing `livekit-token` Edge Function for the authenticated user's `stream-<user-id>` room.
- Signing out attempts to mark the active stream offline before the in-memory session is discarded.
- Connects with the LiveKit Android SDK, enables host camera/microphone, and exposes host-only phone screen share start/stop controls.
- Screen capture requires Android's system consent dialog and LiveKit's MediaProjection foreground service.
- Session credentials are kept in memory only; no service-role key or LiveKit API secret is embedded.

## Build

Open the `android/` folder in Android Studio with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:assembleDebug
```

The Android debug build runs from pull requests that modify `android/**`, and can also be started manually from GitHub Actions. On success, the workflow uploads the debug APK as the `streamarena-android-debug` artifact. No signed release APK is produced.

## Important limitations

- This is an early implementation, not a production-ready release. The supplied reference image contains sample names, view counts, and chat messages; those are presentation placeholders, not live user data. Email confirmation may be required after account creation, depending on Supabase Auth settings.
- No release-key-signed APK has been produced or published; the CI artifact is a debug build signed with the Android debug key.
- No physical Android device test has been performed yet.
- Google OAuth, password reset, Orders, Watch History, and Notifications are not yet connected to backend services. The Community screen now reads the signed-in user's existing family memberships and loads/sends messages through `family_messages`; it does not create communities, accept invites, or change membership. The live-player screen requests a viewer token, connects a LiveKit subscriber, attaches subscribed video tracks, and reads/sends messages through the existing `chat_messages` REST resource; playback and both chat paths still require a successful CI build plus real-device and backend/RLS verification. OTP verification requires Supabase email confirmation and a compatible email template. The Profile screen reads and updates the signed-in user's existing `profiles` row (`full_name` and `username`) and verifies the values with a follow-up read; avatar upload and profile-row creation are not implemented. It also lists the signed-in user's streams, tournament registrations, and support tickets using existing RLS-protected tables. Watch history and order history are explicitly unavailable because no corresponding backend tables or tracking flow exist. The Home game catalogue reads the existing `games` REST resource and displays a visible error if it is unavailable.
- The current implementation creates a `streams` row, marks it live, and sends the website's 25-second heartbeat. This path depends on the existing `streams` Data API exposure and owner-scoped RLS policies; no production schema or policy changes are included.
- Internal game audio is intentionally not captured; the first pass publishes screen video plus microphone.
- The user must explicitly approve each Android screen-capture session. Protected content may be blank.

## Platform requirements

- Android 8.0+ (API 26+) baseline.
- Android 14+ requires the media-projection foreground-service permission/type.
- Android 14 QPR2+ may offer app-window or full-display selection; the system selection is respected.

## References

- [LiveKit Android SDK](https://github.com/livekit/client-sdk-android)
- [LiveKit screen sharing](https://docs.livekit.io/transport/media/screenshare/)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
