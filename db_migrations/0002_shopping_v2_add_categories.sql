-- Datenbank   : shopping.db
-- Version     : 2
-- Beschreibung: Neue Tabelle categories für benutzerdefinierte Kategorien

CREATE TABLE IF NOT EXISTS categories (
    _id  INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE
);
