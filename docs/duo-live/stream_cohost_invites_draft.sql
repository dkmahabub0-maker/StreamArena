-- DRAFT ONLY: review before creating a timestamped Supabase migration.
-- This file is intentionally outside supabase/migrations and must not be applied directly.

create table if not exists public.stream_cohost_invites (
  id uuid primary key default gen_random_uuid(),
  stream_id uuid not null references public.streams(id) on delete cascade,
  host_user_id uuid not null references auth.users(id) on delete cascade,
  invitee_user_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending'
    check (status in ('pending', 'accepted', 'declined', 'revoked', 'expired')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '10 minutes'),
  accepted_at timestamptz,
  constraint stream_cohost_invites_distinct_users
    check (host_user_id <> invitee_user_id),
  constraint stream_cohost_invites_expiry_after_creation
    check (expires_at > created_at)
);

-- A stream can have only one outstanding invitation or accepted co-host.
create unique index if not exists stream_cohost_invites_one_active_per_stream
  on public.stream_cohost_invites (stream_id)
  where status in ('pending', 'accepted');

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
