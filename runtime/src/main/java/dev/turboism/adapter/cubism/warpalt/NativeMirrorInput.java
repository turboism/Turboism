package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.reflect.ReflectAccess;
import java.awt.event.InputEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Reads stored AWT events by field type, without invoking obfuscated event commands. */
final class NativeMirrorInput {
    private static final ClassValue<Optional<Field>> RAW = fields(InputEvent.class.getName());
    private static final ClassValue<Optional<Field>> PACK = fields("com.live2d.ui.event.h");

    private NativeMirrorInput() {}

    static InputEvent awt(Object event) throws IllegalAccessException {
        if (event == null) return null;
        if (event instanceof InputEvent input) return input;
        Field direct = RAW.get(event.getClass()).orElse(null);
        if (direct != null) return (InputEvent) direct.get(event);
        Field pack = PACK.get(event.getClass()).orElse(null);
        if (pack == null) return null;
        Object rawPack = pack.get(event);
        if (rawPack == null) return null;
        Field raw = RAW.get(rawPack.getClass()).orElse(null);
        return raw == null ? null : (InputEvent) raw.get(rawPack);
    }

    private static ClassValue<Optional<Field>> fields(String typeName) {
        return new ClassValue<>() {
            @Override
            protected Optional<Field> computeValue(Class<?> type) {
                Field found = null;
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())
                                || !field.getType().getName().equals(typeName)) continue;
                        if (found != null) return Optional.empty();
                        found = field;
                    }
                }
                if (found != null) ReflectAccess.open(found);
                return Optional.ofNullable(found);
            }
        };
    }
}
