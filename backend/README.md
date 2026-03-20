# 4KitchenBoard – Shopping List Sync Backend

A minimal PHP/MySQL REST API that lets multiple 4KitchenBoard devices share and synchronise the same shopping list in real time.

## Requirements

| Requirement | Version |
|---|---|
| PHP | ≥ 7.4 (including 8.2) |
| PHP extension | `pdo_mysql` (enabled by default in most distros) |
| MySQL / MariaDB | ≥ 5.2 / ≥ 5.2 |
| Web server | Apache 2.4+ with `mod_rewrite` (or Nginx – see below) |

## Installation

1. **Create a MySQL database** for 4KitchenBoard:
   ```sql
   CREATE DATABASE kitchenboard CHARACTER SET utf8 COLLATE utf8_unicode_ci;
   CREATE USER 'kitchenboard'@'localhost' IDENTIFIED BY 'strong-random-password';
   GRANT ALL PRIVILEGES ON kitchenboard.* TO 'kitchenboard'@'localhost';
   FLUSH PRIVILEGES;
   ```
   Use a strong, unique password. The tables are created automatically by `api.php` on the first request.
   > **Note:** `utf8` is used for compatibility with MySQL ≥ 5.2 / MariaDB ≥ 5.2.
   > If your server runs MySQL ≥ 5.5.3 or MariaDB ≥ 5.5 you may use `utf8mb4 / utf8mb4_unicode_ci`
   > for full 4-byte Unicode support (e.g. emoji), but this is not required.

2. **Copy the `backend/` folder** to a directory that is served by your web server.
   The recommended deployment path is `/var/www/html/apps/kitchenboard/`:
   ```bash
   cp -r backend/ /var/www/html/apps/kitchenboard/
   ```

3. **Enable `AllowOverride All`** in your Apache virtual host so that the `.htaccess` rules take effect:
   ```apache
   <Directory /var/www/html/apps/kitchenboard/>
       AllowOverride All
   </Directory>
   ```

4. **Generate an API token** (recommended) so that only your devices can use the API.
   The script also asks for MySQL credentials and writes them to `config.php`:
   ```bash
   php /var/www/html/apps/kitchenboard/generate_token.php
   ```
   Answer the prompts for MySQL host, database, user, and password.
   The script creates `config.php` with a 64-character random hex token and prints
   it to the console.  Copy the token into the app's sync settings on every device.

   Alternatively, set the credentials via environment variables to skip the prompts:
   ```bash
   KB_DB_HOST=localhost KB_DB_NAME=kitchenboard KB_DB_USER=kitchenboard KB_DB_PASS=secret \
       php /var/www/html/apps/kitchenboard/generate_token.php
   ```

5. **Point the app** to the API by opening the shopping list in 4KitchenBoard,
   long-pressing the sync button (⟳ in the title bar) and entering:
   * **Server URL** – `http://<server-ip>/apps/kitchenboard/api.php`
   * **API Token** – the token printed by `generate_token.php` (leave empty if you skipped step 4)

   Use the LAN IP address so all devices on the same network can reach it.

   > **Note (Android 9+ / API 28+):** Android blocks unencrypted HTTP traffic by
   > default.  4KitchenBoard ships with a `network_security_config.xml` that
   > explicitly allows cleartext HTTP, so plain `http://` URLs work correctly.
   > If you see _"Cleartext HTTP traffic … not permitted"_ in the logs, make sure
   > you are running a build from this repository (not an older APK).

## Windows Auto-Update (keeping backend files in sync automatically)

The script `scripts/update_backend_windows.ps1` (in the repository root) clones the
repository once and afterwards keeps the `backend/` files in sync with the `main` branch.
It is designed to run unattended via **Windows Task Scheduler**.

### One-time setup

1. Install **Git for Windows** from <https://git-scm.com/download/win> if it is not
   already installed.

2. Open `scripts/update_backend_windows.ps1` in a text editor and adjust the three
   variables at the top of the file:

   | Variable | Default | Meaning |
   |---|---|---|
   | `$ApacheTargetPath` | `C:\xampp\htdocs\apps\kitchenboard` | Where Apache/XAMPP serves files |
   | `$LocalRepoPath` | `C:\kitchenboard-repo` | Where the repo clone is stored |
   | `$GitHubBranch` | `main` | Branch to track |

3. Run the script once manually to verify it works:
   ```powershell
   powershell -ExecutionPolicy Bypass -File "C:\path\to\scripts\update_backend_windows.ps1"
   ```
   It will clone the repository, copy all backend files (except `config.php`) to your
   Apache directory, and print a log to the console and to `update_backend.log` next to
   the script.

4. After the first copy you still need to run `generate_token.php` once to create
   `config.php` (see step 4 of the Installation section above).

### Scheduling with Windows Task Scheduler

Run the following command **once** in an elevated Command Prompt to register a task that
fires every 6 hours.  Adjust the `-File` path to wherever you saved the script.

```cmd
schtasks /create ^
  /tn "4KitchenBoard Backend Update" ^
  /tr "powershell -ExecutionPolicy Bypass -File \"C:\scripts\update_backend_windows.ps1\"" ^
  /sc hourly /mo 6 ^
  /ru SYSTEM /f
```

To change the interval edit `/mo 6` (every 6 hours) to any number of hours you prefer.
Remove the task with:

```cmd
schtasks /delete /tn "4KitchenBoard Backend Update" /f
```

> **Note:** `config.php` is never overwritten by the update script because it contains
> your local database credentials and API token. It is safe to run the script while
> Apache is serving requests.

## Nginx

If you use Nginx instead of Apache, add this `location` block (the `.htaccess` is ignored by Nginx):
```nginx
location ~* ^/apps/kitchenboard/(config|generate_token)\.php$ {
    deny all;
}
```

Or more broadly:
```nginx
location = /apps/kitchenboard/api.php {
    fastcgi_pass ...;
}
location /apps/kitchenboard/ {
    deny all;
}
```

## Token management

| Scenario | Action |
|---|---|
| First-time setup | Run `php generate_token.php`, copy token into every device |
| Add a new device | Open sync settings on the new device and enter the same token |
| Revoke all access | Run `php generate_token.php` again and update every device |
| Disable auth | Edit `config.php` and set `API_TOKEN` to `''` |

## API Reference

All responses are JSON.  When a token is configured every request must include the `X-Api-Token` HTTP header with the correct value; otherwise the server returns `401 Unauthorized`.

Base URL: `http://<server-ip>/apps/kitchenboard/api.php`

### `GET ?action=list`
Returns all unchecked items sorted by category, then name.

```json
{
  "items": [
    { "id": 1, "name": "Apples",  "category": "Fruits & Vegetables" },
    { "id": 2, "name": "Milk",    "category": "Dairy" }
  ]
}
```

### `POST ?action=add`
Body parameters: `name`, `category`

```json
{ "id": 3, "name": "Butter", "category": "Dairy" }
```

### `POST ?action=check`
Body parameters: `id`

```json
{ "success": true }
```

### `POST ?action=delete`
Body parameters: `id`

```json
{ "success": true }
```

---

## Backend Update Distribution

The backend can distribute APK updates to devices independently of GitHub CI builds.
This uses a three-part version scheme: `version + buildNumber + subNumber`.

**Version ordering:**
- `(buildY, subN > 0)` > `(buildY, 0)` — any backend sub-release is newer than the base GitHub build
- `(buildY+1, any)` > `(buildY, subN)` — a higher build number always wins regardless of sub-number

### `GET ?action=check_update`
Returns the latest backend update entry.  The app calls this endpoint automatically
every 12 hours alongside the GitHub release check.

```json
{
  "build_number": 42,
  "sub_number":    3,
  "download_url": "http://192.168.1.10/apps/kitchenboard/4KitchenBoard.apk",
  "tag":          "v1.0-42+3"
}
```
Returns `{"build_number": 0, "sub_number": 0, "download_url": "", "tag": ""}` when no
update has been published yet.

### `POST ?action=publish_update`
Publishes a new update entry.  Call this once you have placed the APK on the server and
want all devices to receive it.

Body parameters:

| Parameter | Required | Description |
|---|---|---|
| `build_number` | ✓ | GitHub CI build number this release is based on (= `VERSION_CODE`) |
| `sub_number` | | Incremental counter within the same build (default `0`) |
| `download_url` | | Direct URL to the `.apk` file served by this backend |
| `tag` | | Human-readable label, e.g. `v1.0-42+3` (auto-generated if omitted) |

```json
{ "success": true, "id": 7 }
```

**Typical workflow for a backend sub-release:**
1. Build the APK with the same `build_number` as the corresponding GitHub release.
2. Copy the APK to a web-accessible location on the server.
3. Call `publish_update` with `build_number`, `sub_number` (start at 1 for the first
   sub-release within a given build), and the `download_url`.
4. Devices will pick up the update within 12 hours.

### `GET ?action=list_updates`
Returns all published update entries sorted newest-first.

```json
{
  "updates": [
    { "id": 7, "build_number": 42, "sub_number": 3, "download_url": "...", "tag": "v1.0-42+3", "created_at": 1700000000000 },
    { "id": 6, "build_number": 42, "sub_number": 2, "download_url": "...", "tag": "v1.0-42+2", "created_at": 1699999000000 }
  ]
}
```

### `POST ?action=delete_update`
Removes an update entry by its `id`.

Body parameters: `id`

```json
{ "success": true }
```

---

* The `.htaccess` file prevents `config.php` and `generate_token.php` from being accessed via HTTP.
* All SQL queries use PDO prepared statements to prevent injection.
* The `X-Api-Token` header is compared with `hash_equals()` to prevent timing attacks.
* CORS is set to `*` by default; tighten it for production by replacing the wildcard with your device's IP/hostname.
* Even with a token, **do not expose this API to the public internet** – it is designed for a trusted local network.
* Never commit `config.php` to version control – it contains your database password and API token.
