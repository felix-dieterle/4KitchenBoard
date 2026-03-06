<?php
/**
 * 4KitchenBoard – Shopping List Sync API
 *
 * Authentication: when config.php defines a non-empty API_TOKEN constant every
 * request must include an X-Api-Token HTTP header whose value matches that token.
 * Run generate_token.php once to create config.php with a secure random token.
 *
 * All endpoints accept an optional `board_token` parameter (GET or POST).
 * Passing the same token on multiple devices lets them share a board.
 * Existing installations without a token continue to work unchanged.
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
 *   GET  ?action=tasks_list    → JSON list of all tasks sorted by sort_order
 *   POST ?action=tasks_upsert  → body: id, title, sort_order → {"success":true}
 *   POST ?action=tasks_delete  → body: id                    → {"success":true}
 *
 * Update check:
 *   GET  ?action=check_update  → proxies GitHub releases; returns {tag_name, body, download_url}
 *
 * Storage: SQLite3 file (shopping.db) placed beside this script.
 * The database file is protected by .htaccess so it cannot be downloaded.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, X-Api-Token');

// Handle pre-flight OPTIONS request
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// ── Token authentication ───────────────────────────────────────────────────────

// Load the optional token configuration (defines API_TOKEN constant).
// If the file is absent the constant defaults to '' (no auth required).
$configFile = __DIR__ . '/config.php';
if (file_exists($configFile)) {
    require_once $configFile;
}
if (!defined('API_TOKEN')) {
    define('API_TOKEN', '');
}

// When a token is configured, every request must supply it in the
// X-Api-Token header.  An empty token means the server is open (e.g. a
// trusted local network that does not need extra protection).
if (API_TOKEN !== '') {
    // $_SERVER['HTTP_X_API_TOKEN'] is set by Apache/mod_php and most PHP-FPM
    // setups.  As a fallback, try getallheaders() which works on all SAPI
    // environments and preserves the original header name capitalisation.
    $requestToken = $_SERVER['HTTP_X_API_TOKEN'] ?? '';
    if ($requestToken === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $headerName => $headerValue) {
            if (strtolower($headerName) === 'x-api-token') {
                $requestToken = $headerValue;
                break;
            }
        }
    }
    if (!hash_equals(API_TOKEN, $requestToken)) {
        http_response_code(401);
        echo json_encode(['error' => 'Unauthorized: invalid or missing API token']);
        exit;
    }
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
    shop       TEXT    NOT NULL DEFAULT '',
    priority   INTEGER NOT NULL DEFAULT 2,
    board_id   TEXT    NOT NULL DEFAULT ''
)");

// ── Column migrations for the items table ─────────────────────────────────────
$itemsColumns = [];
$r = $db->query('PRAGMA table_info(items)');
while ($row = $r->fetchArray(SQLITE3_ASSOC)) { $itemsColumns[] = $row['name']; }

if (!in_array('quantity', $itemsColumns)) {
    $db->exec('ALTER TABLE items ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1');
}
if (!in_array('shop', $itemsColumns)) {
    $db->exec("ALTER TABLE items ADD COLUMN shop TEXT NOT NULL DEFAULT ''");
}
if (!in_array('priority', $itemsColumns)) {
    $db->exec('ALTER TABLE items ADD COLUMN priority INTEGER NOT NULL DEFAULT 2');
}
if (!in_array('board_id', $itemsColumns)) {
    $db->exec("ALTER TABLE items ADD COLUMN board_id TEXT NOT NULL DEFAULT ''");
}

$db->exec('CREATE TABLE IF NOT EXISTS categories (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT    NOT NULL UNIQUE
)');

$db->exec('CREATE TABLE IF NOT EXISTS calendar_appointments (
    id        INTEGER NOT NULL,
    board_id  TEXT    NOT NULL DEFAULT \'\',
    date      TEXT    NOT NULL,
    time      TEXT,
    title     TEXT    NOT NULL,
    series_id INTEGER,
    PRIMARY KEY (id, board_id)
)');

// Migrate calendar_appointments: add board_id column if missing (old single-PK schema)
$calColumns = [];
$r2 = $db->query('PRAGMA table_info(calendar_appointments)');
while ($row = $r2->fetchArray(SQLITE3_ASSOC)) { $calColumns[] = $row['name']; }
if (!in_array('board_id', $calColumns)) {
    // Old table has id as sole PK – add column and recreate with composite PK
    $db->exec("ALTER TABLE calendar_appointments ADD COLUMN board_id TEXT NOT NULL DEFAULT ''");
    $db->exec('CREATE TABLE IF NOT EXISTS calendar_appointments_new (
        id        INTEGER NOT NULL,
        board_id  TEXT    NOT NULL DEFAULT \'\',
        date      TEXT    NOT NULL,
        time      TEXT,
        title     TEXT    NOT NULL,
        series_id INTEGER,
        PRIMARY KEY (id, board_id)
    )');
    $db->exec('INSERT OR IGNORE INTO calendar_appointments_new
               SELECT id, board_id, date, time, title, series_id FROM calendar_appointments');
    $db->exec('DROP TABLE calendar_appointments');
    $db->exec('ALTER TABLE calendar_appointments_new RENAME TO calendar_appointments');
}

$db->exec('CREATE TABLE IF NOT EXISTS cooking_dishes (
    id               INTEGER NOT NULL,
    board_id         TEXT    NOT NULL DEFAULT \'\',
    name             TEXT    NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 0,
    ingredients      TEXT,
    notes            TEXT,
    last_cooked      TEXT,
    PRIMARY KEY (id, board_id)
)');

// Migrate cooking_dishes: add board_id column if missing
$cookColumns = [];
$r3 = $db->query('PRAGMA table_info(cooking_dishes)');
while ($row = $r3->fetchArray(SQLITE3_ASSOC)) { $cookColumns[] = $row['name']; }
if (!in_array('board_id', $cookColumns)) {
    $db->exec("ALTER TABLE cooking_dishes ADD COLUMN board_id TEXT NOT NULL DEFAULT ''");
    $db->exec('CREATE TABLE IF NOT EXISTS cooking_dishes_new (
        id               INTEGER NOT NULL,
        board_id         TEXT    NOT NULL DEFAULT \'\',
        name             TEXT    NOT NULL,
        duration_minutes INTEGER NOT NULL DEFAULT 0,
        ingredients      TEXT,
        notes            TEXT,
        last_cooked      TEXT,
        PRIMARY KEY (id, board_id)
    )');
    $db->exec('INSERT OR IGNORE INTO cooking_dishes_new
               SELECT id, board_id, name, duration_minutes, ingredients, notes, last_cooked FROM cooking_dishes');
    $db->exec('DROP TABLE cooking_dishes');
    $db->exec('ALTER TABLE cooking_dishes_new RENAME TO cooking_dishes');
}

$db->exec('CREATE TABLE IF NOT EXISTS tasks (
    id         INTEGER NOT NULL,
    board_id   TEXT    NOT NULL DEFAULT \'\',
    title      TEXT    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (id, board_id)
)');

// Migrate tasks: add board_id column if missing
$taskColumns = [];
$r4 = $db->query('PRAGMA table_info(tasks)');
while ($row = $r4->fetchArray(SQLITE3_ASSOC)) { $taskColumns[] = $row['name']; }
if (!in_array('board_id', $taskColumns)) {
    $db->exec("ALTER TABLE tasks ADD COLUMN board_id TEXT NOT NULL DEFAULT ''");
    $db->exec('CREATE TABLE IF NOT EXISTS tasks_new (
        id         INTEGER NOT NULL,
        board_id   TEXT    NOT NULL DEFAULT \'\',
        title      TEXT    NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (id, board_id)
    )');
    $db->exec('INSERT OR IGNORE INTO tasks_new
               SELECT id, board_id, title, sort_order FROM tasks');
    $db->exec('DROP TABLE tasks');
    $db->exec('ALTER TABLE tasks_new RENAME TO tasks');
}

// ── Dispatch ──────────────────────────────────────────────────────────────────

$action  = trim((string)($_GET['action']     ?? $_POST['action']     ?? ''));
$boardId = trim((string)($_GET['board_token'] ?? $_POST['board_token'] ?? ''));

switch ($action) {
    case 'list':
        actionList($db, $boardId);
        break;
    case 'add':
        actionAdd($db, $boardId);
        break;
    case 'check':
        actionCheck($db, $boardId);
        break;
    case 'delete':
        actionDelete($db, $boardId);
        break;
    case 'update_quantity':
        actionUpdateQuantity($db, $boardId);
        break;
    case 'calendar_list':
        calendarList($db, $boardId);
        break;
    case 'calendar_upsert':
        calendarUpsert($db, $boardId);
        break;
    case 'calendar_delete':
        calendarDelete($db, $boardId);
        break;
    case 'calendar_delete_series':
        calendarDeleteSeries($db, $boardId);
        break;
    case 'calendar_update_datetime':
        calendarUpdateDatetime($db, $boardId);
        break;
    case 'cooking_list':
        cookingList($db, $boardId);
        break;
    case 'cooking_upsert':
        cookingUpsert($db, $boardId);
        break;
    case 'cooking_delete':
        cookingDelete($db, $boardId);
        break;
    case 'cooking_mark_cooked':
        cookingMarkCooked($db, $boardId);
        break;
    case 'tasks_list':
        tasksList($db, $boardId);
        break;
    case 'tasks_upsert':
        tasksUpsert($db, $boardId);
        break;
    case 'tasks_delete':
        tasksDelete($db, $boardId);
        break;
    case 'check_update':
        $db->close();
        checkUpdate();
        exit;
    default:
        http_response_code(400);
        echo json_encode(['error' => 'Unknown or missing action']);
}

$db->close();
exit;

// ── Action handlers ───────────────────────────────────────────────────────────

function actionList(SQLite3 $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, name, category, quantity, shop, priority FROM items
         WHERE checked = 0 AND board_id = :board_id
         ORDER BY priority ASC, category ASC, name ASC'
    );
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $result = $stmt->execute();
    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = [
            'id'       => (int)$row['id'],
            'name'     => $row['name'],
            'category' => $row['category'],
            'quantity' => (int)$row['quantity'],
            'shop'     => (string)$row['shop'],
            'priority' => (int)$row['priority'],
        ];
    }
    echo json_encode(['items' => $items]);
}

function actionAdd(SQLite3 $db, string $boardId): void
{
    $name     = trim((string)($_POST['name']     ?? ''));
    $category = trim((string)($_POST['category'] ?? ''));
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));
    $shop     = trim((string)($_POST['shop']     ?? ''));
    $priority = (int)($_POST['priority'] ?? 2);
    if ($priority < 1 || $priority > 3) { $priority = 2; }

    if ($name === '' || $category === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "name" and "category" are required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO items (name, category, checked, created_at, quantity, shop, priority, board_id)
         VALUES (:name, :category, 0, :ts, :quantity, :shop, :priority, :board_id)'
    );
    $stmt->bindValue(':name',     $name,     SQLITE3_TEXT);
    $stmt->bindValue(':category', $category, SQLITE3_TEXT);
    $stmt->bindValue(':ts',       (int)(microtime(true) * 1000), SQLITE3_INTEGER);
    $stmt->bindValue(':quantity', $quantity, SQLITE3_INTEGER);
    $stmt->bindValue(':shop',     $shop,     SQLITE3_TEXT);
    $stmt->bindValue(':priority', $priority, SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId,  SQLITE3_TEXT);
    $stmt->execute();

    $id = $db->lastInsertRowID();

    // Persist category for future suggestions (global, not scoped per board)
    $stmtCat = $db->prepare(
        'INSERT OR IGNORE INTO categories (name) VALUES (:name)'
    );
    $stmtCat->bindValue(':name', $category, SQLITE3_TEXT);
    $stmtCat->execute();

    echo json_encode(['id' => $id, 'name' => $name, 'category' => $category, 'quantity' => $quantity, 'shop' => $shop, 'priority' => $priority]);
}

function actionCheck(SQLite3 $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET checked = 1 WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionDelete(SQLite3 $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM items WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionUpdateQuantity(SQLite3 $db, string $boardId): void
{
    $id       = (int)($_POST['id']       ?? 0);
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET quantity = :quantity WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':quantity', $quantity, SQLITE3_INTEGER);
    $stmt->bindValue(':id',       $id,       SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId,  SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Calendar action handlers ──────────────────────────────────────────────────

function calendarList(SQLite3 $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, date, time, title, series_id FROM calendar_appointments
         WHERE board_id = :board_id
         ORDER BY date ASC, time ASC, title ASC'
    );
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $result = $stmt->execute();
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

function calendarUpsert(SQLite3 $db, string $boardId): void
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

    // Delete existing row for this (id, board_id) pair, then insert fresh
    $del = $db->prepare('DELETE FROM calendar_appointments WHERE id = :id AND board_id = :board_id');
    $del->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $del->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO calendar_appointments (id, board_id, date, time, title, series_id)
         VALUES (:id, :board_id, :date, :time, :title, :series_id)'
    );
    $stmt->bindValue(':id',        $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id',  $boardId, SQLITE3_TEXT);
    $stmt->bindValue(':date',      $date,    SQLITE3_TEXT);
    $stmt->bindValue(':time',      $time !== '' ? $time : null, $time !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':title',     $title,   SQLITE3_TEXT);
    $stmt->bindValue(':series_id', $seriesId, $seriesId !== null ? SQLITE3_INTEGER : SQLITE3_NULL);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDelete(SQLite3 $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDeleteSeries(SQLite3 $db, string $boardId): void
{
    $seriesId = (int)($_POST['series_id'] ?? 0);
    if ($seriesId <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "series_id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE series_id = :series_id AND board_id = :board_id');
    $stmt->bindValue(':series_id', $seriesId, SQLITE3_INTEGER);
    $stmt->bindValue(':board_id',  $boardId,  SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarUpdateDatetime(SQLite3 $db, string $boardId): void
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
        'UPDATE calendar_appointments SET date = :date, time = :time, series_id = NULL
         WHERE id = :id AND board_id = :board_id'
    );
    $stmt->bindValue(':date',     $date,    SQLITE3_TEXT);
    $stmt->bindValue(':time',     $time !== '' ? $time : null, $time !== '' ? SQLITE3_TEXT : SQLITE3_NULL);
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Cooking action handlers ───────────────────────────────────────────────────

function cookingList(SQLite3 $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, name, duration_minutes, ingredients, notes, last_cooked
         FROM cooking_dishes WHERE board_id = :board_id ORDER BY name ASC'
    );
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $result = $stmt->execute();
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

function cookingUpsert(SQLite3 $db, string $boardId): void
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

    // Delete existing row for this (id, board_id) pair, then insert fresh
    $del = $db->prepare('DELETE FROM cooking_dishes WHERE id = :id AND board_id = :board_id');
    $del->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $del->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO cooking_dishes (id, board_id, name, duration_minutes, ingredients, notes, last_cooked)
         VALUES (:id, :board_id, :name, :duration, :ingredients, :notes, :last_cooked)'
    );
    $stmt->bindValue(':id',          $id,       SQLITE3_INTEGER);
    $stmt->bindValue(':board_id',    $boardId,  SQLITE3_TEXT);
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

function cookingDelete(SQLite3 $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM cooking_dishes WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function cookingMarkCooked(SQLite3 $db, string $boardId): void
{
    $id         = (int)($_POST['id']          ?? 0);
    $lastCooked = trim((string)($_POST['last_cooked'] ?? ''));

    if ($id <= 0 || $lastCooked === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "last_cooked" are required']);
        return;
    }

    $stmt = $db->prepare(
        'UPDATE cooking_dishes SET last_cooked = :last_cooked WHERE id = :id AND board_id = :board_id'
    );
    $stmt->bindValue(':last_cooked', $lastCooked, SQLITE3_TEXT);
    $stmt->bindValue(':id',          $id,         SQLITE3_INTEGER);
    $stmt->bindValue(':board_id',    $boardId,    SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Tasks action handlers ─────────────────────────────────────────────────────

function tasksList(SQLite3 $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, title, sort_order FROM tasks WHERE board_id = :board_id ORDER BY sort_order ASC'
    );
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $result = $stmt->execute();
    $tasks = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $tasks[] = [
            'id'         => (int)$row['id'],
            'title'      => $row['title'],
            'sort_order' => (int)$row['sort_order'],
        ];
    }
    echo json_encode(['tasks' => $tasks]);
}

function tasksUpsert(SQLite3 $db, string $boardId): void
{
    $id        = (int)($_POST['id']         ?? 0);
    $title     = trim((string)($_POST['title']     ?? ''));
    $sortOrder = (int)($_POST['sort_order'] ?? 0);

    if ($id <= 0 || $title === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "title" are required']);
        return;
    }

    // Delete existing row for this (id, board_id) pair, then insert fresh
    $del = $db->prepare('DELETE FROM tasks WHERE id = :id AND board_id = :board_id');
    $del->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $del->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO tasks (id, board_id, title, sort_order) VALUES (:id, :board_id, :title, :sort_order)'
    );
    $stmt->bindValue(':id',         $id,        SQLITE3_INTEGER);
    $stmt->bindValue(':board_id',   $boardId,   SQLITE3_TEXT);
    $stmt->bindValue(':title',      $title,     SQLITE3_TEXT);
    $stmt->bindValue(':sort_order', $sortOrder, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function tasksDelete(SQLite3 $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM tasks WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      SQLITE3_INTEGER);
    $stmt->bindValue(':board_id', $boardId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Update check ──────────────────────────────────────────────────────────────

/**
 * Proxies the GitHub Releases API to expose the latest release info.
 * Returns {"tag_name": "v1.0-5", "body": "...", "download_url": "https://..."}.
 * Protected by the same X-Api-Token auth as all other endpoints.
 */
function checkUpdate(): void
{
    $githubUrl = 'https://api.github.com/repos/felix-dieterle/4KitchenBoard/releases/latest';
    $ctx = stream_context_create([
        'http' => [
            'method'  => 'GET',
            'header'  => "User-Agent: 4KitchenBoard-Server\r\nAccept: application/json\r\n",
            'timeout' => 10,
        ],
    ]);

    $raw = @file_get_contents($githubUrl, false, $ctx);
    if ($raw === false) {
        http_response_code(502);
        echo json_encode(['error' => 'Failed to fetch release information from GitHub']);
        return;
    }

    $data = json_decode($raw, true);
    if (!is_array($data) || !isset($data['tag_name'])) {
        http_response_code(502);
        echo json_encode(['error' => 'Invalid release data returned by GitHub']);
        return;
    }

    // Resolve the direct APK download URL from the release assets
    $downloadUrl = '';
    if (!empty($data['assets']) && is_array($data['assets'])) {
        foreach ($data['assets'] as $asset) {
            $name = $asset['name'] ?? '';
            if (substr($name, -4) === '.apk') {
                $downloadUrl = $asset['browser_download_url'];
                break;
            }
        }
    }
    // Fall back to the release HTML page when no APK asset is attached
    if ($downloadUrl === '') {
        $downloadUrl = $data['html_url'] ?? '';
    }

    echo json_encode([
        'tag_name'     => $data['tag_name'],
        'body'         => $data['body'] ?? '',
        'download_url' => $downloadUrl,
    ]);
}
