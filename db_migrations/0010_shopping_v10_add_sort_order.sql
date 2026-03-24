-- Datenbank   : shopping.db
-- Version     : 10
-- Beschreibung: Manuelle Sortierreihenfolge per Drag-and-Drop; Initialisierung anhand Einfügereihenfolge (_id)

ALTER TABLE shopping_items ADD COLUMN sort_order INTEGER DEFAULT 0;

-- Sortierreihenfolge anhand der _id initialisieren (entspricht der ursprünglichen Einfügereihenfolge)
UPDATE shopping_items
SET sort_order = (
    SELECT COUNT(*)
    FROM   shopping_items AS t2
    WHERE  t2._id < shopping_items._id
);
