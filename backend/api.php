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

$db->exec('CREATE TABLE IF NOT EXISTS items (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    category   TEXT    NOT NULL,
    checked    INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT 0,
    quantity   INTEGER NOT NULL DEFAULT 1
)');

// Add quantity column to existing tables that were created without it
$columnExists = false;
$result = $db->query('PRAGMA table_info(items)');
while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
    if ($row['name'] === 'quantity') { $columnExists = true; break; }
}
if (!$columnExists) {
    $db->exec('ALTER TABLE items ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1');
}

$db->exec('CREATE TABLE IF NOT EXISTS categories (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT    NOT NULL UNIQUE
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
        'SELECT id, name, category, quantity FROM items
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
        ];
    }
    echo json_encode(['items' => $items]);
}

function actionAdd(SQLite3 $db): void
{
    $name     = trim((string)($_POST['name']     ?? ''));
    $category = trim((string)($_POST['category'] ?? ''));
    $quantity = max(1, (int)($_POST['quantity'] ?? 1));

    if ($name === '' || $category === '') {
        http_response_code(400);
        echo json_encode(['error' => 'Parameters "name" and "category" are required']);
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO items (name, category, checked, created_at, quantity)
         VALUES (:name, :category, 0, :ts, :quantity)'
    );
    $stmt->bindValue(':name',     $name,     SQLITE3_TEXT);
    $stmt->bindValue(':category', $category, SQLITE3_TEXT);
    $stmt->bindValue(':ts',       (int)(microtime(true) * 1000), SQLITE3_INTEGER);
    $stmt->bindValue(':quantity', $quantity, SQLITE3_INTEGER);
    $stmt->execute();

    $id = $db->lastInsertRowID();

    // Persist category for future suggestions
    $stmtCat = $db->prepare(
        'INSERT OR IGNORE INTO categories (name) VALUES (:name)'
    );
    $stmtCat->bindValue(':name', $category, SQLITE3_TEXT);
    $stmtCat->execute();

    echo json_encode(['id' => $id, 'name' => $name, 'category' => $category, 'quantity' => $quantity]);
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
