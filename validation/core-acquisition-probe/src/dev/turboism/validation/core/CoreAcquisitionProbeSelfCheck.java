package dev.turboism.validation.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal framework-free safety and atomic-output check. */
public final class CoreAcquisitionProbeSelfCheck {
    public static void main(String[] args) throws Exception {
        final String newer = "The file you are trying to load was created using a newer version"
                + " Current editor version: Cubism 5.0.0 Version of the file you are trying to load: Cubism 5.2.3";
        final String update = "A new version has been released."
                + " Do you want to exit the Editor and open the download page?";
        final String trial = "<html><h3>Live2D Cubism Editor</h3>Welcome!<br>Trial license will expire in 42 day(s)<br><br>"
                + "Please note that initializing or updating your operating system during the trial period "
                + "may terminate the trial version.<br></html>";
        if (!"ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", trial, "OK")))
            throw new AssertionError("existing active trial information rejected");
        for (String invalid : new String[] {"Would you like to start the trial?", "Activate PRO or Trial license",
                trial + " Start trial", trial.replace("42", "0"), trial.replace("42", "-1"),
                trial.replace("Trial license will expire", "License expired")}) {
            if (dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", invalid, "OK") != null)
                throw new AssertionError("unreviewed trial action accepted");
        }
        if (dev.turboism.validation.NativeFreeStartup.promptAction("Start", trial, "OK") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", trial, "Start trial") != null)
            throw new AssertionError("trial activation button/title accepted");
        // Every reviewed accept label for the newer-file gate (Load/Cancel,
        // OK/Cancel, Open/Cancel, Yes/No and their mnemonic/localized twins)
        // is confirmed; the cancel side is never answered.
        for (String button : new String[] {"Load", "Yes", "Yes(Y)", "OK", "Open",
                "加载", "打开", "确定", "ロード", "開く", "はい", "是"}) {
            if (!"NEWER_MODEL_OPEN_CONFIRMED".equals(
                    dev.turboism.validation.NativeFreeStartup.promptAction("Warning", newer, button)))
                throw new AssertionError("newer model confirmation rejected");
        }
        if (!"UPDATE_PAGE_CANCELLED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Note!", update, "Cancel")))
            throw new AssertionError("update cancellation rejected");
        final String freeLimit = "FREE version - The max number of Parameters allowed per object: 2"
                + " This operation is cancelled as 3 parameters are set.";
        final String multiplyLimit = "Multiply Color is only available in the PRO version."
                + " Save failed. Please try again meeting the restrictions.";
        if (!"FREE_MULTIPLY_COLOR_SAVE_FAILURE_ACKNOWLEDGED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Information", multiplyLimit, "OK")))
            throw new AssertionError("known Multiply Color save failure rejected");
        if (dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", multiplyLimit, "OK") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Information", multiplyLimit, "Yes") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Information", "Save changes?", "OK") != null)
            throw new AssertionError("unexpected Multiply Color confirmation accepted");
        final String screenLimit = "Screen Color is only available in the PRO version."
                + " Save failed. Please try again meeting the restrictions.";
        if (!"FREE_SCREEN_COLOR_SAVE_FAILURE_ACKNOWLEDGED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Information", screenLimit, "OK")))
            throw new AssertionError("known Screen Color save failure rejected");
        if (dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", screenLimit, "OK") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Information", screenLimit, "Yes") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Information", "Save changes?", "OK") != null)
            throw new AssertionError("unrelated save confirmation accepted");
        if (!"FREE_PARAMETER_LIMIT_ACKNOWLEDGED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Information Dialog Box", freeLimit, "OK")))
            throw new AssertionError("known FREE limit information rejected");
        if (!"FREE_PARAMETER_LIMIT_ACKNOWLEDGED".equals(
                dev.turboism.validation.NativeFreeStartup.promptAction("Information", freeLimit, "OK")))
            throw new AssertionError("Swing FREE information title rejected");
        if (dev.turboism.validation.NativeFreeStartup.promptAction("Confirm", freeLimit, "OK") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Information Dialog Box", "Save changes?", "OK") != null)
            throw new AssertionError("unrelated information accepted");
        for (String text : new String[] {"Would you like to start the Trial?", "Save changes?", "Delete model?"}) {
            if (dev.turboism.validation.NativeFreeStartup.promptAction("Warning", text, "Yes(Y)") != null)
                throw new AssertionError("unrelated confirmation accepted");
        }
        if (dev.turboism.validation.NativeFreeStartup.promptAction("Other", newer, "Yes") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Note!", update, "Open") != null
                || dev.turboism.validation.NativeFreeStartup.promptAction("Warning", newer, "Cancel") != null)
            throw new AssertionError("unapproved action accepted");
        if (!CoreAcquisitionProbeAgent.isFreeStartupButton("Start", "Start as FREE version"))
            throw new AssertionError("FREE startup rejected");
        for (String text : new String[] {"Start PRO Version Free Trial", "Purchase PRO license",
                "Activate PRO or Trial license", "Yes(Y)", "No(N)"}) {
            if (CoreAcquisitionProbeAgent.isFreeStartupButton("Start", text))
                throw new AssertionError("non-FREE startup action accepted");
        }
        if (CoreAcquisitionProbeAgent.isFreeStartupButton("Other", "Start as FREE version"))
            throw new AssertionError("unrelated dialog accepted");
        // The reviewing user authorized the PRO trial entry: it wins whenever
        // offered, the FREE entry is the fallback, and purchase, activation-key
        // or cancel entries are never selected.
        final java.util.List<String> licenseButtons = java.util.List.of("", "Cancel",
                "Activate PRO or Trial license", "Purchase PRO license",
                "Start PRO Version Free Trial", "Start as FREE version");
        if (!"Start PRO Version Free Trial".equals(dev.turboism.validation.NativeFreeStartup
                .preferredStartButton("Start", licenseButtons)))
            throw new AssertionError("PRO trial entry not preferred");
        if (!"Start as FREE version".equals(dev.turboism.validation.NativeFreeStartup
                .preferredStartButton("Start", java.util.List.of("Cancel", "Purchase PRO license",
                        "Start as FREE version"))))
            throw new AssertionError("FREE fallback rejected");
        if (dev.turboism.validation.NativeFreeStartup.preferredStartButton("Start",
                java.util.List.of("Cancel", "Activate PRO or Trial license",
                        "Purchase PRO license")) != null)
            throw new AssertionError("unapproved license action accepted");
        if (!dev.turboism.validation.NativeFreeStartup.isProTrialConfirm(null,
                "The Trial for the PRO version is available for a limited period of 42 days. "
                        + "Would you like to start the Trial?"))
            throw new AssertionError("trial start confirmation rejected");
        if (dev.turboism.validation.NativeFreeStartup.isProTrialConfirm("Confirm",
                "License activation successful"))
            throw new AssertionError("activation notice matched the trial confirmation");
        if (!dev.turboism.validation.NativeFreeStartup.isProTrialActivation("Confirm",
                "The PRO trial license was activated successfully.", java.util.List.of("OK")))
            throw new AssertionError("trial activation confirmation rejected");
        if (!dev.turboism.validation.NativeFreeStartup.isProTrialActivation("Confirm",
                "License activation successful", java.util.List.of("OK")))
            throw new AssertionError("native activation success notice rejected");
        if (dev.turboism.validation.NativeFreeStartup.isProTrialActivation("Confirm",
                "The PRO trial license was activated successfully.",
                java.util.List.of("OK", "Cancel"))
                || dev.turboism.validation.NativeFreeStartup.isProTrialActivation("Confirm",
                        trial, java.util.List.of("OK")))
            throw new AssertionError("non-activation trial prompt accepted");
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
