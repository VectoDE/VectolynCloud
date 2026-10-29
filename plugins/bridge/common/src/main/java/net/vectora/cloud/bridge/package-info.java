/**
 * Platform-neutral core of the VectoraCloud bridges. The Velocity, Paper and legacy bridges only
 * translate platform events into calls of these classes. Every call that may block on the network
 * goes through {@link net.vectora.cloud.bridge.BridgeSender}, never through a server thread.
 */
package net.vectora.cloud.bridge;
