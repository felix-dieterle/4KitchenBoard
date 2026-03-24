-- Datenbank   : shopping.db
-- Version     : 3
-- Beschreibung: Mengenangabe pro Einkaufsartikel

ALTER TABLE shopping_items ADD COLUMN quantity INTEGER DEFAULT 1;
