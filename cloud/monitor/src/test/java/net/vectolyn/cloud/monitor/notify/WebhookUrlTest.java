package net.vectolyn.cloud.monitor.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlTest {

    @Test
    void acceptsHttpsUrls() {
        WebhookUrl url = WebhookUrl.of(Secret.of("https://Discord.com:443/api/webhooks/123/abcDEF_-x?wait=true"));
        assertEquals("discord.com", url.host());
        assertEquals("https", url.uri().getScheme());
        WebhookUrl.of(Secret.of("HTTPS://hooks.slack.com/services/T/B/x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://discord.com/api/webhooks/1/secretpart",
        "ftp://discord.com/secretpart",
        "discord.com/api/webhooks/1/secretpart",
        "//discord.com/secretpart",
        "https:///secretpart",
        "https://user:secretpart@discord.com/x",
        "https://discord.com/x#secretpart",
        "https://discord.com/secret part",
        "https://discord.com/secretpart\n",
        "https://discörd.com/secretpart",
        "https://discord.com/[secretpart",
        "",
    })
    void refusesEverythingElseWithoutQuotingIt(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WebhookUrl.of(Secret.of(text)));
        assertFalse(e.getMessage().contains("secretpart"), e.getMessage());
        assertFalse(e.getMessage().contains("discord"), e.getMessage());
    }

    @Test
    void lengthLimit() {
        String base = "https://example.org/";
        WebhookUrl.of(Secret.of(base + "a".repeat(WebhookUrl.MAX_LENGTH - base.length())));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookUrl.of(Secret.of(base + "a".repeat(WebhookUrl.MAX_LENGTH - base.length() + 1))));
    }
}
