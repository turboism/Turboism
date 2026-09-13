package dev.turboism.validation.atlasimage.t039;

import dev.turboism.validation.atlasimage.t035.T039ShadowPatchBridge;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/** Pure-JDK runtime-source binding and official-data-only regression. */
public final class T040RuntimeSourceBindingHarness {
    private T040RuntimeSourceBindingHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 0, "usage: no arguments");
        final byte[] officialBytes = T039ShadowPatchBridge.official5303Bytes();
        check(T039ShadowPatchBridge.shapeSha256(officialBytes).equals(
            "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f"),
            "official read-only bytes shape mismatch");
        final Path root = Files.createTempDirectory("t040-runtime-source-test-");
        final Path shifted = root.resolve("task-local-shifted.jar");
        final Path sameHash = root.resolve("task-local-second.jar");
        final Path differentHash = root.resolve("task-local-different.jar");
        final Path mismatch = root.resolve("task-local-mismatch.jar");
        try {
            Files.copy(T039ShadowAgent.officialReferenceJarPath(), shifted);
            Files.copy(shifted, sameHash);
            Files.copy(shifted, differentHash);
            Files.write(differentHash, new byte[] {0x01}, StandardOpenOption.APPEND);
            Files.copy(shifted, mismatch);
            final ClassLoader loader = T040RuntimeSourceBindingHarness.class.getClassLoader();
            final String loaderName = loader.getClass().getName();
            final String expectedHash = T039ShadowAgent.officialJarSha256();

            final ProtectionDomain shiftedDomain = fileDomain(shifted, loader);
            final T040RuntimeSourceBinding.Binding binding =
                T040RuntimeSourceBinding.bind(
                    shiftedDomain, loader, loaderName, expectedHash, shifted.toString());
            check(binding.source().equals(shifted.toRealPath()),
                "shifted runtime source was not bound");
            T040RuntimeSourceBinding.verify(
                binding, shiftedDomain, loader, loaderName, expectedHash);

            expectReject("same-hash-multiple-candidates", () ->
                T040RuntimeSourceBinding.bind(
                    shiftedDomain, loader, loaderName, expectedHash,
                    shifted + java.io.File.pathSeparator + sameHash));
            expectReject("different-hash", () ->
                T040RuntimeSourceBinding.bind(
                    fileDomain(differentHash, loader), loader, loaderName, expectedHash,
                    differentHash.toString()));
            expectReject("PD-trusted-path-mismatch", () ->
                T040RuntimeSourceBinding.bind(
                    fileDomain(mismatch, loader), loader, loaderName, expectedHash,
                    shifted.toString()));
            expectReject("wrong-loader", () ->
                T040RuntimeSourceBinding.bind(
                    shiftedDomain, loader, "wrong.Loader", expectedHash, shifted.toString()));
            final URL nonFile = new URL("jar:" + shifted.toUri().toURL() + "!/");
            final ProtectionDomain nonFileDomain = new ProtectionDomain(
                new CodeSource(nonFile, (java.security.cert.Certificate[]) null),
                null, loader, null);
            expectReject("non-file-source", () ->
                T040RuntimeSourceBinding.bind(
                    nonFileDomain, loader, loaderName, expectedHash, shifted.toString()));

            System.out.println("t040OfficialBytes=PASS read-only/shape="
                + T039ShadowPatchBridge.shapeSha256(officialBytes));
            System.out.println("t040RuntimeSourceBinding=PASS shifted-path/hash/unique-PD/loader/non-file");
            System.out.println("T040_RUNTIME_SOURCE_OFFLINE_PASS");
            System.out.println("OFFLINE_PASS");
        } finally {
            delete(shifted);
            delete(sameHash);
            delete(differentHash);
            delete(mismatch);
            delete(root);
        }
    }

    private static ProtectionDomain fileDomain(final Path path, final ClassLoader loader)
        throws IOException {
        return new ProtectionDomain(
            new CodeSource(path.toUri().toURL(), (java.security.cert.Certificate[]) null),
            null, loader, null);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static void expectReject(final String label, final ThrowingCall call) throws Exception {
        try {
            call.run();
        } catch (final IllegalArgumentException | IOException expected) {
            return;
        }
        throw new AssertionError(label + " was accepted");
    }

    private static void delete(final Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
