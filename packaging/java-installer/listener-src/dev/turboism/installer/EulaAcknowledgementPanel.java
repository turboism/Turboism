package dev.turboism.installer;

import com.izforge.izpack.api.data.Panel;
import com.izforge.izpack.api.resource.Resources;
import com.izforge.izpack.gui.log.Log;
import com.izforge.izpack.installer.data.GUIInstallData;
import com.izforge.izpack.installer.gui.InstallerFrame;
import com.izforge.izpack.installer.gui.IzPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** GUI gate requiring each operational EULA acknowledgement before the stock EULA panel. */
public final class EulaAcknowledgementPanel extends IzPanel implements ActionListener {

    private static final long serialVersionUID = 1L;
    private final JCheckBox[] acknowledgements = new JCheckBox[EulaAcknowledgements.KEYS.length];

    public EulaAcknowledgementPanel(
        Panel panel, InstallerFrame parent, GUIInstallData installData, Resources resources, Log log
    ) {
        super(panel, parent, installData, new BorderLayout(8, 8), resources);
        add(new JLabel(getString("EulaAcknowledgementPanel.title")), BorderLayout.NORTH);
        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 12));
        content.add(wrappedText(getString("EulaAcknowledgementPanel.introduction")));
        content.add(Box.createRigidArea(new Dimension(0, 10)));
        for (int index = 0; index < EulaAcknowledgements.KEYS.length; index++) {
            String key = EulaAcknowledgements.KEYS[index];
            acknowledgements[index] = new JCheckBox();
            acknowledgements[index].setName(EulaAcknowledgements.variable(key));
            acknowledgements[index].setSelected("true".equalsIgnoreCase(
                installData.getVariable(EulaAcknowledgements.variable(key))));
            acknowledgements[index].addActionListener(this);
            final JPanel acknowledgement = new JPanel(new BorderLayout(8, 0));
            acknowledgement.setAlignmentX(LEFT_ALIGNMENT);
            acknowledgement.add(acknowledgements[index], BorderLayout.WEST);
            acknowledgement.add(
                wrappedText(getString("EulaAcknowledgementPanel." + key)), BorderLayout.CENTER
            );
            content.add(acknowledgement);
            content.add(Box.createRigidArea(new Dimension(0, 8)));
        }
        add(new JScrollPane(content), BorderLayout.CENTER);
        setInitialFocus(acknowledgements[0]);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        updateNavigation();
    }

    @Override
    public boolean isValidated() {
        updateVariables();
        return EulaAcknowledgements.allAccepted(installData);
    }

    @Override
    public void panelActivate() {
        updateNavigation();
    }

    @Override
    public void createInstallationRecord(com.izforge.izpack.api.adaptator.IXMLElement root) {
        updateVariables();
        EulaAcknowledgements.writeRecord(installData, root);
    }

    private static JTextArea wrappedText(final String text) {
        final JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setAlignmentX(LEFT_ALIGNMENT);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return area;
    }

    private void updateVariables() {
        for (int index = 0; index < acknowledgements.length; index++) {
            installData.setVariable(EulaAcknowledgements.variable(EulaAcknowledgements.KEYS[index]),
                Boolean.toString(acknowledgements[index].isSelected()));
        }
    }

    private void updateNavigation() {
        updateVariables();
        if (EulaAcknowledgements.allAccepted(installData)) {
            parent.unlockNextButton();
        } else {
            parent.lockNextButton();
        }
    }
}
