-- Datenbank   : calendar.db
-- Version     : 2
-- Beschreibung: Optionale Uhrzeit pro Termin (Format HH:mm)

ALTER TABLE appointments ADD COLUMN time TEXT;
