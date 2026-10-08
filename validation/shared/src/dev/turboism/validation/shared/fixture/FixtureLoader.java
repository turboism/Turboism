package dev.turboism.validation.shared.fixture;

import java.net.URL;
import java.net.URLClassLoader;

/** Child-first loader so the fixture's same-named class never resolves to the system classpath. */
public final class FixtureLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    public FixtureLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    c = super.loadClass(name, false);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
