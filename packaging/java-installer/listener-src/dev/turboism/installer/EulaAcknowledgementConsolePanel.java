package dev.turboism.installer;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.exception.UserInterruptException;
import com.izforge.izpack.installer.console.AbstractConsolePanel;
import com.izforge.izpack.installer.console.ConsolePanel;
import com.izforge.izpack.installer.panel.PanelView;
import com.izforge.izpack.util.Console;

import java.util.Properties;

/** Console equivalent of {@link EulaAcknowledgementPanel}; every acknowledgement is mandatory. */
public final class EulaAcknowledgementConsolePanel extends AbstractConsolePanel {

    public EulaAcknowledgementConsolePanel(PanelView<ConsolePanel> panel) {
        super(panel);
    }

    @Override
    public boolean run(InstallData installData, Properties properties) {
        for (String key : EulaAcknowledgements.KEYS) {
            if (!"true".equalsIgnoreCase(properties.getProperty(EulaAcknowledgements.variable(key)))) {
                return false;
            }
            installData.setVariable(EulaAcknowledgements.variable(key), "true");
        }
        return true;
    }

    @Override
    public boolean run(InstallData installData, Console console) {
        printHeadLine(installData, console);
        console.println(installData.getMessages().get("EulaAcknowledgementPanel.introduction"));
        for (String key : EulaAcknowledgements.KEYS) {
            console.println();
            int answer = console.prompt(
                installData.getMessages().get("EulaAcknowledgementPanel." + key), 1, 2, 2
            );
            if (answer != 1) {
                throw new UserInterruptException(
                    installData.getMessages().get("EulaAcknowledgementPanel.rejected")
                );
            }
            installData.setVariable(EulaAcknowledgements.variable(key), "true");
        }
        return true;
    }

    @Override
    public void createInstallationRecord(IXMLElement panelRoot) {
        EulaAcknowledgements.writeRecord(true, panelRoot);
    }
}
