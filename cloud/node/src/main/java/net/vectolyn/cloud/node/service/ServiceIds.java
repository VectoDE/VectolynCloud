package net.vectolyn.cloud.node.service;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.protocol.ServiceId;

/**
 * Naming rule for services: {@code <group>-<index>}, for example {@code lobby-1}. The group name
 * follows the config rule ({@code [a-z0-9][a-z0-9_-]{0,31}}, no Windows device names) and the index
 * runs from 1 to {@value #MAX_INDEX}, without leading zeros. The result is always a valid
 * {@link ServiceId} of the protocol (at most 64 characters).
 */
public final class ServiceIds {

    /** Highest index of a service within its group. */
    public static final int MAX_INDEX = 9999;

    private static final Pattern FORM = Pattern.compile("(.+)-([1-9][0-9]{0,3})");

    private ServiceIds() {
    }

    /**
     * Builds the id of service {@code index} of {@code group}.
     *
     * @param group group name
     * @param index 1 to {@value #MAX_INDEX}
     * @return the id
     * @throws IllegalArgumentException if the group name or the index is invalid
     */
    public static ServiceId of(String group, int index) {
        if (!ConfigNames.isValidName(group)) {
            throw new IllegalArgumentException("invalid group name");
        }
        if (index < 1 || index > MAX_INDEX) {
            throw new IllegalArgumentException("service index must be between 1 and " + MAX_INDEX);
        }
        return new ServiceId(group + "-" + index);
    }

    /**
     * Reads the index back from an id built by {@link #of(String, int)}.
     *
     * @param id    the service id
     * @param group the group the id should belong to
     * @return the index, or empty if the id does not follow {@code <group>-<index>}
     */
    public static OptionalInt index(ServiceId id, String group) {
        Matcher m = FORM.matcher(id.value());
        if (!m.matches() || !m.group(1).equals(group)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            // Cannot happen: the pattern allows one to four digits only.
            return OptionalInt.empty();
        }
    }
}
