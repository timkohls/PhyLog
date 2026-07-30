import javax.swing.*;
import java.awt.*;

/**
 * Dialog zur Konfiguration eines schwellenwertbasierten Mess-Triggers: Kanal, Modus, Flanke,
 * Schwellenwert und Vorlaufzeit. Das Ergebnis wird als {@link Config} zurückgegeben, das
 * {@code GUI} nach {@link #isApplied()} über {@link #getConfig()} abholt und tatsächlich
 * auswertet (siehe {@code GUI.startMeasurement}/{@code GUI.ingestSample}).
 */
public class TriggerDialog extends JDialog {

    /** Ergebnis einer Trigger-Konfiguration, unabhängig von der Dialog-UI. */
    public static final class Config {
        public char channel = 'A';
        public boolean thresholdMode = false;
        public boolean risingEdge = true;
        public double threshold = 2.5;
        public int preTriggerMs = 100;
    }

    private final JComboBox<String> cbChannel;
    private final JComboBox<String> cbTriggerMode;
    private final JComboBox<String> cbEdge;
    private final JTextField txtThreshold;
    private final JSpinner spPreTrigger;

    private boolean applied = false;

    /** @param current aktuell geltende Konfiguration, wird als Startauswahl vorbelegt */
    public TriggerDialog(JFrame parent, Config current) {
        super(parent, "Trigger konfigurieren", true);
        setSize(400, 300);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Kanal:"), gbc);
        cbChannel = new JComboBox<>(new String[]{"Kanal A", "Kanal B"});
        cbChannel.setSelectedIndex(current.channel == 'B' ? 1 : 0);
        gbc.gridx = 1;
        formPanel.add(cbChannel, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Trigger-Modus:"), gbc);
        cbTriggerMode = new JComboBox<>(new String[]{"Manuell (Start-Button)", "Schwellenwert (Analog)"});
        cbTriggerMode.setSelectedIndex(current.thresholdMode ? 1 : 0);
        gbc.gridx = 1;
        formPanel.add(cbTriggerMode, gbc);

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

    /** Aktiviert Kanal/Flanke/Schwellenwert/Vorlaufzeit nur im Schwellenwert-Modus. */
    private void updateFieldStates() {
        boolean isThreshold = cbTriggerMode.getSelectedIndex() == 1;
        cbChannel.setEnabled(isThreshold);
        cbEdge.setEnabled(isThreshold);
        txtThreshold.setEnabled(isThreshold);
        spPreTrigger.setEnabled(isThreshold);
    }

    /** @return {@code true}, wenn der Dialog über "Übernehmen" geschlossen wurde */
    public boolean isApplied() {
        return applied;
    }

    /** @return die im Dialog gewählte Konfiguration (nur sinnvoll, wenn {@link #isApplied()}) */
    public Config getConfig() {
        Config cfg = new Config();
        cfg.channel = (cbChannel.getSelectedIndex() == 1) ? 'B' : 'A';
        cfg.thresholdMode = cbTriggerMode.getSelectedIndex() == 1;
        cfg.risingEdge = cbEdge.getSelectedIndex() == 0;
        cfg.preTriggerMs = (int) spPreTrigger.getValue();
        try {
            cfg.threshold = Double.parseDouble(txtThreshold.getText().replace(",", "."));
        } catch (NumberFormatException ignored) {
            cfg.threshold = 0.0;
        }
        return cfg;
    }
}