package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Language of the console, the setup assistant and error messages. */
public enum Language {
    /** German. */
    @JsonProperty("de") DE,
    /** English. */
    @JsonProperty("en") EN
}
