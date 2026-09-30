package dev.turboism.validation.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal framework-free safety and atomic-output check. */
public final class CoreAcquisitionProbeSelfCheck {
    public static void main(String[] args) throws Exception {
        if (!CoreAcquisitionProbeAgent.isFreeStartupButton("Start", "Start as FREE version"))
            throw new AssertionError("FREE startup rejected");
        for (String text : new String[] {"Start PRO Version Free Trial", "Purchase PRO license",
                "Activate PRO or Trial license", "Yes(Y)", "No(N)"}) {
            if (CoreAcquisitionProbeAgent.isFreeStartupButton("Start", text))
                throw new AssertionError("non-FREE startup action accepted");
        }
        if (CoreAcquisitionProbeAgent.isFreeStartupButton("Other", "Start as FREE version"))
            throw new AssertionError("unrelated dialog accepted");
        if (!CoreAcquisitionProbeAgent.isFreeWelcome("Confirm",
                "<html>Welcome!<br>Start as FREE version with limited functions</html>"))
            throw new AssertionError("FREE welcome rejected");
        if (CoreAcquisitionProbeAgent.isFreeWelcome("Confirm", "Welcome! Trial license will expire in 43 day(s)"))
            throw new AssertionError("trial welcome accepted");
        if (CoreAcquisitionProbeAgent.isFreeWelcome("Confirm", "Would you like to start the trial?"))
            throw new AssertionError("trial activation accepted");
        if (!"6.0.513".equals(CoreAcquisitionProbeAgent.nativeVersionTuple(6, 0, 513)))
            throw new AssertionError("observed native tuple changed");
        for (Object invalid : new Object[] {null, "6", -1, 6L}) {
            try {
                CoreAcquisitionProbeAgent.nativeVersionTuple(invalid, 0, 513);
                throw new AssertionError("invalid native tuple accepted");
            } catch (IllegalArgumentException expected) {
                // No version is guessed or coerced from malformed native values.
            }
        }
        Method getter = Fixture.class.getMethod("getValue");
        Method setter = Fixture.class.getMethod("setValue", String.class);
        Method close = Fixture.class.getMethod("getClose");
        if (!CoreAcquisitionProbeAgent.isSafePublicGetter(getter)) throw new AssertionError("getter rejected");
        if (CoreAcquisitionProbeAgent.isSafePublicGetter(setter)) throw new AssertionError("setter accepted");
        if (CoreAcquisitionProbeAgent.isSafePublicGetter(close)) throw new AssertionError("unsafe getter accepted");
        if (!Modifier.isPublic(getter.getModifiers())) throw new AssertionError("fixture not public");
        Path dir = Files.createTempDirectory("core-acq-self-check");
        Path target = dir.resolve("state/result.properties");
        CoreAcquisitionProbeAgent.writeAtomic(target, "status=PASS\n".getBytes(StandardCharsets.UTF_8));
        if (!"status=PASS\n".equals(Files.readString(target))) throw new AssertionError("atomic output mismatch");
        System.out.println("CORE_ACQUISITION_SELF_CHECK PASS");
    }

    public static final class Fixture {
        public String getValue() { return "ok"; }
        public void setValue(String value) { }
        public String getClose() { return "no"; }
    }
}
