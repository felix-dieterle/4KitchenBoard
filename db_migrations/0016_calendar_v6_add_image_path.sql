-- Datenbank   : calendar.db
-- Version     : 6
-- Beschreibung: Optionaler Profilbild-Pfad für Personen (absoluter Dateipfad im App-Speicher)

ALTER TABLE persons ADD COLUMN image_path TEXT;
