package net.vectora.cloud.monitor.alert;

import java.util.Objects;

/**
 * What happened to an alert. Sealed, so a {@code switch} covers every case. Delivered to
 * {@link AlertListener}s: the log, the notifiers and later the API (PR 15) and the dashboard.
 */
public sealed interface AlertEvent {

    /** The kinds of event, for code that only needs to tell them apart. */
    enum Kind {
        /** {@link Fired}. */
        FIRED,
        /** {@link Resolved}. */
        RESOLVED,
        /** {@link Acknowledged}. */
        ACKNOWLEDGED
    }

    /** The alert after the change. */
    Alert alert();

    /** Which kind of event this is. */
    Kind kind();

    /** The rule's condition held long enough. */
    record Fired(Alert alert) implements AlertEvent {
        /** Checks the value. */
        public Fired {
            Objects.requireNonNull(alert, "alert");
        }

        @Override
        public Kind kind() {
            return Kind.FIRED;
        }
    }

    /** The alert ended; {@link Alert#resolution()} says why. */
    record Resolved(Alert alert) implements AlertEvent {
        /** Checks the value. */
        public Resolved {
            Objects.requireNonNull(alert, "alert");
        }

        @Override
        public Kind kind() {
            return Kind.RESOLVED;
        }
    }

    /** An operator acknowledged the alert. It keeps firing until it clears. */
    record Acknowledged(Alert alert) implements AlertEvent {
        /** Checks the value. */
        public Acknowledged {
            Objects.requireNonNull(alert, "alert");
        }

        @Override
        public Kind kind() {
            return Kind.ACKNOWLEDGED;
        }
    }
}
