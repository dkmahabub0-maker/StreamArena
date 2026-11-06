# Duo Live invitation and token authorization draft

This is a code-review draft, not a production migration or deployment.

## Files in this draft

- `docs/duo-live/stream_cohost_invites_draft.sql`: invitation table and access boundary.
- `supabase/functions/duo-live-invites/index.ts`: authenticated invite/respond/revoke endpoint.
- `docs/duo-live/livekit-token-cohost-authorization.md`: required change to the separately deployed `livekit-token` endpoint.

## Authorization model

1. The caller is authenticated against `/auth/v1/user`; body-supplied user IDs are never treated as caller identity.
2. Invitation creation is restricted to the active stream owner and a real `profiles.id`.
3. Invitees alone can accept/decline; only the host can revoke.
4. Pending invitations expire after 10 minutes. Once accepted, the co-host remains authorized until revoked or the host's stream ends. A partial unique index permits at most one pending or accepted co-host per stream.
5. The invite table has RLS enabled and no browser-role table grants. Only a server-side Edge Function with `SUPABASE_SERVICE_ROLE_KEY` can read/write it. The service key must never reach the browser.
6. A co-host token must be issued only after checking the accepted invitation for the exact host room and authenticated invitee, and verifying that the host's stream remains live.

## Important implementation caveats to resolve before production

- The current deployed `livekit-token` function is separate from this repository. The source here is a draft integration specification, not a replacement or deployment.
- Configure `SUPABASE_SERVICE_ROLE_KEY` as an Edge Function secret before deploying the invitation endpoint. Do not store it in Vercel/browser config.
- The revoke endpoint first marks the invitation revoked and then attempts LiveKit participant removal. If LiveKit removal fails, the response reports `participantRemoved: false`; an accepted participant may remain connected until removed or disconnected. Production UX must surface this state and retry removal safely.
- The invitation API currently accepts an invitee UUID. The UI must obtain that UUID from an authenticated profile search and display a stable username before sending.
- A single active invitation per stream is enforced in Postgres, so simultaneous invite requests cannot create multiple co-host slots.
- Add rate limiting and abuse monitoring before public rollout.
- Test two signed-in accounts plus a third viewer; include pending expiry, replay, unauthorized IDs, concurrent invite, host end, co-host disconnect/reconnect, and revoke failure cases.

## Not implemented yet

- Frontend friend search and invite inbox.
- LiveKit co-host join/publish controls and two-camera viewer layout.
- Automated integration tests against an isolated Supabase project.
- Production migration, Edge Function deployment, merge, or Vercel deployment.
