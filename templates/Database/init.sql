-- First-run setup for the Vectora rank database (LuckPerms).
-- For the manual setup with start-vectora-database.cmd. The cloud's managed mode runs the same
-- statements itself (cloud/database, BootstrapStatements), as the MariaDB root user.
CREATE DATABASE IF NOT EXISTS `{{VECTORA_DB_NAME}}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '{{VECTORA_DB_USER}}'@'{{VECTORA_DB_HOST}}' IDENTIFIED BY '{{VECTORA_DB_PASSWORD}}';
-- Only what LuckPerms needs, only on its own database (same list as the managed mode).
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON `{{VECTORA_DB_NAME}}`.* TO '{{VECTORA_DB_USER}}'@'{{VECTORA_DB_HOST}}';
FLUSH PRIVILEGES;
