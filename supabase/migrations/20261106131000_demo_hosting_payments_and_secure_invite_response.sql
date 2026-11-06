create table if not exists public.tournament_demo_payments (
  id uuid primary key default gen_random_uuid(),
  tournament_id uuid not null references public.tournaments(id) on delete cascade,
  organizer_id uuid not null references auth.users(id) on delete cascade,
  demo_points integer not null check (demo_points in (10,25,50)),
  status text not null default 'succeeded' check (status in ('succeeded')),
  payment_method text not null default 'simulated' check (payment_method = 'simulated'),
  is_demo boolean not null default true check (is_demo = true),
  created_at timestamptz not null default now(),
  completed_at timestamptz not null default now()
);
create index if not exists tournament_demo_payments_organizer_created_idx
  on public.tournament_demo_payments(organizer_id, created_at desc);
create index if not exists tournament_demo_payments_tournament_idx
  on public.tournament_demo_payments(tournament_id, created_at desc);
alter table public.tournament_demo_payments enable row level security;
revoke all on public.tournament_demo_payments from anon, authenticated;
grant select on public.tournament_demo_payments to authenticated;
drop policy if exists "Organizers view own demo hosting payments" on public.tournament_demo_payments;
create policy "Organizers view own demo hosting payments"
  on public.tournament_demo_payments for select to authenticated
  using (organizer_id = (select auth.uid()));
drop policy if exists "Admins view all demo hosting payments" on public.tournament_demo_payments;
create policy "Admins view all demo hosting payments"
  on public.tournament_demo_payments for select to authenticated
  using ((select auth.jwt() -> 'app_metadata' ->> 'role') = 'admin');

drop policy if exists "Users create own free tournaments" on public.tournaments;
create policy "Users create own free tournaments"
  on public.tournaments for insert to authenticated
  with check (
    organizer_id = (select auth.uid())
    and entry_type = 'free'
    and coalesce(entry_fee,0) = 0
    and hosting_payment_status = 'not_required'
  );
drop policy if exists "Users create own hosted demo tournaments" on public.tournaments;
create policy "Users create own hosted demo tournaments"
  on public.tournaments for insert to authenticated
  with check (
    organizer_id = (select auth.uid())
    and entry_type = 'paid'
    and coalesce(entry_fee,0) = 0
    and hosting_payment_status = 'pending'
    and hosting_fee_amount in (10,25,50)
  );

create or replace function public.complete_tournament_demo_hosting(p_tournament_id uuid, p_demo_points integer)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
  v_tournament public.tournaments%rowtype;
  v_payment_id uuid;
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;
  if p_demo_points not in (10,25,50) then raise exception 'Invalid demo hosting tier'; end if;
  select * into v_tournament from public.tournaments
  where id = p_tournament_id and organizer_id = v_user_id for update;
  if not found then raise exception 'Tournament not found or not owned by current user'; end if;
  if v_tournament.entry_type <> 'paid'
     or coalesce(v_tournament.entry_fee,0) <> 0
     or v_tournament.hosting_payment_status <> 'pending'
     or v_tournament.hosting_fee_amount <> p_demo_points then
    raise exception 'Tournament is not eligible for this demo payment';
  end if;
  insert into public.tournament_demo_payments
    (tournament_id, organizer_id, demo_points, status, payment_method, is_demo)
  values (p_tournament_id, v_user_id, p_demo_points, 'succeeded', 'simulated', true)
  returning id into v_payment_id;
  update public.tournaments set hosting_payment_status = 'paid'
  where id = p_tournament_id and organizer_id = v_user_id;
  return jsonb_build_object('success',true,'demo',true,'label','DEMO / NOT REAL PAYMENT','payment_id',v_payment_id,'demo_points',p_demo_points);
end;
$$;
revoke all on function public.complete_tournament_demo_hosting(uuid, integer) from public, anon;
grant execute on function public.complete_tournament_demo_hosting(uuid, integer) to authenticated;

create or replace function public.respond_tournament_invite(p_invite_id uuid, p_accept boolean)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
  v_invite public.tournament_invites%rowtype;
  v_tournament public.tournaments%rowtype;
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;
  select * into v_invite from public.tournament_invites
  where id = p_invite_id and invited_user_id = v_user_id and status = 'pending' for update;
  if not found then raise exception 'Pending invitation not found'; end if;
  if p_accept then
    select * into v_tournament from public.tournaments
    where id = v_invite.tournament_id
      and status in ('upcoming','registration_open')
      and (
        (entry_type = 'free' and hosting_payment_status = 'not_required' and coalesce(entry_fee,0)=0)
        or (entry_type = 'paid' and hosting_payment_status = 'paid' and coalesce(entry_fee,0)=0)
      );
    if not found then raise exception 'Tournament is not open for invited registration'; end if;
    insert into public.tournament_registrations(tournament_id,user_id)
    values(v_invite.tournament_id,v_user_id) on conflict do nothing;
    update public.tournament_invites set status='accepted', responded_at=now() where id=p_invite_id;
    return jsonb_build_object('success',true,'status','accepted','entry_fee',0);
  else
    update public.tournament_invites set status='declined', responded_at=now() where id=p_invite_id;
    return jsonb_build_object('success',true,'status','declined');
  end if;
end;
$$;
revoke all on function public.respond_tournament_invite(uuid, boolean) from public, anon;
grant execute on function public.respond_tournament_invite(uuid, boolean) to authenticated;
drop policy if exists "Players respond to own tournament invites" on public.tournament_invites;
