import org.gradle.api.provider.Property

/** Per-module switches of the `vectora.java-conventions` plugin. */
interface VectoraJavaExtension {
    /**
     * Fail the compile on any javac warning. On by default; only modules with known,
     * tracked warnings switch it off and say why in their build file.
     */
    val warningsAsErrors: Property<Boolean>
}
