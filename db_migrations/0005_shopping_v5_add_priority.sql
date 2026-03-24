-- Datenbank   : shopping.db
-- Version     : 5
-- Beschreibung: Priorität pro Einkaufsartikel (0=hoch, 1=normal, 2=niedrig)

ALTER TABLE shopping_items ADD COLUMN priority INTEGER DEFAULT 2;
