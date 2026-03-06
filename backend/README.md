# 4KitchenBoard – Shopping List Sync Backend

A minimal PHP/SQLite3 REST API that lets multiple 4KitchenBoard devices share and synchronise the same shopping list in real time.

## Requirements

| Requirement | Version |
|---|---|
| PHP | ≥ 7.4 |
| PHP extension | `sqlite3` (enabled by default in most distros) |
| Web server | Apache 2.4+ with `mod_rewrite` (or Nginx – see below) |

## Installation

1. **Copy the `backend/` folder** to a directory that is served by your web server (e.g. `/var/www/html/kitchenboard/`).

2. **Make the directory writable** so that PHP can create the SQLite database file:
   ```bash
   chmod 750 /var/www/html/kitchenboard/
   chown www-data:www-data /var/www/html/kitchenboard/
   ```

3. **Enable `AllowOverride All`** in your Apache virtual host so that the `.htaccess` rules that protect the database file take effect:
   ```apache
   <Directory /var/www/html/kitchenboard/>
       AllowOverride All
   </Directory>
   ```

4. **Generate an API token** (recommended) so that only your devices can use the API:
   ```bash
   php /var/www/html/kitchenboard/generate_token.php
   ```
   The script creates `config.php` with a 64-character random hex token and prints it to the console.  Copy the token into the app's sync settings on every device (see step 5).

5. **Point the app** to the API by opening the shopping list in 4KitchenBoard, long-pressing the sync button (⟳ in the title bar) and entering:
   * **Server URL** – e.g. `http://192.168.1.100/kitchenboard/api.php`
   * **API Token** – the token printed by `generate_token.php` (leave empty if you skipped step 4)

   Use the LAN IP address so all devices on the same network can reach it.

## Nginx

If you use Nginx instead of Apache, add this `location` block (the `.htaccess` is ignored by Nginx):
```nginx
location ~* \.(db|php)$ {
    # Allow api.php only
    location = /kitchenboard/api.php {
        fastcgi_pass ...;
    }
    deny all;
}
```

Or more precisely, block the sensitive files explicitly:
```nginx
location ~* ^/kitchenboard/(config|generate_token)\.php$ {
    deny all;
}
location ~* \.db$ {
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

## Security Notes

* The `.htaccess` file prevents `shopping.db`, `config.php` and `generate_token.php` from being downloaded via HTTP.
* All SQL queries use prepared statements to prevent injection.
* The `X-Api-Token` header is compared with `hash_equals()` to prevent timing attacks.
* CORS is set to `*` by default; tighten it for production by replacing the wildcard with your device's IP/hostname.
* Even with a token, **do not expose this API to the public internet** – it is designed for a trusted local network.
