/**
 * The service core: planning, starting, watching, stopping and restarting the server processes of
 * a node. Entry point is {@link net.vectora.cloud.node.service.ServiceManager}; the seams for other
 * modules are {@link net.vectora.cloud.node.service.JavaRuntimeResolver},
 * {@link net.vectora.cloud.node.service.ServerSoftwareProvider},
 * {@link net.vectora.cloud.node.service.PlaceholderContributor} and
 * {@link net.vectora.cloud.node.service.ServiceListener}. See docs/cloud/node.md.
 */
package net.vectora.cloud.node.service;
