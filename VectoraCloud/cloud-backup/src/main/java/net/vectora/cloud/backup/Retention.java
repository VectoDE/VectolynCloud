package net.vectora.cloud.backup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.vectora.cloud.backup.config.RetentionConfig;

/**
 * Decides which backups a cleanup keeps (rules in {@link RetentionConfig}). Pure function, so the
 * rules can be tested without files.
 */
public final class Retention {

    /**
     * A backup that retention may delete.
     *
     * @param id backup id
     * @param createdAt when it was made
     */
    public record Candidate(String id, Instant createdAt) {

        /** Checks the values. */
        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private Retention() {
    }

    /**
     * The ids to keep.
     *
     * @param backups the candidates, in any order
     * @param policy the rules
     * @param zone time zone for days, weeks and months
     * @return the ids that stay; every other candidate may be deleted
     */
    public static Set<String> keep(List<Candidate> backups, RetentionConfig policy, ZoneId zone) {
        List<Candidate> newestFirst = new ArrayList<>(backups);
        // Newest first; equal times by id, so the result does not depend on the input order.
        newestFirst.sort(Comparator.comparing(Candidate::createdAt).thenComparing(Candidate::id).reversed());
        Set<String> keep = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(policy.keepLast(), newestFirst.size()); i++) {
            keep.add(newestFirst.get(i).id());
        }
        keepOnePer(newestFirst, policy.keepDaily(), keep, c -> day(c, zone));
        keepOnePer(newestFirst, policy.keepWeekly(), keep, c -> {
            LocalDate date = day(c, zone);
            return date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        });
        keepOnePer(newestFirst, policy.keepMonthly(), keep, c -> YearMonth.from(day(c, zone)));
        return keep;
    }

    private static LocalDate day(Candidate candidate, ZoneId zone) {
        return LocalDate.ofInstant(candidate.createdAt(), zone);
    }

    /** Keeps the newest backup of each of the {@code count} newest periods that have one. */
    private static void keepOnePer(List<Candidate> newestFirst, int count, Set<String> keep,
                                   Function<Candidate, Object> period) {
        Set<Object> seen = new HashSet<>();
        for (Candidate candidate : newestFirst) {
            if (seen.size() >= count) {
                return;
            }
            if (seen.add(period.apply(candidate))) {
                keep.add(candidate.id());
            }
        }
    }
}
