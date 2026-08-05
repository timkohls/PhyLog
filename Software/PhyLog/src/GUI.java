import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.TableModelEvent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/**
 * Hauptfenster von PhyLog: Menüleiste, Werkzeugleiste, Messwerttabellen für Kanal A/B mit
 * automatischem Scrollen sowie das {@link ChartPanel}. Die eigentliche Datenaufnahme inkl.
 * Trigger-Logik übernimmt {@link AcquisitionEngine}, CSV-/PNG-Im- und -Export {@link DataFileService}
 * - diese Klasse verdrahtet nur noch UI-Elemente mit beiden und hält den pro Kanal nötigen
 * UI-Zustand ({@link MeasurementChannel}).
 */
public class GUI extends JFrame implements AcquisitionEngine.Listener {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private final MeasurementChannel channelA = new MeasurementChannel('A');
    private final MeasurementChannel channelB = new MeasurementChannel('B');
    private final AcquisitionEngine acquisitionEngine = new AcquisitionEngine(channelA, channelB, this);

    private JPanel tableContainerPanel;
    private ChartPanel chartPanel;
    private JSplitPane mainSplitPane;

    private JButton btnStart, btnStop, btnTrigger, btnZoomIn, btnZoomOut, btnResetZoom, btnClear;
    private JLabel lblTriggerStatus;

    /** "Sensor konfigurieren..."-Menüeintrag - nur nutzbar, solange eine Verbindung zum ESP32
     *  besteht (siehe {@link #openSensorConfigDialog()} und {@link #updateStatusLabel()}), damit
     *  eine Sensorauswahl nie ins Leere läuft, weil die Firmware sie mangels Verbindung gar nicht
     *  erst mitbekommen könnte. */
    private JMenuItem configSensorItem;

    /** Menüeinträge für die Y-Achsen-Steuerung zur Synchronisation mit automatischen Wechseln. */
    private JRadioButtonMenuItem yAxisShared;
    private JRadioButtonMenuItem yAxisDual;

    /** Referenz auf ein bereits geöffnetes Terminal-Fenster. */
    private Terminal terminalWindow;

    /** {@code true}, wenn Kanal B (sofern aktiv) über eine eigene, unabhängig skalierte zweite
     *  Y-Achse dargestellt wird, statt sich - wie im Standardfall - dieselbe Achse mit Kanal A zu
     *  teilen (siehe Menü "Ansicht" -&gt; "Y-Achsen" sowie {@link ChartPanel#setDualYAxisMode}).
     *  Wirkt sich nur auf Achsenbeschriftung/-skalierung aus, nicht auf Fit, Zoom oder Chi². */
    private boolean dualYAxisMode = false;

    /** {@code true}, solange das Diagramm aktuell im Frequenzspektrum-Modus zeigt (mindestens ein
     *  Kanal hat einen Spektrum-Sensor, siehe {@link Sensor#producesSpectrum()}) - dient nur dazu,
     *  einen Wechsel zwischen Zeit- und Frequenzachse zu erkennen, um dann den Zoom
     *  zurückzusetzen (siehe {@link #updateTableLayout()}), da beide Achsen einen grundverschiedenen
     *  Wertebereich haben. */
    private boolean spectrumModeActive = false;
    /** Zuletzt empfangenes Spektrum je Kanal (dB je Bin), {@code null} ohne aktiven Spektrum-Sensor
     *  bzw. bevor das erste Paket eingetroffen ist (siehe {@link #onSpectrumFrame}). */
    private double[] lastSpectrumA, lastSpectrumB;
    private int lastSpectrumRateA = 16000, lastSpectrumRateB = 16000;

    public GUI() {
        super("PhyLog");

        Theme.setup();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        loadWindowIcon();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                DeviceConnection.getInstance().disconnect();
            }
        });

        initMenuBar();
        initToolBar();
        initMainArea();

        // Anfangszustand von Start/Stop, Sensor-Menüpunkt etc. korrekt setzen (siehe
        // #updateStatusLabel), statt bis zur ersten Verbindungsänderung auf die in initToolBar()
        // gesetzten Platzhalterwerte angewiesen zu sein.
        updateStatusLabel();

        // Läuft dauerhaft, nicht nur während einer Aufzeichnung - siehe AcquisitionEngine#ingestSample.
        DeviceConnection.getInstance().addLineListener(acquisitionEngine::onLineReceived);

        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            if (mainSplitPane != null) {
                mainSplitPane.setDividerLocation(0.25);
            }
        });

        updateTableLayout();
    }

    /** @return den Kanal 'A' oder 'B'; alles andere fällt auf Kanal A zurück. */
    private MeasurementChannel channel(char id) {
        return (id == 'B') ? channelB : channelA;
    }

    private void loadWindowIcon() {
        try {
            setIconImage(new ImageIcon("src/assets/icon.png").getImage());
        } catch (Exception e) {
            System.err.println("Warnung: Fenster-Icon konnte nicht geladen werden: " + e.getMessage());
        }
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // --- Datei Menü ---
        JMenu menuFile = new JMenu("Datei");

        JMenuItem openItem = new JMenuItem("Öffnen...");
        openItem.addActionListener(e -> openCsv());
        menuFile.add(openItem);

        menuFile.addSeparator();

        JMenuItem exportCsvItem = new JMenuItem("Als CSV exportieren...");
        exportCsvItem.addActionListener(e -> exportCsv());
        menuFile.add(exportCsvItem);

        JMenuItem exportPngItem = new JMenuItem("Diagramm als PNG exportieren...");
        exportPngItem.addActionListener(e -> exportPng());
        menuFile.add(exportPngItem);

        menuFile.addSeparator();
        JMenuItem exitItem = new JMenuItem("Beenden");
        exitItem.addActionListener(e -> System.exit(0));
        menuFile.add(exitItem);

        // --- Sensor Menü ---
        JMenu menuSensor = new JMenu("Sensor");
        menuSensor.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                updateStatusLabel();
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });

        // Bluetooth-Verbindung ist noch nicht implementiert (der bisherige Menüpunkt öffnete nur
        // einen Platzhalter-Dialog ohne echte Funktion) - statt Nutzer:innen mit einem Fake-Dialog
        // in die Irre zu führen, bleibt der Punkt sichtbar, aber deaktiviert, mit erklärendem Tooltip.
        JMenuItem connectionItem = new JMenuItem("Verbindung (Bluetooth)...");
        connectionItem.setEnabled(false);
        connectionItem.setToolTipText("Noch nicht implementiert - Verbindung aktuell nur per COM-Port oben rechts möglich.");
        menuSensor.add(connectionItem);

        configSensorItem = new JMenuItem("Sensor konfigurieren...");
        configSensorItem.addActionListener(e -> openSensorConfigDialog());
        configSensorItem.setEnabled(DeviceConnection.getInstance().isConnected());
        configSensorItem.setToolTipText("Erst mit dem ESP32 verbinden, dann Sensoren auswählen.");
        menuSensor.add(configSensorItem);

        // --- Terminal Menü ---
        JMenu menuTerminal = new JMenu("Terminal");

        JMenuItem terminalItem = new JMenuItem("Terminal öffnen...");
        terminalItem.addActionListener(e -> openTerminal());
        menuTerminal.add(terminalItem);

        // --- Ansicht Menü ---
        JMenu menuView = new JMenu("Ansicht");

        JMenuItem resetLayoutItem = new JMenuItem("Ansicht zurücksetzen");
        resetLayoutItem.addActionListener(e -> resetLayout());
        menuView.add(resetLayoutItem);

        JCheckBoxMenuItem showPoints = new JCheckBoxMenuItem("Messpunkte anzeigen", true);
        showPoints.addActionListener(e -> chartPanel.setShowPoints(showPoints.isSelected()));

        JMenu menuLineMode = new JMenu("Linienart");
        ButtonGroup lineModeGroup = new ButtonGroup();

        JRadioButtonMenuItem lineModeNone = new JRadioButtonMenuItem("Keine Linie", true);
        lineModeNone.addActionListener(e -> chartPanel.setLineMode(ChartPanel.LineMode.NONE));
        lineModeGroup.add(lineModeNone);
        menuLineMode.add(lineModeNone);

        JRadioButtonMenuItem lineModeStraight = new JRadioButtonMenuItem("Verbindungslinie (gerade)");
        lineModeStraight.addActionListener(e -> chartPanel.setLineMode(ChartPanel.LineMode.STRAIGHT));
        lineModeGroup.add(lineModeStraight);
        menuLineMode.add(lineModeStraight);

        JRadioButtonMenuItem lineModeSpline = new JRadioButtonMenuItem("Spline (glatt)");
        lineModeSpline.addActionListener(e -> chartPanel.setLineMode(ChartPanel.LineMode.SPLINE));
        lineModeGroup.add(lineModeSpline);
        menuLineMode.add(lineModeSpline);

        menuView.addSeparator();
        menuView.add(showPoints);
        menuView.add(menuLineMode);

        // --- Y-Achsen Untermenü ---
        JMenu menuYAxis = new JMenu("Y-Achsen");
        ButtonGroup yAxisGroup = new ButtonGroup();

        yAxisShared = new JRadioButtonMenuItem("Eine gemeinsame Y-Achse", true);
        yAxisShared.addActionListener(e -> setDualYAxisMode(false));
        yAxisGroup.add(yAxisShared);
        menuYAxis.add(yAxisShared);

        yAxisDual = new JRadioButtonMenuItem("Zwei unabhängige Y-Achsen (je Kanal skaliert)");
        yAxisDual.addActionListener(e -> setDualYAxisMode(true));
        yAxisGroup.add(yAxisDual);
        menuYAxis.add(yAxisDual);

        menuView.addSeparator();
        menuView.add(menuYAxis);

        // --- Funktions-Fit Menü ---
        JMenu menuFit = new JMenu("Funktions-Fit");
        ButtonGroup fitButtonGroup = new ButtonGroup();

        JRadioButtonMenuItem itemNone = new JRadioButtonMenuItem("Kein Fit", true);
        itemNone.addActionListener(e -> chartPanel.setFitMode(ChartPanel.FitMode.NONE));
        fitButtonGroup.add(itemNone);

        JMenu menuPoly = new JMenu("Polynomfunktion");

        JRadioButtonMenuItem itemDeg1 = new JRadioButtonMenuItem("Grad 1 (Lineare Funktion)");
        itemDeg1.addActionListener(e -> chartPanel.setFitMode(ChartPanel.FitMode.LINEAR));
        fitButtonGroup.add(itemDeg1);
        menuPoly.add(itemDeg1);

        for (int degree = 2; degree <= 6; degree++) {
            int deg = degree;
            String label = (deg == 2) ? "Grad 2 (Parabel)" : "Grad " + deg;
            JRadioButtonMenuItem itemDeg = new JRadioButtonMenuItem(label);
            itemDeg.addActionListener(e -> {
                chartPanel.setPolynomialDegree(deg);
                chartPanel.setFitMode(ChartPanel.FitMode.POLYNOMIAL);
            });
            fitButtonGroup.add(itemDeg);
            menuPoly.add(itemDeg);
        }

        JRadioButtonMenuItem itemSinus = new JRadioButtonMenuItem("Sinusfunktion");
        itemSinus.addActionListener(e -> chartPanel.setFitMode(ChartPanel.FitMode.SINUS));
        fitButtonGroup.add(itemSinus);

        JRadioButtonMenuItem itemExp = new JRadioButtonMenuItem("Exponentialfunktion");
        itemExp.addActionListener(e -> chartPanel.setFitMode(ChartPanel.FitMode.EXPONENTIAL));
        fitButtonGroup.add(itemExp);

        JMenuItem itemStdDev = new JMenuItem("Standardabweichung...");
        itemStdDev.addActionListener(e -> openStandardDeviationDialog());

        JLabel portLabel = new JLabel(" COM-Port: ");

        JComboBox<String> portSelector = new JComboBox<>();
        portSelector.setEditable(true);
        portSelector.setMaximumSize(new Dimension(140, 25));

        for (String name : DeviceConnection.getInstance().listPortNames()) {
            portSelector.addItem(name);
        }

        JButton btnRefreshPorts = new JButton("↻");
        btnRefreshPorts.setToolTipText("Ports aktualisieren");
        btnRefreshPorts.setFocusPainted(false);
        btnRefreshPorts.setMargin(new Insets(2, 4, 2, 4));
        btnRefreshPorts.addActionListener(e -> {
            portSelector.removeAllItems();
            for (String name : DeviceConnection.getInstance().listPortNames()) {
                portSelector.addItem(name);
            }
        });

        JButton connectButton = new JButton(DeviceConnection.getInstance().isConnected() ? "Trennen" : "Verbinden");
        connectButton.setFocusPainted(false);
        connectButton.setMargin(new Insets(2, 8, 2, 8));

        connectButton.addActionListener(e -> {
            if (DeviceConnection.getInstance().isConnected()) {
                DeviceConnection.getInstance().disconnect();
                connectButton.setText("Verbinden");
            } else {
                Object selectedItem = portSelector.getSelectedItem();
                if (selectedItem != null) {
                    String portName = selectedItem.toString().trim();
                    if (!portName.isEmpty()) {
                        boolean success = DeviceConnection.getInstance().connect(portName, 115200);
                        if (success) {
                            connectButton.setText("Trennen");
                        } else {
                            JOptionPane.showMessageDialog(null,
                                    "Verbindung zu " + portName + " fehlgeschlagen.",
                                    "Verbindungsfehler",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
            updateStatusLabel();
        });

        menuFit.add(itemNone);
        menuFit.add(menuPoly);
        menuFit.add(itemSinus);
        menuFit.add(itemExp);
        menuFit.addSeparator();
        menuFit.add(itemStdDev);

        menuBar.add(menuFile);
        menuBar.add(menuSensor);
        menuBar.add(menuTerminal);
        menuBar.add(menuView);
        menuBar.add(menuFit);

        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(portLabel);
        menuBar.add(portSelector);
        menuBar.add(Box.createRigidArea(new Dimension(5, 0)));
        menuBar.add(btnRefreshPorts);
        menuBar.add(Box.createRigidArea(new Dimension(5, 0)));
        menuBar.add(connectButton);
        menuBar.add(Box.createRigidArea(new Dimension(15, 0)));

        setJMenuBar(menuBar);
    }

    private void openStandardDeviationDialog() {
        StandardDeviationDialog dialog = new StandardDeviationDialog(this, chartPanel.getStandardDeviation(),
                chartPanel.getSigmaMode(), chartPanel.getLocalSigmaNeighbors());
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            chartPanel.setStandardDeviation(dialog.getStandardDeviation());
            chartPanel.setLocalSigmaNeighbors(dialog.getLocalSigmaNeighbors());
            chartPanel.setSigmaMode(dialog.getSigmaMode());
        }
    }

    private void initToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        btnStart = new JButton("Start");
        btnStop = new JButton("Stop");
        btnStart.addActionListener(e -> startMeasurement());
        btnStop.addActionListener(e -> stopMeasurement());
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);

        btnZoomIn = new JButton(" + ");
        btnZoomIn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnZoomIn.setMargin(new Insets(2, 6, 2, 6));
        btnZoomIn.setToolTipText("Hineinzoomen");
        btnZoomIn.addActionListener(e -> chartPanel.zoomIn());

        btnZoomOut = new JButton(" − ");
        btnZoomOut.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnZoomOut.setMargin(new Insets(2, 6, 2, 6));
        btnZoomOut.setToolTipText("Herauszoomen");
        btnZoomOut.addActionListener(e -> chartPanel.zoomOut());

        btnResetZoom = new JButton("Reset");
        btnResetZoom.setToolTipText("Gesamten Graphen anzeigen");
        btnResetZoom.addActionListener(e -> chartPanel.resetZoom());

        btnTrigger = new JButton("Trigger");
        btnClear = new JButton("Leeren");

        btnTrigger.addActionListener(e -> openTriggerDialog());
        btnClear.addActionListener(e -> clearData());

        toolBar.add(btnStart);
        toolBar.add(btnStop);
        toolBar.addSeparator();

        toolBar.add(btnZoomIn);
        toolBar.add(btnZoomOut);
        toolBar.add(btnResetZoom);

        toolBar.addSeparator();
        toolBar.add(btnTrigger);
        toolBar.addSeparator();
        toolBar.add(btnClear);

        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(new JLabel("Status: "));
        lblTriggerStatus = new JLabel();
        toolBar.add(lblTriggerStatus);
        toolBar.add(Box.createHorizontalStrut(8));

        add(toolBar, BorderLayout.NORTH);
        updateStatusLabel();
    }

    private void initMainArea() {
        initChannelTable(channelA);
        initChannelTable(channelB);

        tableContainerPanel = new JPanel(new BorderLayout());
        tableContainerPanel.setBackground(Theme.BG);

        chartPanel = new ChartPanel();
        chartPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                "Diagramm",
                0, 0, null, Theme.TEXT));

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableContainerPanel, chartPanel);
        mainSplitPane.setResizeWeight(0.25);

        add(mainSplitPane, BorderLayout.CENTER);
    }

    private void initChannelTable(MeasurementChannel ch) {
        ch.tableModel = createTableModel();
        ch.table = new JTable(ch.tableModel);
        ch.table.setFillsViewportHeight(true);
        ch.tableModel.addTableModelListener(e -> {
            updateChartData();
            if (e.getType() == TableModelEvent.INSERT) {
                SwingUtilities.invokeLater(() -> {
                    int lastRow = ch.table.getRowCount() - 1;
                    if (lastRow >= 0) {
                        ch.table.scrollRectToVisible(ch.table.getCellRect(lastRow, 0, true));
                    }
                });
            }
        });
        ch.scrollPane = new JScrollPane(ch.table);
    }

    private void resetLayout() {
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);
        revalidate();
        repaint();

        SwingUtilities.invokeLater(() -> {
            if (mainSplitPane != null) {
                mainSplitPane.setDividerLocation(0.25);
            }
        });
    }

    private void openCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("CSV-Datei öffnen");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV-Dateien (*.csv)", "csv"));

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        clearData();

        try {
            int columnCount = channelA.tableModel.getColumnCount();
            DataFileService.readCsv(file, columnCount,
                    row -> channelA.tableModel.addRow(row),
                    detectedSensor -> {
                        channelA.sensor = detectedSensor;
                        updateTableLayout();
                    });
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Lesen der Datei: " + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openSensorConfigDialog() {
        if (!DeviceConnection.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte zuerst über 'Sensor \u2192 Verbindung (Bluetooth)...' bzw. den COM-Port oben rechts mit dem ESP32 verbinden.",
                    "Keine Verbindung", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Sensor previousA = channelA.sensor;
        Sensor previousB = channelB.sensor;

        boolean liveMonitoringStartedByDialog = ensureLiveDataFlowing();

        SensorConfigDialog dialog = new SensorConfigDialog(this, channelA.sensor, channelB.sensor,
                acquisitionEngine.getSampleRateHz(),
                () -> channelA.latestValue, () -> channelB.latestValue,
                this::pushSensorSelectionToFirmware, this::onTareRequested);
        dialog.setVisible(true);

        if (liveMonitoringStartedByDialog) {
            DeviceConnection.getInstance().sendLine("STOP");
        }

        if (dialog.isApplied()) {
            channelA.sensor = dialog.getSelectedSensorA();
            channelB.sensor = dialog.getSelectedSensorB();
            acquisitionEngine.setSampleRateHz(dialog.getSampleRate());
            updateTableLayout();
            acquisitionEngine.pushSampleRateToFirmware();
        } else {
            pushSensorSelectionToFirmware('A', previousA);
            pushSensorSelectionToFirmware('B', previousB);
        }

        updateStatusLabel();
    }

    private boolean ensureLiveDataFlowing() {
        if (!DeviceConnection.getInstance().isConnected() || acquisitionEngine.isRecording()) {
            return false;
        }
        DeviceConnection.getInstance().sendLine("START");
        return true;
    }

    private void pushSensorSelectionToFirmware(char channelId, Sensor sensor) {
        MeasurementChannel ch = channel(channelId);
        if (ch.sensor != sensor) ch.tareOffset = 0.0;
        ch.sensor = sensor;

        if (!DeviceConnection.getInstance().isConnected()) {
            return;
        }
        DeviceConnection.getInstance().sendLine("SET," + channelId + "," + sensor.getFirmwareTypeName());
    }

    private void onTareRequested(char channelId) {
        MeasurementChannel ch = channel(channelId);
        if (ch.latestValue != null) ch.tareOffset += ch.latestValue;
    }

    private void openTerminal() {
        if (terminalWindow == null) {
            terminalWindow = new Terminal();
        }
        terminalWindow.setVisible(true);
        terminalWindow.toFront();
    }

    private void startMeasurement() {
        if (!DeviceConnection.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Keine Verbindung zum Gerät! Bitte zuerst verbinden.",
                    "Fehler: Kein Gerät",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        if (!channelA.hasSensor() && !channelB.hasSensor()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Es ist kein Sensor ausgewählt! Bitte wähle über 'Sensor -> Sensor konfigurieren...' mindestens einen Sensor aus.",
                    "Fehler: Kein Sensor gewählt",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        TriggerDialog.Config triggerConfig = acquisitionEngine.getTriggerConfig();
        if (triggerConfig.thresholdMode && !channel(triggerConfig.channel).hasSensor()) {
            JOptionPane.showMessageDialog(this,
                    "Für den Trigger-Kanal " + triggerConfig.channel + " ist kein Sensor konfiguriert.",
                    "Trigger nicht konfigurierbar", JOptionPane.WARNING_MESSAGE);
            return;
        }

        acquisitionEngine.start();
    }

    private void stopMeasurement() {
        acquisitionEngine.stop();
    }

    /** {@inheritDoc} Reagiert auf Start/Stop der {@link AcquisitionEngine} - aktualisiert nur die
     *  Statusanzeige, die eigentliche Aufnahmelogik liegt komplett dort. */
    @Override
    public void onStatusChanged() {
        updateStatusLabel();
    }

    /** {@inheritDoc} */
    @Override
    public void onDurationLimitReached() {
        lblTriggerStatus.setText("Maximale Messdauer erreicht - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.POINT_A);
    }

    /** {@inheritDoc} Speichert das neue Spektrum für den jeweiligen Kanal und zeichnet es direkt
     *  ins {@link ChartPanel} - unabhängig von Tabelle/Zeitachse, siehe {@link #renderSpectrumChart()}. */
    @Override
    public void onSpectrumFrame(char channelId, double[] magnitudesDb, int sampleRateHz) {
        if (channelId == 'A') {
            lastSpectrumA = magnitudesDb;
            lastSpectrumRateA = sampleRateHz;
        } else if (channelId == 'B') {
            lastSpectrumB = magnitudesDb;
            lastSpectrumRateB = sampleRateHz;
        }
        renderSpectrumChart();
    }

    /** Zeichnet die zwischengespeicherten Spektren der Kanäle, die aktuell einen Spektrum-Sensor
     *  haben, ins {@link ChartPanel} - als Haupt-Serie (Kanal A, oder Kanal B falls A keines hat)
     *  bzw. als Extra-Serie, analog zur normalen Zwei-Kanal-Überlagerung. Tut nichts, wenn
     *  inzwischen kein Kanal mehr einen Spektrum-Sensor hat (z. B. veraltetes, spät eintreffendes
     *  Paket nach einem Sensorwechsel). */
    private void renderSpectrumChart() {
        if (chartPanel == null) return;

        boolean spectrumA = channelA.producesSpectrum();
        boolean spectrumB = channelB.producesSpectrum();
        if (!spectrumA && !spectrumB) return;

        List<double[]> mainSeries = new ArrayList<>();
        List<ChartPanel.Series> extras = new ArrayList<>();

        if (spectrumA && lastSpectrumA != null) {
            mainSeries = toFrequencyPoints(lastSpectrumA, lastSpectrumRateA);
        }

        if (spectrumB && lastSpectrumB != null) {
            List<double[]> pointsB = toFrequencyPoints(lastSpectrumB, lastSpectrumRateB);
            if (mainSeries.isEmpty()) {
                mainSeries = pointsB; // A liefert (noch) nichts - B übernimmt die Haupt-Serie
            } else {
                extras.add(new ChartPanel.Series("Kanal B: Frequenzspektrum", Theme.POINT_B, pointsB));
            }
        }

        chartPanel.setData(mainSeries);
        chartPanel.setExtraSeries(extras);
    }

    /** Wandelt ein Spektrum (dB je Bin) in (Frequenz, dB)-Punkte um. Die Bin-Breite ergibt sich
     *  aus Abtastrate und FFT-Größe - Letztere ist immer doppelt so groß wie die Anzahl der
     *  (nutzbaren) Bins, siehe {@code captureAndSendSpectrum} in phylog_firmware.ino. */
    private List<double[]> toFrequencyPoints(double[] magnitudesDb, int sampleRateHz) {
        int bins = magnitudesDb.length;
        int fftSize = bins * 2;
        List<double[]> points = new ArrayList<>(bins);
        for (int i = 0; i < bins; i++) {
            double freq = i * (double) sampleRateHz / fftSize;
            points.add(new double[]{freq, magnitudesDb[i]});
        }
        return points;
    }

    private void updateStatusLabel() {
        boolean connected = DeviceConnection.getInstance().isConnected();

        if (configSensorItem != null) {
            configSensorItem.setEnabled(connected);
        }

        updateActionAvailability(connected);

        if (lblTriggerStatus == null) return;

        TriggerDialog.Config triggerConfig = acquisitionEngine.getTriggerConfig();

        if (!connected) {
            lblTriggerStatus.setText("Nicht verbunden");
            lblTriggerStatus.setForeground(Theme.MUTED);
        } else if (acquisitionEngine.isWaitingForTrigger()) {
            lblTriggerStatus.setText("Warte auf Trigger (Kanal " + triggerConfig.channel + ") …");
            lblTriggerStatus.setForeground(Theme.POINT_A);
        } else if (acquisitionEngine.isRecording()) {
            lblTriggerStatus.setText(triggerConfig.thresholdMode ? "Aufnahme läuft (getriggert)" : "Aufnahme läuft");
            lblTriggerStatus.setForeground(Theme.SUCCESS);
        } else {
            lblTriggerStatus.setText("Bereit");
            lblTriggerStatus.setForeground(Theme.ACCENT);
        }
    }

    private void updateActionAvailability(boolean connected) {
        if (btnStart == null || btnStop == null) return;

        TriggerDialog.Config triggerConfig = acquisitionEngine.getTriggerConfig();
        boolean hasAnySensor = channelA.hasSensor() || channelB.hasSensor();
        boolean triggerChannelReady = !triggerConfig.thresholdMode || channel(triggerConfig.channel).hasSensor();
        boolean alreadyRunning = acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger();

        boolean canStart = connected && hasAnySensor && triggerChannelReady && !alreadyRunning;
        btnStart.setEnabled(canStart);
        btnStart.setToolTipText(startButtonTooltip(connected, hasAnySensor, triggerChannelReady, alreadyRunning, triggerConfig));

        boolean canStop = alreadyRunning;
        btnStop.setEnabled(canStop);
        btnStop.setToolTipText(canStop ? "Laufende Messung stoppen" : "Es läuft aktuell keine Messung.");
    }

    private String startButtonTooltip(boolean connected, boolean hasAnySensor, boolean triggerChannelReady,
                                      boolean alreadyRunning, TriggerDialog.Config triggerConfig) {
        if (!connected) {
            return "Erst mit dem ESP32 verbinden.";
        }
        if (!hasAnySensor) {
            return "Erst unter 'Sensor \u2192 Sensor konfigurieren...' mindestens einen Sensor auswählen.";
        }
        if (!triggerChannelReady) {
            return "Für den gewählten Trigger-Kanal (" + triggerConfig.channel + ") ist kein Sensor konfiguriert.";
        }
        if (alreadyRunning) {
            return "Es läuft bereits eine Messung.";
        }
        return "Messung starten";
    }

    private void openTriggerDialog() {
        TriggerDialog dialog = new TriggerDialog(this, acquisitionEngine.getTriggerConfig());
        dialog.setVisible(true);

        if (dialog.isApplied()) {
            acquisitionEngine.setTriggerConfig(dialog.getConfig());
            updateStatusLabel();
        }
    }

    private void updateTableLayout() {
        tableContainerPanel.removeAll();

        configureTableModel(channelA);
        configureTableModel(channelB);

        // Veraltete Spektren verwerfen, sobald der jeweilige Kanal kein Spektrum-Sensor mehr ist -
        // sonst würde ein späterer Wechsel zurück kurzzeitig eine Grafik von vorhin zeigen.
        if (!channelA.producesSpectrum()) lastSpectrumA = null;
        if (!channelB.producesSpectrum()) lastSpectrumB = null;

        // Zeit- und Frequenzachse haben einen grundverschiedenen Wertebereich - ein beim
        // vorherigen Modus aktiver Zoom würde im neuen Modus u. U. schlicht nichts mehr zeigen.
        boolean nowSpectrumMode = channelA.producesSpectrum() || channelB.producesSpectrum();
        if (nowSpectrumMode != spectrumModeActive) {
            spectrumModeActive = nowSpectrumMode;
            chartPanel.resetZoom();
        }

        // Wenn 2 Sensoren ausgewählt sind, wird standardmäßig der Modus mit zwei Y-Achsen gesetzt.
        boolean shouldBeDual = channelA.hasSensor() && channelB.hasSensor();
        setDualYAxisMode(shouldBeDual);

        channelA.scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                channelTitle('A', channelA),
                0, 0, null, Theme.TEXT));

        if (channelB.hasSensor()) {
            channelB.scrollPane.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    channelTitle('B', channelB),
                    0, 0, null, Theme.TEXT));

            JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, channelA.scrollPane, channelB.scrollPane);
            verticalSplit.setResizeWeight(0.5);
            tableContainerPanel.add(verticalSplit, BorderLayout.CENTER);
        } else {
            tableContainerPanel.add(channelA.scrollPane, BorderLayout.CENTER);
        }

        tableContainerPanel.revalidate();
        tableContainerPanel.repaint();

        // Im Spektrum-Modus liefert die Tabelle keine Daten mehr (siehe updateChartData) - das
        // Diagramm muss hier explizit mit dem zuletzt gecachten Frame angestoßen werden.
        if (nowSpectrumMode) {
            renderSpectrumChart();
        } else {
            updateChartData();
        }
    }

    /** Titel für die Tabellen-Umrahmung eines Kanals - weist bei einem Spektrum-Sensor darauf
     *  hin, dass die Tabelle hier leer bleibt und die Anzeige stattdessen im Diagramm läuft. */
    private String channelTitle(char id, MeasurementChannel ch) {
        String base = "Sensor " + id + ": " + ch.sensor.getName();
        return ch.producesSpectrum() ? base + " - siehe Diagramm" : base;
    }

    private void configureTableModel(MeasurementChannel ch) {
        List<Sensor.Quantity> quantities = ch.sensor.getQuantities();
        ch.tableModel.setRowCount(0);
        String header = quantities.isEmpty() ? "Messwert" : quantities.getFirst().getColumnHeader();
        ch.tableModel.setColumnIdentifiers(new Object[]{"Zeit (s)", header});
    }

    private void setDualYAxisMode(boolean dualYAxisMode) {
        this.dualYAxisMode = dualYAxisMode;
        if (chartPanel != null) {
            chartPanel.setDualYAxisMode(dualYAxisMode);
        }

        // Synchronisiere die Menü-RadioButtons, falls der Modus geändert wurde
        if (dualYAxisMode && yAxisDual != null) {
            yAxisDual.setSelected(true);
        } else if (!dualYAxisMode && yAxisShared != null) {
            yAxisShared.setSelected(true);
        }

        updateChartUnits();
    }

    private void updateChartUnits() {
        if (chartPanel == null) return;

        boolean spectrumA = channelA.producesSpectrum();
        boolean spectrumB = channelB.producesSpectrum();

        if (spectrumA || spectrumB) {
            chartPanel.setXAxisTitle("Frequenz");
            chartPanel.setUnits("Hz", "Magnitude (dB)");
            chartPanel.setMainLabel(spectrumA ? "Kanal A: Frequenzspektrum" : "Kanal B: Frequenzspektrum");
            return;
        }

        chartPanel.setXAxisTitle("Zeit");

        List<Sensor.Quantity> quantitiesA = channelA.sensor.getQuantities();
        boolean hasSecondSensor = channelB.hasSensor();
        boolean useSpecificAxisLabels = dualYAxisMode && hasSecondSensor;

        String axisLabel = (hasSecondSensor && !useSpecificAxisLabels)
                ? "Messwerte"
                : (quantitiesA.isEmpty() ? "Messwert" : quantitiesA.get(0).getColumnHeader());
        chartPanel.setUnits("s", axisLabel);

        String labelA = quantitiesA.isEmpty() ? "Kanal A" : "Kanal A: " + quantitiesA.get(0).getColumnHeader();
        chartPanel.setMainLabel(labelA);

        if (hasSecondSensor) {
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String secondaryLabel = quantitiesB.isEmpty()
                    ? channelB.sensor.getName()
                    : quantitiesB.get(0).getColumnHeader();
            chartPanel.setSecondaryUnits(secondaryLabel);
        }
    }

    private DefaultTableModel createTableModel() {
        return new DefaultTableModel(new Object[]{"Zeit (s)", "Messwert"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return Double.class;
            }
        };
    }

    private void updateChartData() {
        if (chartPanel == null) return;
        // Im Spektrum-Modus kommt die Anzeige direkt aus onSpectrumFrame/renderSpectrumChart,
        // nicht aus der (dort ohnehin leer bleibenden) Tabelle - siehe channelTitle().
        if (channelA.producesSpectrum() || channelB.producesSpectrum()) return;

        chartPanel.setData(extractDataFromTable(channelA.tableModel, 1));

        List<ChartPanel.Series> extras = new ArrayList<>();
        if (channelB.hasSensor()) {
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String labelB = "Kanal B: " + (quantitiesB.isEmpty() ? channelB.sensor.getName() : quantitiesB.get(0).getColumnHeader());
            extras.add(new ChartPanel.Series(labelB, Theme.POINT_B, extractDataFromTable(channelB.tableModel, 1)));
        }
        chartPanel.setExtraSeries(extras);

        chartPanel.repaint();
    }

    private void exportCsv() {
        boolean hasDataA = channelA.tableModel.getRowCount() > 0;
        boolean hasDataB = channelB.tableModel.getRowCount() > 0;

        if (!hasDataA && !hasDataB) {
            JOptionPane.showMessageDialog(this, "Keine Daten zum Exportieren vorhanden.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("CSV Exportieren");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV-Dateien (*.csv)", "csv"));

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File selectedFile = chooser.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith(".csv")) {
            selectedFile = new File(selectedFile.getAbsolutePath() + ".csv");
        }

        boolean bothActive = hasDataA && hasDataB;
        List<String> writtenFileNames = new ArrayList<>();

        try {
            for (MeasurementChannel ch : new MeasurementChannel[]{channelA, channelB}) {
                if (ch.tableModel.getRowCount() == 0) continue;
                File file = bothActive ? DataFileService.withSuffix(selectedFile, "Kanal" + ch.id) : selectedFile;
                DataFileService.writeCsv(file, ch.tableModel);
                writtenFileNames.add(file.getName());
            }
            JOptionPane.showMessageDialog(this, "CSV erfolgreich gespeichert: " + String.join(", ", writtenFileNames),
                    "Erfolg", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Speichern der CSV: " + e.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportPng() {
        if (chartPanel.getWidth() <= 0 || chartPanel.getHeight() <= 0) {
            JOptionPane.showMessageDialog(this, "Diagramm ist noch nicht bereit.", "Hinweis", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Diagramm als PNG exportieren");
        chooser.setFileFilter(new FileNameExtensionFilter("PNG-Bilder (*.png)", "png"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }

            try {
                DataFileService.exportPng(chartPanel, file);
                JOptionPane.showMessageDialog(this, "Diagramm erfolgreich als PNG gespeichert!", "Erfolg", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Fehler beim Speichern des Bildes: " + e.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void clearData() {
        channelA.tableModel.setRowCount(0);
        channelB.tableModel.setRowCount(0);
    }

    private List<double[]> extractDataFromTable(DefaultTableModel model, int valueColumnIndex) {
        List<double[]> data = new ArrayList<>();
        if (valueColumnIndex <= 0 || valueColumnIndex >= model.getColumnCount()) {
            return data;
        }
        for (int i = 0; i < model.getRowCount(); i++) {
            Object timeObj = model.getValueAt(i, 0);
            Object valObj = model.getValueAt(i, valueColumnIndex);
            if (timeObj instanceof Number && valObj instanceof Number) {
                data.add(new double[]{
                        ((Number) timeObj).doubleValue(),
                        ((Number) valObj).doubleValue()
                });
            }
        }
        return data;
    }
}
