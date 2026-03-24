# db_migrations

Dieser Ordner enthält explizite SQL-Migrationsskripte für alle SQLite-Datenbanken der
App.  Die Dateien dienen als menschenlesbare Quelle der Wahrheit über die
Datenbankhistorie und können von Skripten automatisiert eingelesen werden.

---

## Namenskonvention

```
NNNN_<datenbankname>_<beschreibung>.sql
```

| Bestandteil       | Bedeutung                                                                      |
|-------------------|--------------------------------------------------------------------------------|
| `NNNN`            | Vierstellige, führende Nullen, fortlaufende Nummer (0001, 0002, …)             |
| `<datenbankname>` | Kurzname der betroffenen SQLite-Datenbank (shopping, calendar, tasks, …)       |
| `<beschreibung>`  | Kurzer Snake-Case-Text, der beschreibt was die Migration tut                   |

**Beispiele:**

```
0001_shopping_v1_initial.sql
0010_shopping_v10_add_sort_order.sql
0026_tasks_v3_add_due_date.sql
```

---

## Regeln

1. **Fortlaufend** – Jede neue Migrationsdatei bekommt die nächste freie Nummer.  
   Nummern dürfen **nicht** wiederverwendet oder neu sortiert werden.
2. **Eine Migration pro Datei** – Jede Datei enthält genau eine logische Schemaänderung.
3. **Idempotenz** – Nutze `CREATE TABLE IF NOT EXISTS` und `ADD COLUMN … IF NOT EXISTS`
   (oder fange Fehler ab), damit ein Skript dieselbe Datei mehrfach ausführen kann,
   ohne Fehler zu erzeugen.
4. **Kein Datenverlust** – Migrations-SQL darf keine Daten löschen, außer das ist explizit
   beabsichtigt und in einem Kommentar begründet.
5. **Kommentar-Header** – Jede Datei beginnt mit einem Kommentarblock der Form:
   ```sql
   -- Datenbank : <db-dateiname>.db
   -- Version   : <ziel-db-version>
   -- Beschreibung: <was macht diese migration>
   ```
6. **Java-Gegenstück** – Jede hier dokumentierte Migration muss auch im zugehörigen
   `*DatabaseHelper.java` in `onUpgrade()` implementiert sein.  Beide müssen
   synchron gehalten werden.

---

## Automatisierte Verarbeitung

Skripte können alle Migrations-SQL-Dateien in lexikalischer Reihenfolge einlesen
(die numerische Sortierung ergibt sich automatisch durch das führende NNNN):

```bash
# Beispiel: alle SQL-Dateien der Reihe nach einlesen
for f in $(ls db_migrations/*.sql | sort); do
    echo "Applying: $f"
    # z. B. sqlite3 <db-datei> < "$f"
done
```

---

## Aktueller Stand der Datenbanken

| Datenbankdatei      | Java-Klasse                    | Aktuelle Version | Migrations-Dateien |
|---------------------|--------------------------------|------------------|--------------------|
| `shopping.db`       | `ShoppingDatabaseHelper`       | 10               | 0001 – 0010        |
| `calendar.db`       | `CalendarDatabaseHelper`       | 8                | 0011 – 0018        |
| `tasks.db`          | `TaskDatabaseHelper`           | 2                | 0019 – 0020        |
| `chat.db`           | `ChatDatabaseHelper`           | 3                | 0021 – 0023        |
| `immobilien.db`     | `ImmobilienDatabaseHelper`     | 1                | 0024               |
| `cooking.db`        | `CookingDatabaseHelper`        | 1                | 0025               |

---

## Neue Migration hinzufügen

1. Nächste freie Nummer ermitteln (höchste vorhandene + 1).
2. Datei anlegen: `NNNN_<db>_<beschreibung>.sql`.
3. Kommentar-Header ergänzen (s. o.).
4. SQL schreiben.
5. `DB_VERSION` in der zugehörigen `*DatabaseHelper.java` erhöhen.
6. Dieselbe SQL-Logik in `onUpgrade()` implementieren.

---

## Hinweise für KI-Agenten (AI Agents)

- Die Nummerierung ist **global und monoton steigend** – es gibt keine datenbankspezifischen
  Teilsequenzen.
- Beim Erstellen einer neuen Migrationsdatei: aktuell höchste Dateinummer suchen,
  um `+1` hochzählen.
- Die Datei `README.md` (diese Datei) ist die einzige Dokumentationsquelle für die
  Konvention.  Keine anderen Markdown-Dateien in diesem Ordner anlegen.
- SQL-Dateien enthalten **ausschließlich DDL/DML** – kein Java, kein Kommentarcode
  anderer Sprachen.
- Wenn ein DB_VERSION-Increment im Java-Code fehlt, ist das ein Fehler – beides
  muss gleichzeitig angepasst werden.
