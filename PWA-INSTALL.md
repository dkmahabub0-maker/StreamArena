# Installing StreamArena as a PWA

The installable PWA flow is targeted at **Google Chrome on Android and desktop**. StreamArena remains a website; this does not create a native app or publish anything to an app store.

## Android (Chrome)
1. Open the StreamArena HTTPS website in Chrome.
2. Wait for Chrome to offer installation; the StreamArena **Install App** button appears only when Chrome emits its install prompt.
3. Select **Install App** and confirm in Chrome, or use Chrome's menu and choose **Install app** if offered.

## Desktop (Chrome)
1. Open StreamArena in Chrome.
2. Use the install icon in the address bar or Chrome's menu option if shown.

## Not included
- Chrome on iOS does not expose the same Android/desktop PWA install-prompt flow. iOS installation is not part of this Chrome-targeted task.
- No native Android/iOS application or app-store publishing is included.
- Live video, chat, sign-in and streaming still require an internet connection.
- Offline support is limited to a simple offline notice and the query-free root app shell. The service worker does not cache query-bearing auth callbacks, private account data, API responses, chat, or LiveKit media.
- A PWA cannot guarantee background live broadcasting or capture another app/game's screen.


## Verification note

Installation visibility depends on the browser's installability checks and a successful HTTPS deployment. A browser may show its own Install app option even when the in-page prompt button is unavailable. The PWA does not provide native Android screen capture or background broadcasting.
