-- DRAFT ONLY: review before creating a timestamped Supabase migration.
-- This file is intentionally outside supabase/migrations and must not be applied directly.

create table if not exists public.stream_cohost_invites (
  id uuid primary key default gen_random_uuid(),
  stream_id uuid not null references public.streams(id) on delete cascade,
  host_user_id uuid not null references auth.users(id) on delete cascade,
  invitee_user_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending'
    check (status in ('pending', 'accepted', 'revoking', 'declined', 'revoked', 'expired')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '10 minutes'),
  accepted_at timestamptz,
  revocation_requested_at timestamptz,
  revocation_completed_at timestamptz,
  constraint stream_cohost_invites_distinct_users
    check (host_user_id <> invitee_user_id),
  constraint stream_cohost_invites_expiry_after_creation
    check (expires_at > created_at)
);

-- A stream can have only one outstanding invitation or accepted co-host.
create unique index if not exists stream_cohost_invites_one_active_per_stream
  on public.stream_cohost_invites (stream_id)
  where status in ('pending', 'accepted', 'revoking');

create index if not exists stream_cohost_invites_invitee_status_expiry
  on public.stream_cohost_invites (invitee_user_id, status, expires_at desc);

create index if not exists stream_cohost_invites_host_status
  on public.stream_cohost_invites (host_user_id, status, created_at desc);

alter table public.stream_cohost_invites enable row level security;

-- All reads/writes go through the authenticated Edge Function.
-- Never expose this table to browser roles; service_role is server-only.
revoke all on table public.stream_cohost_invites from anon, authenticated;

-- Explicit server-only access for the Edge Function's service-role client.
grant all on table public.stream_cohost_invites to service_role;


-- Durable, atomic per-user rate limits for authenticated profile search and invitations.
-- One row per user/action is retained and reset at the next time bucket.
create table if not exists public.stream_cohost_rate_limits (
  user_id uuid not null references auth.users(id) on delete cascade,
  action text not null check (action in ('search', 'invite')),
  window_started_at timestamptz not null,
  request_count integer not null default 0 check (request_count >= 0),
  primary key (user_id, action)
);

alter table public.stream_cohost_rate_limits enable row level security;
revoke all on table public.stream_cohost_rate_limits from anon, authenticated;
grant all on table public.stream_cohost_rate_limits to service_role;

create or replace function public.consume_stream_cohost_rate_limit(
  p_user_id uuid,
  p_action text,
  p_window_started_at timestamptz,
  p_limit integer
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  allowed boolean := false;
begin
  if p_action not in ('search', 'invite') or p_limit < 1 or p_limit > 100 then
    raise exception 'Invalid Duo Live rate-limit parameters';
  end if;

  insert into public.stream_cohost_rate_limits as limits
    (user_id, action, window_started_at, request_count)
  values
    (p_user_id, p_action, p_window_started_at, 1)
  on conflict (user_id, action) do update
    set window_started_at = case
          when limits.window_started_at < excluded.window_started_at then excluded.window_started_at
          else limits.window_started_at
        end,
        request_count = case
          when limits.window_started_at < excluded.window_started_at then 1
          else limits.request_count + 1
        end
    where limits.window_started_at <= excluded.window_started_at
      and (limits.window_started_at < excluded.window_started_at or limits.request_count < p_limit)
  returning true into allowed;

  return coalesce(allowed, false);
end;
$$;

-- The Edge Function's service-role client is the only caller.
revoke all on function public.consume_stream_cohost_rate_limit(uuid, text, timestamptz, integer)
  from public, anon, authenticated;
grant execute on function public.consume_stream_cohost_rate_limit(uuid, text, timestamptz, integer)
  to service_role;
