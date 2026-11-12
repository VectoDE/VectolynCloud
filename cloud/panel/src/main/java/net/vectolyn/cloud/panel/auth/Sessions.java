// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Sign-in state of the panel, in memory only (a restart signs everybody out):
 * <ul>
 *   <li>sessions: a random id in an HttpOnly cookie plus a CSRF token the page sends in a header;
 *       they end after {@link #IDLE} without use and {@link #ABSOLUTE} after sign-in;</li>
 *   <li>tickets: short-lived one-time values for the second sign-in step, passkey challenges,
 *       single sign-on attempts and TOTP enrolment;</li>
 *   <li>failed sign-ins per account: {@value #MAX_FAILURES} failures lock the account for
 *       {@link #LOCKOUT}.</li>
 * </ul>
 */
public final class Sessions {

    /** A session ends after this long without a request. */
    public static final Duration IDLE = Duration.ofMinutes(30);
    /** A session ends this long after sign-in. */
    public static final Duration ABSOLUTE = Duration.ofHours(12);
    /** A ticket is valid for this long. */
    public static final Duration TICKET = Duration.ofMinutes(5);
    /** Failed sign-ins before the account is locked. */
    public static final int MAX_FAILURES = 5;
    /** How long a lock lasts. */
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    private static final int MAX_SESSIONS = 10_000;
    private static final int MAX_TICKETS = 10_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Ticket<?>> tickets = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /**
     * A signed-in browser.
     *
     * @param id       the cookie value
     * @param csrf     the token the page must send in {@code X-Vectolyn-CSRF}
     * @param user     the user name
     * @param method   how the user signed in ({@code password}, {@code totp}, {@code passkey}, {@code oidc}, {@code setup})
     * @param created  sign-in time
     * @param lastSeen last request
     */
    public record Session(String id, String csrf, String user, String method, Instant created, Instant lastSeen) {

        @Override
        public String toString() {
            return "Session[" + user + "]";
        }
    }

    /**
     * A one-time value with data.
     *
     * @param kind    what it is for
     * @param value   the data
     * @param expires end of validity
     * @param <T>     the data's type
     */
    record Ticket<T>(String kind, T value, Instant expires) {
    }

    private record Failures(int count, @Nullable Instant lockedUntil, Instant last) {
    }

    /**
     * Creates the store.
     *
     * @param clock the clock
     */
    public Sessions(Clock clock) {
        this.clock = clock;
    }

    /**
     * Signs a user in.
     *
     * @param user   the user name
     * @param method how
     * @return the new session
     */
    public Session create(String user, String method) {
        cleanup();
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.values().stream().min(java.util.Comparator.comparing(Session::lastSeen))
                    .ifPresent(oldest -> sessions.remove(oldest.id()));
        }
        Instant now = clock.instant();
        Session session = new Session(randomId(), randomId(), user, method, now, now);
        sessions.put(session.id(), session);
        failures.remove(key(user));
        return session;
    }

    /**
     * The session of a cookie value, which counts as use.
     *
     * @param id the cookie value
     * @return the session, or empty if unknown or ended
     */
    public Optional<Session> touch(@Nullable String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Session session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(session, now)) {
            sessions.remove(id);
            return Optional.empty();
        }
        Session used = new Session(session.id(), session.csrf(), session.user(), session.method(), session.created(), now);
        sessions.replace(id, session, used);
        return Optional.of(used);
    }

    /**
     * Whether a session still runs, without counting as use (for open event streams).
     *
     * @param id the session id
     * @return true while it has not ended
     */
    public boolean alive(String id) {
        Session session = id == null ? null : sessions.get(id);
        return session != null && !expired(session, clock.instant());
    }

    /**
     * The user of a session.
     *
     * @param id the session id
     * @return the user name, or empty
     */
    public Optional<String> userOf(String id) {
        Session session = id == null ? null : sessions.get(id);
        return session == null ? Optional.empty() : Optional.of(session.user());
    }

    /**
     * Whether a presented CSRF token belongs to a session, compared in constant time.
     *
     * @param session the session
     * @param token   the header value
     * @return true if equal
     */
    public static boolean csrfMatches(Session session, @Nullable String token) {
        return token != null && MessageDigest.isEqual(session.csrf().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /**
     * Ends a session.
     *
     * @param id the session id
     */
    public void end(@Nullable String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /**
     * Ends every session of a user (password change, removal, lock), except one.
     *
     * @param user   the user name
     * @param except a session id to keep, or null
     */
    public void endAll(String user, @Nullable String except) {
        sessions.values().removeIf(s -> s.user().equalsIgnoreCase(user) && !s.id().equals(except));
    }

    /**
     * Stores a one-time value.
     *
     * @param kind  what it is for
     * @param value the data
     * @param <T>   its type
     * @return the ticket id
     */
    public <T> String issue(String kind, T value) {
        cleanup();
        if (tickets.size() >= MAX_TICKETS) {
            tickets.clear(); // under a flood, every pending step starts over
        }
        String id = randomId();
        tickets.put(id, new Ticket<>(kind, value, clock.instant().plus(TICKET)));
        return id;
    }

    /**
     * Takes a one-time value: it is gone afterwards, whatever the caller does with it.
     *
     * @param id    the ticket id
     * @param kind  the expected kind
     * @param type  the expected data type
     * @param <T>   its type
     * @return the data, or empty if unknown, of another kind or expired
     */
    public <T> Optional<T> take(@Nullable String id, String kind, Class<T> type) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Ticket<?> ticket = tickets.remove(id);
        if (ticket == null || !ticket.kind().equals(kind) || !clock.instant().isBefore(ticket.expires())
                || !type.isInstance(ticket.value())) {
            return Optional.empty();
        }
        return Optional.of(type.cast(ticket.value()));
    }

    /**
     * Whether an account is locked now.
     *
     * @param user the user name as typed
     * @return seconds until the lock ends, or 0
     */
    public long lockedSeconds(String user) {
        Failures f = failures.get(key(user));
        Instant now = clock.instant();
        Instant until = f == null ? null : f.lockedUntil();
        if (until == null || !now.isBefore(until)) {
            return 0;
        }
        return Math.max(1, Duration.between(now, until).toSeconds());
    }

    /**
     * Counts a failed sign-in.
     *
     * @param user the user name as typed
     * @return true if the account is locked from now on
     */
    public boolean fail(String user) {
        Instant now = clock.instant();
        if (failures.size() > MAX_SESSIONS) {
            failures.values().removeIf(f -> f.last().plus(LOCKOUT).isBefore(now));
        }
        Failures next = failures.compute(key(user), (k, f) -> {
            int count = f == null || f.last().plus(LOCKOUT).isBefore(now) ? 1 : f.count() + 1;
            Instant locked = count >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
            return new Failures(locked != null ? 0 : count, locked, now);
        });
        return next.lockedUntil() != null;
    }

    /**
     * Number of open sessions.
     *
     * @return the count
     */
    public int size() {
        cleanup();
        return sessions.size();
    }

    private boolean expired(Session session, Instant now) {
        return !now.isBefore(session.lastSeen().plus(IDLE)) || !now.isBefore(session.created().plus(ABSOLUTE));
    }

    private void cleanup() {
        Instant now = clock.instant();
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext();) {
            if (expired(it.next(), now)) {
                it.remove();
            }
        }
        tickets.values().removeIf(t -> !now.isBefore(t.expires()));
    }

    private static String key(@Nullable String user) {
        return user == null ? "" : user.toLowerCase(Locale.ROOT);
    }

    /**
     * 256 random bits, Base64url without padding (43 characters).
     *
     * @return the id
     */
    public String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
