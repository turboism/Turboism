package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;

/**
 * Deliberately small loader used by T038. It has only two child-owned names;
 * every other class follows ordinary parent delegation.
 */
final class T038OwnedClassLoader extends ClassLoader {
    private final byte[] fixtureBytes;
    private final byte[] helperBytes;
    private final boolean blockParentHelper;
    private int fixtureDefineCount;

    T038OwnedClassLoader(
        final byte[] fixtureBytes,
        final ClassLoader parent,
        final byte[] helperBytes,
        final boolean blockParentHelper
    ) {
        super(parent);
        this.fixtureBytes = fixtureBytes == null ? null : fixtureBytes.clone();
        this.helperBytes = helperBytes == null ? null : helperBytes.clone();
        this.blockParentHelper = blockParentHelper;
    }

    Class<?> visibleHelper() {
        try {
            return loadClass(T038ArrayHelper.BINARY_NAME);
        } catch (final ClassNotFoundException exception) {
            return null;
        }
    }

    Class<?> defineFixture() throws ClassNotFoundException {
        return loadClass(T035OwnedFixtureGenerator.CLASS_NAME);
    }

    int fixtureDefineCount() {
        return fixtureDefineCount;
    }

    @Override
    protected synchronized Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        if (name.equals(T035OwnedFixtureGenerator.CLASS_NAME)) {
            return defineLocal(name, fixtureBytes, resolve, true);
        }
        if (name.equals(T038ArrayHelper.BINARY_NAME)) {
            if (helperBytes != null) {
                return defineLocal(name, helperBytes, resolve, false);
            }
            if (blockParentHelper) {
                throw new ClassNotFoundException(name);
            }
        }
        return super.loadClass(name, resolve);
    }

    private Class<?> defineLocal(
        final String name,
        final byte[] bytes,
        final boolean resolve,
        final boolean fixture
    ) throws ClassNotFoundException {
        final Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        final Class<?> defined = defineClass(name, bytes, 0, bytes.length);
        if (fixture) {
            fixtureDefineCount++;
        }
        if (resolve) {
            resolveClass(defined);
        }
        return defined;
    }
}
