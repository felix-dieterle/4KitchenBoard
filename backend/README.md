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

4. **Point the app** to the API by opening the shopping list in 4KitchenBoard, long-pressing the sync button (⟳ in the title bar) and entering the full URL, e.g.:
   ```
   http://192.168.1.100/kitchenboard/api.php
   ```
   Use the LAN IP address so all devices on the same network can reach it.

## Nginx

If you use Nginx instead of Apache, add this `location` block (the `.htaccess` is ignored by Nginx):
```nginx
location ~* \.db$ {
    deny all;
}
```

## API Reference

All responses are JSON.

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

* The `.htaccess` file prevents the `shopping.db` SQLite file from being downloaded via HTTP.
* All SQL queries use prepared statements to prevent injection.
* CORS is set to `*` by default; tighten it for production by replacing the wildcard with your device's IP/hostname.
* There is no authentication. The API is designed for a **trusted local network** only.  Do **not** expose it to the public internet.
