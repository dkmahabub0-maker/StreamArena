# Duo Live implementation plan

## Product scope

Duo Live lets a signed-in broadcaster invite exactly one other signed-in player into the same LiveKit room. Both players publish their own camera and microphone tracks. Viewers see two camera tiles in one live stage and hear both participants. Both players play the same game independently; Duo Live does not synchronize game state or share a game session.

## What the current implementation supports

- The browser creates one LiveKit room per broadcaster: `stream-<owner-user-id>`.
- The current client requests only `publisher` and `viewer` roles from the `livekit-token` Edge Function.
- The deployed Edge Function validates the Supabase bearer token and active stream, then issues a LiveKit token whose identity is the authenticated user ID.
- Publisher tokens are restricted to the owner's room. Viewer tokens can subscribe but cannot publish.
- The current broadcaster UI attaches the local camera to the live stage. The viewer flow attaches subscribed media tracks.
- The current production Edge Function is deployed separately from this repository; no source copy is currently maintained in the repository.

## Security blocker before co-host can join

The current token endpoint has no co-host role and no invitation authorization. It must not be changed to let any authenticated user request publisher permission for an arbitrary stream room. That would allow unauthorized users to publish into another person's live session.

The inspected public schema has `streams`, `follows`, `family_invites`, and `tournament_invites`, but no Duo Live invitation/acceptance record. A follow relationship or family membership is not proof that the broadcaster invited someone to co-host.

A secure co-host join therefore requires a persisted, server-verified invitation (or an equivalently strong authorization mechanism). The intended invitation record needs:
- stream owner ID and invited user ID;
- pending / accepted / declined / revoked / expired lifecycle;
- creation and expiry timestamps;
- owner-only invitation creation/revocation and invitee-only acceptance;
- one active co-host maximum per stream;
- server-side checks that the stream is still live and the accepting user matches the invitation.

The Edge Function should issue a co-host token only after verifying the authenticated user and an accepted, unexpired invitation for the exact room owner. The token identity must remain the authenticated user's own ID. It should grant camera/microphone publishing and subscribing, but not screen-share publishing or room administration. The host remains the only participant who can end the stream.

## Implementation phases

1. **Authorization and invitation contract**
   - Add an invitation data model and RLS policies in a reviewed migration.
   - Add secure create/respond/revoke operations; never trust a user ID supplied by the browser as the caller identity.
   - Extend the token Edge Function with a narrowly scoped `cohost` role that verifies an accepted invitation server-side.
2. **Broadcaster controls**
   - Add an Invite Co-host control to the live setup / active broadcast UI.
   - Search/select an existing StreamArena user, send an invitation, show pending/accepted/declined/expired states, and allow the host to revoke or remove the co-host.
3. **LiveKit publishing**
   - Co-host joins the exact host room with the server-issued co-host token.
   - Co-host publishes camera and microphone only; handle permission denial and reconnects.
   - Host and co-host remain distinct LiveKit participants.
4. **Viewer presentation**
   - Render both camera tracks in a responsive two-tile layout; preserve the existing game/screen track if a future supported capture flow is added.
   - Attach both audio tracks, avoid duplicate attachments, and clean up on unsubscribe/disconnect.
   - Show participant names and connection status.
5. **Validation**
   - Automated checks for authorization branches, invitation lifecycle, two-camera rendering, duplicate track attachment, and cleanup.
   - Test with two real signed-in accounts and a third viewer on desktop and mobile before production release.

## Acceptance criteria

- Only the specifically invited and accepting account can obtain a co-host publishing token.
- A third, uninvited account cannot publish even if it knows the room name.
- At most one co-host can be active in a stream.
- Host and co-host camera/microphone tracks appear separately to viewers and both audio tracks are audible.
- Co-host leaving does not end the host's stream; host ending the stream disconnects the session.
- Viewer layout works on narrow mobile screens without hiding either camera.
- No production schema migration, Edge Function deployment, merge, or production deployment occurs without the required review/approval.

## Current boundary

This document records the implementation contract. No production database, RLS, authentication policy, deployed Edge Function, or production deployment has been changed.
