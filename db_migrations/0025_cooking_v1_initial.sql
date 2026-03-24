-- Datenbank   : cooking.db
-- Version     : 1
-- Beschreibung: Initiales Schema – Rezept-/Gerichteverwaltung

CREATE TABLE IF NOT EXISTS dishes (
    _id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL,
    duration_minutes INTEGER DEFAULT 0,
    ingredients      TEXT,
    notes            TEXT,
    last_cooked      TEXT
);
