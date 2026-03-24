-- Datenbank   : calendar.db
-- Version     : 1
-- Beschreibung: Initiales Schema – Termine und Vorlagen

CREATE TABLE IF NOT EXISTS appointments (
    _id   INTEGER PRIMARY KEY AUTOINCREMENT,
    date  TEXT    NOT NULL,
    title TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS standard_templates (
    _id   INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT    NOT NULL UNIQUE
);
