/**
 * Autoscaling of the tasks of a node: {@link net.vectolyn.cloud.node.scaling.ScalingPlanner} decides
 * without any I/O, {@link net.vectolyn.cloud.node.scaling.Autoscaler} runs the rounds and carries the
 * decisions out through the service core. See docs/cloud/scaling.md.
 */
package net.vectolyn.cloud.node.scaling;
