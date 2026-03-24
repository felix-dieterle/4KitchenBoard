-- Datenbank   : calendar.db
-- Version     : 3
-- Beschreibung: Serien-ID für wiederkehrende Termine; alle Einträge einer Serie teilen dieselbe series_id

ALTER TABLE appointments ADD COLUMN series_id INTEGER;
