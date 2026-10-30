/**
 * Database setup of the network: the four modes behind {@link net.vectolyn.cloud.database.DatabaseProvisioner}
 * (managed MariaDB, Docker, external server, embedded H2) and the central sizing of the connection
 * pools ({@link net.vectolyn.cloud.database.ConnectionBudget}). See docs/cloud/database.md.
 */
package net.vectolyn.cloud.database;
