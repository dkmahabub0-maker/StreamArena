-- Restrict direct API registrations to authenticated users and open free tournaments.
drop policy if exists "Users can register themselves" on public.tournament_registrations;

create policy "Users can register for open free tournaments"
on public.tournament_registrations
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.tournaments t
    where t.id = tournament_id
      and t.entry_type = 'free'
      and t.status in ('upcoming', 'registration_open')
  )
);
