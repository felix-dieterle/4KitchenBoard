-- Datenbank   : calendar.db
-- Version     : 4
-- Beschreibung: Personenzuweisung an Termine; neue Tabellen persons, person_groups, group_members

ALTER TABLE appointments ADD COLUMN person_id INTEGER;

CREATE TABLE IF NOT EXISTS persons (
    _id   INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT    NOT NULL UNIQUE,
    color TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS person_groups (
    _id   INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT    NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id  INTEGER NOT NULL,
    person_id INTEGER NOT NULL,
    PRIMARY KEY (group_id, person_id)
);
