-- Datenbank   : chat.db
-- Version     : 2
-- Beschreibung: Empfängerfelder für gerichtete Nachrichten (leerer String = Broadcast an alle)

ALTER TABLE chat_messages ADD COLUMN recipient_id   TEXT NOT NULL DEFAULT '';
ALTER TABLE chat_messages ADD COLUMN recipient_name TEXT NOT NULL DEFAULT '';
