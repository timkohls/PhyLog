import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Zweispaltiger Sensor-Konfigurationsdialog für Kanal A und B. Beide Kanäle sind unabhängig
 * wählbar, auch mit demselben Sensortyp auf beiden Kanälen gleichzeitig.
 *
 * <p>Ist eine {@link LiveSource} für einen Kanal angegeben, zeigt die Zeile "Offset / Tara"
 * fortlaufend den aktuellen Live-Messwert an und die "Nullen"-Schaltfläche übernimmt ihn als
 * neuen Offset. Ohne verfügbaren Live-Wert bleiben Anzeige und Schaltfläche deaktiviert, statt
 * einen falschen Platzhalterwert vorzutäuschen.</p>
 */
public class SensorConfigDialog extends JDialog {

    /** Liefert den aktuellen Live-Messwert eines Kanals, oder {@code null} falls (noch) keiner vorliegt. */
    @FunctionalInterface
    public interface LiveSource {
        Double poll();
    }

    /** Wird sofort bei jeder Sensor-Auswahl im Dialog aufgerufen (nicht erst bei "Übernehmen"),
     *  damit die Firmware rechtzeitig weiß, welchen Sensor sie abtasten soll - sonst kämen für
     *  einen frisch gewählten, noch nicht angewendeten Sensor niemals Live-Daten an. */
    @FunctionalInterface
    public interface SensorSelectionListener {
        void onSensorSelected(char channel, Sensor sensor);
    }

    private static final String[] RANGES = {"Automatisch", "± 10", "± 50", "± 100", "± 500"};
    private static final String[] SAMPLE_RATES = {"10 Hz", "50 Hz", "100 Hz", "250 Hz", "500 Hz", "1000 Hz"};
    private static final int LIVE_REFRESH_MS = 200;

    private final Channel channelA = new Channel();
    private final Channel channelB = new Channel();

    private Sensor selectedSensor1;
    private Sensor selectedSensor2;
    private boolean applied = false;

    private final LiveSource live1;
    private final LiveSource live2;
    private final SensorSelectionListener selectionListener;
    private Timer liveUpdateTimer;

    /** Bündelt alle UI-Komponenten eines Kanals (A oder B). */
    private static class Channel {
        JComboBox<Sensor> comboSensor;
        JLabel lblUnit;
        JTextField txtOffset;
        JLabel lblLive;
        JButton btnTara;
        JComboBox<String> comboRange;
        JComboBox<String> comboRate;
    }

    /** Bequemer Konstruktor ohne Live-Anbindung (Live-Anzeige und Tara bleiben deaktiviert). */
    public SensorConfigDialog(Frame owner, Sensor current1, Sensor current2) {
        this(owner, current1, current2, null, null, null);
    }

    /** Voller Konstruktor mit optionaler Live-Quelle und Auswahl-Rückmeldung je Kanal (siehe Klassenkommentar). */
    public SensorConfigDialog(Frame owner, Sensor current1, Sensor current2,
                               LiveSource live1, LiveSource live2,
                               SensorSelectionListener selectionListener) {
        super(owner, "Sensoren konfigurieren", true);
        this.live1 = live1;
        this.live2 = live2;
        this.selectionListener = selectionListener;

        setSize(760, 320);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        Sensor[] availableSensors = SensorRegistry.getAvailableSensors().toArray(new Sensor[0]);

        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 15, 0));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.add(buildChannelPanel(channelA, 'A', "Kanal A", availableSensors, current1));
        mainPanel.add(buildChannelPanel(channelB, 'B', "Kanal B", availableSensors, current2));
        add(mainPanel, BorderLayout.CENTER);

        add(buildButtonPanel(), BorderLayout.SOUTH);

        updateChannelStates();
        startLiveUpdates();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopLiveUpdates();
            }
        });
    }

    private JPanel buildChannelPanel(Channel ch, char channelId, String title, Sensor[] availableSensors, Sensor current) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints gbc = createGbc();

        ch.comboSensor = new JComboBox<>(availableSensors);
        ch.comboSensor.setSelectedItem(current != null ? current : SensorRegistry.NO_SENSOR);
        ch.comboSensor.addActionListener(e -> {
            updateChannelStates();
            if (selectionListener != null) {
                selectionListener.onSensorSelected(channelId, (Sensor) ch.comboSensor.getSelectedItem());
            }
        });

        ch.lblUnit = new JLabel();
        ch.txtOffset = new JTextField("0.0", 5);
        ch.lblLive = new JLabel("–");
        ch.lblLive.setForeground(Theme.ACCENT);
        ch.btnTara = new JButton("Nullen");
        ch.comboRange = new JComboBox<>(RANGES);
        ch.comboRate = new JComboBox<>(SAMPLE_RATES);

        ch.btnTara.addActionListener(e -> {
            LiveSource source = (ch == channelA) ? live1 : live2;
            Double value = (source != null) ? source.poll() : null;
            if (value != null) {
                ch.txtOffset.setText(String.format("%.3f", value));
            }
        });

        addFormRow(panel, gbc, 0, "Sensor:", ch.comboSensor);
        addFormRow(panel, gbc, 1, "Einheit:", ch.lblUnit);
        addTaraRow(panel, gbc, 2, "Offset / Tara:", ch.txtOffset, ch.lblLive, ch.btnTara);
        addFormRow(panel, gbc, 3, "Messbereich:", ch.comboRange);
        addFormRow(panel, gbc, 4, "Abtastrate:", ch.comboRate);

        return panel;
    }

    private JPanel buildButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnOk = new JButton("Übernehmen");
        JButton btnCancel = new JButton("Abbrechen");

        btnOk.addActionListener(e -> {
            selectedSensor1 = (Sensor) channelA.comboSensor.getSelectedItem();
            selectedSensor2 = (Sensor) channelB.comboSensor.getSelectedItem();
            applied = true;
            dispose();
        });
        btnCancel.addActionListener(e -> dispose());

        buttonPanel.add(btnOk);
        buttonPanel.add(btnCancel);
        return buttonPanel;
    }

    private void startLiveUpdates() {
        liveUpdateTimer = new Timer(LIVE_REFRESH_MS, e -> updateChannelStates());
        liveUpdateTimer.start();
    }

    private void stopLiveUpdates() {
        if (liveUpdateTimer != null) {
            liveUpdateTimer.stop();
        }
    }

    /** Aktualisiert Einheit, Aktivierung und Live-Anzeige beider Kanäle in einem Schritt. */
    private void updateChannelStates() {
        updateChannel(channelA, live1);
        updateChannel(channelB, live2);
    }

    private void updateChannel(Channel ch, LiveSource source) {
        Sensor sensor = (Sensor) ch.comboSensor.getSelectedItem();
        boolean active = (sensor != null && sensor != SensorRegistry.NO_SENSOR);

        ch.lblUnit.setText(active ? sensor.getUnit() : "-");
        ch.txtOffset.setEnabled(active);
        ch.comboRange.setEnabled(active);
        ch.comboRate.setEnabled(active);

        Double liveValue = (active && source != null) ? source.poll() : null;
        ch.lblLive.setText(liveValue != null ? String.format("Live: %.3f %s", liveValue, sensor.getUnit()) : "–");
        ch.btnTara.setEnabled(liveValue != null);
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.35;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.65;
        panel.add(comp, gbc);
    }

    private void addTaraRow(JPanel panel, GridBagConstraints gbc, int row, String label,
                             JTextField textField, JLabel liveLabel, JButton button) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.35;
        panel.add(new JLabel(label), gbc);

        JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
        rowPanel.add(textField, BorderLayout.WEST);
        rowPanel.add(liveLabel, BorderLayout.CENTER);
        rowPanel.add(button, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.weightx = 0.65;
        panel.add(rowPanel, gbc);
    }

    public boolean isApplied() {
        return applied;
    }

    public Sensor getSelectedSensorA() {
        return selectedSensor1;
    }

    public Sensor getSelectedSensorB() {
        return selectedSensor2;
    }

    /** @return für Kanal A gewählte Abtastrate in Hz (z. B. 50 aus "50 Hz"). */
    public int getSampleRateA() {
        return parseRate((String) channelA.comboRate.getSelectedItem());
    }

    /** @return für Kanal B gewählte Abtastrate in Hz (z. B. 50 aus "50 Hz"). */
    public int getSampleRateB() {
        return parseRate((String) channelB.comboRate.getSelectedItem());
    }

    private int parseRate(String rateText) {
        if (rateText == null) return 0;
        try {
            return Integer.parseInt(rateText.replace("Hz", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
