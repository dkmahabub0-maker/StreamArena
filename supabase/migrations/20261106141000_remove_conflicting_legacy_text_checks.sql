-- Remove legacy checks that conflict with the current UI/database limits.
alter table public.chat_messages
  drop constraint if exists chat_messages_message_check;

alter table public.support_tickets
  drop constraint if exists support_tickets_subject_check;
