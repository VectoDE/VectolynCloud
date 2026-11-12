// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.api.provider.Property

/** Per-module switches of the `vectolyn.java-conventions` plugin. */
interface VectolynJavaExtension {
    /**
     * Fail the compile on any javac warning. On by default; only modules with known,
     * tracked warnings switch it off and say why in their build file.
     */
    val warningsAsErrors: Property<Boolean>

    /**
     * Name of the module's jar (`<name>-<version>.jar`) and its `Implementation-Title`. Defaults
     * to the project name. The modules set the name they had before the repository layout change
     * (`cloud-node`, `common/protocol`, ...), so the jars inside VectolynCloud.jar and the bridge
     * plugins keep their file names.
     */
    val artifactName: Property<String>

    /**
     * Minimum line coverage (0.0 to 1.0) of the module's unit tests; `check` fails below it. The
     * default applies to every module; a module with less coverage than that sets its own lower
     * value and says why in its build file.
     */
    val minimumLineCoverage: Property<Double>
}
