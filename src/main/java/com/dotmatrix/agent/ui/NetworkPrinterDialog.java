package com.dotmatrix.agent.ui;

import com.dotmatrix.agent.model.NetworkPrinter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Add/Edit form for a network target: a printer reached by IP + port, or
 * another Print Agent on the LAN that owns the printer.
 */
public class NetworkPrinterDialog extends JDialog {

    private static final String[] ENCODINGS = {"ISO-8859-1", "UTF-8", "US-ASCII", "CP437", "Cp850"};
    private static final String TYPE_RAW_LABEL = "Network printer (raw TCP, usually port 9100)";
    private static final String TYPE_AGENT_LABEL = "Another Print Agent on the network (usually port 8787)";

    private final JComboBox<String> typeCombo = new JComboBox<String>(new String[]{TYPE_RAW_LABEL, TYPE_AGENT_LABEL});
    private final JTextField nameField = new JTextField(20);
    private final JTextField hostField = new JTextField(20);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(9100, 1, 65535, 1));
    private final JComboBox<String> encodingCombo = new JComboBox<String>(ENCODINGS);
    private boolean confirmed = false;

    public NetworkPrinterDialog(Window owner, NetworkPrinter existing) {
        super(owner, existing == null ? "Add Network Printer" : "Edit Network Printer",
                ModalityType.APPLICATION_MODAL);

        if (existing != null) {
            typeCombo.setSelectedItem(existing.isAgent() ? TYPE_AGENT_LABEL : TYPE_RAW_LABEL);
            nameField.setText(existing.getName());
            hostField.setText(existing.getHost());
            portSpinner.setValue(existing.getPort() > 0 ? existing.getPort() : 9100);
            encodingCombo.setSelectedItem(existing.getEncoding());
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, c, 0, "Type:", typeCombo);
        addRow(form, c, 1, "Name:", nameField);
        addRow(form, c, 2, "Host / IP:", hostField);
        addRow(form, c, 3, "Port:", portSpinner);
        addRow(form, c, 4, "Encoding:", encodingCombo);

        typeCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onTypeChanged();
            }
        });
        encodingCombo.setEnabled(!isAgentSelected());

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onOk();
            }
        });
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(okButton);
        buttons.add(cancelButton);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private boolean isAgentSelected() {
        return TYPE_AGENT_LABEL.equals(typeCombo.getSelectedItem());
    }

    private void onTypeChanged() {
        boolean agent = isAgentSelected();
        // Swap the port only while it still holds the other type's default,
        // so a custom port the user typed is never overwritten.
        int port = (Integer) portSpinner.getValue();
        if (agent && port == NetworkPrinter.DEFAULT_RAW_PORT) {
            portSpinner.setValue(NetworkPrinter.DEFAULT_AGENT_PORT);
        } else if (!agent && port == NetworkPrinter.DEFAULT_AGENT_PORT) {
            portSpinner.setValue(NetworkPrinter.DEFAULT_RAW_PORT);
        }
        // A remote agent encodes the text itself, with its own printer's encoding.
        encodingCombo.setEnabled(!agent);
    }

    private void onOk() {
        if (nameField.getText().trim().isEmpty() || hostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name and Host are required.", "Validation",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        confirmed = true;
        setVisible(false);
    }

    private void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Applies the form values onto {@code existing}, or a new
     * {@link NetworkPrinter} if {@code existing} is {@code null}.
     */
    public NetworkPrinter apply(NetworkPrinter existing) {
        NetworkPrinter np = existing != null ? existing : new NetworkPrinter();
        np.setType(isAgentSelected() ? NetworkPrinter.Type.AGENT : NetworkPrinter.Type.RAW);
        np.setName(nameField.getText().trim());
        np.setHost(hostField.getText().trim());
        np.setPort((Integer) portSpinner.getValue());
        np.setEncoding((String) encodingCombo.getSelectedItem());
        return np;
    }
}
