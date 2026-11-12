// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.scaling;

/**
 * Receives the decisions of the {@link Autoscaler}. Called on the autoscaler's threads; it must
 * return quickly and should not throw (an exception is logged and ignored).
 */
@FunctionalInterface
public interface ScalingListener {

    /**
     * A decision was carried out or held back.
     *
     * @param event the decision
     */
    void decided(ScalingEvent event);
}
