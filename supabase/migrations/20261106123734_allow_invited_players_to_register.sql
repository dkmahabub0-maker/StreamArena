drop policy if exists "Users can register for open free tournaments or accepted invites" on public.tournament_registrations;
create policy "Users register for free tournaments or invited paid tournaments"
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
              and i.status in ('pending','accepted')
          )
        )
      )
  )
);