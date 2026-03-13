#!/usr/bin/env php
<?php
/**
 * 4KitchenBoard – Token Generator
 *
 * Run this script once from the command line to create a cryptographically
 * secure random token and write it to config.php:
 *
 *   php generate_token.php
 *
 * The script prompts for MySQL connection credentials, tests the connection
 * before writing anything, and writes a ready-to-use config.php.
 * Copy the printed token into the app's sync settings (API-Token field) on
 * every device that should be allowed to use this server.
 *
 * Re-running this script overwrites the old token with a new one, which
 * effectively locks out any device that still has the old token.
 *
 * Non-interactive mode (skip all prompts via environment variables):
 *   KB_DB_HOST=localhost KB_DB_NAME=kitchenboard \
 *   KB_DB_USER=kitchenboard KB_DB_PASS=secret \
 *   php generate_token.php
 */

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Print a line to STDOUT. */
function out(string $msg): void { echo $msg . PHP_EOL; }

/** Print a coloured header line (green). */
function heading(string $msg): void
{
    echo "\033[1;32m" . $msg . "\033[0m" . PHP_EOL;
}

/** Print a warning / error line (yellow). */
function warn(string $msg): void
{
    echo "\033[1;33m⚠  " . $msg . "\033[0m" . PHP_EOL;
}

/** Print an error and exit with a non-zero code. */
function fail(string $msg): void
{
    echo "\033[1;31m✗  " . $msg . "\033[0m" . PHP_EOL;
    exit(1);
}

/** Prompt interactively and return the trimmed input (or $default). */
function prompt(string $label, string $default = '', bool $isPassword = false): string
{
    echo $label . ($default !== '' ? " [$default]" : '') . ': ';

    // Hide password input where supported (Unix stty).
    if ($isPassword && PHP_OS_FAMILY !== 'Windows') {
        system('stty -echo 2>/dev/null');
        $input = fgets(STDIN);
        system('stty echo 2>/dev/null');
        echo PHP_EOL; // newline after hidden input
    } else {
        $input = fgets(STDIN);
    }

    $input = trim((string)$input);
    return $input !== '' ? $input : $default;
}

/** Return true when stdin is an interactive terminal. */
function isInteractive(): bool
{
    return defined('STDIN') && stream_isatty(STDIN);
}

/** Try to detect the local LAN IP address (best-effort). */
function localIpHint(): string
{
    if (PHP_OS_FAMILY === 'Windows') {
        $out = shell_exec('ipconfig 2>NUL') ?? '';
        if (preg_match('/IPv4[^:]+:\s*(192\.168\.\d+\.\d+)/i', $out, $m)) {
            return $m[1];
        }
        if (preg_match('/IPv4[^:]+:\s*(10\.\d+\.\d+\.\d+)/i', $out, $m)) {
            return $m[1];
        }
        if (preg_match('/IPv4[^:]+:\s*(172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)/i', $out, $m)) {
            return $m[1];
        }
    } else {
        $out = shell_exec('hostname -I 2>/dev/null') ?? '';
        foreach (explode(' ', $out) as $ip) {
            $ip = trim($ip);
            if (filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)
                    && strpos($ip, '127.') !== 0) {
                return $ip;
            }
        }
    }
    return '<server-ip>';
}

// ── Prerequisite checks ───────────────────────────────────────────────────────

if (!extension_loaded('pdo_mysql')) {
    fail('PHP extension pdo_mysql is not loaded.'
       . ' Install it (e.g. `apt install php-mysql`) and try again.');
}

if (!function_exists('random_bytes')) {
    fail('random_bytes() is not available. PHP 7.0+ is required.');
}

// ── Resolve configuration values ─────────────────────────────────────────────

$configFile = __DIR__ . '/config.php';
$configExists = file_exists($configFile);

if ($configExists) {
    out('');
    warn('config.php already exists.');
}

// Collect credentials: environment variables → interactive prompts → defaults.
$dbHost = getenv('KB_DB_HOST') ?: 'localhost';
$dbName = getenv('KB_DB_NAME') ?: 'kitchenboard';
$dbUser = getenv('KB_DB_USER') ?: '';
$dbPass = getenv('KB_DB_PASS') ?: '';

$interactive = isInteractive();
if ($interactive) {
    out('');
    heading('4KitchenBoard – Backend Setup');
    out('');

    if ($configExists) {
        $answer = prompt('Regenerate token and overwrite config.php? [y/N]', 'N');
        if (!in_array(strtolower($answer), ['y', 'yes'], true)) {
            out('Aborted.  config.php was not changed.');
            exit(0);
        }
        out('');
    }

    heading('MySQL credentials');

    // When a config already exists, try to parse current values as defaults.
    if ($configExists) {
        include_once $configFile; // defines DB_HOST, DB_NAME, DB_USER, DB_PASS
        $dbHost = defined('DB_HOST') ? DB_HOST : $dbHost;
        $dbName = defined('DB_NAME') ? DB_NAME : $dbName;
        $dbUser = defined('DB_USER') ? DB_USER : $dbUser;
        // Don't pre-fill password in prompt for security.
    }

    $dbHost = prompt('MySQL host', $dbHost);
    $dbName = prompt('MySQL database', $dbName);
    $dbUser = prompt('MySQL user', $dbUser);

    if ($dbUser === '') {
        fail('MySQL user must not be empty.');
    }

    $dbPass = prompt('MySQL password', '', true);
} else {
    // Non-interactive: validate that required fields are present.
    if ($dbUser === '') {
        fail('KB_DB_USER environment variable is required in non-interactive mode.');
    }
}

// ── Test MySQL connection ─────────────────────────────────────────────────────

out('');
out('Testing MySQL connection …');

try {
    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=utf8', $dbHost, $dbName);
    $pdo = new PDO($dsn, $dbUser, $dbPass, [PDO::ATTR_TIMEOUT => 5]);
    unset($pdo); // close connection immediately
    out("\033[1;32m✓  Connection successful.\033[0m");
} catch (PDOException $e) {
    out('');
    warn('Could not connect to MySQL: ' . $e->getMessage());

    if ($interactive) {
        $answer = prompt('Write config.php anyway? [y/N]', 'N');
        if (!in_array(strtolower($answer), ['y', 'yes'], true)) {
            out('Aborted.  config.php was not changed.');
            exit(1);
        }
    } else {
        fail('Aborting due to connection failure.'
            . ' Set KB_DB_* environment variables and re-run, or fix your MySQL setup.');
    }
}

// ── Generate token and write config.php ───────────────────────────────────────

// Generate 32 bytes (256 bits) of cryptographically secure random data,
// encoded as a 64-character lowercase hex string.
$token = bin2hex(random_bytes(32));
$generatedAt = date('Y-m-d H:i:s');

$dbHostEsc = var_export($dbHost, true);
$dbNameEsc = var_export($dbName, true);
$dbUserEsc = var_export($dbUser, true);
$dbPassEsc = var_export($dbPass, true);

$content = <<<PHP
<?php
/**
 * 4KitchenBoard – API access-token and database configuration
 *
 * Generated by generate_token.php on $generatedAt.
 * Set API_TOKEN to a non-empty string to require every API request to
 * supply a matching token in the X-Api-Token HTTP header.
 * Leave it empty ('') to disable token checking (open access – trusted
 * local network only).
 *
 * !! Keep this file private – never commit it or expose it via HTTP.
 * The accompanying .htaccess already blocks direct HTTP access.
 */

define('API_TOKEN', '$token');

// MySQL connection settings
define('DB_HOST', $dbHostEsc);
define('DB_NAME', $dbNameEsc);
define('DB_USER', $dbUserEsc);
define('DB_PASS', $dbPassEsc);
PHP;

if (file_put_contents($configFile, $content . PHP_EOL) === false) {
    fail('Could not write config.php – check directory permissions.');
}

// ── Summary ───────────────────────────────────────────────────────────────────

$ipHint  = localIpHint();
$baseUrl = "http://{$ipHint}/apps/kitchenboard/api.php";

out('');
heading('✓  Setup complete!');
out('');
out('  config.php has been written to: ' . realpath($configFile));
out('');
heading('Your API token (copy this into every device):');
out('  ' . $token);
out('');
heading('Server URL for the app (use your server\'s IP address):');
out('  ' . $baseUrl);
out('');
out('In the 4KitchenBoard app:');
out('  1. Open the shopping list and long-press the sync button (⟳ in the title bar).');
out('  2. Enter the Server URL shown above (replace the IP if different).');
out('  3. Paste the API token above into the "API-Token" field.');
out('  4. Tap "Save" – the sync indicator should turn green within a few seconds.');
out('');
out('Running this script again will generate a new token and revoke the old one.');
out('');
