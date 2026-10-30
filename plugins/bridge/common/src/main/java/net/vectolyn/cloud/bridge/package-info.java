/**
 * Platform-neutral core of the VectolynCloud bridges. The Velocity, Paper and legacy bridges only
 * translate platform events into calls of these classes. Every call that may block on the network
 * goes through {@link net.vectolyn.cloud.bridge.BridgeSender}, never through a server thread.
 */
package net.vectolyn.cloud.bridge;
