-- First-run setup for the Vectora rank database (LuckPerms).
-- Rendered by VectoraCloud and run once as the MariaDB root user.
CREATE DATABASE IF NOT EXISTS `{{VECTORA_DB_NAME}}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '{{VECTORA_DB_USER}}'@'{{VECTORA_DB_HOST}}' IDENTIFIED BY '{{VECTORA_DB_PASSWORD}}';
GRANT ALL PRIVILEGES ON `{{VECTORA_DB_NAME}}`.* TO '{{VECTORA_DB_USER}}'@'{{VECTORA_DB_HOST}}';
FLUSH PRIVILEGES;
