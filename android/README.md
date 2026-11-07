# StreamArena Native Android App — implementation plan

## Goal

Add a native Android client so a signed-in host can broadcast the Android display or a selected app window to the existing StreamArena Live room. The website remains available for viewers and desktop broadcasters.

This is a native Android app workstream, separate from the Chrome-installable PWA. A browser-only PWA cannot provide the Android `MediaProjection` capture path.

## Phase 1 — safe foundation (this PR)

- Document the native architecture and platform constraints.
- Keep all changes isolated under `android/`; do not alter the production website, Supabase schema, Edge Functions, LiveKit server settings, or existing PWA.
- Define the first release as Android host broadcast + camera/microphone + explicit screen-share start/stop. Web viewers continue watching the same LiveKit room.
- Use the existing authenticated `livekit-token` publisher flow. The app must obtain a short-lived token from the trusted backend after authenticating the user; never embed Supabase service-role or LiveKit API secrets in the APK.
- Use LiveKit Android SDK v2 and Android `MediaProjectionManager` consent UI. A host must explicitly approve every capture session.
- Require a foreground service with the `mediaProjection` service type while capture is active. Stop capture and release resources when the user stops sharing, Android revokes the projection, the Live ends, or the process/session is torn down.
- Never enable screen publishing for viewers. Co-host authorization remains governed by the separate Duo Live server-side role grant; this app must not bypass it.

## Phase 2 — implementation

1. Create a Kotlin Android application module and configure the Android/LiveKit dependencies.
2. Add sign-in/session handling compatible with the existing Supabase Auth project without shipping privileged keys.
3. Add host Live creation and request the existing publisher token for room `stream-<authenticated-user-id>`.
4. Connect the LiveKit Android Room and publish camera/microphone.
5. Add a host-only “Share phone screen” action. Launch `MediaProjectionManager.createScreenCaptureIntent()`, pass the approved result to LiveKit `setScreenShareEnabled(true, data)`, and provide a visible stop action.
6. Implement foreground-service lifecycle, Android 14+ service-type requirements, projection-stop callback, orientation/size changes, reconnect handling, and cleanup.
7. Reuse the same room naming and LiveKit publication model so existing web viewers can subscribe to the host's screen-share track.
8. Add CI checks and run a real Android build before calling the app installable.

## Security and privacy requirements

- Capture is host-initiated and requires Android's system consent dialog each time.
- Viewers receive subscribe-only permissions; they never get a screen-publishing control or token.
- Do not capture or upload screen content before explicit consent.
- Display a clear in-app sharing state and an immediate stop control. Respect Android's system screen-capture indicator/stop action.
- Do not persist screen recordings. The first implementation is live streaming only.
- Do not request Accessibility Service, overlay, or unrelated permissions for screen capture.
- Treat the Android APK as an untrusted client; enforce roles and room access in the existing token backend.

## Platform constraints

- Android 14+ may let the user choose one app window or the entire display; the app must respect the system selection.
- Each projection session requires fresh user consent. Android 14+ also enforces one-use projection tokens and foreground-service ordering.
- Protected/DRM content and some secure windows may appear black or be excluded by Android.
- Internal game audio is a separate, optional capability and is not part of Phase 1. Start with screen video plus the host microphone to avoid silently capturing device audio.
- This repository work does not create an APK, sign a release, publish to Google Play, or prove device compatibility. Those require an Android SDK build/signing setup and physical-device tests.

## Acceptance tests before release

- Host can start a Live, grant screen capture, and stream the selected app/display to an existing web viewer.
- Viewer can see the host screen and cannot initiate or publish screen sharing.
- Host can stop sharing without ending the Live; the screen track disappears.
- Android system stop/revoke, app backgrounding, orientation change, network loss, and Live end all release capture resources safely.
- Camera/microphone and screen video can coexist without duplicate audio or leaked projection sessions.
- Test on at least one Android 14+ device and one older supported Android version; do not claim success from static checks alone.

## References

- Android MediaProjection guide: https://developer.android.com/media/grow/media-projection
- Android MediaProjectionManager API: https://developer.android.com/reference/android/media/projection/MediaProjectionManager
- LiveKit screen-sharing guide: https://docs.livekit.io/transport/media/screenshare/
- LiveKit Android SDK: https://github.com/livekit/client-sdk-android

## Current status

Planning/foundation only. No Android app module, APK, production integration, backend permission change, or device test is included in this first phase.
