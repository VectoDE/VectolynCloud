/**
 * Monitoring of VectoraCloud: {@link net.vectora.cloud.monitor.Monitor} samples every service and
 * the node, keeps the history, evaluates the alert rules of {@code monitor.yml} and hands alerts to
 * the log, notifiers and listeners. {@link net.vectora.cloud.monitor.MonitorRuntime} wires it from
 * the configuration; the node connects it through {@link net.vectora.cloud.monitor.ServiceDirectory}
 * and two calls. Reference: docs/cloud/monitor.md
 */
package net.vectora.cloud.monitor;
