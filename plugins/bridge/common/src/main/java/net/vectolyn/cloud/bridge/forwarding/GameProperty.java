package net.vectolyn.cloud.bridge.forwarding;

import java.util.Objects;

/**
 * One profile property as Velocity forwards it, for example the skin ({@code textures}).
 *
 * @param name property name
 * @param value property value
 * @param signature Mojang's signature, or {@code null} if the property has none
 */
public record GameProperty(String name, String value, String signature) {

    /** Creates the property. */
    public GameProperty {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    /** The value is left out: the token of the legacy proxy is a property as well. */
    @Override
    public String toString() {
        return "GameProperty[name=" + name + ", value=[" + value.length() + " chars], signed=" + (signature != null)
                + "]";
    }
}
