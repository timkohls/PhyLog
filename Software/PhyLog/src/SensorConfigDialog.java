import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Modaler Dialog zur Auswahl von Sensor und Offset je Kanal sowie der gemeinsamen Abtastrate.
 * Zeigt Live-Messwerte während der Konfiguration an (siehe {@link LiveSource}) und meldet jede
 * Sensorwahl sofort per {@link SensorSelectionListener} zurück an {@link GUI}, damit die
 * Firmware auch bei Abbruch per "Abbrechen" nicht auf einem inkonsistenten Stand bleibt -
 * "Übernehmen" bestätigt lediglich die zuletzt gewählten Sensoren und die Abtastrate.
 */
public class SensorConfigDialog extends JDialog {

    /** Liefert den aktuellen Live-Messwert eines Kanals, oder {@code null} ohne gültigen Wert. */
    @FunctionalInterface
    public interface LiveSource {
        Double poll();
    }

    /** Wird bei jeder Sensorwahl in der Combobox sofort aufgerufen (auch vor "Übernehmen"). */
    @FunctionalInterface
    public interface SensorSelectionListener {
        void onSensorSelected(char channel, Sensor sensor);
    }

    /** Wird beim Klick auf "Nullen" aufgerufen, nachdem der Offset lokal gesetzt wurde. */
    @FunctionalInterface
    public interface TareRequestListener {
        void onTareRequested(char channel);
    }

    private static final String[] SAMPLE_RATES = {"10 Hz", "20 Hz", "50 Hz", "100 Hz", "200 Hz", "500 Hz", "1000 Hz"};
    /** Wie oft die Live-Anzeige während der Konfiguration aktualisiert wird. */
    private static final int LIVE_REFRESH_MS = 200;

    private final Channel channelA = new Channel();
    private final Channel channelB = new Channel();

    private JComboBox<String> comboSampleRate;

    /** Erst bei "Übernehmen" gesetzt, siehe {@link #getSelectedSensorA()}/{@link #getSelectedSensorB()}. */
    private Sensor selectedSensor1;
    private Sensor selectedSensor2;
    private boolean applied = false;

    private final LiveSource live1;
    private final LiveSource live2;
    private final SensorSelectionListener selectionListener;
    private final TareRequestListener tareListener;
    private Timer liveUpdateTimer;

    /** Bündelt die UI-Komponenten eines Kanals. */
    private static class Channel {
        JComboBox<Sensor> comboSensor;
        JLabel lblUnit;
        JTextField txtOffset;
        JLabel lblLive;
        JButton btnTara;
        JButton btnCalibrate;
    }

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

        Sensor[] availableSensorsA = buildChannelSensorList(current1);
        Sensor[] availableSensorsB = buildChannelSensorList(current2);

        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 15, 0));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.add(buildChannelPanel(channelA, 'A', "Kanal A", availableSensorsA, current1));
        mainPanel.add(buildChannelPanel(channelB, 'B', "Kanal B", availableSensorsB, current2));
        add(mainPanel, BorderLayout.CENTER);

        JPanel southWrapper = new JPanel();
        southWrapper.setLayout(new BoxLayout(southWrapper, BoxLayout.Y_AXIS));
        southWrapper.add(buildSharedRatePanel(currentSampleRateHz));
        southWrapper.add(buildButtonPanel());
        add(southWrapper, BorderLayout.SOUTH);

        enforceSpectrumExclusivity();
        updateChannelStates();
        refreshSampleRateOptions();
        startLiveUpdates();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopLiveUpdates();
            }
        });
    }

    /** Baut die Sensorliste für eine Combobox: die Registry-Standardinstanzen, außer der aktuell
     *  gewählte Sensor ist vom gleichen Typ - dann wird dessen Instanz (mit ihrer Kalibrierung)
     *  eingesetzt, damit die Auswahl nicht die bestehende Kalibrierung verwirft. */
    private static Sensor[] buildChannelSensorList(Sensor current) {
        List<Sensor> sensors = new ArrayList<>(SensorRegistry.getAvailableSensors());
        if (current != null && current != SensorRegistry.NO_SENSOR) {
            for (int i = 0; i < sensors.size(); i++) {
                if (sensors.get(i).getClass() == current.getClass()) {
                    sensors.set(i, current);
                    break;
                }
            }
        }
        return sensors.toArray(new Sensor[0]);
    }

    /** Baut das Formular eines Kanals (Sensorwahl, Einheit, Offset/Live/Nullen, Kalibrieren). */
    private JPanel buildChannelPanel(Channel ch, char channelId, String title, Sensor[] availableSensors, Sensor current) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints gbc = createGbc();

        ch.comboSensor = new JComboBox<>(availableSensors);
        ch.comboSensor.setSelectedItem(current != null ? current : SensorRegistry.NO_SENSOR);
        ch.comboSensor.addActionListener(_ -> {
            enforceSpectrumExclusivity();
            updateChannelStates();
            refreshSampleRateOptions();
            // Sofort melden, nicht erst bei "Übernehmen" - GUI schickt den Sensor direkt an die
            // Firmware weiter, damit die Live-Anzeige (Tara-Vorschau) sofort zum neuen Sensor passt.
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

        ch.btnTara.addActionListener(_ -> {
            LiveSource source = (ch == channelA) ? live1 : live2;
            Double value = (source != null) ? source.poll() : null;
            if (value != null) {
                ch.txtOffset.setText(String.format("%.3f", value));
            }
            if (tareListener != null) {
                tareListener.onTareRequested(channelId);
            }
        });

        ch.btnCalibrate.addActionListener(_ -> {
            Sensor sensor = (Sensor) ch.comboSensor.getSelectedItem();
            if (sensor != null && !sensor.getCalibrationParameters().isEmpty()) {
                new CalibrationDialog(SwingUtilities.getWindowAncestor(panel), sensor).setVisible(true);
                updateChannelStates();
            }
        });

        addFormRow(panel, gbc, 0, "Sensor:", ch.comboSensor);
        addFormRow(panel, gbc, 1, "Einheit:", ch.lblUnit);
        addTaraRow(panel, gbc, 2, ch.txtOffset, ch.lblLive, ch.btnTara);
        addFormRow(panel, gbc, 3, "Kalibrierung:", ch.btnCalibrate);

        return panel;
    }

    /** Baut das Abtastraten-Auswahlfeld, gemeinsam für beide Kanäle (die Firmware kennt nur
     *  eine globale Rate). */
    private JPanel buildSharedRatePanel(int currentSampleRateHz) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));
        panel.add(new JLabel("Abtastrate (beide Kanäle):"));

        comboSampleRate = new JComboBox<>(SAMPLE_RATES);
        selectSampleRate(currentSampleRateHz);
        panel.add(comboSampleRate);

        return panel;
    }

    /** Wählt den zu {@code hz} passenden Eintrag in {@link #comboSampleRate}, falls vorhanden. */
    private void selectSampleRate(int hz) {
        for (String rate : SAMPLE_RATES) {
            if (parseRate(rate) == hz) {
                comboSampleRate.setSelectedItem(rate);
                return;
            }
        }
    }

    /** Baut "Übernehmen"/"Abbrechen"; "Übernehmen" übernimmt die zuletzt gewählten Sensoren
     *  final in {@link #selectedSensor1}/{@link #selectedSensor2} - "Abbrechen" verwirft nur
     *  diese finalen Felder, die während der Konfiguration bereits per
     *  {@link SensorSelectionListener} gemeldeten Sensoren bleiben aktiv. */
    private JPanel buildButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnOk = new JButton("Übernehmen");
        JButton btnCancel = new JButton("Abbrechen");

        btnOk.addActionListener(_ -> {
            selectedSensor1 = (Sensor) channelA.comboSensor.getSelectedItem();
            selectedSensor2 = (Sensor) channelB.comboSensor.getSelectedItem();
            applied = true;
            dispose();
        });
        btnCancel.addActionListener(_ -> dispose());

        buttonPanel.add(btnOk);
        buttonPanel.add(btnCancel);
        return buttonPanel;
    }

    /** Startet die periodische Aktualisierung der Live-Werte, solange der Dialog offen ist. */
    private void startLiveUpdates() {
        liveUpdateTimer = new Timer(LIVE_REFRESH_MS, _ -> updateChannelStates());
        liveUpdateTimer.start();
    }

    private void stopLiveUpdates() {
        if (liveUpdateTimer != null) {
            liveUpdateTimer.stop();
        }
    }

    /** Erzwingt, dass höchstens ein Kanal einen Spektrum-Sensor hat - die Firmware unterstützt
     *  kein gleichzeitiges Spektrum auf beiden Kanälen. Schaltet bei einem Konflikt automatisch
     *  den jeweils anderen Kanal auf "Kein Sensor". */
    private void enforceSpectrumExclusivity() {
        Sensor selectedA = (Sensor) channelA.comboSensor.getSelectedItem();
        Sensor selectedB = (Sensor) channelB.comboSensor.getSelectedItem();
        boolean spectrumOnA = selectedA != null && selectedA.producesSpectrum();
        boolean spectrumOnB = selectedB != null && selectedB.producesSpectrum();

        if (spectrumOnA && selectedB != SensorRegistry.NO_SENSOR) {
            channelB.comboSensor.setSelectedItem(SensorRegistry.NO_SENSOR);
        } else if (spectrumOnB && selectedA != SensorRegistry.NO_SENSOR) {
            channelA.comboSensor.setSelectedItem(SensorRegistry.NO_SENSOR);
        }
    }

    /** Aktualisiert Enabled-Status, Einheit, Live-Wert etc. beider Kanäle. */
    private void updateChannelStates() {
        Sensor selectedA = (Sensor) channelA.comboSensor.getSelectedItem();
        Sensor selectedB = (Sensor) channelB.comboSensor.getSelectedItem();
        boolean spectrumOnA = selectedA != null && selectedA.producesSpectrum();
        boolean spectrumOnB = selectedB != null && selectedB.producesSpectrum();

        updateChannel(channelA, live1, spectrumOnB);
        updateChannel(channelB, live2, spectrumOnA);
    }

    /** Berechnet die maximal zulässige Abtastrate (Minimum aus Sensorgrenzen und, falls per
     *  Bluetooth verbunden, der Verbindungsgrenze) und passt Optionsliste, Auswahl und Tooltip
     *  entsprechend an. Spektrum-Sensoren haben eine fest in der Firmware vorgegebene Bildrate -
     *  die Abtastrate ist dann irrelevant und die Auswahl wird deaktiviert. */
    private void refreshSampleRateOptions() {
        Sensor selectedA = (Sensor) channelA.comboSensor.getSelectedItem();
        Sensor selectedB = (Sensor) channelB.comboSensor.getSelectedItem();
        boolean spectrumOnA = selectedA != null && selectedA.producesSpectrum();
        boolean spectrumOnB = selectedB != null && selectedB.producesSpectrum();

        int maxA = (selectedA != null) ? selectedA.getMaxSampleRateHz() : Integer.MAX_VALUE;
        int maxB = (selectedB != null) ? selectedB.getMaxSampleRateHz() : Integer.MAX_VALUE;
        boolean bluetoothLimited = DeviceConnection.getInstance().isBluetoothConnection();
        int maxConnection = bluetoothLimited ? DeviceConnection.BLUETOOTH_MAX_SAMPLE_RATE_HZ : Integer.MAX_VALUE;
        int effectiveMax = Math.min(Math.min(maxA, maxB), maxConnection);

        int previousHz = parseRate((String) comboSampleRate.getSelectedItem());

        List<String> allowed = new ArrayList<>();
        for (String rate : SAMPLE_RATES) {
            if (parseRate(rate) <= effectiveMax) allowed.add(rate);
        }
        if (allowed.isEmpty()) {
            allowed.add(effectiveMax + " Hz");
        }

        comboSampleRate.setModel(new DefaultComboBoxModel<>(allowed.toArray(new String[0])));

        if (previousHz > 0 && previousHz <= effectiveMax) {
            selectSampleRate(previousHz);
        } else {
            comboSampleRate.setSelectedIndex(allowed.size() - 1);
        }

        boolean sampleRateRelevant = !spectrumOnA && !spectrumOnB;
        comboSampleRate.setEnabled(sampleRateRelevant);

        if (!sampleRateRelevant) {
            comboSampleRate.setToolTipText("Wirkt sich nicht auf das Frequenzspektrum aus - dessen Bildrate ist fest in der Firmware vorgegeben.");
        } else if (bluetoothLimited && maxConnection < Math.min(maxA, maxB)) {
            comboSampleRate.setToolTipText("Auf " + effectiveMax + " Hz begrenzt, weil aktuell per Bluetooth verbunden - "
                    + "die Verbindung schafft weniger Bandbreite als USB. Für höhere Raten per USB verbinden.");
        } else if (effectiveMax < 1000) {
            comboSampleRate.setToolTipText("Auf " + effectiveMax + " Hz begrenzt - schneller kann mindestens einer der gewählten Sensoren keine neuen Werte liefern.");
        } else {
            comboSampleRate.setToolTipText(null);
        }
    }

    /** Aktualisiert Einheit, Enabled-Status und Live-Wert eines einzelnen Kanals.
     *
     * @param lockedBySpectrum ob der andere Kanal gerade ein Spektrum aufnimmt und diese
     *                         Sensorwahl deshalb gesperrt werden muss */
    private void updateChannel(Channel ch, LiveSource source, boolean lockedBySpectrum) {
        Sensor sensor = (Sensor) ch.comboSensor.getSelectedItem();
        boolean active = (sensor != null && sensor != SensorRegistry.NO_SENSOR);

        ch.comboSensor.setEnabled(!lockedBySpectrum);
        ch.comboSensor.setToolTipText(lockedBySpectrum
                ? "Nicht wählbar, solange der andere Kanal ein Frequenzspektrum aufnimmt - beide gleichzeitig unterstützt die Firmware nicht."
                : null);

        ch.lblUnit.setText(active ? sensor.getUnit() : "-");
        ch.txtOffset.setEnabled(active);
        ch.btnCalibrate.setEnabled(active && !sensor.getCalibrationParameters().isEmpty());

        Double liveValue = (active && source != null) ? source.poll() : null;
        ch.lblLive.setText(liveValue != null ? String.format("Live: %.3f %s", liveValue, sensor.getUnit()) : "–");
        ch.btnTara.setEnabled(liveValue != null);
    }

    /** Standard-GridBagConstraints für die Formularzeilen: horizontal ausgefüllt, kleine Abstände. */
    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    /** Fügt eine Formularzeile "Label: Komponente" hinzu. */
    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.35;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.65;
        panel.add(comp, gbc);
    }

    /** Fügt die Offset-Zeile hinzu: Textfeld, Live-Wert und Nullen-Button nebeneinander. */
    private void addTaraRow(JPanel panel, GridBagConstraints gbc, int row,
                            JTextField textField, JLabel liveLabel, JButton button) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.35;
        panel.add(new JLabel("Offset:"), gbc);

        JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
        rowPanel.add(textField, BorderLayout.WEST);
        rowPanel.add(liveLabel, BorderLayout.CENTER);
        rowPanel.add(button, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.weightx = 0.65;
        panel.add(rowPanel, gbc);
    }

    /** @return {@code true}, wenn der Dialog per "Übernehmen" (nicht "Abbrechen"/Schließen) beendet wurde */
    public boolean isApplied() {
        return applied;
    }

    /** @return den bei "Übernehmen" gewählten Sensor für Kanal A, {@code null} ohne Übernahme */
    public Sensor getSelectedSensorA() {
        return selectedSensor1;
    }

    /** @return den bei "Übernehmen" gewählten Sensor für Kanal B, {@code null} ohne Übernahme */
    public Sensor getSelectedSensorB() {
        return selectedSensor2;
    }

    public int getSampleRate() {
        return parseRate((String) comboSampleRate.getSelectedItem());
    }

    /** Parst "123 Hz" zu 123; liefert 0 bei {@code null} oder ungültigem Text. */
    private int parseRate(String rateText) {
        if (rateText == null) return 0;
        try {
            return Integer.parseInt(rateText.replace("Hz", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
