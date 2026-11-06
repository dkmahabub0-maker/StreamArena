-- Track publisher activity so stale streams are excluded from listings and token issuance.
alter table public.streams
  add column if not exists last_heartbeat_at timestamptz;

create index if not exists streams_live_heartbeat_idx
  on public.streams (last_heartbeat_at desc)
  where is_live = true;

comment on column public.streams.last_heartbeat_at is
  'Last authenticated publisher heartbeat; streams older than the freshness window are not shown as live.';
