alter table public.tournaments
  add column if not exists hosting_payment_status text not null default 'not_required',
  add column if not exists hosting_fee_amount numeric(12,2);

alter table public.tournaments
  drop constraint if exists tournaments_hosting_payment_status_check;
alter table public.tournaments
  add constraint tournaments_hosting_payment_status_check
  check (hosting_payment_status in ('not_required','pending','paid','rejected'));

create table if not exists public.tournament_invites (
  id uuid primary key default gen_random_uuid(),
  tournament_id uuid not null references public.tournaments(id) on delete cascade,
  inviter_id uuid not null references auth.users(id) on delete cascade,
  invited_user_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','declined','cancelled')),
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  constraint tournament_invites_unique_player unique (tournament_id, invited_user_id),
  constraint tournament_invites_not_self check (inviter_id <> invited_user_id)
);

create index if not exists tournament_invites_invited_user_idx
  on public.tournament_invites(invited_user_id, status, created_at desc);
create index if not exists tournament_invites_tournament_idx
  on public.tournament_invites(tournament_id, created_at desc);

alter table public.tournament_invites enable row level security;

drop policy if exists "Users create own free tournaments" on public.tournaments;
create policy "Users create own free tournaments"
on public.tournaments for insert to authenticated
with check (
  organizer_id = (select auth.uid())
  and entry_type = 'free'
  and coalesce(entry_fee, 0) = 0
  and hosting_payment_status = 'not_required'
);

drop policy if exists "Organizers update own free tournaments" on public.tournaments;
create policy "Organizers update own free tournaments"
on public.tournaments for update to authenticated
using (
  organizer_id = (select auth.uid())
  and entry_type = 'free'
  and hosting_payment_status = 'not_required'
)
with check (
  organizer_id = (select auth.uid())
  and entry_type = 'free'
  and hosting_payment_status = 'not_required'
  and coalesce(entry_fee, 0) = 0
);

drop policy if exists "Organizers view own tournaments" on public.tournaments;
create policy "Organizers view own tournaments"
on public.tournaments for select to authenticated
using (organizer_id = (select auth.uid()));

create policy "Organizers send eligible tournament invites"
on public.tournament_invites for insert to authenticated
with check (
  inviter_id = (select auth.uid())
  and exists (
    select 1 from public.tournaments t
    where t.id = tournament_id
      and t.organizer_id = (select auth.uid())
      and t.status in ('upcoming','registration_open')
      and (
        (t.entry_type = 'free' and t.hosting_payment_status = 'not_required')
        or (t.entry_type = 'paid' and t.hosting_payment_status = 'paid')
      )
  )
);

create policy "Players view own tournament invites"
on public.tournament_invites for select to authenticated
using (invited_user_id = (select auth.uid()) or inviter_id = (select auth.uid()));

create policy "Players respond to own tournament invites"
on public.tournament_invites for update to authenticated
using (invited_user_id = (select auth.uid()) and status = 'pending')
with check (
  invited_user_id = (select auth.uid())
  and status in ('accepted','declined')
);

create policy "Organizers cancel own tournament invites"
on public.tournament_invites for update to authenticated
using (
  inviter_id = (select auth.uid())
  and status = 'pending'
  and exists (
    select 1 from public.tournaments t
    where t.id = tournament_id and t.organizer_id = (select auth.uid())
  )
)
with check (inviter_id = (select auth.uid()) and status = 'cancelled');

drop policy if exists "Users can register for open free tournaments" on public.tournament_registrations;
create policy "Users can register for open free tournaments or accepted invites"
on public.tournament_registrations for insert to authenticated
with check (
  user_id = (select auth.uid())
  and exists (
    select 1 from public.tournaments t
    where t.id = tournament_id
      and t.status in ('upcoming','registration_open')
      and (
        (t.entry_type = 'free' and t.hosting_payment_status = 'not_required' and coalesce(t.entry_fee,0)=0)
        or (
          t.entry_type = 'paid'
          and t.hosting_payment_status = 'paid'
          and exists (
            select 1 from public.tournament_invites i
            where i.tournament_id = t.id
              and i.invited_user_id = (select auth.uid())
              and i.status = 'accepted'
          )
        )
      )
  )
);
grant select, insert, update on public.tournament_invites to authenticated;
