# StreamArena Native Android App

This is a separate native Android client workstream. It does not replace the existing website or Chrome PWA.

## Current prototype

- Kotlin Android application module with a branded launcher icon, Android 12+ splash screen, a dedicated authentication screen, and an app-style creator dashboard.
- Authentication screen has explicit Log in and Sign up tabs. When Supabase email confirmation is required, the app accepts the email OTP and verifies it through the Supabase Auth verification endpoint before opening the dashboard.
- The dashboard includes a hero banner, sample game categories, creator shortcuts, and a fixed bottom navigation bar (Home, +, Notifications, Community, Menu). The + action reveals the live setup panel.
- Live setup allows Camera Live or Phone Screen Live selection; Screen Live requests Android's MediaProjection permission after the stream connects.
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

- This is an early prototype, not a production-ready release. Email confirmation may be required after account creation, depending on the Supabase Auth project settings.
- No release-key-signed APK has been produced or published; the CI artifact is a debug build signed with the Android debug key.
- No physical Android device test has been performed yet.
- This remains a prototype. Password reset, OAuth, session refresh, profile setup, and robust token refresh are not implemented. OTP verification requires Supabase email confirmation to be enabled and the email template to deliver a usable OTP code. Notifications, Community, and Menu are navigation placeholders, not connected product features; game categories are sample UI only.
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
