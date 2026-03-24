-- Datenbank   : calendar.db
-- Version     : 5
-- Beschreibung: Gruppenreferenz in Terminen (Termin kann einer Personengruppe zugeordnet werden)

ALTER TABLE appointments ADD COLUMN group_id INTEGER;
