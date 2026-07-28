import javax.swing.*;
import java.awt.*;

/**
 * Dialog zur Konfiguration eines schwellenwertbasierten Mess-Triggers (Modus, Flanke,
 * Schwellenwert, Vorlaufzeit).
 *
 * <p>Hinweis: Sammelt nur die gewünschte Konfiguration über {@link #getTriggerMode()},
 * {@link #getEdge()}, {@link #getThreshold()} und {@link #getPreTriggerMs()} - {@code GUI}
 * wertet das Ergebnis aktuell noch nicht aus, da noch keine echte Trigger-Hardware angebunden
 * ist. Sobald ein Live-Datenstrom existiert, kann der Aufrufer nach {@link #isApplied()} diese
 * Getter auslesen und die Aufnahme entsprechend steuern.</p>
 */
public class TriggerDialog extends JDialog {

    private final JComboBox<String> cbTriggerMode;
    private final JComboBox<String> cbEdge;
    private final JTextField txtThreshold;
    private final JSpinner spPreTrigger;

    private boolean applied = false;

    public TriggerDialog(JFrame parent) {
        super(parent, "Trigger konfigurieren", true);
        setSize(400, 260);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Trigger-Modus:"), gbc);
        cbTriggerMode = new JComboBox<>(new String[]{"Manuell (Start-Button)", "Schwellenwert (Analog)"});
        gbc.gridx = 1;
        formPanel.add(cbTriggerMode, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Flanke:"), gbc);
        cbEdge = new JComboBox<>(new String[]{"Steigend (▲)", "Fallend (▼)"});
        gbc.gridx = 1;
        formPanel.add(cbEdge, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        formPanel.add(new JLabel("Schwellenwert:"), gbc);
        txtThreshold = new JTextField("2.50");
        gbc.gridx = 1;
        formPanel.add(txtThreshold, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        formPanel.add(new JLabel("Vorlaufzeit (ms):"), gbc);
        spPreTrigger = new JSpinner(new SpinnerNumberModel(100, 0, 5000, 50));
        gbc.gridx = 1;
        formPanel.add(spPreTrigger, gbc);

        cbTriggerMode.addActionListener(e -> updateFieldStates());
        updateFieldStates();

        add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Abbrechen");
        JButton btnApply = new JButton("Übernehmen");

        btnCancel.addActionListener(e -> dispose());
        btnApply.addActionListener(e -> {
            applied = true;
            dispose();
        });

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnApply);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    /** Aktiviert Flanke/Schwellenwert/Vorlaufzeit nur im Schwellenwert-Modus. */
    private void updateFieldStates() {
        boolean isThreshold = cbTriggerMode.getSelectedIndex() == 1;
        cbEdge.setEnabled(isThreshold);
        txtThreshold.setEnabled(isThreshold);
        spPreTrigger.setEnabled(isThreshold);
    }

    /** @return {@code true}, wenn der Dialog über "Übernehmen" geschlossen wurde */
    public boolean isApplied() { return applied; }

    /** @return der gewählte Trigger-Modus als Anzeigetext */
    public String getTriggerMode() { return (String) cbTriggerMode.getSelectedItem(); }

    /** @return die gewählte Flanke (nur relevant im Schwellenwert-Modus) */
    public String getEdge() { return (String) cbEdge.getSelectedItem(); }

    /** @return der eingestellte Schwellenwert, oder 0.0 bei ungültiger Eingabe */
    public double getThreshold() {
        try {
            return Double.parseDouble(txtThreshold.getText().replace(",", "."));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** @return die eingestellte Vorlaufzeit in Millisekunden */
    public int getPreTriggerMs() { return (int) spPreTrigger.getValue(); }
}