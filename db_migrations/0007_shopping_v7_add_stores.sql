-- Datenbank   : shopping.db
-- Version     : 7
-- Beschreibung: Neue Tabelle stores für Geofencing-Erinnerungen je Supermarkt

CREATE TABLE IF NOT EXISTS stores (
    _id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT    NOT NULL UNIQUE,
    latitude      REAL    DEFAULT 0,
    longitude     REAL    DEFAULT 0,
    radius_meters INTEGER DEFAULT 200
);
