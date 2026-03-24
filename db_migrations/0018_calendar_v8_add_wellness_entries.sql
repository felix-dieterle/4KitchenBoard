-- Datenbank   : calendar.db
-- Version     : 8
-- Beschreibung: Neue Tabelle wellness_entries für Befindlichkeits-Tracking je Person und Tag

CREATE TABLE IF NOT EXISTS wellness_entries (
    _id       INTEGER PRIMARY KEY AUTOINCREMENT,
    person_id INTEGER NOT NULL,
    date      TEXT    NOT NULL,
    tiredness INTEGER NOT NULL,
    health    INTEGER NOT NULL,
    mood      INTEGER NOT NULL
);
