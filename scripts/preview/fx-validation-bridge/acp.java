import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Validation-only ACP stdio bridge from Cubism's Windows JRE to a loopback Linux broker.
 *
 * <p>The production plugin still launches a configured executable as {@code executable acp}. During
 * exact-host validation the executable is Cubism's bundled {@code java.exe}, this class is supplied
 * through an inherited validation class path, and the real reviewed Linux fx process remains behind
 * an authenticated loopback broker. No ACP line or credential is logged by this bridge.</p>
 */
public final class acp {

    private static final String CONFIG_PROPERTY = "turboism.fx.validation.bridgeConfig";
    private static final String DEFAULT_CONFIG_FILE = "fx-validation-bridge.properties";
    private static final String MAGIC = "TURBOISM_FX_BRIDGE/1";
    private static final String CHALLENGE = MAGIC + " CHALLENGE";
    private static final String AUTH = MAGIC + " AUTH";
    private static final String OK = MAGIC + " OK";
    private static final String CLIENT_DOMAIN = "turboism-fx-bridge-client-v1";
    private static final String BROKER_DOMAIN = "turboism-fx-bridge-broker-v1";
    private static final int MAX_CONFIG_BYTES = 8192;
    private static final int MAX_TOKEN_BYTES = 256;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final AtomicReference<String> FAILURE_STAGE =
        new AtomicReference<>("startup");

    private acp() {
    }

    /** Runs the authenticated byte bridge, emitting only a fixed failure marker on stderr. */
    public static void main(final String[] arguments) {
        try {
            if (arguments.length > 1 || arguments.length == 1 && !"acp".equals(arguments[0])) {
                throw new IOException("unexpected arguments");
            }
            final String configured = System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_FILE);
            FAILURE_STAGE.set("configuration");
            run(readConfiguration(Path.of(configured)));
        } catch (Exception failure) {
            System.err.println(
                "Turboism fx validation bridge failed at "
                    + FAILURE_STAGE.get()
            );
            System.exit(1);
        }
    }

    private static void run(final Configuration configuration) throws Exception {
        FAILURE_STAGE.set("token");
        final String token = readToken(configuration.tokenFile());
        try (Socket socket = new Socket()) {
            FAILURE_STAGE.set("connect");
            socket.setTcpNoDelay(true);
            socket.connect(
                new InetSocketAddress(InetAddress.getByName(configuration.host()), configuration.port()),
                CONNECT_TIMEOUT_MILLIS
            );
            if (!socket.getInetAddress().isLoopbackAddress()) {
                throw new IOException("broker is not loopback");
            }
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            FAILURE_STAGE.set("handshake");
            final InputStream brokerInput = socket.getInputStream();
            final OutputStream brokerOutput = socket.getOutputStream();
            final byte[] clientNonce = new byte[32];
            new SecureRandom().nextBytes(clientNonce);
            writeAsciiLine(brokerOutput, MAGIC);
            writeAsciiLine(brokerOutput, Base64.getUrlEncoder().withoutPadding().encodeToString(clientNonce));
            brokerOutput.flush();
            if (!CHALLENGE.equals(readAsciiLine(brokerInput, 128))) {
                throw new IOException("broker authentication failed");
            }
            final byte[] brokerNonce = decodeNonce(readAsciiLine(brokerInput, 128));
            final byte[] brokerMac = decodeMac(readAsciiLine(brokerInput, 128));
            final byte[] expectedBrokerMac = mac(
                token,
                BROKER_DOMAIN,
                configuration.sessionId(),
                clientNonce,
                brokerNonce
            );
            if (!MessageDigest.isEqual(expectedBrokerMac, brokerMac)) {
                throw new IOException("broker authentication failed");
            }
            writeAsciiLine(brokerOutput, AUTH);
            writeAsciiLine(brokerOutput, HexFormat.of().formatHex(mac(
                token,
                CLIENT_DOMAIN,
                configuration.sessionId(),
                clientNonce,
                brokerNonce
            )));
            brokerOutput.flush();
            if (!OK.equals(readAsciiLine(brokerInput, 128))) {
                throw new IOException("broker authentication failed");
            }
            FAILURE_STAGE.set("proxy");
            socket.setSoTimeout(0);

            final AtomicReference<IOException> upstreamFailure = new AtomicReference<>();
            final Thread upstream = daemon("turboism-fx-bridge-input", () -> {
                try {
                    copy(System.in, brokerOutput);
                    socket.shutdownOutput();
                } catch (IOException failure) {
                    upstreamFailure.compareAndSet(null, failure);
                }
            });
            upstream.start();
            copy(brokerInput, System.out);
            System.out.flush();
            if (upstreamFailure.get() != null && !socket.isClosed()) {
                throw upstreamFailure.get();
            }
        }
    }

    private static Configuration readConfiguration(final Path requested) throws IOException {
        final Path path = requested.toAbsolutePath().normalize();
        final byte[] bytes = readRegularFile(path, MAX_CONFIG_BYTES);
        final String text = ascii(bytes, "configuration");
        final Map<String, String> values = new HashMap<>();
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.isEmpty()) continue;
            final int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IOException("invalid configuration line");
            }
            final String key = line.substring(0, separator);
            final String value = line.substring(separator + 1);
            if (values.putIfAbsent(key, value) != null) {
                throw new IOException("duplicate configuration key");
            }
        }
        if (!values.keySet().equals(java.util.Set.of(
            "schemaVersion", "sessionId", "host", "port", "tokenFile"
        ))) {
            throw new IOException("invalid configuration keys");
        }
        if (!"1".equals(values.get("schemaVersion"))
            || !"127.0.0.1".equals(values.get("host"))
            || !TOKEN.matcher(values.get("sessionId")).matches()) {
            throw new IOException("unsupported configuration");
        }
        final int port;
        try {
            port = Integer.parseInt(values.get("port"));
        } catch (NumberFormatException failure) {
            throw new IOException("invalid broker port", failure);
        }
        if (port < 1 || port > 65535) throw new IOException("invalid broker port");
        final Path tokenFile = Path.of(windowsPath(values.get("tokenFile"))).normalize();
        if (!tokenFile.isAbsolute()) throw new IOException("token file is not absolute");
        return new Configuration(values.get("sessionId"), values.get("host"), port, tokenFile);
    }

    private static String windowsPath(final String value) throws IOException {
        if (java.io.File.separatorChar == '/') {
            if (value.length() >= 3 && (value.charAt(0) == 'Z' || value.charAt(0) == 'z')
                && value.charAt(1) == ':' && (value.charAt(2) == '/' || value.charAt(2) == '\\')) {
                return value.substring(2).replace('\\', '/');
            }
            return value;
        }
        if (value.length() < 3 || !Character.isLetter(value.charAt(0)) || value.charAt(1) != ':') {
            throw new IOException("token file is not an absolute Windows path");
        }
        return value;
    }

    private static byte[] decodeNonce(final String value) throws IOException {
        try {
            final byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length != 32) throw new IOException("broker nonce is invalid");
            return bytes;
        } catch (IllegalArgumentException failure) {
            throw new IOException("broker nonce is invalid", failure);
        }
    }

    private static byte[] decodeMac(final String value) throws IOException {
        try {
            final byte[] bytes = HexFormat.of().parseHex(value);
            if (bytes.length != 32) throw new IOException("broker MAC is invalid");
            return bytes;
        } catch (IllegalArgumentException failure) {
            throw new IOException("broker MAC is invalid", failure);
        }
    }

    private static byte[] mac(
        final String secret,
        final String domain,
        final String sessionId,
        final byte[] clientNonce,
        final byte[] brokerNonce
    ) throws IOException {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            update(mac, domain.getBytes(StandardCharsets.US_ASCII));
            update(mac, sessionId.getBytes(StandardCharsets.US_ASCII));
            update(mac, clientNonce);
            update(mac, brokerNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IOException("bridge authentication is unavailable", failure);
        }
    }

    private static void update(final Mac mac, final byte[] value) {
        mac.update((byte) (value.length >>> 24));
        mac.update((byte) (value.length >>> 16));
        mac.update((byte) (value.length >>> 8));
        mac.update((byte) value.length);
        mac.update(value);
    }

    private static String readToken(final Path path) throws IOException {
        final String token = ascii(readRegularFile(path, MAX_TOKEN_BYTES), "token").strip();
        if (!TOKEN.matcher(token).matches()) throw new IOException("invalid broker token");
        return token;
    }

    private static byte[] readRegularFile(final Path path, final int maximum) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("symbolic links are unsupported");
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > maximum) {
            throw new IOException("file is invalid");
        }
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 1 || bytes.length > maximum) throw new IOException("file is invalid");
        return bytes;
    }

    private static String ascii(final byte[] bytes, final String label) throws IOException {
        for (byte value : bytes) {
            final int unsigned = value & 0xff;
            if (unsigned != '\r' && unsigned != '\n' && (unsigned < 0x20 || unsigned > 0x7e)) {
                throw new IOException(label + " is not ASCII");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void writeAsciiLine(final OutputStream output, final String line) throws IOException {
        output.write(line.getBytes(StandardCharsets.US_ASCII));
        output.write('\n');
    }

    private static String readAsciiLine(final InputStream input, final int maximum) throws IOException {
        final byte[] bytes = new byte[maximum];
        int count = 0;
        while (true) {
            final int value = input.read();
            if (value < 0) throw new IOException("broker handshake ended");
            if (value == '\n') break;
            if (value == '\r') continue;
            if (value < 0x20 || value > 0x7e || count == bytes.length) {
                throw new IOException("broker handshake is invalid");
            }
            bytes[count++] = (byte) value;
        }
        return new String(bytes, 0, count, StandardCharsets.US_ASCII);
    }

    private static void copy(final InputStream input, final OutputStream output) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (count == 0) continue;
            output.write(buffer, 0, count);
            output.flush();
        }
    }

    private static Thread daemon(final String name, final Runnable work) {
        final Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    private record Configuration(String sessionId, String host, int port, Path tokenFile) {
    }
}
