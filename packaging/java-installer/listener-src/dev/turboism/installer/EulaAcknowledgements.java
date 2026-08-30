package dev.turboism.installer;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.adaptator.impl.XMLElementImpl;
import com.izforge.izpack.api.data.InstallData;

/** Shared, fail-closed state and install-record contract for the EULA acknowledgements. */
final class EulaAcknowledgements {

    static final String VARIABLE_PREFIX = "turboism.eulaAcknowledgement.";
    static final String[] KEYS = {"independent", "license", "backup", "asIs"};

    private EulaAcknowledgements() {
    }

    static void acceptAll(InstallData installData) {
        for (String key : KEYS) {
            installData.setVariable(variable(key), "true");
        }
    }

    static boolean allAccepted(InstallData installData) {
        for (String key : KEYS) {
            if (!"true".equalsIgnoreCase(installData.getVariable(variable(key)))) {
                return false;
            }
        }
        return true;
    }

    static void writeRecord(InstallData installData, IXMLElement panelRoot) {
        for (String key : KEYS) {
            addRecordEntry(
                panelRoot,
                key,
                "true".equalsIgnoreCase(installData.getVariable(variable(key)))
            );
        }
    }

    static void writeRecord(boolean accepted, IXMLElement panelRoot) {
        for (String key : KEYS) {
            addRecordEntry(panelRoot, key, accepted);
        }
    }

    private static void addRecordEntry(IXMLElement panelRoot, String key, boolean accepted) {
        IXMLElement acknowledgement = new XMLElementImpl("acknowledgement", panelRoot);
        acknowledgement.setAttribute("id", key);
        acknowledgement.setAttribute("accepted", Boolean.toString(accepted));
        panelRoot.addChild(acknowledgement);
    }

    static void loadRequiredRecord(InstallData installData, IXMLElement panelRoot) {
        final java.util.List<IXMLElement> entries =
            panelRoot.getChildrenNamed("acknowledgement");
        if (panelRoot.getChildrenCount() != KEYS.length || entries.size() != KEYS.length) {
            throw new IllegalStateException(
                "Turboism EULA record must contain exactly four acknowledgements"
            );
        }
        for (IXMLElement entry : entries) {
            if (entry.getAttributes().size() != 2
                || entry.getAttribute("id") == null
                || entry.getAttribute("accepted") == null) {
                throw new IllegalStateException("Turboism EULA acknowledgement record is malformed");
            }
        }
        for (String key : KEYS) {
            int matches = 0;
            boolean accepted = false;
            for (IXMLElement candidate : entries) {
                if (key.equals(candidate.getAttribute("id"))) {
                    matches++;
                    accepted = "true".equalsIgnoreCase(candidate.getAttribute("accepted"));
                }
            }
            if (matches != 1 || !accepted) {
                throw new IllegalStateException(
                    "Missing or invalid Turboism EULA acknowledgement: " + key
                );
            }
        }
        acceptAll(installData);
    }

    static String variable(String key) {
        return VARIABLE_PREFIX + key;
    }
}
