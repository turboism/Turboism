package dev.turboism.adapter.cubism.optimization;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits the reviewed property-slot handoff that carries a bridge callback into host bytecode.
 *
 * <p>A bridge publishes its callback as a system property, and the woven host method reads that
 * slot, keeps one value on the operand stack, and guards the call with {@code instanceof}. One
 * {@code Hashtable} read then serves both the guard and the cast, so the host never reads the
 * slot twice per call site.</p>
 *
 * <p>The emitter leaves the resolved value on the stack. The caller follows with the guard
 * branch, the cast, and the callback arguments, so every woven site keeps the exact stack shape
 * its reviewed shape declares.</p>
 */
public final class SlotBridgeEmitter {

    /** Owner of the property lookup the woven host code links against. */
    public static final String SYSTEM_OWNER = "java/lang/System";

    /** Name of the property-table accessor the woven host code links against. */
    public static final String PROPERTIES_ACCESSOR = "getProperties";

    /** Descriptor of {@link #PROPERTIES_ACCESSOR}: {@code () -> Properties}. */
    public static final String PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";

    /** Owner of the property table itself. */
    public static final String PROPERTIES_OWNER = "java/util/Properties";

    /** Name of the slot read on the property table. */
    public static final String SLOT_READ = "get";

    /** Descriptor of {@link #SLOT_READ}: {@code (Object) -> Object}. */
    public static final String SLOT_READ_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private SlotBridgeEmitter() {}

    /**
     * Emits the slot read, duplicates the value, and tests it against {@code slotType}.
     *
     * @param visitor the host method being woven
     * @param property the system property that carries the callback
     * @param slotType internal name of the callback interface the host expects
     */
    public static void emitSlot(final MethodVisitor visitor, final String property, final String slotType) {
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, SYSTEM_OWNER, PROPERTIES_ACCESSOR, PROPERTIES_DESCRIPTOR, false);
        visitor.visitLdcInsn(property);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, SLOT_READ, SLOT_READ_DESCRIPTOR, false);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitTypeInsn(Opcodes.INSTANCEOF, slotType);
    }
}
