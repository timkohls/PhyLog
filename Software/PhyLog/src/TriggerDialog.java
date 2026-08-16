import javax.swing.*;

/** Dialog zur Konfiguration von Mess-Triggern und Messdauer. */
public class TriggerDialog extends FormDialog {

    /** Trigger-Konfigurationseinstellungen. */
    public static final class Config {
        /** Messkanal ('A' oder 'B'). */
        public char channel = 'A';
        /** {@code true} für Schwellenwert, {@code false} für manuell. */
        public boolean thresholdMode = false;
        /** {@code true} für steigende, {@code false} für fallende Flanke. */
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

    public TriggerDialog(JFrame parent, Config current) {
        super(parent, "Trigger konfigurieren");

        cbTriggerMode = new JComboBox<>(new String[]{"Manuell (Start-Button)", "Schwellenwert"});
        cbTriggerMode.setSelectedIndex(current.thresholdMode ? 1 : 0);
        addRow("Trigger-Modus:", cbTriggerMode);

        cbChannel = new JComboBox<>(new String[]{"Kanal A", "Kanal B"});
        cbChannel.setSelectedIndex(current.channel == 'B' ? 1 : 0);
        addRow("Kanal:", cbChannel);

        cbEdge = new JComboBox<>(new String[]{"Steigend (▲)", "Fallend (▼)"});
        cbEdge.setSelectedIndex(current.risingEdge ? 0 : 1);
        addRow("Flanke:", cbEdge);

        txtThreshold = new JTextField(String.valueOf(current.threshold));
        addRow("Schwellenwert:", txtThreshold);

        spPreTrigger = new JSpinner(new SpinnerNumberModel(current.preTriggerMs, 0, 5000, 50));
        addRow("Vorlaufzeit (ms):", spPreTrigger);

        cbLimitDuration = new JCheckBox("Messdauer:");
        cbLimitDuration.setFont(Theme.FONT_UI);
        cbLimitDuration.setOpaque(false);
        cbLimitDuration.setSelected(current.maxDurationMs > 0);

        int initialSeconds = (current.maxDurationMs > 0) ? Math.max(1, current.maxDurationMs / 1000) : 60;
        spMaxDuration = new JSpinner(new SpinnerNumberModel(initialSeconds, 1, 36000, 1));
        spMaxDuration.setEnabled(cbLimitDuration.isSelected());
        addRow(cbLimitDuration, spMaxDuration);

        cbLimitDuration.addActionListener(_ -> spMaxDuration.setEnabled(cbLimitDuration.isSelected()));
        cbTriggerMode.addActionListener(_ -> updateFieldStates());
        updateFieldStates();

        JButton btnCancel = new JButton("Abbrechen");
        JButton btnApply = new JButton("Übernehmen");

        btnCancel.addActionListener(_ -> dispose());
        btnApply.addActionListener(_ -> {
            applied = true;
            dispose();
        });

        finishLayout(btnCancel, btnApply);
    }

    private void updateFieldStates() {
        boolean isThreshold = cbTriggerMode.getSelectedIndex() == 1;
        cbChannel.setEnabled(isThreshold);
        cbEdge.setEnabled(isThreshold);
        txtThreshold.setEnabled(isThreshold);
        spPreTrigger.setEnabled(isThreshold);
    }

    public boolean isApplied() {
        return applied;
    }

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
