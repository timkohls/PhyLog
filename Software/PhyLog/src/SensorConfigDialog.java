import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog zur Konfiguration der Sensoren und der Abtastrate für Kanal A und B.
 */
public class SensorConfigDialog extends JDialog {

    /**
     * Schnittstelle zum Abfragen des aktuellen Live-Messwerts eines Kanals.
     */
    @FunctionalInterface
    public interface LiveSource {
        /**
         * Liest den aktuellen Messwert aus.
         *
         * @return Der Messwert oder {@code null}, falls nicht verfügbar.
         */
        Double poll();
    }

    /**
     * Listener für sofortige Benachrichtigungen bei Änderung der Sensorauswahl.
     */
    @FunctionalInterface
    public interface SensorSelectionListener {
        /**
         * Wird aufgerufen, wenn ein Sensor für einen Kanal ausgewählt wurde.
         *
         * @param channel Der betroffene Kanal ('A' oder 'B').
         * @param sensor  Der neu ausgewählte {@link Sensor}.
         */
        void onSensorSelected(char channel, Sensor sensor);
    }

    /**
     * Listener für Anforderungen zum Nullen (Tarieren) eines Kanals.
     */
    @FunctionalInterface
    public interface TareRequestListener {
        /**
         * Wird aufgerufen, wenn der Nutzer den Nullung-Button betätigt.
         *
         * @param channel Der zu tarierende Kanal ('A' oder 'B').
         */
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

    /**
     * Bündelt die UI-Komponenten eines einzelnen Sensorkanals.
     */
    private static class Channel {
        JComboBox<Sensor> comboSensor;
        JLabel lblUnit;
        JTextField txtOffset;
        JLabel lblLive;
        JButton btnTara;
        JButton btnCalibrate;
    }

    /**
     * Erstellt den Dialog zur Sensorkonfiguration.
     *
     * @param owner               Eigentümerfenster.
     * @param current1            Aktueller Sensor auf Kanal A.
     * @param current2            Aktueller Sensor auf Kanal B.
     * @param currentSampleRateHz Aktuell eingestellte Abtastrate in Hz.
     * @param live1               Quelle für Live-Werte von Kanal A.
     * @param live2               Quelle für Live-Werte von Kanal B.
     * @param selectionListener   Listener für Sensoränderungen.
     * @param tareListener        Listener für Tara-Anfragen.
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

    /**
     * Baut die Sensor-Auswahlliste für genau einen Kanal auf. Jeder Kanal bekommt dabei seine
     * eigenen, unabhängigen Sensor-Instanzen (siehe {@link SensorRegistry#getAvailableSensors()})
     * - nur für den bereits aktiven Sensortyp dieses Kanals wird {@code current} selbst
     * wiederverwendet, statt auch dafür eine frische Instanz zu erzeugen. Damit bleibt eine
     * zuvor gesetzte Kalibrierung beim erneuten Öffnen des Dialogs erhalten, und - der eigentliche
     * Zweck der pro-Kanal-Listen - Kanal A und Kanal B teilen sich nie dieselbe Sensor-Instanz,
     * selbst wenn beide denselben Sensortyp verwenden. Ohne das würde z. B. das Kalibrieren einer
     * HX711-Wägezelle auf Kanal A denselben Kalibrierfaktor auch auf Kanal B verändern, sobald
     * dort ebenfalls ein HX711 gewählt ist - der Kalibrierwert steckt im Sensor-Objekt selbst
     * (siehe {@link Sensor.CalibrationParameter}), nicht im {@link MeasurementChannel}.
     *
     * @param current Aktuell für diesen Kanal aktiver Sensor (oder {@code null}/{@link SensorRegistry#NO_SENSOR}).
     * @return Sensor-Auswahlliste für genau diesen Kanal.
     */
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

    /**
     * Baut das Panel für die Steuerung eines einzelnen Kanals auf.
     */
    private JPanel buildChannelPanel(Channel ch, char channelId, String title, Sensor[] availableSensors, Sensor current) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints gbc = createGbc();

        ch.comboSensor = new JComboBox<>(availableSensors);
        ch.comboSensor.setSelectedItem(current != null ? current : SensorRegistry.NO_SENSOR);
        ch.comboSensor.addActionListener(e -> {
            enforceSpectrumExclusivity();
            updateChannelStates();
            refreshSampleRateOptions();
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
        addTaraRow(panel, gbc, 2, "Offset:", ch.txtOffset, ch.lblLive, ch.btnTara);
        addFormRow(panel, gbc, 3, "Kalibrierung:", ch.btnCalibrate);

        return panel;
    }

    /**
     * Baut das Auswahlpanel für die gemeinsame Abtastrate auf.
     */
    private JPanel buildSharedRatePanel(int currentSampleRateHz) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));
        panel.add(new JLabel("Abtastrate (beide Kanäle):"));

        comboSampleRate = new JComboBox<>(SAMPLE_RATES);
        selectSampleRate(currentSampleRateHz);
        panel.add(comboSampleRate);

        return panel;
    }

    /**
     * Wählt die angegebene Abtastrate im ComboBox-Feld aus.
     */
    private void selectSampleRate(int hz) {
        for (String rate : SAMPLE_RATES) {
            if (parseRate(rate) == hz) {
                comboSampleRate.setSelectedItem(rate);
                return;
            }
        }
    }

    /**
     * Baut die Buttons "Übernehmen" und "Abbrechen" auf.
     */
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

    /**
     * Startet den Timer für regelmäßige Live-Anzeigen-Updates.
     */
    private void startLiveUpdates() {
        liveUpdateTimer = new Timer(LIVE_REFRESH_MS, e -> updateChannelStates());
        liveUpdateTimer.start();
    }

    /**
     * Stoppt den Timer für Live-Updates.
     */
    private void stopLiveUpdates() {
        if (liveUpdateTimer != null) {
            liveUpdateTimer.stop();
        }
    }

    /**
     * Verhindert, dass beide Kanäle gleichzeitig ein Frequenzspektrum aufnehmen bzw. dass ein
     * Kanal parallel zu einem Spektrum-Kanal noch einen normalen Sensor betreibt: sobald ein
     * Kanal auf einen Spektrum-Sensor gestellt wird, wird der jeweils andere Kanal automatisch
     * auf "kein Sensor" zurückgesetzt (siehe {@link #updateChannel} fürs anschließende Ausgrauen).
     * Die Firmware unterstützt das ohnehin nicht sinnvoll gleichzeitig - zwei Mikrofon-Captures
     * über denselben, ansonsten bereits knappen seriellen Kanal ergäben nur unnötige Bandbreite
     * für einen Kanal, der am Ende gar nicht ausgewertet wird.
     */
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

    /**
     * Aktualisiert Status und Live-Werte aller Kanäle.
     */
    private void updateChannelStates() {
        Sensor selectedA = (Sensor) channelA.comboSensor.getSelectedItem();
        Sensor selectedB = (Sensor) channelB.comboSensor.getSelectedItem();
        boolean spectrumOnA = selectedA != null && selectedA.producesSpectrum();
        boolean spectrumOnB = selectedB != null && selectedB.producesSpectrum();

        updateChannel(channelA, live1, spectrumOnB);
        updateChannel(channelB, live2, spectrumOnA);
    }

    /**
     * Baut die Abtastraten-Auswahl neu auf und begrenzt sie auf das Minimum aus den von beiden
     * gewählten Sensoren tatsächlich erreichbaren Obergrenzen (siehe {@link Sensor#getMaxSampleRateHz})
     * sowie, falls aktuell per Bluetooth statt per USB verbunden, {@link DeviceConnection#BLUETOOTH_MAX_SAMPLE_RATE_HZ}
     * - schnellere Schritte werden erst gar nicht angeboten, statt eine Rate einstellbar zu lassen,
     * die entweder der Sensor nicht liefern oder die Verbindung nicht mehr zuverlässig übertragen
     * kann. Solange mindestens ein Kanal ein Frequenzspektrum aufnimmt, ist die Auswahl komplett
     * irrelevant und bleibt deaktiviert (das Spektrum ignoriert diese Einstellung ohnehin, siehe
     * unten - über Bluetooth wird es aber ebenfalls spürbar langsamer ankommen, dafür gibt es
     * keine Drosselung, da die Bildrate fest in der Firmware steht).
     *
     * <p>Bewusst nur bei einer echten Sensor-Auswahländerung aufgerufen (siehe Aufrufer), nicht
     * bei jedem periodischen Live-Update von {@link #updateChannelStates} - ein wiederholtes
     * Neuaufbauen des (inhaltlich unveränderten) Modells alle {@link #LIVE_REFRESH_MS} wäre
     * unnötig, u. U. sogar störend, falls die Auswahlliste gerade aufgeklappt ist. Ein Wechsel der
     * Verbindungsart selbst (USB/Bluetooth) kann während offenem Dialog ohnehin nicht passieren -
     * dafür müsste man sich erst trennen, wofür der Dialog wieder schließt (siehe {@link GUI}).</p>
     */
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
            allowed.add(effectiveMax + " Hz"); // z. B. HX711 mit 80 Hz - kein Standard-Schritt passt darunter
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
            // Die Abtastrate wirkt sich nur auf normale Sensoren aus - das Frequenzspektrum läuft
            // mit eigener, fest in der Firmware vorgegebener Taktung (siehe phylog_firmware.ino,
            // SPECTRUM_INTERVAL_MS) und ignoriert diese Einstellung komplett.
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

    /**
     * Aktualisiert Status und Anzeigen eines einzelnen Kanals.
     *
     * @param lockedBySpectrum ob der jeweils andere Kanal ein Frequenzspektrum aufnimmt und
     *                         dieser Kanal deshalb aktuell keinen Sensor haben darf (siehe
     *                         {@link #enforceSpectrumExclusivity}).
     */
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

    /**
     * Erstellt Standard-Layoutbedingungen für Formularelemente.
     */
    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    /**
     * Fügt eine Zeile mit Label und Komponente zum Formular hinzu.
     */
    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.35;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.65;
        panel.add(comp, gbc);
    }

    /**
     * Fügt eine Zeile für die Tara-Verwaltung zum Formular hinzu.
     */
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

    /**
     * Gibt zurück, ob die Einstellungen angewendet wurden.
     *
     * @return {@code true}, wenn "Übernehmen" geklickt wurde.
     */
    public boolean isApplied() {
        return applied;
    }

    /**
     * Gibt den für Kanal A gewählten Sensor zurück.
     *
     * @return Gewählter {@link Sensor} für Kanal A.
     */
    public Sensor getSelectedSensorA() {
        return selectedSensor1;
    }

    /**
     * Gibt den für Kanal B gewählten Sensor zurück.
     *
     * @return Gewählter {@link Sensor} für Kanal B.
     */
    public Sensor getSelectedSensorB() {
        return selectedSensor2;
    }

    /**
     * Gibt die ausgewählte Abtastrate in Hz zurück.
     *
     * @return Abtastrate in Hz.
     */
    public int getSampleRate() {
        return parseRate((String) comboSampleRate.getSelectedItem());
    }

    /**
     * Parst die Zahlenwert-Abtastrate aus dem Auswahltext.
     */
    private int parseRate(String rateText) {
        if (rateText == null) return 0;
        try {
            return Integer.parseInt(rateText.replace("Hz", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}