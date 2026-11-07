import { RoomServiceClient } from "npm:livekit-server-sdk@2.19.2";

const productionOrigin = "https://streamarena-nu.vercel.app";
const exactOrigins = new Set([
  productionOrigin,
  "http://localhost:3000",
  "http://127.0.0.1:5500",
]);
const previewOrigin = /^https:\/\/streamarena(?:-[a-z0-9-]+)?\.vercel\.app$/i;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function allowedOrigin(origin: string | null): boolean {
  return !origin || exactOrigins.has(origin) || previewOrigin.test(origin);
}

function headers(origin: string | null) {
  return {
    "Access-Control-Allow-Origin": origin && allowedOrigin(origin) ? origin : productionOrigin,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
    "Content-Type": "application/json",
  };
}

function reply(data: unknown, status: number, origin: string | null) {
  return new Response(JSON.stringify(data), { status, headers: headers(origin) });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

async function readJson(response: Response) {
  const text = await response.text();
  if (!response.ok) {
    const error = new Error(`Supabase request failed (${response.status}): ${text.slice(0, 300)}`) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  return text ? JSON.parse(text) : null;
}

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("origin");
  if (!allowedOrigin(origin)) return reply({ error: "Origin not allowed" }, 403, origin);
  if (req.method === "OPTIONS") return new Response("ok", { headers: headers(origin) });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405, origin);

  try {
    const authHeader = req.headers.get("authorization") ?? "";
    const bearer = authHeader.match(/^Bearer\s+(.+)$/i)?.[1];
    if (!bearer) return reply({ error: "A valid sign-in session is required" }, 401, origin);

    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const publishableKey = Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? Deno.env.get("SUPABASE_ANON_KEY");
    if (!publishableKey) throw new Error("Missing Supabase publishable key");
    const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");

    // Authenticate the caller independently; never trust a user ID from the body.
    const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: { apikey: publishableKey, Authorization: `Bearer ${bearer}` },
    });
    if (!userResponse.ok) return reply({ error: "Your sign-in session is invalid or expired" }, 401, origin);
    const user = await userResponse.json();
    if (typeof user?.id !== "string") return reply({ error: "Unable to identify signed-in user" }, 401, origin);

    const body = await req.json().catch(() => null);
    const action = body?.action;
    const restHeaders = {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    };
    const rest = async (path: string, method = "GET", payload?: unknown) => {
      const response = await fetch(`${supabaseUrl}/rest/v1/${path}`, {
        method,
        headers: restHeaders,
        body: payload === undefined ? undefined : JSON.stringify(payload),
      });
      return await readJson(response);
    };
    const consumeRateLimit = async (actionName: "search" | "invite", windowMs: number, limit: number) => {
      const windowStartedAt = new Date(Math.floor(Date.now() / windowMs) * windowMs).toISOString();
      const allowed = await rest("rpc/consume_stream_cohost_rate_limit", "POST", {
        p_user_id: user.id,
        p_action: actionName,
        p_window_started_at: windowStartedAt,
        p_limit: limit,
      });
      return allowed === true;
    };

    const freshAfter = new Date(Date.now() - 120_000).toISOString();
    const activeStreamFor = async (ownerId: string) => {
      const params = new URLSearchParams({
        select: "id,user_id",
        user_id: `eq.${ownerId}`,
        is_live: "eq.true",
        last_heartbeat_at: `gt.${freshAfter}`,
        limit: "1",
      });
      const rows = await rest(`streams?${params}`);
      return Array.isArray(rows) ? rows[0] ?? null : null;
    };

    if (action === "invite") {
      const inviteeUserId = typeof body?.inviteeUserId === "string" ? body.inviteeUserId : "";
      if (!uuidPattern.test(inviteeUserId) || inviteeUserId === user.id) {
        return reply({ error: "Choose another valid StreamArena user" }, 400, origin);
      }
      if (!await consumeRateLimit("invite", 60 * 60_000, 10)) {
        return reply({ error: "You have reached the Duo Live invite limit. Try again in the next hour." }, 429, origin);
      }
      const stream = await activeStreamFor(user.id);
      if (!stream) return reply({ error: "Start your Live before inviting a co-host" }, 409, origin);
      // Expired pending invitations must release the stream's single co-host slot.
      const expiredBefore = new Date().toISOString();
      await rest(
        `stream_cohost_invites?stream_id=eq.${stream.id}&host_user_id=eq.${user.id}&status=eq.pending&expires_at=lte.${encodeURIComponent(expiredBefore)}`,
        "PATCH",
        { status: "expired" },
      );

      const profiles = await rest(`profiles?select=id&id=eq.${inviteeUserId}&limit=1`);
      if (!Array.isArray(profiles) || profiles.length !== 1) {
        return reply({ error: "That StreamArena user was not found" }, 404, origin);
      }
      const rows = await rest("stream_cohost_invites", "POST", {
        stream_id: stream.id,
        host_user_id: user.id,
        invitee_user_id: inviteeUserId,
        status: "pending",
        expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
      });
      return reply({ invitation: rows?.[0] ?? null }, 201, origin);
    }

    const invitationId = typeof body?.invitationId === "string" ? body.invitationId : "";
    if (!uuidPattern.test(invitationId)) {
      return reply({ error: "A valid invitation ID is required" }, 400, origin);
    }
    const inviteRows = await rest(
      `stream_cohost_invites?select=id,stream_id,host_user_id,invitee_user_id,status,expires_at,revocation_requested_at,last_token_issued_at&id=eq.${invitationId}&limit=1`,
    );
    const invite = Array.isArray(inviteRows) ? inviteRows[0] : null;
    if (!invite) return reply({ error: "Invitation not found" }, 404, origin);

    if (action === "respond") {
      if (invite.invitee_user_id !== user.id || invite.status !== "pending") {
        return reply({ error: "This invitation cannot be changed by this account" }, 403, origin);
      }
      if (Date.parse(invite.expires_at) <= Date.now()) {
        const expiredAt = new Date().toISOString();
        await rest(
          `stream_cohost_invites?id=eq.${invitationId}&invitee_user_id=eq.${user.id}&status=eq.pending&expires_at=lte.${encodeURIComponent(expiredAt)}`,
          "PATCH",
          { status: "expired" },
        );
        return reply({ error: "This invitation has expired" }, 410, origin);
      }
      if (body?.decision !== "accept" && body?.decision !== "decline") {
        return reply({ error: "Decision must be accept or decline" }, 400, origin);
      }
      const stream = await activeStreamFor(invite.host_user_id);
      if (body.decision === "accept" && (!stream || stream.id !== invite.stream_id)) {
        return reply({ error: "The host's Live is no longer active" }, 409, origin);
      }
      const acceptBefore = new Date().toISOString();
      const updated = await rest(
        `stream_cohost_invites?id=eq.${invitationId}&invitee_user_id=eq.${user.id}&status=eq.pending&expires_at=gt.${encodeURIComponent(acceptBefore)}`,
        "PATCH",
        { status: body.decision === "accept" ? "accepted" : "declined", accepted_at: body.decision === "accept" ? new Date().toISOString() : null },
      );
      if (!Array.isArray(updated) || updated.length !== 1) {
        return reply({ error: "Invitation changed before your response; refresh and try again" }, 409, origin);
      }
      return reply({ invitation: updated[0] }, 200, origin);
    }

    if (action === "revoke") {
      if (invite.host_user_id !== user.id || !["pending", "accepted", "revoking"].includes(invite.status)) {
        return reply({ error: "Only the host can revoke an active invitation" }, 403, origin);
      }

      if (invite.status === "pending") {
        const revoked = await rest(
          `stream_cohost_invites?id=eq.${invitationId}&host_user_id=eq.${user.id}&status=eq.pending`,
          "PATCH",
          { status: "revoked", revocation_completed_at: new Date().toISOString() },
        );
        if (!Array.isArray(revoked) || revoked.length !== 1) {
          return reply({ error: "Invitation changed before it could be revoked" }, 409, origin);
        }
        return reply({ invitation: revoked[0], participantRemoved: true, revocationPending: false }, 200, origin);
      }

      let revoking = invite;
      if (invite.status === "accepted") {
        const marked = await rest(
          `stream_cohost_invites?id=eq.${invitationId}&host_user_id=eq.${user.id}&status=eq.accepted`,
          "PATCH",
          { status: "revoking", revocation_requested_at: new Date().toISOString(), revocation_completed_at: null },
        );
        if (!Array.isArray(marked) || marked.length !== 1) {
          return reply({ error: "Invitation changed before revocation could start" }, 409, origin);
        }
        revoking = marked[0];
      }

      const requestedAt = Math.max(
        Date.parse(revoking.revocation_requested_at || "") || 0,
        Date.parse(revoking.last_token_issued_at || "") || 0,
      );
      if (!requestedAt) {
        return reply({ invitation: revoking, participantRemoved: false, revocationPending: true,
          error: "Revocation is pending but its start time is missing. Keep this co-host slot reserved and retry after review." }, 202, origin);
      }

      try {
        const livekit = new RoomServiceClient(
          requiredEnv("LIVEKIT_URL").replace(/^wss:/i, "https:"),
          requiredEnv("LIVEKIT_API_KEY"),
          requiredEnv("LIVEKIT_API_SECRET"),
        );
        const roomName = `stream-${user.id}`;
        const participants = await livekit.listParticipants(roomName);
        const participantPresent = participants.some((participant) => participant.identity === invite.invitee_user_id);

        if (participantPresent) {
          // LiveKit removes the active participant and revokes its current token.
          await livekit.removeParticipant(roomName, invite.invitee_user_id);
        } else if (Date.now() < requestedAt + 70_000) {
          // A previously issued 60-second token may still be unused. Keep the
          // partial-unique-index slot reserved until its expiry plus clock skew.
          return reply({ invitation: revoking, participantRemoved: false, revocationPending: true,
            retryAfterMs: requestedAt + 70_000 - Date.now() }, 202, origin);
        }

        const completed = await rest(
          `stream_cohost_invites?id=eq.${invitationId}&host_user_id=eq.${user.id}&status=eq.revoking`,
          "PATCH",
          { status: "revoked", revocation_completed_at: new Date().toISOString() },
        );
        if (!Array.isArray(completed) || completed.length !== 1) {
          return reply({ invitation: revoking, participantRemoved: false, revocationPending: true,
            error: "Participant removal was attempted, but the invitation state could not be finalized. Retry safely." }, 202, origin);
        }
        return reply({ invitation: completed[0], participantRemoved: true, revocationPending: false }, 200, origin);
      } catch (error) {
        console.error("Duo Live participant removal is pending:", error instanceof Error ? error.message : "unknown error");
        return reply({ invitation: revoking, participantRemoved: false, revocationPending: true,
          error: "LiveKit could not confirm participant removal. The co-host slot remains reserved; retry removal." }, 202, origin);
      }
    }

    return reply({ error: "Unsupported action" }, 400, origin);
  } catch (error) {
    const status = typeof error === "object" && error !== null && "status" in error ? Number(error.status) : 0;
    console.error("Duo Live invitation request failed:", error instanceof Error ? error.message : "unknown error");
    if (status === 409) return reply({ error: "A pending or accepted co-host invitation already exists for this Live" }, 409, origin);
    return reply({ error: "Unable to process Duo Live invitation" }, 500, origin);
  }
});
