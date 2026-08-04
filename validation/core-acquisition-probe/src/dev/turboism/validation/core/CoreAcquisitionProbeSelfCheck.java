package dev.turboism.validation.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal framework-free safety and atomic-output check. */
public final class CoreAcquisitionProbeSelfCheck {
    public static void main(String[] args) throws Exception {
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
