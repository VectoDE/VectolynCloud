package net.vectora.cloud.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.vectora.cloud.launcher.libs.LibraryException;
import net.vectora.cloud.launcher.libs.LibraryExtractor;
import net.vectora.cloud.launcher.libs.LibraryIndex;

/**
 * Step 4 of the start order: unpacks the embedded libraries, builds the node's class loader and
 * calls the node entry point by name.
 *
 * <p><b>Entry point contract.</b> The node provides
 * <pre>
 * package net.vectora.cloud.node;
 * public final class NodeMain {
 *     public static void start(java.util.Map&lt;String, String&gt; settings) { ... }
 * }
 * </pre>
 * The settings are plain strings because the node cannot depend on the launcher (the dependency
 * points the other way). Keys are listed in docs/cloud/launcher.md. {@code start} may return
 * after starting its own non-daemon threads; the JVM keeps running until they end.
 *
 * <p>No class of the node is referenced at compile time, so nothing of the node or its libraries
 * is loaded before the Java, root and argument checks have passed.
 */
public final class NodeStarter {

    /** Fully qualified name of the node entry class. */
    public static final String NODE_MAIN_CLASS = "net.vectora.cloud.node.NodeMain";

    /** Name of the static entry method, taking a {@code Map<String, String>}. */
    public static final String ENTRY_METHOD = "start";

    private NodeStarter() {
    }

    /**
     * Builds the class loader for the node. If the launcher's class loader has a
     * {@link LibraryIndex#RESOURCE}, the libraries are unpacked to {@code libsDirectory}, checked
     * and loaded through a new {@link URLClassLoader}. Without an index (IDE, tests, a plain
     * Gradle class path) the launcher's own class loader is returned.
     *
     * @param launcherLoader class loader that loaded the launcher
     * @param libsDirectory  {@code <home>/cache/libs}
     * @return the class loader for the node
     * @throws LibraryException if the index or a library is invalid
     */
    public static ClassLoader classLoader(ClassLoader launcherLoader, Path libsDirectory) throws LibraryException {
        URL indexUrl = launcherLoader.getResource(LibraryIndex.RESOURCE);
        if (indexUrl == null) {
            return launcherLoader;
        }
        String text;
        try (InputStream in = indexUrl.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LibraryException(LibraryException.Kind.IO, LibraryIndex.RESOURCE, e.toString(), e);
        }
        LibraryExtractor.Source source =
                name -> launcherLoader.getResourceAsStream(LibraryIndex.LIBS_DIRECTORY + name);
        return classLoader(LibraryIndex.parse(text), source, libsDirectory, launcherLoader);
    }

    /**
     * Unpacks and checks the libraries of an index and builds a class loader over them.
     *
     * @param index         the library index
     * @param source        where the embedded jars come from
     * @param libsDirectory target directory
     * @param parent        parent class loader
     * @return a new class loader named {@code vectora-node}
     * @throws LibraryException if a library is missing, unsafe or has the wrong hash
     */
    public static ClassLoader classLoader(LibraryIndex index, LibraryExtractor.Source source, Path libsDirectory,
                                          ClassLoader parent) throws LibraryException {
        List<Path> jars = new LibraryExtractor(source, libsDirectory).extract(index);
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new LibraryException(LibraryException.Kind.IO, jars.get(i).toString(), e.toString(), e);
            }
        }
        // Lives as long as the node, i.e. until the JVM exits, so it is never closed.
        return new URLClassLoader("vectora-node", urls, parent);
    }

    /**
     * Loads the entry class and calls its {@code start} method on the current thread, with the
     * given class loader as context class loader.
     *
     * @param loader    class loader from {@link #classLoader(ClassLoader, Path)}
     * @param className entry class, normally {@link #NODE_MAIN_CLASS}
     * @param settings  settings for the node
     * @throws NodeStartException if the class or method is missing or the method throws
     */
    public static void start(ClassLoader loader, String className, Map<String, String> settings)
            throws NodeStartException {
        Class<?> entry;
        try {
            entry = Class.forName(className, true, loader);
        } catch (ClassNotFoundException e) {
            throw new NodeStartException(NodeStartException.Kind.MISSING, className + " not found", e);
        } catch (LinkageError e) {
            throw new NodeStartException(NodeStartException.Kind.FAILED, className + " cannot be loaded", e);
        }
        Method method;
        try {
            method = entry.getMethod(ENTRY_METHOD, Map.class);
        } catch (NoSuchMethodException e) {
            throw new NodeStartException(NodeStartException.Kind.INVALID_ENTRY,
                    className + " has no public " + ENTRY_METHOD + "(Map)", e);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NodeStartException(NodeStartException.Kind.INVALID_ENTRY,
                    className + "." + ENTRY_METHOD + "(Map) is not static", null);
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            method.invoke(null, Map.copyOf(settings));
        } catch (InvocationTargetException e) {
            throw new NodeStartException(NodeStartException.Kind.FAILED, className + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new NodeStartException(NodeStartException.Kind.INVALID_ENTRY, className + " is not accessible", e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
