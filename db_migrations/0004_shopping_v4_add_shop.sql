-- Datenbank   : shopping.db
-- Version     : 4
-- Beschreibung: Optionaler Shop-Name pro Einkaufsartikel

ALTER TABLE shopping_items ADD COLUMN shop TEXT DEFAULT '';
