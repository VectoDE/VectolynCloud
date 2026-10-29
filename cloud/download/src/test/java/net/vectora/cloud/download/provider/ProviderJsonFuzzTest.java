package net.vectora.cloud.download.provider;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import net.vectora.cloud.download.MalformedResponseException;

/**
 * Fuzz targets for the JSON mappers of the providers: whatever a download API answers, the
 * mappers either return a value or throw {@link MalformedResponseException}, never anything else.
 * In a normal test run they execute in regression mode (stored inputs only).
 */
class ProviderJsonFuzzTest {

    @FuzzTest
    void fillBuilds(byte @NotNull [] input) {
        try {
            PaperFillProvider.parseBuilds(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            PaperFillProvider.parseBuild(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void fillVersionAndProject(byte @NotNull [] input) {
        try {
            PaperFillProvider.parseVersion(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            PaperFillProvider.parseProjectVersions(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void mojang(byte @NotNull [] input) {
        try {
            MojangProvider.parseManifest(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            MojangProvider.parseVersion(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void fabric(byte @NotNull [] input) {
        try {
            FabricMetaProvider.parseComponents(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            FabricMetaProvider.parseLoaderForGame(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void bibliothek(byte @NotNull [] input) {
        try {
            BibliothekProvider.parseProject(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            BibliothekProvider.parseVersionBuilds(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            BibliothekProvider.parseBuild(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void modrinth(byte @NotNull [] input) {
        try {
            ModrinthProvider.parseVersions(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void hangar(byte @NotNull [] input) {
        try {
            HangarProvider.parseVersionPage(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            HangarProvider.parseVersion(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void mariadb(byte @NotNull [] input) {
        try {
            MariaDbProvider.parseSeries(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            MariaDbProvider.parseReleases(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }

    @FuzzTest
    void adoptium(byte @NotNull [] input) {
        try {
            AdoptiumProvider.parseLatest(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
        try {
            AdoptiumProvider.parseRelease(input);
        } catch (MalformedResponseException expected) {
            // Reported correctly.
        }
    }
}
