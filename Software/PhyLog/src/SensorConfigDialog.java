import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Zweispaltiger Sensor-Konfigurationsdialog für Kanal A und B. Beide Kanäle sind unabhängig
 * wählbar, auch mit demselben Sensortyp auf beiden Kanälen gleichzeitig.
 *
 * <p>Ist eine {@link LiveSource} für einen Kanal angegeben, zeigt die Zeile "Offset / Tara"
 * fortlaufend den aktuellen Live-Messwert an. Die "Nullen"-Schaltfläche übernimmt ihn nicht nur
 * zur Anzeige, sondern meldet den Tara-Wunsch über {@link TareRequestListener} zurück, damit der
 * aufrufende Code (siehe {@code GUI}) den Live-Wert ab sofort tatsächlich als 0 behandelt. Ohne
 * verfügbaren Live-Wert bleiben Anzeige und Schaltfläche deaktiviert, statt einen falschen
 * Platzhalterwert vorzutäuschen.</p>
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

    /** Wird beim Klick auf "Nullen" aufgerufen, damit der aufrufende Code den aktuellen
     *  Live-Wert des Kanals tatsächlich als neuen Tara-Offset übernimmt - der Dialog selbst
     *  kennt keine dekodierten Rohwerte, nur was ihm über {@link LiveSource} gemeldet wird. */
    @FunctionalInterface
    public interface TareRequestListener {
        void onTareRequested(char channel);
    }

    private static final String[] SAMPLE_RATES = {"10 Hz", "20 Hz", "50 Hz", "100 Hz", "200 Hz", "500 Hz", "1000 Hz"};
    private static final int LIVE_REFRESH_MS = 200;

    private final Channel channelA = new Channel();
    private final Channel channelB = new Channel();

    private JComboBox<String> comboSampleRate;

    private Sensor selectedSensor1;
    private Sensor selectedSensor2;
    private boolean applied = false;

    private final LiveSource live1;
    private final LiveSource live2;
    private final SensorSelectionListener selectionListener;
    private final TareRequestListener tareListener;
    private Timer liveUpdateTimer;

    /** Bündelt alle UI-Komponenten eines Kanals (A oder B). */
    private static class Channel {
        JComboBox<Sensor> comboSensor;
        JLabel lblUnit;
        JTextField txtOffset;
        JLabel lblLive;
        JButton btnTara;
        JButton btnCalibrate;
    }

    /**
     * @param owner               Eigentümerfenster (für Modalität und Positionierung)
     * @param current1            aktuell aktiver Sensor auf Kanal A (oder {@link SensorRegistry#NO_SENSOR})
     * @param current2            aktuell aktiver Sensor auf Kanal B
     * @param currentSampleRateHz aktuell geltende, für beide Kanäle gemeinsame Abtastrate in Hz
     * @param live1               liefert den Live-Messwert von Kanal A, oder {@code null} ohne Live-Anbindung
     * @param live2               liefert den Live-Messwert von Kanal B, oder {@code null} ohne Live-Anbindung
     * @param selectionListener   wird bei jeder Sensor-Auswahl sofort benachrichtigt (siehe Klassenkommentar)
     * @param tareListener        wird beim Klick auf "Nullen" benachrichtigt, damit der Tara-Offset
     *                            tatsächlich angewendet wird (siehe Klassenkommentar)
     */
    public SensorConfigDialog(Frame owner, Sensor current1, Sensor current2, int currentSampleRateHz,
                              LiveSource live1, LiveSource live2,
                              SensorSelectionListener selectionListener, TareRequestListener tareListener) {
        super(owner, "Sensoren konfigurieren", true);
        this.live1 = live1;
        this.live2 = live2;
        this.selectionListener = selectionListener;
        this.tareListener = tareListener;

        setSize(760, 330);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        Sensor[] availableSensors = SensorRegistry.getAvailableSensors().toArray(new Sensor[0]);

        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 15, 0));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.add(buildChannelPanel(channelA, 'A', "Kanal A", availableSensors, current1));
        mainPanel.add(buildChannelPanel(channelB, 'B', "Kanal B", availableSensors, current2));
        add(mainPanel, BorderLayout.CENTER);

        JPanel southWrapper = new JPanel();
        southWrapper.setLayout(new BoxLayout(southWrapper, BoxLayout.Y_AXIS));
        southWrapper.add(buildSharedRatePanel(currentSampleRateHz));
        southWrapper.add(buildButtonPanel());
        add(southWrapper, BorderLayout.SOUTH);

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
        ch.btnCalibrate = new JButton("Kalibrieren...");

        ch.btnTara.addActionListener(e -> {
            LiveSource source = (ch == channelA) ? live1 : live2;
            Double value = (source != null) ? source.poll() : null;
            if (value != null) {
                ch.txtOffset.setText(String.format("%.3f", value));
            }
            if (tareListener != null) {
                tareListener.onTareRequested(channelId);
            }
        });

        ch.btnCalibrate.addActionListener(e -> {
            Sensor sensor = (Sensor) ch.comboSensor.getSelectedItem();
            if (sensor != null && !sensor.getCalibrationParameters().isEmpty()) {
                new CalibrationDialog(SwingUtilities.getWindowAncestor(panel), sensor).setVisible(true);
                updateChannelStates(); // Live-Anzeige kann sich durch die neue Kalibrierung ändern
            }
        });

        addFormRow(panel, gbc, 0, "Sensor:", ch.comboSensor);
        addFormRow(panel, gbc, 1, "Einheit:", ch.lblUnit);
        addTaraRow(panel, gbc, 2, "Offset / Tara:", ch.txtOffset, ch.lblLive, ch.btnTara);
        addFormRow(panel, gbc, 3, "Kalibrierung:", ch.btnCalibrate);

        return panel;
    }

    /** Baut die Zeile mit der für beide Kanäle gemeinsam geltenden Abtastrate. */
    private JPanel buildSharedRatePanel(int currentSampleRateHz) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));
        panel.add(new JLabel("Abtastrate (beide Kanäle):"));

        comboSampleRate = new JComboBox<>(SAMPLE_RATES);
        selectSampleRate(currentSampleRateHz);
        panel.add(comboSampleRate);

        return panel;
    }

    /** Wählt in {@link #comboSampleRate} den zu {@code hz} passenden Eintrag, oder belässt es
     *  beim Standardwert, falls {@code hz} keinem Listeneintrag entspricht. */
    private void selectSampleRate(int hz) {
        for (String rate : SAMPLE_RATES) {
            if (parseRate(rate) == hz) {
                comboSampleRate.setSelectedItem(rate);
                return;
            }
        }
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
        ch.btnCalibrate.setEnabled(active && !sensor.getCalibrationParameters().isEmpty());

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

    /** @return die für beide Kanäle gemeinsam gewählte Abtastrate in Hz (z. B. 50 aus "50 Hz"). */
    public int getSampleRate() {
        return parseRate((String) comboSampleRate.getSelectedItem());
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