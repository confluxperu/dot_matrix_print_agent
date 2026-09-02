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
 * Add/Edit form for a network (IP + port) printer.
 */
public class NetworkPrinterDialog extends JDialog {

    private static final String[] ENCODINGS = {"ISO-8859-1", "UTF-8", "US-ASCII", "CP437", "Cp850"};

    private final JTextField nameField = new JTextField(20);
    private final JTextField hostField = new JTextField(20);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(9100, 1, 65535, 1));
    private final JComboBox<String> encodingCombo = new JComboBox<String>(ENCODINGS);
    private boolean confirmed = false;

    public NetworkPrinterDialog(Window owner, NetworkPrinter existing) {
        super(owner, existing == null ? "Add Network Printer" : "Edit Network Printer",
                ModalityType.APPLICATION_MODAL);

        if (existing != null) {
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

        addRow(form, c, 0, "Name:", nameField);
        addRow(form, c, 1, "Host / IP:", hostField);
        addRow(form, c, 2, "Port:", portSpinner);
        addRow(form, c, 3, "Encoding:", encodingCombo);

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
        np.setName(nameField.getText().trim());
        np.setHost(hostField.getText().trim());
        np.setPort((Integer) portSpinner.getValue());
        np.setEncoding((String) encodingCombo.getSelectedItem());
        return np;
    }
}
