-- Persist each user's live-stream defaults and publish per-stream metadata.
alter table public.streams
  add column if not exists caption text not null default '',
  add column if not exists thumbnail_url text;

alter table public.streams
  drop constraint if exists streams_caption_length_check;
alter table public.streams
  add constraint streams_caption_length_check
  check (char_length(caption) <= 500) not valid;
alter table public.streams
  validate constraint streams_caption_length_check;

create table if not exists public.stream_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  title text not null default '',
  caption text not null default '',
  thumbnail_url text,
  updated_at timestamptz not null default now(),
  constraint stream_settings_title_length_check check (char_length(title) <= 120),
  constraint stream_settings_caption_length_check check (char_length(caption) <= 500)
);

alter table public.stream_settings enable row level security;
drop policy if exists "Users read own stream settings" on public.stream_settings;
create policy "Users read own stream settings"
  on public.stream_settings for select to authenticated
  using ((select auth.uid()) = user_id);
drop policy if exists "Users insert own stream settings" on public.stream_settings;
create policy "Users insert own stream settings"
  on public.stream_settings for insert to authenticated
  with check ((select auth.uid()) = user_id);
drop policy if exists "Users update own stream settings" on public.stream_settings;
create policy "Users update own stream settings"
  on public.stream_settings for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('stream-thumbnails', 'stream-thumbnails', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Users upload own stream thumbnails" on storage.objects;
create policy "Users upload own stream thumbnails"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'stream-thumbnails'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
