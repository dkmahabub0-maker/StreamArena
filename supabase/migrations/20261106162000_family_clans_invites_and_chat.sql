-- Family / Clan MVP: private clans, invitations, member controls and chat.
create table if not exists public.families (
 id uuid primary key default gen_random_uuid(),
 name text not null check (char_length(btrim(name)) between 3 and 40),
 owner_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now()
);
create table if not exists public.family_members (
 family_id uuid not null references public.families(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 role text not null default 'member' check (role in ('owner','admin','member')),
 joined_at timestamptz not null default now(),
 primary key(family_id,user_id)
);
create index if not exists family_members_user_id_idx on public.family_members(user_id);
create table if not exists public.family_invites (
 id uuid primary key default gen_random_uuid(),
 family_id uuid not null references public.families(id) on delete cascade,
 inviter_id uuid not null references auth.users(id) on delete cascade,
 invitee_id uuid not null references auth.users(id) on delete cascade,
 status text not null default 'pending' check(status in ('pending','accepted','declined','cancelled')),
 created_at timestamptz not null default now(),
 responded_at timestamptz,
 check(inviter_id<>invitee_id)
);
create unique index if not exists family_invites_one_pending_idx on public.family_invites(family_id,invitee_id) where status='pending';
create index if not exists family_invites_inbox_idx on public.family_invites(invitee_id,status,created_at desc);
create table if not exists public.family_messages (
 id uuid primary key default gen_random_uuid(),
 family_id uuid not null references public.families(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 message text not null check(char_length(btrim(message)) between 1 and 1000),
 created_at timestamptz not null default now()
);
create index if not exists family_messages_family_created_idx on public.family_messages(family_id,created_at desc);

create or replace function public.is_family_member(p_family_id uuid)
returns boolean language sql stable security definer set search_path=public,pg_temp
as $$ select exists(select 1 from public.family_members fm where fm.family_id=p_family_id and fm.user_id=auth.uid()); $$;
revoke all on function public.is_family_member(uuid) from public,anon;
grant execute on function public.is_family_member(uuid) to authenticated;

alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.family_invites enable row level security;
alter table public.family_messages enable row level security;
drop policy if exists "Family members can view their families" on public.families;
create policy "Family members can view their families" on public.families for select to authenticated using(public.is_family_member(id));
drop policy if exists "Members can view family membership" on public.family_members;
create policy "Members can view family membership" on public.family_members for select to authenticated using(public.is_family_member(family_id));
drop policy if exists "Invite participants can view family invites" on public.family_invites;
create policy "Invite participants can view family invites" on public.family_invites for select to authenticated using(invitee_id=auth.uid() or inviter_id=auth.uid() or public.is_family_member(family_id));
drop policy if exists "Family members can read family chat" on public.family_messages;
create policy "Family members can read family chat" on public.family_messages for select to authenticated using(public.is_family_member(family_id));
drop policy if exists "Family members can send family chat" on public.family_messages;
create policy "Family members can send family chat" on public.family_messages for insert to authenticated with check(user_id=auth.uid() and public.is_family_member(family_id));
grant select on public.families,public.family_members,public.family_invites,public.family_messages to authenticated;
grant insert on public.family_messages to authenticated;
revoke insert,update,delete on public.families,public.family_members,public.family_invites from anon,authenticated;
revoke update,delete on public.family_messages from anon,authenticated;

create or replace function public.create_family(p_name text)
returns uuid language plpgsql security definer set search_path=public,pg_temp
as $$
declare v_id uuid; v_name text:=btrim(coalesce(p_name,''));
begin
 if auth.uid() is null then raise exception 'Sign in to create a Family.'; end if;
 if char_length(v_name)<3 or char_length(v_name)>40 then raise exception 'Family name must be 3 to 40 characters.'; end if;
 if exists(select 1 from public.family_members where user_id=auth.uid() and role='owner') then raise exception 'You already own a Family. You can still join other Families by invitation.'; end if;
 insert into public.families(name,owner_id) values(v_name,auth.uid()) returning id into v_id;
 insert into public.family_members(family_id,user_id,role) values(v_id,auth.uid(),'owner');
 return v_id;
end; $$;

create or replace function public.invite_family_member(p_family_id uuid,p_username text)
returns uuid language plpgsql security definer set search_path=public,pg_temp
as $$
declare v_target uuid; v_invite uuid; v_name text:=lower(btrim(coalesce(p_username,'')));
begin
 if auth.uid() is null then raise exception 'Sign in first.'; end if;
 if not exists(select 1 from public.family_members where family_id=p_family_id and user_id=auth.uid() and role in ('owner','admin')) then raise exception 'Only a Family owner or admin can invite members.'; end if;
 select id into v_target from public.profiles where lower(username)=v_name limit 1;
 if v_target is null then raise exception 'No account found with that username.'; end if;
 if v_target=auth.uid() then raise exception 'You are already in this Family.'; end if;
 if exists(select 1 from public.family_members where family_id=p_family_id and user_id=v_target) then raise exception 'That player is already a Family member.'; end if;
 if (select count(*) from public.family_members where family_id=p_family_id)>=50 then raise exception 'A Family can have up to 50 members.'; end if;
 insert into public.family_invites(family_id,inviter_id,invitee_id) values(p_family_id,auth.uid(),v_target) returning id into v_invite;
 return v_invite;
exception when unique_violation then raise exception 'A pending invitation already exists for that player.';
end; $$;

create or replace function public.respond_family_invite(p_invite_id uuid,p_accept boolean)
returns boolean language plpgsql security definer set search_path=public,pg_temp
as $$
declare v_invite public.family_invites%rowtype;
begin
 if auth.uid() is null then raise exception 'Sign in first.'; end if;
 select * into v_invite from public.family_invites where id=p_invite_id for update;
 if not found or v_invite.invitee_id<>auth.uid() then raise exception 'Invitation not found.'; end if;
 if v_invite.status<>'pending' then raise exception 'This invitation has already been answered.'; end if;
 if p_accept then
  if exists(select 1 from public.family_members where family_id=v_invite.family_id and user_id=auth.uid()) then raise exception 'You are already a member of this Family.'; end if;
  if (select count(*) from public.family_members where family_id=v_invite.family_id)>=50 then raise exception 'This Family is full (50 members).'; end if;
  insert into public.family_members(family_id,user_id,role) values(v_invite.family_id,auth.uid(),'member');
  update public.family_invites set status='accepted',responded_at=now() where id=p_invite_id;
 else
  update public.family_invites set status='declined',responded_at=now() where id=p_invite_id;
 end if;
 return true;
end; $$;

create or replace function public.remove_family_member(p_family_id uuid,p_user_id uuid)
returns boolean language plpgsql security definer set search_path=public,pg_temp
as $$
declare v_actor_role text; v_target_role text;
begin
 if auth.uid() is null then raise exception 'Sign in first.'; end if;
 select role into v_actor_role from public.family_members where family_id=p_family_id and user_id=auth.uid();
 select role into v_target_role from public.family_members where family_id=p_family_id and user_id=p_user_id;
 if v_actor_role not in ('owner','admin') then raise exception 'Only a Family owner or admin can remove members.'; end if;
 if v_target_role is null then raise exception 'Member not found.'; end if;
 if v_target_role='owner' then raise exception 'The Family owner cannot be removed.'; end if;
 if v_actor_role='admin' and v_target_role='admin' then raise exception 'An admin cannot remove another admin.'; end if;
 delete from public.family_members where family_id=p_family_id and user_id=p_user_id;
 update public.family_invites set status='cancelled',responded_at=now() where family_id=p_family_id and invitee_id=p_user_id and status='pending';
 return true;
end; $$;

create or replace function public.leave_family(p_family_id uuid)
returns boolean language plpgsql security definer set search_path=public,pg_temp
as $$
begin
 if auth.uid() is null then raise exception 'Sign in first.'; end if;
 if exists(select 1 from public.family_members where family_id=p_family_id and user_id=auth.uid() and role='owner') then raise exception 'The owner cannot leave yet. Transfer ownership or delete the Family first.'; end if;
 delete from public.family_members where family_id=p_family_id and user_id=auth.uid();
 if not found then raise exception 'You are not a member of this Family.'; end if;
 return true;
end; $$;

revoke all on function public.create_family(text) from public,anon;
revoke all on function public.invite_family_member(uuid,text) from public,anon;
revoke all on function public.respond_family_invite(uuid,boolean) from public,anon;
revoke all on function public.remove_family_member(uuid,uuid) from public,anon;
revoke all on function public.leave_family(uuid) from public,anon;
grant execute on function public.create_family(text) to authenticated;
grant execute on function public.invite_family_member(uuid,text) to authenticated;
grant execute on function public.respond_family_invite(uuid,boolean) to authenticated;
grant execute on function public.remove_family_member(uuid,uuid) to authenticated;
grant execute on function public.leave_family(uuid) to authenticated;

do $$
begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') and not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='family_messages') then
  alter publication supabase_realtime add table public.family_messages;
 end if;
end $$;
