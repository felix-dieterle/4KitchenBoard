<?php
/**
 * 4KitchenBoard – Shopping List Sync API
 *
 * Authentication: when config.php defines a non-empty API_TOKEN constant every
 * request must include an X-Api-Token HTTP header whose value matches that token.
 * Run generate_token.php once to create config.php with a secure random token and
 * MySQL connection settings.
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
 *   POST ?action=tasks_upsert  → body: id, title, sort_order[, assigned_to] → {"success":true}
 *   POST ?action=tasks_delete  → body: id                    → {"success":true}
 *
 * Error log endpoint:
 *   POST ?action=log_error  → body: message, level[, timestamp] → {"success":true}
 *
 * Update check:
 *   GET  ?action=check_update  → proxies GitHub releases; returns {tag_name, body, download_url}
 *
 * Storage: MySQL database.  Connection credentials are read from config.php
 * (DB_HOST, DB_NAME, DB_USER, DB_PASS).  Run generate_token.php to create
 * config.php with a secure API token and database credentials.
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

// Load the optional database credentials from config.php.
// Defaults work for a local dev setup; always set real credentials in production.
if (!defined('DB_HOST')) { define('DB_HOST', 'localhost'); }
if (!defined('DB_NAME')) { define('DB_NAME', 'kitchenboard'); }
if (!defined('DB_USER')) { define('DB_USER', ''); }
if (!defined('DB_PASS')) { define('DB_PASS', ''); }

try {
    $dsn = 'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8';
    $db  = new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(['error' => 'Could not connect to database: ' . $e->getMessage()]);
    exit;
}

$db->exec("CREATE TABLE IF NOT EXISTS items (
    id         INT           AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255)  NOT NULL,
    category   VARCHAR(255)  NOT NULL,
    checked    TINYINT(1)    NOT NULL DEFAULT 0,
    created_at BIGINT        NOT NULL DEFAULT 0,
    quantity   INT           NOT NULL DEFAULT 1,
    shop       VARCHAR(255)  NOT NULL DEFAULT '',
    priority   INT           NOT NULL DEFAULT 2,
    board_id   VARCHAR(255)  NOT NULL DEFAULT ''
)");

// ── Column migrations for the items table ─────────────────────────────────────
if (!columnExists($db, 'items', 'quantity')) {
    $db->exec('ALTER TABLE items ADD COLUMN quantity INT NOT NULL DEFAULT 1');
}
if (!columnExists($db, 'items', 'shop')) {
    $db->exec("ALTER TABLE items ADD COLUMN shop VARCHAR(255) NOT NULL DEFAULT ''");
}
if (!columnExists($db, 'items', 'priority')) {
    $db->exec('ALTER TABLE items ADD COLUMN priority INT NOT NULL DEFAULT 2');
}
if (!columnExists($db, 'items', 'board_id')) {
    $db->exec("ALTER TABLE items ADD COLUMN board_id VARCHAR(255) NOT NULL DEFAULT ''");
}

$db->exec('CREATE TABLE IF NOT EXISTS categories (
    id   INT          AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
)');

$db->exec("CREATE TABLE IF NOT EXISTS calendar_appointments (
    id        INT          NOT NULL,
    board_id  VARCHAR(255) NOT NULL DEFAULT '',
    date      VARCHAR(20)  NOT NULL,
    time      VARCHAR(10)  DEFAULT NULL,
    title     VARCHAR(255) NOT NULL,
    series_id INT          DEFAULT NULL,
    PRIMARY KEY (id, board_id)
)");

if (!columnExists($db, 'calendar_appointments', 'board_id')) {
    $db->exec("ALTER TABLE calendar_appointments ADD COLUMN board_id VARCHAR(255) NOT NULL DEFAULT ''");
}

$db->exec("CREATE TABLE IF NOT EXISTS cooking_dishes (
    id               INT          NOT NULL,
    board_id         VARCHAR(255) NOT NULL DEFAULT '',
    name             VARCHAR(255) NOT NULL,
    duration_minutes INT          NOT NULL DEFAULT 0,
    ingredients      TEXT         DEFAULT NULL,
    notes            TEXT         DEFAULT NULL,
    last_cooked      VARCHAR(20)  DEFAULT NULL,
    PRIMARY KEY (id, board_id)
)");

if (!columnExists($db, 'cooking_dishes', 'board_id')) {
    $db->exec("ALTER TABLE cooking_dishes ADD COLUMN board_id VARCHAR(255) NOT NULL DEFAULT ''");
}

$db->exec("CREATE TABLE IF NOT EXISTS tasks (
    id          INT          NOT NULL,
    board_id    VARCHAR(255) NOT NULL DEFAULT '',
    title       VARCHAR(255) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    assigned_to VARCHAR(255) NOT NULL DEFAULT '',
    PRIMARY KEY (id, board_id)
)");

if (!columnExists($db, 'tasks', 'board_id')) {
    $db->exec("ALTER TABLE tasks ADD COLUMN board_id VARCHAR(255) NOT NULL DEFAULT ''");
}

if (!columnExists($db, 'tasks', 'assigned_to')) {
    $db->exec("ALTER TABLE tasks ADD COLUMN assigned_to VARCHAR(255) NOT NULL DEFAULT ''");
}

$db->exec("CREATE TABLE IF NOT EXISTS error_logs (
    id         INT          AUTO_INCREMENT PRIMARY KEY,
    board_id   VARCHAR(255) NOT NULL DEFAULT '',
    level      VARCHAR(20)  NOT NULL DEFAULT 'ERROR',
    message    TEXT         NOT NULL,
    timestamp  VARCHAR(30)  NOT NULL DEFAULT ''
)");

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
    case 'log_error':
        logErrorEntry($db, $boardId);
        break;
    case 'check_update':
        $db = null;
        checkUpdate();
        exit;
    default:
        http_response_code(400);
        echo json_encode(['error' => 'Unknown or missing action']);
}

$db = null;
exit;

// ── Action handlers ───────────────────────────────────────────────────────────

/**
 * Returns true when the given column exists in the given table.
 * Uses information_schema so it works reliably across MySQL versions.
 */
function columnExists(PDO $db, string $table, string $column): bool
{
    $stmt = $db->prepare(
        'SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?'
    );
    $stmt->execute([$table, $column]);
    return (int)$stmt->fetchColumn() > 0;
}

function actionList(PDO $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, name, category, quantity, shop, priority FROM items
         WHERE checked = 0 AND board_id = :board_id
         ORDER BY priority ASC, category ASC, name ASC'
    );
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();
    $items = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
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

function actionAdd(PDO $db, string $boardId): void
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
    $stmt->bindValue(':name',     $name,     PDO::PARAM_STR);
    $stmt->bindValue(':category', $category, PDO::PARAM_STR);
    $stmt->bindValue(':ts',       (int)(microtime(true) * 1000), PDO::PARAM_INT);
    $stmt->bindValue(':quantity', $quantity, PDO::PARAM_INT);
    $stmt->bindValue(':shop',     $shop,     PDO::PARAM_STR);
    $stmt->bindValue(':priority', $priority, PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId,  PDO::PARAM_STR);
    $stmt->execute();

    $id = (int)$db->lastInsertId();

    // Persist category for future suggestions (global, not scoped per board)
    $stmtCat = $db->prepare(
        'INSERT IGNORE INTO categories (name) VALUES (:name)'
    );
    $stmtCat->bindValue(':name', $category, PDO::PARAM_STR);
    $stmtCat->execute();

    echo json_encode(['id' => $id, 'name' => $name, 'category' => $category, 'quantity' => $quantity, 'shop' => $shop, 'priority' => $priority]);
}

function actionCheck(PDO $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET checked = 1 WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionDelete(PDO $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM items WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function actionUpdateQuantity(PDO $db, string $boardId): void
{
    $id       = (int)($_POST['id']       ?? 0);
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('UPDATE items SET quantity = :quantity WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':quantity', $quantity, PDO::PARAM_INT);
    $stmt->bindValue(':id',       $id,       PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId,  PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Calendar action handlers ──────────────────────────────────────────────────

function calendarList(PDO $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, date, time, title, series_id FROM calendar_appointments
         WHERE board_id = :board_id
         ORDER BY date ASC, time ASC, title ASC'
    );
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();
    $appointments = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
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

function calendarUpsert(PDO $db, string $boardId): void
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
    $del->bindValue(':id',       $id,      PDO::PARAM_INT);
    $del->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO calendar_appointments (id, board_id, date, time, title, series_id)
         VALUES (:id, :board_id, :date, :time, :title, :series_id)'
    );
    $stmt->bindValue(':id',        $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id',  $boardId, PDO::PARAM_STR);
    $stmt->bindValue(':date',      $date,    PDO::PARAM_STR);
    $stmt->bindValue(':time',      $time !== '' ? $time : null, $time !== '' ? PDO::PARAM_STR : PDO::PARAM_NULL);
    $stmt->bindValue(':title',     $title,   PDO::PARAM_STR);
    $stmt->bindValue(':series_id', $seriesId, $seriesId !== null ? PDO::PARAM_INT : PDO::PARAM_NULL);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDelete(PDO $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarDeleteSeries(PDO $db, string $boardId): void
{
    $seriesId = (int)($_POST['series_id'] ?? 0);
    if ($seriesId <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "series_id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM calendar_appointments WHERE series_id = :series_id AND board_id = :board_id');
    $stmt->bindValue(':series_id', $seriesId, PDO::PARAM_INT);
    $stmt->bindValue(':board_id',  $boardId,  PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function calendarUpdateDatetime(PDO $db, string $boardId): void
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
    $stmt->bindValue(':date',     $date,    PDO::PARAM_STR);
    $stmt->bindValue(':time',     $time !== '' ? $time : null, $time !== '' ? PDO::PARAM_STR : PDO::PARAM_NULL);
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Cooking action handlers ───────────────────────────────────────────────────

function cookingList(PDO $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, name, duration_minutes, ingredients, notes, last_cooked
         FROM cooking_dishes WHERE board_id = :board_id ORDER BY name ASC'
    );
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();
    $dishes = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
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

function cookingUpsert(PDO $db, string $boardId): void
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
    $del->bindValue(':id',       $id,      PDO::PARAM_INT);
    $del->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO cooking_dishes (id, board_id, name, duration_minutes, ingredients, notes, last_cooked)
         VALUES (:id, :board_id, :name, :duration, :ingredients, :notes, :last_cooked)'
    );
    $stmt->bindValue(':id',          $id,       PDO::PARAM_INT);
    $stmt->bindValue(':board_id',    $boardId,  PDO::PARAM_STR);
    $stmt->bindValue(':name',        $name,     PDO::PARAM_STR);
    $stmt->bindValue(':duration',    $duration, PDO::PARAM_INT);
    $stmt->bindValue(':ingredients', $ingredients !== '' ? $ingredients : null,
                     $ingredients !== '' ? PDO::PARAM_STR : PDO::PARAM_NULL);
    $stmt->bindValue(':notes',       $notes !== '' ? $notes : null,
                     $notes !== '' ? PDO::PARAM_STR : PDO::PARAM_NULL);
    $stmt->bindValue(':last_cooked', $lastCooked !== '' ? $lastCooked : null,
                     $lastCooked !== '' ? PDO::PARAM_STR : PDO::PARAM_NULL);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function cookingDelete(PDO $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM cooking_dishes WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function cookingMarkCooked(PDO $db, string $boardId): void
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
    $stmt->bindValue(':last_cooked', $lastCooked, PDO::PARAM_STR);
    $stmt->bindValue(':id',          $id,         PDO::PARAM_INT);
    $stmt->bindValue(':board_id',    $boardId,    PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Tasks action handlers ─────────────────────────────────────────────────────

function tasksList(PDO $db, string $boardId): void
{
    $stmt = $db->prepare(
        'SELECT id, title, sort_order, assigned_to FROM tasks WHERE board_id = :board_id ORDER BY sort_order ASC'
    );
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();
    $tasks = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
        $tasks[] = [
            'id'          => (int)$row['id'],
            'title'       => $row['title'],
            'sort_order'  => (int)$row['sort_order'],
            'assigned_to' => $row['assigned_to'],
        ];
    }
    echo json_encode(['tasks' => $tasks]);
}

function tasksUpsert(PDO $db, string $boardId): void
{
    $id         = (int)($_POST['id']          ?? 0);
    $title      = trim((string)($_POST['title']      ?? ''));
    $sortOrder  = (int)($_POST['sort_order']  ?? 0);
    $assignedTo = trim((string)($_POST['assigned_to'] ?? ''));

    if ($id <= 0 || $title === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "id" and "title" are required']);
        return;
    }

    // Delete existing row for this (id, board_id) pair, then insert fresh
    $del = $db->prepare('DELETE FROM tasks WHERE id = :id AND board_id = :board_id');
    $del->bindValue(':id',       $id,      PDO::PARAM_INT);
    $del->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $del->execute();

    $stmt = $db->prepare(
        'INSERT INTO tasks (id, board_id, title, sort_order, assigned_to) VALUES (:id, :board_id, :title, :sort_order, :assigned_to)'
    );
    $stmt->bindValue(':id',          $id,         PDO::PARAM_INT);
    $stmt->bindValue(':board_id',    $boardId,    PDO::PARAM_STR);
    $stmt->bindValue(':title',       $title,      PDO::PARAM_STR);
    $stmt->bindValue(':sort_order',  $sortOrder,  PDO::PARAM_INT);
    $stmt->bindValue(':assigned_to', $assignedTo, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function tasksDelete(PDO $db, string $boardId): void
{
    $id = (int)($_POST['id'] ?? 0);
    if ($id <= 0) {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "id" is required']);
        return;
    }

    $stmt = $db->prepare('DELETE FROM tasks WHERE id = :id AND board_id = :board_id');
    $stmt->bindValue(':id',       $id,      PDO::PARAM_INT);
    $stmt->bindValue(':board_id', $boardId, PDO::PARAM_STR);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ── Error log action handler ──────────────────────────────────────────────────

/**
 * Stores a single error log entry sent by the Android app.
 * Body parameters: message (required), level (optional, default ERROR), timestamp (optional).
 */
function logErrorEntry(PDO $db, string $boardId): void
{
    $message   = trim((string)($_POST['message']   ?? ''));
    $level     = trim((string)($_POST['level']     ?? 'ERROR'));
    $timestamp = trim((string)($_POST['timestamp'] ?? ''));

    if ($message === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameter "message" is required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO error_logs (board_id, level, message, timestamp)
         VALUES (:board_id, :level, :message, :timestamp)'
    );
    $stmt->bindValue(':board_id',  $boardId,  PDO::PARAM_STR);
    $stmt->bindValue(':level',     $level,    PDO::PARAM_STR);
    $stmt->bindValue(':message',   $message,  PDO::PARAM_STR);
    $stmt->bindValue(':timestamp', $timestamp, PDO::PARAM_STR);
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
