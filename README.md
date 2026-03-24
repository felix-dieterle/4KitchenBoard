# 4KitchenBoard
A board android app to be able to show multiple ui modules and provide kind of a grid layout to position those app internal modules on the apps screen via drag and drop

## Database Migrations

Explicit SQL migration scripts for all SQLite databases live in [`db_migrations/`](db_migrations/).
Each file is prefixed with a zero-padded sequential number (`NNNN_<db>_<description>.sql`) so
scripts can process them in order.  See [`db_migrations/README.md`](db_migrations/README.md) for
the full convention, current DB versions, and instructions for adding new migrations.
