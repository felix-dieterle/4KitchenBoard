-- Datenbank   : shopping.db
-- Version     : 9
-- Beschreibung: Kategoriespalte in item_history; Rückbefüllung aus shopping_items

ALTER TABLE item_history ADD COLUMN category TEXT DEFAULT '';

-- Kategorie aus dem letzten passenden Eintrag in shopping_items nachfüllen
UPDATE item_history
SET category = COALESCE(
    (SELECT shopping_items.category
     FROM   shopping_items
     WHERE  shopping_items.name = item_history.name
     LIMIT  1),
    ''
);
