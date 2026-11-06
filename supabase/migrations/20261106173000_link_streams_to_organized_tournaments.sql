alter table public.tournaments
  add column if not exists organizer_id uuid references auth.users(id) on delete set null;

alter table public.streams
  add column if not exists tournament_id uuid references public.tournaments(id) on delete set null;

create index if not exists tournaments_organizer_id_idx
  on public.tournaments(organizer_id);
create index if not exists streams_tournament_id_idx
  on public.streams(tournament_id);
