-- Correct invitee family visibility, enable all Family realtime events, and index FK lookups.
drop policy if exists "Family members and invitees can view their families" on public.families;
create policy "Family members and invitees can view their families"
on public.families for select to authenticated
using (
  public.is_family_member(families.id)
  or exists (
    select 1
    from public.family_invites fi
    where fi.family_id = families.id
      and fi.invitee_id = (select auth.uid())
      and fi.status = 'pending'
  )
);

create index if not exists families_owner_id_idx on public.families(owner_id);
create index if not exists family_invites_inviter_id_idx on public.family_invites(inviter_id);
create index if not exists family_messages_user_id_idx on public.family_messages(user_id);

do $$
declare
  v_table text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach v_table in array array['families','family_members','family_invites','family_messages']
    loop
      if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = v_table
      ) then
        execute format('alter publication supabase_realtime add table public.%I', v_table);
      end if;
    end loop;
  end if;
end $$;
