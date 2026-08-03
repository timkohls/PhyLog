import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

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
     * Aktualisiert Status und Live-Werte aller Kanäle.
     */
    private void updateChannelStates() {
        updateChannel(channelA, live1);
        updateChannel(channelB, live2);
    }

    /**
     * Aktualisiert Status und Anzeigen eines einzelnen Kanals.
     */
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
