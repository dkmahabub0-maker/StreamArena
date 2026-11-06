# Duo Live co-host token authorization draft

**Status: draft only. This file is not deployed.** The source in `supabase/functions/livekit-token/index.ts` is based on the currently deployed `livekit-token` v6 source inspected on 2026-11-06. It preserves the existing `publisher` and `viewer` paths and adds a narrowly scoped `cohost` path.

## Authorization checks

For `role: "cohost"`, the server:
1. Authenticates the caller by validating their Supabase bearer token with `/auth/v1/user`. The caller ID is never accepted from the request body.
2. Derives the host ID only from the requested `stream-<host UUID>` room name.
3. Uses the server-only `SUPABASE_SERVICE_ROLE_KEY` to confirm a live stream row owned by that host, with `is_live=true` and a heartbeat newer than 120 seconds.
4. Requires exactly an `accepted` row in `stream_cohost_invites` matching the exact stream ID, host ID, and authenticated invitee ID.
5. Uses the LiveKit RoomService API to confirm the host participant is still connected and publishing at least one track in that exact room.
6. Issues a 60-second token scoped to that one room. It permits publishing only `camera` and `microphone`, subscribing to other tracks, and explicitly disables data publishing. It grants no room-admin, recording, screen-share, or room-creation permission.

The 60-second token expiry limits the period in which a previously issued but unused token could be used after an invitation is revoked. LiveKit token expiry applies to the initial connection; a connected participant is removed by the invitation revoke action. Revocation still depends on successful server-side participant removal; if that fails, the UI must surface the failure and the host should end the Live if necessary.

## Required deployment prerequisites

- Review and apply the separate draft SQL schema in `docs/duo-live/stream_cohost_invites_draft.sql` through an approved migration. It is not in `supabase/migrations` and has not been applied to production.
- Deploy the `duo-live-invites` Edge Function from the matching reviewed branch.
- Ensure `SUPABASE_SERVICE_ROLE_KEY` is configured as a server-side Edge Function secret. It must never be exposed to the browser.
- Preserve `verify_jwt=false` for this function because the current function performs explicit bearer-token validation itself. The request handler still rejects missing/invalid user sessions.
- Deploy the replacement `livekit-token` function only after reviewing the diff against the live v6 function and testing with two signed-in accounts plus a separate viewer.

## Not enabled by this PR

No production migration, secret change, Edge Function deployment, LiveKit server setting, website deployment, or PR merge has been performed. The frontend does not yet connect the co-host to the room or render two camera tiles. This PR only prepares the server-side authorization path.

## Validation and limitations

The Deno type-check workflow validates the function source. It does not prove the production secret is configured, the migration is applied, the LiveKit Cloud room behavior, or end-to-end co-host publishing. Those require an approved staging deployment and a two-account integration test.
