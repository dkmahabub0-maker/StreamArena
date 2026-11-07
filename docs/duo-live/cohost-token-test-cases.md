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
10. Revoke C's accepted invitation while C is connected: the row first becomes `revoking`, new token requests are denied, LiveKit removes C, and only then does the row become `revoked`.
11. Force LiveKit participant removal to fail: verify the row remains `revoking`, the unique co-host slot stays occupied, the host sees a retry action, and a replacement invite is rejected.
12. Revoke while C has an unused token: verify the final conditional token-issuance update and revocation state transition serialize correctly; no token is returned if revocation wins.
13. If C is absent after revocation, verify the row remains `revoking` for at least 70 seconds after the later of `revocation_requested_at` and `last_token_issued_at`; after that, recheck LiveKit and finalize only if C is still absent.
14. After revocation is confirmed, verify a replacement invite can be created and the old invitation cannot mint a new token.
15. After a pending invitation expires, the host can invite a replacement; the old row becomes `expired` and no longer occupies the one-active-invitation index.
16. An accept/decline request racing the expiry timestamp cannot update an expired pending row.
17. Profile search is limited to 30 requests per user per UTC minute; invitation creation is limited to 10 per user per UTC hour. Concurrent requests cannot exceed a bucket limit, and RPC failure fails closed.

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
