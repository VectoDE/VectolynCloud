/**
 * Wire format of the VectolynCloud channel: frames, the payload reader and writer, the protocol
 * version and the validated identifier types. Has no dependencies, so it can run inside every
 * proxy, server and the cloud itself. Messages live in {@link net.vectolyn.protocol.message}.
 * Format and rules: {@code docs/cloud/protocol.md}.
 */
package net.vectolyn.protocol;
