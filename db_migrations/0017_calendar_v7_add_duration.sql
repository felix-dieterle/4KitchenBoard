-- Datenbank   : calendar.db
-- Version     : 7
-- Beschreibung: Erinnerungsminuten vor einem Termin (0 = keine Erinnerung).
--               Hinweis: Die Spalte heißt in der DB 'duration', enthält aber
--               die Vorwarnzeit in Minuten (nicht die Termindauer).

ALTER TABLE appointments ADD COLUMN duration INTEGER NOT NULL DEFAULT 0;
