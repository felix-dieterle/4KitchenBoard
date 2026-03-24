-- Datenbank   : chat.db
-- Version     : 1
-- Beschreibung: Initiales Schema – lokaler Cache für Chat-Nachrichten

CREATE TABLE IF NOT EXISTS chat_messages (
    id           INTEGER PRIMARY KEY,
    sender_id    TEXT    NOT NULL DEFAULT '',
    sender_name  TEXT    NOT NULL DEFAULT '',
    message      TEXT    NOT NULL DEFAULT '',
    timestamp_ms INTEGER NOT NULL DEFAULT 0,
    is_read      INTEGER NOT NULL DEFAULT 0
);
