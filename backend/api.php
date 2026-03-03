<?php
/**
 * 4KitchenBoard – Shopping List Sync API
 *
 * Endpoints (action= GET or POST parameter):
 *   GET  ?action=list            → JSON list of active items (includes quantity)
 *   POST ?action=add             → body: name, category[, quantity] → new item JSON
 *   POST ?action=check           → body: id              → {"success":true}
 *   POST ?action=delete          → body: id              → {"success":true}
 *   POST ?action=update_quantity → body: id, quantity    → {"success":true}
 *
 * Calendar endpoints (action= GET or POST parameter):
 *   GET  ?action=calendar_list            → JSON list of all appointments
 *   POST ?action=calendar_upsert          → body: id, date, title[, time, series_id] → {"success":true}
 *   POST ?action=calendar_delete          → body: id                                 → {"success":true}
 *   POST ?action=calendar_delete_series   → body: series_id                          → {"success":true}
 *   POST ?action=calendar_update_datetime → body: id, date[, time]                   → {"success":true}
 *
 * Cooking-list endpoints (action= GET or POST parameter):
 *   GET  ?action=cooking_list         → JSON list of all dishes
 *   POST ?action=cooking_upsert       → body: id, name[, duration_minutes, ingredients, notes, last_cooked] → {"success":true}
 *   POST ?action=cooking_delete       → body: id                                  → {"success":true}
 *   POST ?action=cooking_mark_cooked  → body: id, last_cooked                     → {"success":true}
 *
 * Storage: SQLite3 file (shopping.db) placed beside this script.
 * The database file is protected by .htaccess so it cannot be downloaded.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

// Handle pre-flight OPTIONS request
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// ── Database setup ────────────────────────────────────────────────────────────

$dbPath = __DIR__ . '/shopping.db';

try {
    $db = new SQLite3($dbPath);
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode(['error' => 'Could not open database: ' . $e->getMessage()]);
    exit;
}

$db->busyTimeout(5000);

$db->exec("CREATE TABLE IF NOT EXISTS items (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    category   TEXT    NOT NULL,
    checked    INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT 0,
    quantity   INTEGER NOT NULL DEFAULT 1,
    shop       TEXT    NOT NULL DEFAULT ''
)");

// Add quantity column to existing tables that were created without it
$columnExists = false;
$result = $db->query('PRAGMA table_info(items)');
while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
    if ($row['name'] === 'quantity') { $columnExists = true; break; }
}
if (!$columnExists) {
    $db->exec('ALTER TABLE items ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1');
}

// Add shop column to existing tables that were created without it
$shopColumnExists = false;
$result2 = $db->query('PRAGMA table_info(items)');
while ($row = $result2->fetchArray(SQLITE3_ASSOC)) {
    if ($row['name'] === 'shop') { $shopColumnExists = true; break; }
}
if (!$shopColumnExists) {
    $db->exec("ALTER TABLE items ADD COLUMN shop TEXT NOT NULL DEFAULT ''");
}

$db->exec('CREATE TABLE IF NOT EXISTS categories (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT    NOT NULL UNIQUE
)');

$db->exec('CREATE TABLE IF NOT EXISTS calendar_appointments (
    id        INTEGER PRIMARY KEY,
    date      TEXT    NOT NULL,
    time      TEXT,
    title     TEXT    NOT NULL,
    series_id INTEGER
)');

$db->exec('CREATE TABLE IF NOT EXISTS cooking_dishes (
    id               INTEGER PRIMARY KEY,
    name             TEXT    NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 0,
    ingredients      TEXT,
    notes            TEXT,
    last_cooked      TEXT
)');

// ── Dispatch ──────────────────────────────────────────────────────────────────

$action = trim((string)($_GET['action'] ?? $_POST['action'] ?? ''));

switch ($action) {
    case 'list':
        actionList($db);
        break;
    case 'add':
        actionAdd($db);
        break;
    case 'check':
        actionCheck($db);
        break;
    case 'delete':
        actionDelete($db);
        break;
    case 'update_quantity':
        actionUpdateQuantity($db);
        break;
    case 'calendar_list':
        calendarList($db);
        break;
    case 'calendar_upsert':
        calendarUpsert($db);
        break;
    case 'calendar_delete':
        calendarDelete($db);
        break;
    case 'calendar_delete_series':
        calendarDeleteSeries($db);
        break;
    case 'calendar_update_datetime':
        calendarUpdateDatetime($db);
        break;
    case 'cooking_list':
        cookingList($db);
        break;
    case 'cooking_upsert':
        cookingUpsert($db);
        break;
    case 'cooking_delete':
        cookingDelete($db);
        break;
    case 'cooking_mark_cooked':
        cookingMarkCooked($db);
        break;
    default:
        http_response_code(400);
        echo json_encode(['error' => 'Unknown or missing action']);
}

$db->close();
exit;

// ── Action handlers ───────────────────────────────────────────────────────────

function actionList(SQLite3 $db): void
{
    $result = $db->query(
        'SELECT id, name, category, quantity, shop FROM items
         WHERE checked = 0
         ORDER BY category ASC, name ASC'
    );
    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = [
            'id'       => (int)$row['id'],
            'name'     => $row['name'],
            'category' => $row['category'],
            'quantity' => (int)$row['quantity'],
            'shop'     => (string)$row['shop'],
        ];
    }
    echo json_encode(['items' => $items]);
}

function actionAdd(SQLite3 $db): void
{
    $name     = trim((string)($_POST['name']     ?? ''));
    $category = trim((string)($_POST['category'] ?? ''));
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));
    $shop     = trim((string)($_POST['shop']     ?? ''));

    if ($name === '' || $category === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "name" and "category" are required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO items (name, category, checked, created_at, quantity, shop)
         VALUES (:name, :category, 0, :ts, :quantity, :shop)'
    );
    $stmt->bindValue(':name',     $name,     SQLITE3_TEXT);
    $stmt->bindValue(':category', $category, SQLITE3_TEXT);
    $stmt->bindValue(':ts',       (int)(microtime(true) * 1000), SQLITE3_INTEGER);
    $stmt->bindValue(':quantity', $quantity, SQLITE3_INTEGER);
    $stmt->bindValue(':shop',     $shop,     SQLITE3_TEXT);
    $stmt->execute();

    $id = $db->lastInsertRowID();

    // Persist category for future suggestions
    $stmtCat = $db->prepare(
        'INSERT OR IGNORE INTO categories (name) VALUES (:name)'
    );
    $stmtCat->bindValue(':name', $category, SQLITE3_TEXT);
    $stmtCat->execute();

    echo json_encode(['id' => $id, 'name' => $name, 'category' => $category, 'quantity' => $quantity, 'shop' => $shop]);
}

function actionCheck(SQLite3 $db): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET checked = 1 WHERE id = :id');
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionDelete(SQLite3 $db): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM items WHERE id = :id');
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionUpdateQuantity(SQLite3 $db): void
{
    $id       = (int)($_POST['id']       ?? 0);
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET quantity = :quantity WHERE id = :id');
    $stmt->bindValue(':quantity', $quantity, SQLITE3_INTEGER);
    $stmt->bindValue(':id',       $id,       SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Calendar action handlers ──────────────────────────────────────────────────

function calendarList(SQLite3 $db): void
{
    $result = $db->query(
        'SELECT id, date, time, title, series_id FROM calendar_appointments
         ORDER BY date ASC, time ASC, title ASC'
    );
    $appointments = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $appointments[] = [
            'id'        => (int)$row['id'],
            'date'      => $row['date'],
            'time'      => $row['time'],
            'title'     => $row['title'],
            'series_id' => $row['series_id'] !== null ? (int)$row['series_id'] : null,
        ];
    }
    echo json_encode(['appointments' => $appointments]);
}

function calendarUpsert(SQLite3 $db): void
{
    $id       = (int)($_POST['id']        ?? 0);
    $date     = trim((string)($_POST['date']    ?? ''));
    $title    = trim((string)($_POST['title']   ?? ''));
    $time     = trim((string)($_POST['time']    ?? ''));
    $seriesId = isset($_POST['series_id']) && $_POST['series_id'] !== ''
                ? (int)$_POST['series_id'] : null;

    if ($id <= 0 || $date === '' || $title === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id", "date" and "title" are required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT OR REPLACE INTO calendar_appointments (id, date, time, title, series_id)
         VALUES (:id, :date, :time, :title, :series_id)'
    );
    $stmt->bindValue(':id',        $id,    SQLITE3_INTEGER);
    $stmt->bindValue(':date',      $date,  SQLITE3_TEXT);
    $stmt->bindValue(':time',      $time !== '' ? $time : null, $time !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':title',     $title, SQLITE3_TEXT);
    $stmt->bindValue(':series_id', $seriesId, $seriesId !== null ? SQLITE3_INTEGER : SQLITE3_NULL);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDelete(SQLite3 $db): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE id = :id');
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDeleteSeries(SQLite3 $db): void
{
    $seriesId = (int)($_POST['series_id'] ?? 0);
    if ($seriesId <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "series_id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE series_id = :series_id');
    $stmt->bindValue(':series_id', $seriesId, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarUpdateDatetime(SQLite3 $db): void
{
    $id   = (int)($_POST['id']   ?? 0);
    $date = trim((string)($_POST['date'] ?? ''));
    $time = trim((string)($_POST['time'] ?? ''));

    if ($id <= 0 || $date === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "date" are required']);
        return;
    }

    $stmt = $db->prepare(
        'UPDATE calendar_appointments SET date = :date, time = :time, series_id = NULL WHERE id = :id'
    );
    $stmt->bindValue(':date', $date, SQLITE3_TEXT);
    $stmt->bindValue(':time', $time !== '' ? $time : null, $time !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':id',   $id,   SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Cooking action handlers ───────────────────────────────────────────────────

function cookingList(SQLite3 $db): void
{
    $result = $db->query(
        'SELECT id, name, duration_minutes, ingredients, notes, last_cooked
         FROM cooking_dishes ORDER BY name ASC'
    );
    $dishes = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $dishes[] = [
            'id'               => (int)$row['id'],
            'name'             => $row['name'],
            'duration_minutes' => (int)$row['duration_minutes'],
            'ingredients'      => $row['ingredients'],
            'notes'            => $row['notes'],
            'last_cooked'      => $row['last_cooked'],
        ];
    }
    echo json_encode(['dishes' => $dishes]);
}

function cookingUpsert(SQLite3 $db): void
{
    $id          = (int)($_POST['id']               ?? 0);
    $name        = trim((string)($_POST['name']     ?? ''));
    $duration    = max(0, (int)($_POST['duration_minutes'] ?? 0));
    $ingredients = trim((string)($_POST['ingredients'] ?? ''));
    $notes       = trim((string)($_POST['notes']    ?? ''));
    $lastCooked  = trim((string)($_POST['last_cooked'] ?? ''));

    if ($id <= 0 || $name === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "name" are required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT OR REPLACE INTO cooking_dishes (id, name, duration_minutes, ingredients, notes, last_cooked)
         VALUES (:id, :name, :duration, :ingredients, :notes, :last_cooked)'
    );
    $stmt->bindValue(':id',          $id,       SQLITE3_INTEGER);
    $stmt->bindValue(':name',        $name,     SQLITE3_TEXT);
    $stmt->bindValue(':duration',    $duration, SQLITE3_INTEGER);
    $stmt->bindValue(':ingredients', $ingredients !== '' ? $ingredients : null,
                     $ingredients !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':notes',       $notes !== '' ? $notes : null,
                     $notes !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':last_cooked', $lastCooked !== '' ? $lastCooked : null,
                     $lastCooked !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function cookingDelete(SQLite3 $db): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM cooking_dishes WHERE id = :id');
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function cookingMarkCooked(SQLite3 $db): void
{
    $id         = (int)($_POST['id']          ?? 0);
    $lastCooked = trim((string)($_POST['last_cooked'] ?? ''));

    if ($id <= 0 || $lastCooked === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "last_cooked" are required']);
        return;
    }

    $stmt = $db->prepare('UPDATE cooking_dishes SET last_cooked = :last_cooked WHERE id = :id');
    $stmt->bindValue(':last_cooked', $lastCooked, SQLITE3_TEXT);
    $stmt->bindValue(':id',          $id,         SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}
