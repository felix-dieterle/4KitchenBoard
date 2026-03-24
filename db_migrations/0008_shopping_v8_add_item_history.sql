-- Datenbank   : shopping.db
-- Version     : 8
-- Beschreibung: Neue Tabelle item_history für Autovervollständigung; befüllt aus bestehenden Artikeln

CREATE TABLE IF NOT EXISTS item_history (
    _id  INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT    NOT NULL UNIQUE
);

-- Vorhandene Artikelnamen als Startwerte übernehmen
INSERT OR IGNORE INTO item_history (name)
SELECT DISTINCT name FROM shopping_items;
