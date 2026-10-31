package dev.turboism.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * TEST-ONLY helper entry that trusts one explicitly supplied public key. It lives in the test source set so it is
 * never packaged: the production {@link FrameworkUpdateHelper#main} trusts only the bundled production keys.
 * First argument: file holding the Base64 X.509 Ed25519 public key; optional second argument: --installed;
 * the rest are normal helper arguments.
 */
public final class TestKeyHelperMain {
    private TestKeyHelperMain() {}

    public static void main(final String[] args) throws Exception {
        final byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(args[0])).trim());
        final PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        boolean installed = false;
        int offset = 1;
        if (args.length > 1 && args[1].equals("--installed")) {
            installed = true;
            offset = 2;
        }
        final String[] helperArgs = Arrays.copyOfRange(args, offset, args.length);
        final Map<String, PublicKey> keys = Map.of("fixture", key, "local-test", key);
        final int exitCode = installed
                ? FrameworkUpdateHelper.runInstalled(
                        helperArgs,
                        System.out,
                        System.err,
                        keys,
                        ProcessHandle::of,
                        Instant::now)
                : FrameworkUpdateHelper.run(
                        helperArgs,
                        System.out,
                        System.err,
                        keys,
                        ProcessHandle::of,
                        Instant::now);
        System.exit(exitCode);
    }
}
