package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the five operation families: every operation exists as a typed method,
 * every unavailable implementation fails closed, and no {@code com.live2d} type leaks into the
 * public surface.
 */
final class EditOpsContractTest {

    /** The 30 typed operations across the five families, by official operation count. */
    private static final Map<Class<?>, Integer> FAMILY_OPERATION_COUNTS = Map.of(
        ParameterKeyOps.class, 5,
        ParameterStructureOps.class, 9,
        SelectionOps.class, 3,
        PartObjectOps.class, 8,
        DeformerOps.class, 5
    );

    /** Sample values for request types whose canonical constructor rejects the generic default. */
    private static final Map<Class<?>, Object> REQUEST_SAMPLES = Map.of(
        SelectionOps.AddSelectedObjects.class,
        new SelectionOps.AddSelectedObjects(List.of(new ModelObjectId("mesh-1")))
    );

    @Test
    void familiesDeclareTheOfficialTypedOperations() throws Exception {
        for (final var entry : FAMILY_OPERATION_COUNTS.entrySet()) {
            final List<Method> operations = operationsOf(entry.getKey());

            assertEquals(
                entry.getValue().intValue(),
                operations.size(),
                entry.getKey().getSimpleName() + " operation count");

            for (final Method operation : operations) {
                for (final Class<?> parameterType : operation.getParameterTypes()) {
                    assertTrue(
                        parameterType.isRecord(),
                        operation.getName() + " parameter must be a typed record, got "
                            + parameterType.getName());
                }
            }
        }
    }

    @Test
    void unavailableFamiliesFailClosedOnEveryOperation() throws Exception {
        final Map<Class<?>, Object> unavailableFamilies = Map.of(
            ParameterKeyOps.class, ParameterKeyOps.unavailable(),
            ParameterStructureOps.class, ParameterStructureOps.unavailable(),
            SelectionOps.class, SelectionOps.unavailable(),
            PartObjectOps.class, PartObjectOps.unavailable(),
            DeformerOps.class, DeformerOps.unavailable()
        );

        for (final var family : unavailableFamilies.entrySet()) {
            final Object instance = family.getValue();
            for (final Method operation : operationsOf(family.getKey())) {
                final Object[] args = new Object[operation.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = sampleValue(operation.getParameterTypes()[i]);
                }
                final InvocationTargetException thrown = assertThrows(
                    InvocationTargetException.class,
                    () -> operation.invoke(instance, args),
                    operation.getName() + " must throw on an unavailable family");
                assertTrue(
                    thrown.getCause() instanceof EditUnavailableException,
                    operation.getName() + " must fail closed with EditUnavailableException, got "
                        + thrown.getCause());
                assertEquals(
                    EditUnavailableException.CODE,
                    ((EditUnavailableException) thrown.getCause()).code());
            }
        }
    }

    @Test
    void requestRecordsValidateTheirArguments() {
        assertThrows(NullPointerException.class, () -> new ParameterKeyOps.AddParameterKey(
            null, new dev.turboism.sdk.cubism.id.ParameterId("p"), 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ParameterKeyOps.AddParameterKey(
            object(), new dev.turboism.sdk.cubism.id.ParameterId("p"), Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> new SelectionOps.AddSelectedObjects(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new EditLabelColor(EditLabelColorType.CUSTOM, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new EditLabelColor(EditLabelColorType.RED, Optional.of("#fff")));
        assertThrows(IllegalArgumentException.class, () -> new EditRectangle(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EditTriangle(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ParameterStructureOps.MoveParameter(
            new dev.turboism.sdk.cubism.id.ParameterId("p"),
            new dev.turboism.sdk.cubism.id.ParameterGroupId("g"),
            Optional.of(-1)));
    }

    @Test
    void noPublicMemberExposesLive2dTypes() throws Exception {
        for (final Class<?> type : editPackageTypes()) {
            for (final Method method : type.getDeclaredMethods()) {
                assertFreeOfLive2d(method.getGenericReturnType().toString(), type, method.getName());
                for (final var parameter : method.getGenericParameterTypes()) {
                    assertFreeOfLive2d(parameter.toString(), type, method.getName());
                }
            }
            for (final var field : type.getDeclaredFields()) {
                assertFreeOfLive2d(field.getGenericType().toString(), type, field.getName());
            }
            for (final var constructor : type.getDeclaredConstructors()) {
                for (final var parameter : constructor.getGenericParameterTypes()) {
                    assertFreeOfLive2d(parameter.toString(), type, "<init>");
                }
            }
        }
    }

    private static void assertFreeOfLive2d(
        final String signature, final Class<?> type, final String member) {
        assertTrue(
            !signature.contains("com.live2d"),
            type.getName() + "#" + member + " exposes a com.live2d type: " + signature);
    }

    private static List<Method> operationsOf(final Class<?> family) {
        final List<Method> operations = new ArrayList<>();
        for (final Method method : family.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == family) {
                operations.add(method);
            }
        }
        return operations;
    }

    private static Object sampleValue(final Class<?> type) throws Exception {
        if (REQUEST_SAMPLES.containsKey(type)) {
            return REQUEST_SAMPLES.get(type);
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == String.class) {
            return "value";
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isRecord()) {
            final RecordComponent[] components = type.getRecordComponents();
            final Class<?>[] parameterTypes = new Class<?>[components.length];
            final Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                parameterTypes[i] = components[i].getType();
                args[i] = sampleValue(components[i].getType());
            }
            final var constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        }
        throw new AssertionError("no sample value for " + type.getName());
    }

    private static dev.turboism.sdk.cubism.model.ModelObjectReference object() {
        return new dev.turboism.sdk.cubism.model.ModelObjectReference(
            dev.turboism.sdk.cubism.model.ModelObjectKind.ART_MESH, "mesh-1");
    }

    private static List<Class<?>> editPackageTypes() throws Exception {
        final ClassLoader loader = EditSession.class.getClassLoader();
        final Enumeration<URL> roots = loader.getResources("dev/turboism/sdk/cubism/edit");
        final List<Class<?>> types = new ArrayList<>();
        while (roots.hasMoreElements()) {
            final URL root = roots.nextElement();
            if (!"file".equals(root.getProtocol())) {
                continue;
            }
            final File directory = new File(root.toURI());
            final File[] files = directory.listFiles((dir, name) -> name.endsWith(".class"));
            assertNotNull(files);
            for (final File file : files) {
                final String name = file.getName().replace(".class", "");
                if (name.endsWith("-info")) {
                    continue;
                }
                types.add(Class.forName("dev.turboism.sdk.cubism.edit." + name));
            }
        }
        return types;
    }
}
