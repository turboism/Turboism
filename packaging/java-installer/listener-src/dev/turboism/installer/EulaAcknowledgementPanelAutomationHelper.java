package dev.turboism.installer;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.data.Overrides;
import com.izforge.izpack.api.exception.InstallerException;
import com.izforge.izpack.installer.automation.PanelAutomation;

/** Fail-closed automation gate: an auto-install record must affirm every acknowledgement. */
public final class EulaAcknowledgementPanelAutomationHelper implements PanelAutomation {

    @Override
    public void createInstallationRecord(InstallData installData, IXMLElement panelRoot) {
        EulaAcknowledgements.writeRecord(installData, panelRoot);
    }

    @Override
    public void runAutomated(InstallData installData, IXMLElement panelRoot) throws InstallerException {
        try {
            EulaAcknowledgements.loadRequiredRecord(installData, panelRoot);
        } catch (IllegalStateException failure) {
            throw new InstallerException(failure.getMessage(), failure);
        }
    }

    @Override
    public void processOptions(InstallData installData, Overrides overrides) {
        throw new IllegalStateException("EULA acknowledgement automation requires an explicit install record.");
    }
}
