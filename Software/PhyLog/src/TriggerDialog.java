import javax.swing.*;
import java.awt.*;

/**
 * Dialog zur Konfiguration von Mess-Triggern und Messdauer.
 */
public class TriggerDialog extends JDialog {

    /**
     * Datenhaltung für Trigger-Konfigurationseinstellungen.
     */
    public static final class Config {
        /** Messkanal ('A' oder 'B'). */
        public char channel = 'A';
        /** Modus: {@code true} für Schwellenwert, {@code false} für manuell. */
        public boolean thresholdMode = false;
        /** Flanke: {@code true} für steigend, {@code false} für fallend. */
        public boolean risingEdge = true;
        /** Schwellenwert in Volt. */
        public double threshold = 2.5;
        /** Vorlaufzeit vor dem Trigger in Millisekunden. */
        public int preTriggerMs = 100;
        /** Maximale Messdauer in ms (0 = unbegrenzt). */
        public int maxDurationMs = 0;
    }

    private final JComboBox<String> cbChannel;
    private final JComboBox<String> cbTriggerMode;
    private final JComboBox<String> cbEdge;
    private final JTextField txtThreshold;
    private final JSpinner spPreTrigger;
    private final JCheckBox cbLimitDuration;
    private final JSpinner spMaxDuration;

    private boolean applied = false;

    /**
     * Erstellt den Trigger-Dialog.
     *
     * @param parent  Das übergeordnete Fenster.
     * @param current Aktuelle Konfiguration als Startwert.
     */
    public TriggerDialog(JFrame parent, Config current) {
        super(parent, "Trigger konfigurieren", true);
        setSize(400, 340);
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
        cbTriggerMode = new JComboBox<>(new String[]{"Manuell (Start-Button)", "Schwellenwert"});
        cbTriggerMode.setSelectedIndex(current.thresholdMode ? 1 : 0);
        gbc.gridx = 1;
        formPanel.add(cbTriggerMode, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Kanal:"), gbc);
        cbChannel = new JComboBox<>(new String[]{"Kanal A", "Kanal B"});
        cbChannel.setSelectedIndex(current.channel == 'B' ? 1 : 0);
        gbc.gridx = 1;
        formPanel.add(cbChannel, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        formPanel.add(new JLabel("Flanke:"), gbc);
        cbEdge = new JComboBox<>(new String[]{"Steigend (▲)", "Fallend (▼)"});
        cbEdge.setSelectedIndex(current.risingEdge ? 0 : 1);
        gbc.gridx = 1;
        formPanel.add(cbEdge, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        formPanel.add(new JLabel("Schwellenwert:"), gbc);
        txtThreshold = new JTextField(String.valueOf(current.threshold));
        gbc.gridx = 1;
        formPanel.add(txtThreshold, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        formPanel.add(new JLabel("Vorlaufzeit (ms):"), gbc);
        spPreTrigger = new JSpinner(new SpinnerNumberModel(current.preTriggerMs, 0, 5000, 50));
        gbc.gridx = 1;
        formPanel.add(spPreTrigger, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        cbLimitDuration = new JCheckBox("Messdauer:");
        cbLimitDuration.setSelected(current.maxDurationMs > 0);
        formPanel.add(cbLimitDuration, gbc);

        int initialSeconds = (current.maxDurationMs > 0) ? Math.max(1, current.maxDurationMs / 1000) : 60;
        spMaxDuration = new JSpinner(new SpinnerNumberModel(initialSeconds, 1, 36000, 1));
        spMaxDuration.setEnabled(cbLimitDuration.isSelected());
        gbc.gridx = 1;
        formPanel.add(spMaxDuration, gbc);

        cbLimitDuration.addActionListener(e -> spMaxDuration.setEnabled(cbLimitDuration.isSelected()));

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

    /**
     * Aktiviert oder deaktiviert Eingabefelder basierend auf dem gewählten Trigger-Modus.
     */
    private void updateFieldStates() {
        boolean isThreshold = cbTriggerMode.getSelectedIndex() == 1;
        cbChannel.setEnabled(isThreshold);
        cbEdge.setEnabled(isThreshold);
        txtThreshold.setEnabled(isThreshold);
        spPreTrigger.setEnabled(isThreshold);
    }

    /**
     * Gibt zurück, ob die Konfiguration übernommen wurde.
     *
     * @return {@code true}, wenn der Dialog mit "Übernehmen" bestätigt wurde.
     */
    public boolean isApplied() {
        return applied;
    }

    /**
     * Liest die im Dialog eingestellten Werte aus.
     *
     * @return Die erstelle {@link Config}.
     */
    public Config getConfig() {
        Config cfg = new Config();
        cfg.channel = (cbChannel.getSelectedIndex() == 1) ? 'B' : 'A';
        cfg.thresholdMode = cbTriggerMode.getSelectedIndex() == 1;
        cfg.risingEdge = cbEdge.getSelectedIndex() == 0;
        cfg.preTriggerMs = (int) spPreTrigger.getValue();
        cfg.maxDurationMs = cbLimitDuration.isSelected() ? (int) spMaxDuration.getValue() * 1000 : 0;
        try {
            cfg.threshold = Double.parseDouble(txtThreshold.getText().replace(",", "."));
        } catch (NumberFormatException ignored) {
            cfg.threshold = 0.0;
        }
        return cfg;
    }
}