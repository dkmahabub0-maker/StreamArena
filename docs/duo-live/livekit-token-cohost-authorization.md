# Required `livekit-token` co-host authorization change

The production function currently validates the bearer token manually and permits only `publisher` and `viewer`. Keep that existing session validation. Do not enable arbitrary room publishing.

## Request contract

Accept `role: "cohost"` only with a valid `roomName = stream-<host UUID>`. Resolve the host UUID from the validated room name, not from a second body field.

## Required server-side checks before minting a token

1. Read `SUPABASE_SERVICE_ROLE_KEY` only inside the Edge Function.
2. Query `stream_cohost_invites` for all of:
   - `host_user_id = <room owner>`
   - `invitee_user_id = <authenticated user.id>`
   - `status = 'accepted'`
   - The invitation's `stream_id` matches the host's current live stream.
   - `expires_at` is checked only while the invitation is pending. Once accepted, the co-host authorization remains active until revoked or the stream ends.
3. Verify the stream is live with a fresh heartbeat.
4. Before returning a token, conditionally update `last_token_issued_at` on the exact invitation row with `status = 'accepted'`, host ID, invitee ID, and stream ID. If that update returns no row, revocation or another state change won the race; discard the minted-but-undelivered token and return 403.
5. Verify the LiveKit room currently contains the host participant. Enforce the single-cohost slot and reject a second co-host.
6. Issue a token with `identity = authenticated user.id`, `roomJoin = true`, `canPublish = true`, `canSubscribe = true`, `canPublishData = false`, and `canPublishSources = ["camera", "microphone"]`. LiveKit's v2.19.2 `VideoGrant.canPublishSources` explicitly supersedes `canPublish` and limits the allowed track sources. Do not grant room administration, recording, screen-share, or screen-share-audio permissions. See the [LiveKit VideoGrant reference](https://docs.livekit.io/reference/server-sdk-js/interfaces/VideoGrant.html).
7. Preserve existing publisher/viewer checks unchanged.

## Revocation semantics

Revocation first atomically changes `accepted` to `revoking`, which immediately blocks future token issuance while preserving the unique co-host slot. Remove the active participant using `RoomServiceClient.removeParticipant(roomName, inviteeUserId)` and handle failure explicitly. A failed removal must leave the invitation in `revoking`; do not mark it `revoked` or free the slot. If the participant is absent, retain the slot until 70 seconds after the later of `revocation_requested_at` and `last_token_issued_at`, then recheck LiveKit and finalize only if the participant is still absent. LiveKit Cloud documents that this operation also revokes the participant's current access token; if the participant has already left, the API can still revoke the token while returning a participant-not-found error. See the [LiveKit participant management documentation](https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/). Never claim a successful kick unless the API outcome is understood and handled.

## Security tests required

- Authenticated non-invitee requests `cohost` -> 403.
- Invitee with pending, declined, revoked, or expired-pending invitation -> denied.
- Accepted invite for another host/room -> denied.
- Accepted invite with ended/stale stream -> denied.
- Uninvited account knowing the room name -> denied.
- Host publisher and viewer behavior remain unchanged.
- Co-host can publish camera and microphone but cannot publish screen-share or screen-share-audio.
- Co-host has no room-admin or room-record grants.


## Token/revocation race

The token endpoint mints the token, then performs a conditional `PATCH` on the exact invitation row where `status = 'accepted'`. It records `last_token_issued_at` and returns the token only if exactly one row is updated. A revoke request that changes the row to `revoking` before this final update prevents the token from being returned. If the token update wins first, the later revocation timestamp covers the token's 60-second TTL plus 10 seconds of clock skew.
