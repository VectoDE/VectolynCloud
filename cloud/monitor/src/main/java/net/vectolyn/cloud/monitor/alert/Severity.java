// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.monitor.alert;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How urgent an alert is. */
public enum Severity {
    /** Needs attention soon. */
    @JsonProperty("warning") WARNING,
    /** Needs attention now. */
    @JsonProperty("critical") CRITICAL;

    /** Lower-case name, as in {@code monitor.yml}. */
    public String id() {
        return this == WARNING ? "warning" : "critical";
    }
}
