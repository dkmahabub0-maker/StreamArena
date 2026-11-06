-- Enforce message/title limits at the database boundary, not only in the browser.
do $$
begin
  if not exists (select 1 from pg_constraint where conrelid='public.chat_messages'::regclass and conname='chat_messages_message_length_check') then
    alter table public.chat_messages add constraint chat_messages_message_length_check
      check (char_length(btrim(message)) between 1 and 2000) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conrelid='public.support_tickets'::regclass and conname='support_tickets_subject_length_check') then
    alter table public.support_tickets add constraint support_tickets_subject_length_check
      check (char_length(btrim(subject)) between 1 and 160) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conrelid='public.support_tickets'::regclass and conname='support_tickets_message_length_check') then
    alter table public.support_tickets add constraint support_tickets_message_length_check
      check (char_length(btrim(message)) between 1 and 5000) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conrelid='public.support_replies'::regclass and conname='support_replies_message_length_check') then
    alter table public.support_replies add constraint support_replies_message_length_check
      check (char_length(btrim(message)) between 1 and 5000) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conrelid='public.streams'::regclass and conname='streams_title_length_check') then
    alter table public.streams add constraint streams_title_length_check
      check (char_length(btrim(title)) between 1 and 120) not valid;
  end if;
end $$;

alter table public.chat_messages validate constraint chat_messages_message_length_check;
alter table public.support_tickets validate constraint support_tickets_subject_length_check;
alter table public.support_tickets validate constraint support_tickets_message_length_check;
alter table public.support_replies validate constraint support_replies_message_length_check;
alter table public.streams validate constraint streams_title_length_check;
