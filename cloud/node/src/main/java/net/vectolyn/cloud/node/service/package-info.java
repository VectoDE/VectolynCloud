// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * The service core: planning, starting, watching, stopping and restarting the server processes of
 * a node. Entry point is {@link net.vectolyn.cloud.node.service.ServiceManager}; the seams for other
 * modules are {@link net.vectolyn.cloud.node.service.JavaRuntimeResolver},
 * {@link net.vectolyn.cloud.node.service.ServerSoftwareProvider},
 * {@link net.vectolyn.cloud.node.service.PlaceholderContributor} and
 * {@link net.vectolyn.cloud.node.service.ServiceListener}. See docs/cloud/node.md.
 */
package net.vectolyn.cloud.node.service;
