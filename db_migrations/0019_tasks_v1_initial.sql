-- Datenbank   : tasks.db
-- Version     : 1
-- Beschreibung: Initiales Schema – Aufgabenliste

CREATE TABLE IF NOT EXISTS tasks (
    _id        INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0
);
