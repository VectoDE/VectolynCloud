/**
 * Autoscaling of the tasks of a node: {@link net.vectora.cloud.node.scaling.ScalingPlanner} decides
 * without any I/O, {@link net.vectora.cloud.node.scaling.Autoscaler} runs the rounds and carries the
 * decisions out through the service core. See docs/cloud/scaling.md.
 */
package net.vectora.cloud.node.scaling;
