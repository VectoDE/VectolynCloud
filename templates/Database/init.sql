-- First-run setup for the Vectolyn rank database (LuckPerms).
-- For the manual setup with start-vectolyn-database.cmd. The cloud's managed mode runs the same
-- statements itself (cloud/database, BootstrapStatements), as the MariaDB root user.
CREATE DATABASE IF NOT EXISTS `{{VECTOLYN_DB_NAME}}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '{{VECTOLYN_DB_USER}}'@'{{VECTOLYN_DB_HOST}}' IDENTIFIED BY '{{VECTOLYN_DB_PASSWORD}}';
-- Only what LuckPerms needs, only on its own database (same list as the managed mode).
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON `{{VECTOLYN_DB_NAME}}`.* TO '{{VECTOLYN_DB_USER}}'@'{{VECTOLYN_DB_HOST}}';
FLUSH PRIVILEGES;
