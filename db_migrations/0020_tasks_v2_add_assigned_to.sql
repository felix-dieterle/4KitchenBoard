-- Datenbank   : tasks.db
-- Version     : 2
-- Beschreibung: Zuweisung einer Aufgabe an eine Person (freier Text, z. B. Name aus Personenliste)

ALTER TABLE tasks ADD COLUMN assigned_to TEXT NOT NULL DEFAULT '';
