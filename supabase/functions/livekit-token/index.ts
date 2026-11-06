import { AccessToken, RoomServiceClient } from "npm:livekit-server-sdk@2.19.2";

const productionOrigin = "https://streamarena-nu.vercel.app";
const exactOrigins = new Set([
  productionOrigin,
  "http://localhost:3000",
  "http://127.0.0.1:5500",
]);
const previewOrigin = /^https:\/\/streamarena(?:-[a-z0-9-]+)?\.vercel\.app$/i;

function isAllowedOrigin(origin: string | null): boolean {
  return !origin || exactOrigins.has(origin) || previewOrigin.test(origin);
}

function corsHeaders(origin: string | null) {
  const permitted = origin && isAllowedOrigin(origin) ? origin : productionOrigin;
  return {
    "Access-Control-Allow-Origin": permitted,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(data: unknown, status: number, origin: string | null) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders(origin), "Content-Type": "application/json" },
  });
}

function getPublishableKey(): string {
  const direct = Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? Deno.env.get("SUPABASE_ANON_KEY");
  if (direct) return direct;
  const keysJson = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  if (keysJson) {
    try {
      const keys = JSON.parse(keysJson);
      if (typeof keys.default === "string") return keys.default;
      const first = Object.values(keys).find((value) => typeof value === "string");
      if (typeof first === "string") return first;
    } catch {
      // Fall through to a clear configuration error.
    }
  }
  throw new Error("Supabase publishable key is not available to this function");
}

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("origin");
  if (!isAllowedOrigin(origin)) return json({ error: "Origin not allowed" }, 403, origin);
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders(origin) });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405, origin);

  try {
    const authorization = req.headers.get("authorization") ?? "";
    const match = authorization.match(/^Bearer\s+(.+)$/i);
    if (!match) return json({ error: "A valid sign-in session is required" }, 401, origin);

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const livekitUrl = Deno.env.get("LIVEKIT_URL");
    const livekitApiKey = Deno.env.get("LIVEKIT_API_KEY");
    const livekitApiSecret = Deno.env.get("LIVEKIT_API_SECRET");
    if (!supabaseUrl || !livekitUrl || !livekitApiKey || !livekitApiSecret) {
      console.error("Missing required function environment configuration");
      return json({ error: "Streaming service is not configured" }, 500, origin);
    }
    if (!/^wss:\/\/.+\.livekit\.cloud\/?$/i.test(livekitUrl)) {
      console.error("LIVEKIT_URL must be the LiveKit Cloud WebSocket URL");
      return json({ error: "Streaming service URL is incorrectly configured" }, 500, origin);
    }

    const publishableKey = getPublishableKey();
    const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: { apikey: publishableKey, Authorization: `Bearer ${match[1]}` },
    });
    if (!userResponse.ok) return json({ error: "Your sign-in session is invalid or expired" }, 401, origin);
    const user = await userResponse.json();
    if (!user?.id || typeof user.id !== "string") {
      return json({ error: "Unable to identify signed-in user" }, 401, origin);
    }

    const body = await req.json().catch(() => null);
    const roomName = typeof body?.roomName === "string" ? body.roomName.trim() : "";
    const role = body?.role;
    if (!/^[a-zA-Z0-9_-]{3,80}$/.test(roomName)) {
      return json({ error: "Invalid room name" }, 400, origin);
    }
    if (role !== "publisher" && role !== "viewer") {
      return json({ error: "Role must be publisher or viewer" }, 400, origin);
    }

    const ownRoom = `stream-${user.id}`;
    if (role === "publisher" && roomName !== ownRoom) {
      return json({ error: "You can only publish to your own stream room" }, 403, origin);
    }
    if (role === "viewer" && !/^stream-[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(roomName)) {
      return json({ error: "Invalid stream room" }, 400, origin);
    }

    const roomOwnerId = role === "publisher" ? user.id : roomName.slice("stream-".length);
    const freshAfter = new Date(Date.now() - 120_000).toISOString();
    const streamUrl = new URL(`${supabaseUrl}/rest/v1/streams`);
    streamUrl.searchParams.set("select", "id,user_id");
    streamUrl.searchParams.set("user_id", `eq.${roomOwnerId}`);
    streamUrl.searchParams.set("is_live", "eq.true");
    streamUrl.searchParams.set("last_heartbeat_at", `gt.${freshAfter}`);
    streamUrl.searchParams.set("limit", "1");
    const streamCheck = await fetch(streamUrl, {
      headers: { apikey: publishableKey, Authorization: `Bearer ${match[1]}` },
    });
    if (!streamCheck.ok) {
      console.error("Unable to verify active stream:", streamCheck.status);
      return json({ error: "Unable to verify stream status" }, 502, origin);
    }
    const activeStreams = await streamCheck.json();
    if (!Array.isArray(activeStreams) || activeStreams.length === 0) {
      return json({ error: "No active stream was found for this room" }, 404, origin);
    }

    // A client can write its own heartbeat row, so database freshness alone
    // is not proof that a publisher is actually connected and sending media.
    if (role === "viewer") {
      const roomService = new RoomServiceClient(
        livekitUrl.replace(/^wss:/i, "https:"),
        livekitApiKey,
        livekitApiSecret,
      );
      const participants = await roomService.listParticipants(roomName);
      const activePublisher = participants.find((participant) =>
        participant.identity === roomOwnerId && (participant.tracks?.length ?? 0) > 0
      );
      if (!activePublisher) {
        return json({ error: "The streamer is not currently publishing media" }, 404, origin);
      }
    }

    const displayName = typeof user.user_metadata?.username === "string"
      ? user.user_metadata.username.slice(0, 80)
      : "Player";
    const token = new AccessToken(livekitApiKey, livekitApiSecret, {
      identity: user.id,
      name: displayName,
      ttl: 60 * 60,
    });
    token.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: role === "publisher",
      canSubscribe: true,
      canPublishData: role === "publisher",
    });
    const participantToken = await token.toJwt();
    return json({ token: participantToken, serverUrl: livekitUrl, roomName, role }, 200, origin);
  } catch (error) {
    console.error("LiveKit token request failed:", error instanceof Error ? error.message : "unknown error");
    return json({ error: "Unable to create a streaming session" }, 500, origin);
  }
});
