-- Datenbank   : shopping.db
-- Version     : 1
-- Beschreibung: Initiales Schema – Einkaufsliste

CREATE TABLE IF NOT EXISTS shopping_items (
    _id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    category   TEXT    NOT NULL,
    checked    INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT 0
);
