-- Datenbank   : chat.db
-- Version     : 3
-- Beschreibung: Zustellstatus für ausgehende Nachrichten (0=gesendet, 1=zugestellt, 2=gelesen)

ALTER TABLE chat_messages ADD COLUMN delivery_status INTEGER NOT NULL DEFAULT 0;
