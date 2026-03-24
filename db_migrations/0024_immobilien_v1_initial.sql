-- Datenbank   : immobilien.db
-- Version     : 1
-- Beschreibung: Initiales Schema – Immobilien-Suchalarme und gefundene Angebote

CREATE TABLE IF NOT EXISTS immobilien_alerts (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    name                   TEXT    NOT NULL,
    search_url             TEXT    NOT NULL,
    check_interval_minutes INTEGER NOT NULL DEFAULT 60,
    active                 INTEGER NOT NULL DEFAULT 1,
    last_check_ms          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS immobilien_listings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_id      INTEGER NOT NULL,
    listing_url   TEXT    NOT NULL,
    first_seen_ms INTEGER NOT NULL,
    notified      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (alert_id, listing_url)
);
