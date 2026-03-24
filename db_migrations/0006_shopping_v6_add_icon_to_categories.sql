-- Datenbank   : shopping.db
-- Version     : 6
-- Beschreibung: Optionaler Icon-Name pro Kategorie (verweist auf Drawable via IconProvider)

ALTER TABLE categories ADD COLUMN icon_name TEXT DEFAULT '';
