# Host Live session recovery

The host recovery path is client-side and reuses the existing stream row. It does not create a second stream when the in-memory host session is still active.

- While the host page is active, the heartbeat updates the exact stream row every 25 seconds and verifies that the row is still marked live.
- If the exact row is no longer live, the client stops the heartbeat, disconnects the old room, and clears the stale local session instead of continuing to show an already-live state.
- On page visibility return, pageshow, or window focus, an in-memory host session checks its exact stream row and attempts recovery.
- A connected room refreshes the heartbeat and reacquires camera/microphone only when the corresponding local track ended and the user had it enabled.
- A disconnected room is replaced with a new authenticated publisher connection for the same room. The heartbeat is refreshed before requesting a token so the existing active-stream freshness check can pass.
- If a full page reload cleared the in-memory stream ID, pressing Go Live searches for a fresh active stream owned by the signed-in user and offers a manual Resume Live action. It does not automatically restart camera or microphone after reload.
- Ending a stream sets a stop guard before disconnecting so the disconnect event cannot restart a stream the host intentionally ended.
- If the database row is no longer live, the client disconnects and clears the stale local session instead of attempting to republish.

This change does not alter database schema, RLS, authentication policy, server-side token authorization, or production data. It is statically validated only; browser background/foreground, network handoff, camera reacquisition, and two-device streaming still require real-device testing.
