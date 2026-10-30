/**
 * Monitoring of VectolynCloud: {@link net.vectolyn.cloud.monitor.Monitor} samples every service and
 * the node, keeps the history, evaluates the alert rules of {@code monitor.yml} and hands alerts to
 * the log, notifiers and listeners. {@link net.vectolyn.cloud.monitor.MonitorRuntime} wires it from
 * the configuration; the node connects it through {@link net.vectolyn.cloud.monitor.ServiceDirectory}
 * and two calls. Reference: docs/cloud/monitor.md
 */
package net.vectolyn.cloud.monitor;
