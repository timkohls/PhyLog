import javax.swing.*;
import java.awt.*;

/**
 * Dialog zur Konfiguration eines schwellenwertbasierten Mess-Triggers (Modus, Flanke,
 * Schwellenwert, Vorlaufzeit).
 *
 * <p><b>Hinweis / Platzhalter:</b> Dieser Dialog sammelt die gewünschte Trigger-Konfiguration
 * und stellt sie über {@link #getTriggerMode()}, {@link #getEdge()}, {@link #getThreshold()}
 * und {@link #getPreTriggerMs()} bereit. Aktuell wertet {@code GUI} dieses Ergebnis nach dem
 * Schließen des Dialogs jedoch noch nicht aus - es gibt noch keine echte Messhardware, die
 * getriggert werden könnte. Das ist bewusst so belassen (kein Bug, sondern ein offener Punkt
 * für die künftige Sensor-Anbindung): sobald ein echter Live-Datenstrom existiert, kann der
 * Aufrufer von {@code TriggerDialog} nach {@link #isApplied()} diese Getter auslesen und die
 * Aufnahme entsprechend steuern.</p>
 */
public class TriggerDialog extends JDialog {

    private JComboBox<String> cbTriggerMode;
    private JComboBox<String> cbEdge;
    private JTextField txtThreshold;
    private JSpinner spPreTrigger;

    private boolean applied = false;

    /** @param parent Eigentümerfenster (für Modalität und Positionierung) */
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

        // 1. Trigger-Modus (Nur Manuell & Schwellenwert)
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Trigger-Modus:"), gbc);
        cbTriggerMode = new JComboBox<>(new String[]{
                "Manuell (Start-Button)",
                "Schwellenwert (Analog)"
        });
        gbc.gridx = 1;
        formPanel.add(cbTriggerMode, gbc);

        // 2. Flanke (Steigend/Fallend)
        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Flanke:"), gbc);
        cbEdge = new JComboBox<>(new String[]{"Steigend (▲)", "Fallend (▼)"});
        gbc.gridx = 1;
        formPanel.add(cbEdge, gbc);

        // 3. Schwellenwert
        gbc.gridx = 0; gbc.gridy = 2;
        formPanel.add(new JLabel("Schwellenwert:"), gbc);
        txtThreshold = new JTextField("2.50");
        gbc.gridx = 1;
        formPanel.add(txtThreshold, gbc);

        // 4. Pre-Trigger (Vorlaufzeit in ms)
        gbc.gridx = 0; gbc.gridy = 3;
        formPanel.add(new JLabel("Vorlaufzeit (ms):"), gbc);
        spPreTrigger = new JSpinner(new SpinnerNumberModel(100, 0, 5000, 50));
        gbc.gridx = 1;
        formPanel.add(spPreTrigger, gbc);

        // Felder aktivieren/deaktivieren je nach Modus
        cbTriggerMode.addActionListener(e -> updateFieldStates());
        updateFieldStates();

        add(formPanel, BorderLayout.CENTER);

        // Button-Leiste
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

    /** Aktiviert Flanke/Schwellenwert/Vorlaufzeit nur, wenn der Schwellenwert-Modus gewählt ist. */
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

    /** @return die gewählte Flanke als Anzeigetext (nur relevant im Schwellenwert-Modus) */
    public String getEdge() { return (String) cbEdge.getSelectedItem(); }

    /** @return der eingestellte Schwellenwert, oder 0.0 falls das Feld keine gültige Zahl enthält */
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