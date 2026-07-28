package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Objects;

final class StartupSuppressionTransformer {

    private final StartupSuppressionProfile profile;
    private final RuntimeStartupConfig policy;

    StartupSuppressionTransformer(
        final StartupSuppressionProfile profile,
        final RuntimeStartupConfig policy
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    byte[] transformClass(final byte[] original) {
        Objects.requireNonNull(original, "original");
        if (policy.safeMode()) {
            throw rejected("safe mode disables startup suppression");
        }
        if (!policy.skipStartupUpdateCheck()
            && !policy.skipStartupSplash()
            && !policy.skipStartupInformation()) {
            throw rejected("startup suppression was not requested");
        }

        final MatchCounts counts = inspect(original);
        requireExactCounts(counts);

        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final StartupSuppressionProfile.MethodSelector splash = profile.splashMethod();
                if (policy.skipStartupSplash()
                    && name.equals(splash.name())
                    && descriptor.equals(splash.descriptor())) {
                    final MethodVisitor replacement = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                    );
                    replacement.visitCode();
                    replacement.visitInsn(Opcodes.ACONST_NULL);
                    replacement.visitInsn(Opcodes.ARETURN);
                    replacement.visitMaxs(0, 0);
                    replacement.visitEnd();
                    return null;
                }

                final MethodVisitor delegate = super.visitMethod(
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions
                );
                final StartupSuppressionProfile.MethodSelector startup = profile.startupMethod();
                if (!name.equals(startup.name()) || !descriptor.equals(startup.descriptor())) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String invokedName,
                        final String invokedDescriptor,
                        final boolean isInterface
                    ) {
                        if (policy.skipStartupUpdateCheck()
                            && matches(
                                opcode,
                                owner,
                                invokedName,
                                invokedDescriptor,
                                profile.updateCheckCall()
                            )) {
                            super.visitInsn(Opcodes.POP);
                            return;
                        }
                        if (policy.skipStartupInformation()
                            && matches(
                                opcode,
                                owner,
                                invokedName,
                                invokedDescriptor,
                                profile.informationCall()
                            )) {
                            super.visitInsn(Opcodes.POP);
                            return;
                        }
                        super.visitMethodInsn(
                            opcode,
                            owner,
                            invokedName,
                            invokedDescriptor,
                            isInterface
                        );
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private MatchCounts inspect(final byte[] original) {
        final MatchCounts counts = new MatchCounts();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(
                final int version,
                final int access,
                final String name,
                final String signature,
                final String superName,
                final String[] interfaces
            ) {
                if (name.equals(profile.targetOwner())) {
                    counts.ownerMatches++;
                }
            }

            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final StartupSuppressionProfile.MethodSelector startup = profile.startupMethod();
                final StartupSuppressionProfile.MethodSelector splash = profile.splashMethod();
                if (name.equals(splash.name()) && descriptor.equals(splash.descriptor())) {
                    counts.splashMethods++;
                }
                if (!name.equals(startup.name()) || !descriptor.equals(startup.descriptor())) {
                    return null;
                }
                counts.startupMethods++;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String invokedName,
                        final String invokedDescriptor,
                        final boolean isInterface
                    ) {
                        if (matches(
                            opcode,
                            owner,
                            invokedName,
                            invokedDescriptor,
                            profile.updateCheckCall()
                        )) {
                            counts.updateCalls++;
                        }
                        if (matches(
                            opcode,
                            owner,
                            invokedName,
                            invokedDescriptor,
                            profile.informationCall()
                        )) {
                            counts.informationCalls++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }

    private void requireExactCounts(final MatchCounts counts) {
        if (counts.ownerMatches != 1 || counts.startupMethods != 1) {
            throw rejected("exact startup owner/method was not found");
        }
        if (policy.skipStartupUpdateCheck() && counts.updateCalls != 1) {
            throw rejected("startup update invocation cardinality was not exactly one");
        }
        if (policy.skipStartupInformation() && counts.informationCalls != 1) {
            throw rejected("startup information invocation cardinality was not exactly one");
        }
        if (policy.skipStartupSplash() && counts.splashMethods != 1) {
            throw rejected("Splash method cardinality was not exactly one");
        }
    }

    private static boolean matches(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final StartupSuppressionProfile.MethodSelector selector
    ) {
        return opcode == Opcodes.INVOKEVIRTUAL
            && owner.equals(selector.owner())
            && name.equals(selector.name())
            && descriptor.equals(selector.descriptor());
    }

    private static TransformationRejectedException rejected(final String message) {
        return new TransformationRejectedException(message);
    }

    static final class TransformationRejectedException extends RuntimeException {
        TransformationRejectedException(final String message) {
            super(message);
        }
    }

    private static final class MatchCounts {
        private int ownerMatches;
        private int startupMethods;
        private int updateCalls;
        private int informationCalls;
        private int splashMethods;
    }
}
