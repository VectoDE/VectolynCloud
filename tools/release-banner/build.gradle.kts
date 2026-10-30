// Release banner: renders the image at the top of every GitHub release (1280 x 320 and 2560 x 640)
// from the pre-rendered background in branding/png/ and Pixelify Sans Bold from branding/fonts/.
// Plain Java2D, no dependencies. The tool itself is not shipped; release.yml runs it in the build
// job (through banner-from-tag.sh) and attaches the two PNGs to the release.
// Details: docs/cloud/operations.md#release-banner
//
// Preview:  ./gradlew :tools:release-banner:releaseBanner -PbannerVersion=v0.2.0-beta.2 \
//             -PbannerTitle="Stage 2: cloud core" -PbannerDate=2026-10-30
// Output:   tools/release-banner/build/release-banner/VectolynCloud-0.2.0-beta.2-banner.png (+ -2x.png)
// Each value can also come from BANNER_VERSION, BANNER_TITLE, BANNER_DATE and BANNER_OUT
// (output directory); a Gradle property wins over the environment variable.

import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    id("vectolyn.java-conventions")
}

vectolyn {
    artifactName = "release-banner"
}

val brandFont = layout.settingsDirectory.file("branding/fonts/pixelify-sans/PixelifySans-Bold.ttf")
val brandBackground = layout.settingsDirectory.file("branding/png/release-banner-background.png")
val brandBackground2x = layout.settingsDirectory.file("branding/png/release-banner-background@2x.png")

fun setting(property: String, variable: String): Provider<String> =
    providers.gradleProperty(property).orElse(providers.environmentVariable(variable))

tasks.register<JavaExec>("releaseBanner") {
    group = "release"
    description = "Renders the release banner (-PbannerVersion, -PbannerTitle, -PbannerDate, -PbannerOut)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "net.vectolyn.tools.releasebanner.ReleaseBanner"
    jvmArgs("-Djava.awt.headless=true")

    val version = setting("bannerVersion", "BANNER_VERSION")
    val title = setting("bannerTitle", "BANNER_TITLE").orElse("")
    val date = setting("bannerDate", "BANNER_DATE")
    // A relative path is taken from the repository root.
    val root = layout.settingsDirectory
    val outDir = setting("bannerOut", "BANNER_OUT")
        .map { root.dir(it) }
        .orElse(layout.buildDirectory.dir("release-banner"))
    val font = brandFont
    val background = brandBackground
    val background2x = brandBackground2x
    inputs.files(font, background, background2x).withPropertyName("brandFiles")

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--font", font.asFile.absolutePath,
            "--background", background.asFile.absolutePath,
            "--background-2x", background2x.asFile.absolutePath,
            "--out-dir", outDir.get().asFile.absolutePath,
        )
    })
    // Version, title and date reach the program as environment variables, never as arguments:
    // no quoting problems with a title on any platform, and the program checks every value.
    doFirst {
        val exec = this as JavaExec
        exec.environment("BANNER_VERSION", version.orNull
            ?: throw GradleException("set -PbannerVersion=v1.2.3 (or BANNER_VERSION)"))
        exec.environment("BANNER_TITLE", title.get())
        // Without a date (local preview) today's date in UTC.
        exec.environment("BANNER_DATE", date.orNull ?: LocalDate.now(ZoneOffset.UTC).toString())
    }
}

tasks.test {
    val font = brandFont
    val background = brandBackground
    val background2x = brandBackground2x
    inputs.files(font, background, background2x).withPropertyName("brandFiles")
    systemProperty("java.awt.headless", "true")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dbanner.font=" + font.asFile.absolutePath,
            "-Dbanner.background=" + background.asFile.absolutePath,
            "-Dbanner.background2x=" + background2x.asFile.absolutePath,
        )
    })
}
