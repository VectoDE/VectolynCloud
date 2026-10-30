/**
 * Check of the BungeeCord handshake that the legacy proxy (Velocity in {@code bungeeguard} mode)
 * forwards to the 1.8.8 backends: parse it, compare the {@code bungeeguard-token} with the
 * forwarding secret in constant time and strip the token before the server sees the properties.
 */
package net.vectolyn.cloud.bridge.forwarding;
