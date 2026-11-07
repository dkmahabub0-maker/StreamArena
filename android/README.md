# StreamArena Native Android App

This is a separate native Android client workstream. It does not replace the existing website or Chrome PWA.

## Current prototype

- Kotlin Android application module.
- Uses the existing Supabase Auth password sign-in endpoint.
- Requests a publisher token from the existing `livekit-token` Edge Function for the authenticated user's `stream-<user-id>` room.
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

- This is an early prototype, not a production-ready release.
- No APK has been signed or published.
- No physical Android device test has been performed yet.
- Current prototype uses email/password authentication; password reset, OAuth, session refresh, profile setup, and robust token refresh are not yet implemented.
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
