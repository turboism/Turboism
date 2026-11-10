package dev.turboism.tests.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Invokes the real native Auto Mesh Generate funnel for one ArtMesh.
 *
 * <p>The funnel is the exact method the Editor's own popup calls, so the transformed guard sees the
 * same invocation a user produces. Calling the algorithm directly instead would bypass the guard and
 * prove nothing about the framework.</p>
 *
 * <p>Every host type resolves through the host's own class loader, because the plugin loader refuses
 * Cubism classes. The funnel is matched by name and arity rather than by one pinned descriptor, so the
 * same probe covers both reviewed modeling-mode signatures.</p>
 */
final class NativeAutoMeshFunnel {

    /** Returned when the modeling funnel ran; every other answer is a stable reason token. */
    static final String INVOKED = "inspector";

    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String APP_CONTROLLER_INSTANCE = "_instance";
    private static final String APP_CONTROLLER_INSTANCE_ACCESSOR = "access$get_instance$cp";
    private static final String COMPLETE_PACK = "getCompletePack";
    private static final String CURRENT_DOCUMENT = "getCurrentDoc";
    private static final String MODEL_SOURCE = "getModelSource";
    private static final String OBJECT_LOOKUP = "getObject";
    private static final String ART_MESH_LIST = "getAllArtMeshes";
    private static final String ID_ACCESSOR = "getId";
    private static final String EXTENSIONS = "getExtensions";
    private static final String MESH_GENERATOR_EXTENSION = "meshGenerator.CMeshGeneratorExtension";
    private static final String FUNNEL_OWNER = "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.AutoMeshUtil";
    private static final String FUNNEL_METHOD = "updateMeshFromInspector_inModelingMode";
    private static final String FUNNEL_INSTANCE = "INSTANCE";
    private static final String EVENT_TYPE = "com.live2d.ui.event.z";
    private static final String EVENT_DIRECT_UPDATE = "d";
    private static final String GROUP_UNDO = "com.live2d.undo.GroupUndo";

    /** Declared parameter count of the modeling funnel that carries the Undo group and callback. */
    private static final int FUNNEL_WITH_CALLBACK_PARAMETERS = 5;

    /** Index of the completion callback among the modeling funnel's declared parameters. */
    private static final int CALLBACK_PARAMETER = 4;

    private NativeAutoMeshFunnel() {}

    /**
     * Invokes the modeling-mode funnel for one ArtMesh.
     *
     * <p>Runs on the Editor UI thread. The completion callback counts the latch down, so the caller
     * reads the geometry only after the native body finished.</p>
     *
     * @param artMeshId the ArtMesh to generate for
     * @param completed latch the funnel's completion callback counts down
     * @return {@link #INVOKED} when the funnel ran, otherwise a stable reason token
     */
    static String invoke(final String artMeshId, final CountDownLatch completed) {
        try {
            final Object app = appController();
            if (app == null) {
                return "no-app-controller";
            }
            final Object pack = invokeByName(app, COMPLETE_PACK);
            final Object source = artMeshSourceOf(app, artMeshId);
            if (pack == null || source == null) {
                return "no-pack-or-source";
            }
            final Object generator = generatorOf(source);
            if (generator == null) {
                return "no-generator-extension";
            }
            final ClassLoader loader = generator.getClass().getClassLoader();
            final Class<?> funnelOwner = Class.forName(FUNNEL_OWNER, false, loader);
            final Object funnel = staticField(funnelOwner, FUNNEL_INSTANCE);
            if (funnel == null) {
                return "no-funnel-instance";
            }
            final Object event = staticField(Class.forName(EVENT_TYPE, false, loader), EVENT_DIRECT_UPDATE);
            if (event == null) {
                return "no-direct-update-event";
            }
            for (final Method method : funnelOwner.getDeclaredMethods()) {
                if (!FUNNEL_METHOD.equals(method.getName())
                        || method.getParameterCount() != FUNNEL_WITH_CALLBACK_PARAMETERS) {
                    continue;
                }
                final Object groupUndo = Class.forName(GROUP_UNDO, false, loader)
                        .getDeclaredConstructor()
                        .newInstance();
                final Object callback = callback(loader, method.getParameterTypes()[CALLBACK_PARAMETER], completed);
                method.setAccessible(true);
                method.invoke(funnel, pack, event, List.of(generator), groupUndo, callback);
                return INVOKED;
            }
            return "no-modeling-funnel";
        } catch (Throwable failure) {
            completed.countDown();
            return "funnel-error:" + failure.getClass().getSimpleName();
        }
    }

    /**
     * Returns the mesh-generator extension of one ArtMesh source.
     *
     * <p>The funnel takes extensions, not ArtMeshes, so the probe resolves the same object the
     * Editor's popup collects from the current selection.</p>
     */
    private static Object generatorOf(final Object source) {
        final Object extensions = invokeByName(source, EXTENSIONS);
        if (extensions instanceof Iterable<?> iterable) {
            for (final Object extension : iterable) {
                if (extension != null && extension.getClass().getName().contains(MESH_GENERATOR_EXTENSION)) {
                    return extension;
                }
            }
        }
        return null;
    }

    private static Object callback(final ClassLoader loader, final Class<?> callbackType, final CountDownLatch done) {
        final InvocationHandler handler = (proxy, method, arguments) -> {
            done.countDown();
            return null;
        };
        return Proxy.newProxyInstance(loader, new Class<?>[] {callbackType}, handler);
    }

    private static Object artMeshSourceOf(final Object app, final String artMeshId) {
        final Object document = invokeByName(app, CURRENT_DOCUMENT);
        final Object modelSource = document == null ? null : invokeByName(document, MODEL_SOURCE);
        if (modelSource == null) {
            return null;
        }
        final Object hit = invokeSingleStringArg(modelSource, OBJECT_LOOKUP, artMeshId);
        if (hit != null) {
            return hit;
        }
        final Object all = invokeByName(modelSource, ART_MESH_LIST);
        if (all instanceof Iterable<?> iterable) {
            for (final Object candidate : iterable) {
                if (candidate != null && artMeshId.equals(String.valueOf(invokeByName(candidate, ID_ACCESSOR)))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Object appController() {
        final Class<?> controller = loadHostClass(APP_CONTROLLER);
        if (controller == null) {
            return null;
        }
        final Object instance = staticField(controller, APP_CONTROLLER_INSTANCE);
        return instance != null ? instance : invokeStaticNoArg(controller, APP_CONTROLLER_INSTANCE_ACCESSOR);
    }

    private static Class<?> loadHostClass(final String name) {
        for (final ClassLoader loader :
                new ClassLoader[] {Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()
                }) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader.
            }
        }
        return null;
    }

    private static Object staticField(final Class<?> owner, final String name) {
        try {
            final java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static Object invokeStaticNoArg(final Class<?> owner, final String name) {
        for (final Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                try {
                    method.setAccessible(true);
                    return method.invoke(null);
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object invokeByName(final Object owner, final String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object invokeSingleStringArg(final Object owner, final String name, final String argument) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(owner, argument);
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return null;
                }
            }
        }
        return null;
    }
}
