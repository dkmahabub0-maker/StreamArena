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
   - `expires_at > now()`
3. Verify the invitation's `stream_id` still belongs to the host and the stream is live with a fresh heartbeat.
4. Verify the LiveKit room currently contains the host participant. Enforce the single-cohost slot and reject a second co-host.
5. Issue a token with `identity = authenticated user.id`, `roomJoin = true`, `canPublish = true`, `canSubscribe = true`. Keep room administration and room recording privileges disabled. Restrict publish sources to camera and microphone only after confirming the exact `livekit-server-sdk@2.19.2` grant API and testing it against the deployed LiveKit version.
6. Preserve existing publisher/viewer checks unchanged.

## Revocation semantics

Revoking the database invitation prevents future token issuance. Existing LiveKit tokens are not retroactively invalidated; remove the active participant using `RoomServiceClient.removeParticipant(roomName, inviteeUserId)` and handle failure explicitly. Never claim a successful kick unless LiveKit confirms it.

## Security tests required

- Authenticated non-invitee requests `cohost` -> 403.
- Invitee with pending, declined, revoked, or expired invitation -> denied.
- Accepted invite for another host/room -> denied.
- Accepted invite with ended/stale stream -> denied.
- Uninvited account knowing the room name -> denied.
- Host publisher and viewer behavior remain unchanged.
- Co-host cannot join as room admin or publish screen-share tracks.
