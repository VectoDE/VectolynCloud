/**
 * The database of the network, run by the node: {@link net.vectora.cloud.node.database.NodeDatabase}
 * provisions it before the first service and shuts it down after the last one,
 * {@link net.vectora.cloud.node.database.NodeDatabaseProcesses} runs MariaDB's programs in managed
 * mode. See docs/cloud/database.md.
 */
package net.vectora.cloud.node.database;
