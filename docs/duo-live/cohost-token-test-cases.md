# Duo Live co-host token integration test plan

These tests require a staging project with the reviewed invitation migration and both Edge Functions deployed. They have **not** been run against production.

## Accounts
- Host account H starts a Live and publishes camera + microphone.
- Invitee account C accepts H's invitation.
- Uninvited account U remains signed in.
- Viewer account V watches the same Live.

## Authorization cases
1. C requests `role=cohost` for H's exact room after accepting: token is issued.
2. U requests `role=cohost` for H's room: reject (no accepted invitation).
3. C requests a different host's room: reject.
4. C requests H's room before accepting: reject.
5. C requests after H ends the Live or heartbeat becomes stale: reject.
6. C requests while H has no publishing participant in the room: reject.
7. H requests `role=cohost` for H's own room: reject.
8. A request with missing/invalid bearer token: reject.
9. A request attempting a different role or malformed room name: reject.
10. Revoke C's accepted invitation while C is connected: verify the revoke endpoint removes C from LiveKit; if removal fails, surface the failure and end the Live.
11. A previously issued but unused co-host token after revocation: verify its short 60-second initial-connection window has elapsed and the old invitation cannot mint a new token.

## Media permission cases
- C can publish camera and microphone.
- C can subscribe to H's camera/microphone.
- C cannot publish screen share or screen-share audio.
- C cannot publish data, record, administer, or create rooms.
- Existing H publisher and V viewer token flows still work unchanged.

## User experience
- Test two different mobile devices and a third viewer device.
- Verify both camera tiles render, audio from both users is audible, and mute/camera-off controls affect only the local participant.
- Test network loss, reconnect, host ending Live, invite revoke, and browser background/foreground transitions.

Do not mark Duo Live ready until all authorization and media cases pass.
