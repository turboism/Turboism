package dev.turboism.validation;

import java.nio.file.Path;

/** Offline task-binding checks; never starts a UI or Cubism process. */
public final class NativeStartupAssistanceAgentTest {
    public static void main(String[] args) {
        final String run = "queue-0123456789abcdef0123456789abcdef";
        NativeStartupAssistanceAgent.validateTaskHome(Path.of("/tmp", run, "turboism-home"), run);
        for (Path invalid : new Path[] {Path.of("/tmp/normal-profile"),
                Path.of("/tmp/another-task/turboism-home"), Path.of("/tmp", run, "other-home"), Path.of("/")}) {
            try {
                NativeStartupAssistanceAgent.validateTaskHome(invalid, run);
                throw new AssertionError("unbound home accepted");
            } catch (IllegalArgumentException expected) { }
        }
        for (String invalid : new String[] {"", "normal-profile", "queue-../escape", run + "extra"}) {
            try {
                NativeStartupAssistanceAgent.validateTaskHome(Path.of("/tmp", run, "turboism-home"), invalid);
                throw new AssertionError("unbound run accepted");
            } catch (IllegalArgumentException expected) { }
        }

        // User-authorized license selection: the PRO trial entry wins whenever
        // it is offered, the FREE entry is the fallback, and purchase,
        // activation-key or cancel entries are never selected.
        final java.util.List<String> licenseButtons = java.util.List.of("", "Cancel",
                "Activate PRO or Trial license", "Purchase PRO license",
                "Start PRO Version Free Trial", "Start as FREE version");
        if (!"Start PRO Version Free Trial".equals(
                NativeFreeStartup.preferredStartButton("Start", licenseButtons)))
            throw new AssertionError("PRO trial entry not preferred");
        final java.util.List<String> alternateLicenseButtons = java.util.List.of("Cancel",
                "PRO and Trial license activation", "Purchase PRO license",
                "Start PRO Version Free Trial", "Start as FREE version");
        if (!"Start PRO Version Free Trial".equals(
                NativeFreeStartup.preferredStartButton("Start", alternateLicenseButtons)))
            throw new AssertionError("PRO trial entry not preferred on the alternate label variant");
        if (!"Start as FREE version".equals(NativeFreeStartup.preferredStartButton("Start",
                java.util.List.of("Cancel", "Purchase PRO license", "Start as FREE version"))))
            throw new AssertionError("FREE fallback rejected");
        for (java.util.List<String> foreign : new java.util.List[] {
                java.util.List.of("Cancel", "Activate PRO or Trial license", "Purchase PRO license"),
                java.util.List.of("OK"), java.util.List.of()}) {
            if (NativeFreeStartup.preferredStartButton("Start", foreign) != null)
                throw new AssertionError("unapproved license action selected");
        }
        if (NativeFreeStartup.preferredStartButton("Other", licenseButtons) != null)
            throw new AssertionError("license selection accepted on a foreign dialog");
        if (!NativeFreeStartup.isTrialStartButton("Start", "Start PRO Version Free Trial")
                || NativeFreeStartup.isTrialStartButton("Start", "Start as FREE version")
                || NativeFreeStartup.isTrialStartButton("Other", "Start PRO Version Free Trial"))
            throw new AssertionError("trial entry predicate drifted");

        // The user-authorized "start the Trial?" confirmation (COR3-0378) is
        // recognized exactly: the distinctive question or the 42-day trial
        // text identifies it, nothing else matches.
        final String trialConfirmText = "The Trial for the PRO version is available for a limited "
                + "period of 42 days. After 42 days, it will automatically switch to the FREE "
                + "version. If you would like to continue using the PRO version, please purchase "
                + "a license. Once the Trial is activated, you cannot pause the Trial period. "
                + "Would you like to start the Trial?";
        if (!NativeFreeStartup.isProTrialConfirm(null, trialConfirmText)
                || !NativeFreeStartup.isProTrialConfirm("Select an Option",
                        "<html>" + trialConfirmText + "</html>")
                || !NativeFreeStartup.isProTrialConfirm("",
                        "The Trial for the PRO version is available for a limited period of 42 days."))
            throw new AssertionError("trial start confirmation rejected");
        for (String text : new String[] {"License activation successful",
                "Trial license will expire in 42 day(s)",
                "Would you like to start the trial as FREE user?",
                "Purchase PRO license?",
                "A new version has been released."}) {
            if (NativeFreeStartup.isProTrialConfirm("Confirm", text))
                throw new AssertionError("foreign prompt matched the trial confirmation: " + text);
        }

        // The post-selection trial activation confirmation is acknowledged only
        // when title+text report the trial activation and every labelled choice
        // is OK-class.
        final String activated = "The PRO trial license was activated successfully.";
        if (!NativeFreeStartup.isProTrialActivation("Confirm", activated, java.util.List.of("OK")))
            throw new AssertionError("trial activation confirmation rejected");
        if (!NativeFreeStartup.isProTrialActivation("Information",
                "<html>Trial activation succeeded.</html>", java.util.List.of("", "OK")))
            throw new AssertionError("HTML trial activation confirmation rejected");
        for (java.util.List<String> buttons : new java.util.List[] {
                java.util.List.of("OK", "Cancel"), java.util.List.of("Yes", "No"),
                java.util.List.of("Purchase PRO license"), java.util.List.of()}) {
            if (NativeFreeStartup.isProTrialActivation("Confirm", activated, buttons))
                throw new AssertionError("activation confirmation with a non-OK choice accepted");
        }
        for (String text : new String[] {"Trial license will expire in 42 day(s)",
                "Would you like to start the trial?",
                "Welcome! Start as FREE version with limited functions",
                "The PRO trial license was cancelled.",
                "The license was activated successfully."}) {
            if (NativeFreeStartup.isProTrialActivation("Confirm", text, java.util.List.of("OK")))
                throw new AssertionError("non-activation prompt accepted");
        }

        // The non-modal Home news window is recognized exactly so the responder
        // can close the window itself without touching its browser/preference
        // controls.
        if (!NativeFreeStartup.isHomeNewsDialog("Home", "Home ",
                java.util.List.of("Opens upon start only when News and Release Notes have been updated",
                        "", "A new version is available.", "Release Notes")))
            throw new AssertionError("Home news window rejected");
        if (NativeFreeStartup.isHomeNewsDialog("Start", "Home",
                java.util.List.of("Opens upon start only when News and Release Notes have been updated",
                        "A new version is available."))
                || NativeFreeStartup.isHomeNewsDialog("Home", "Home",
                        java.util.List.of("A new version is available."))
                || NativeFreeStartup.isHomeNewsDialog("Home", "Home",
                        java.util.List.of("Opens upon start only when News and Release Notes have been updated"))
                || NativeFreeStartup.isHomeNewsDialog("Home", "Save changes?", java.util.List.of("OK")))
            throw new AssertionError("foreign window matched the Home news signature");

        // The newer-file load gate is identified on every reviewed variant:
        // the exact English signature, a differently titled build carrying the
        // marker text, and localized damaged-file wording — while the update
        // download prompt and unrelated confirmations never match.
        final String newerWarning = "<html><body>The file you are trying to load was created using a newer "
                + "version of the editor currently in use.<br>Current editor version: Cubism 5.1.0 "
                + "<br>Version of the file you are trying to load: Cubism 5.2.3</body></html>";
        if (!NativeFreeStartup.isNewerModelPrompt("Warning", newerWarning)
                || !NativeFreeStartup.isNewerModelPrompt("警告", newerWarning)
                || !NativeFreeStartup.isNewerModelPrompt("Warning",
                        "该文件由较新版本创建，打开时可能会损坏"))
            throw new AssertionError("newer-file gate rejected");
        if (NativeFreeStartup.isNewerModelPrompt("Note!",
                        "A new version has been released. Do you want to exit the Editor and open the download page?")
                || NativeFreeStartup.isNewerModelPrompt("Confirm", "Save changes?")
                || NativeFreeStartup.isNewerModelPrompt("Warning", "A new version is available."))
            throw new AssertionError("foreign prompt matched the newer-file gate");
        for (String label : new String[] {"Load", "Load(L)", "OK", "Open", "Yes", "Yes(Y)",
                "加载", "打开", "确定", "ロード", "開く", "はい", "是"}) {
            if (!NativeFreeStartup.isNewerModelAcceptLabel(label))
                throw new AssertionError("newer-file accept label rejected: " + label);
        }
        for (String label : new String[] {"Cancel", "Cancel(C)", "No", "No(N)", "取消", "否",
                "キャンセル", "いいえ", "Purchase PRO license", "Activate PRO or Trial license"}) {
            if (NativeFreeStartup.isNewerModelAcceptLabel(label))
                throw new AssertionError("non-accept label accepted for the newer-file gate: " + label);
        }
        // The active-trial welcome may carry a "Confirm" caption label ahead
        // of the message (observed on 5.1.03): the signature must still
        // match, and an acknowledged day-count expiry notice must not be
        // confused with the trial-start confirm or the FREE welcome.
        final String activeWelcome = "Confirm <html><h3>Live2D Cubism Editor</h3>Welcome!<br>"
                + "Trial license will expire in 33 day(s)<br><br>Please note that initializing "
                + "or updating your operating system during the trial period<br>may terminate "
                + "the trial version.<br></html>";
        if (!NativeFreeStartup.isActiveTrialWelcome("Confirm", activeWelcome))
            throw new AssertionError("active-trial welcome with caption label rejected");
        if (NativeFreeStartup.isActiveTrialWelcome("Confirm",
                        "The free trial for the PRO version is available for a limited period of 42 days.")
                || NativeFreeStartup.isActiveTrialWelcome("Warning", activeWelcome)
                || NativeFreeStartup.isActiveTrialWelcome("Confirm", "Welcome!"))
            throw new AssertionError("foreign confirm matched the active-trial welcome");
        if (!"ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED".equals(
                NativeFreeStartup.promptAction("Confirm", activeWelcome, "OK")))
            throw new AssertionError("active-trial welcome OK rejected");
        System.out.println("PASS: startup assistance bound to queue task; foreign homes/runs rejected");
    }
}
