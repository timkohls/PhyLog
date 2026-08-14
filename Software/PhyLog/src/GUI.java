import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.TableModelEvent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

    /** Muss exakt zur Firmware passen (siehe BAUD_RATE in phylog_firmware.ino) - sonst
     *  verbindet sich nichts mehr. 460800 statt der früheren 115200, damit vor allem das
     *  Frequenzspektrum (Kanal A/B) deutlich flüssiger übertragen werden kann. */
    private static final int BAUD_RATE = 460800;

    private final MeasurementChannel channelA = new MeasurementChannel('A');
    private final MeasurementChannel channelB = new MeasurementChannel('B');
    private final AcquisitionEngine acquisitionEngine = new AcquisitionEngine(channelA, channelB, this);

    private JPanel tableContainerPanel;
    private ChartPanel chartPanel;
    private JSplitPane mainSplitPane;

    private JButton btnStart, btnStop, btnSnapshot, btnTrigger, btnZoomIn, btnZoomOut, btnResetZoom, btnClear;
    private JButton connectButton;
    private JLabel lblTriggerStatus;
    /** Kleiner Hinweis, der nur bei aktiver Bluetooth-Verbindung neben {@link #lblTriggerStatus}
     *  eingeblendet wird - siehe {@link #updateStatusLabel()} und den Bluetooth-Hinweis in
     *  SensorConfigDialog#refreshSampleRateOptions(). */
    private JLabel lblBluetoothInfo;

    /** Bündelt sämtlichen Zugriff auf die geteilte {@link DeviceConnection} (siehe
     *  {@link ConnectionController}) - hält u. a. den Verbindungsstatus-Listener fest, der beim
     *  Schließen des Fensters wieder abgemeldet werden muss (siehe {@link #dispose()}-Ersatz im
     *  WindowListener). */
    private final ConnectionController connectionController = new ConnectionController(this::updateStatusLabel);

    /** "Sensor konfigurieren..."-Menüeintrag - nur nutzbar, solange eine Verbindung zum ESP32
     *  besteht (siehe {@link #openSensorConfigDialog()} und {@link #updateStatusLabel()}), damit
     *  eine Sensorauswahl nie ins Leere läuft, weil die Firmware sie mangels Verbindung gar nicht
     *  erst mitbekommen könnte. */
    private JMenuItem configSensorItem;

    /** Menüeinträge für die Y-Achsen-Steuerung zur Synchronisation mit automatischen Wechseln. */
    private JRadioButtonMenuItem yAxisShared;
    private JRadioButtonMenuItem yAxisDual;

    /** Menüeinträge für das Fit-Ziel (siehe {@link ChartPanel.FitTarget}) - "Kanal B" und "Beide
     *  Kanäle" ergeben nur mit einem aktiven Sensor auf Kanal B Sinn, siehe {@link #updateTableLayout()}. */
    private JRadioButtonMenuItem fitTargetA;
    private JRadioButtonMenuItem fitTargetB;
    private JRadioButtonMenuItem fitTargetBoth;

    /** Referenz auf ein bereits geöffnetes Terminal-Fenster. */
    private Terminal terminalWindow;

    /** {@code true}, wenn Kanal B (sofern aktiv) über eine eigene, unabhängig skalierte zweite
     *  Y-Achse dargestellt wird, statt sich - wie im Standardfall - dieselbe Achse mit Kanal A zu
     *  teilen (siehe Menü "Ansicht" -&gt; "Y-Achsen" sowie {@link ChartPanel#setDualYAxisMode}).
     *  Wirkt sich nur auf Achsenbeschriftung/-skalierung aus, nicht auf Fit, Zoom oder Chi². */
    private boolean dualYAxisMode = false;
    /** Ob beim letzten Aufruf von {@link #updateTableLayout()} beide Kanäle einen Sensor hatten -
     *  dient nur dazu, den Übergang 1 -&gt; 2 (bzw. 2 -&gt; 1) aktive Sensoren zu erkennen, siehe dort. */
    private boolean previousBothActive = false;

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

    /** Fasst häufige Tabellen-Updates (bis zu 1000/s je Kanal bei hoher Abtastrate) auf eine
     *  feste Bildwiederholrate zusammen, statt Diagramm und Auto-Scroll bei jeder einzelnen neu
     *  eintreffenden Zeile sofort neu zu berechnen. Ohne diese Bündelung liest
     *  {@link #updateChartData()} bei jedem einzelnen Messwert die komplette, bereits
     *  vorhandene Tabelle neu ein - der Aufwand pro Messwert wächst also mit der Zeilenzahl, und
     *  die Oberfläche beginnt nach einigen tausend Zeilen (bei 1000 Hz nach wenigen Sekunden)
     *  spürbar zu ruckeln. Mit der Bündelung bleibt die Bildwiederholrate konstant, unabhängig
     *  von der Abtastrate. */
    private final Timer liveViewRefreshTimer = new Timer(50, e -> flushPendingUiUpdates());
    private volatile boolean chartRefreshPending = false;
    private volatile boolean scrollPendingA = false;
    private volatile boolean scrollPendingB = false;

    public GUI() {
        super("PhyLog");

        Theme.setup();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        loadWindowIcon();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                connectionController.dispose();
                connectionController.disconnect();
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
        connectionController.addLineListener(acquisitionEngine::onLineReceived);

        liveViewRefreshTimer.start();

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

        configSensorItem = new JMenuItem("Sensor konfigurieren...");
        configSensorItem.addActionListener(e -> openSensorConfigDialog());
        configSensorItem.setEnabled(connectionController.isConnected());
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

        // --- Fit-Bezug (auf welche Messgröße(n) sich Fit & Chi² beziehen) ---
        JMenu menuFitTarget = new JMenu("Fit bezieht sich auf");
        ButtonGroup fitTargetGroup = new ButtonGroup();

        fitTargetA = new JRadioButtonMenuItem("Kanal A", true);
        fitTargetA.addActionListener(e -> chartPanel.setFitTarget(ChartPanel.FitTarget.A));
        fitTargetGroup.add(fitTargetA);
        menuFitTarget.add(fitTargetA);

        fitTargetB = new JRadioButtonMenuItem("Kanal B");
        fitTargetB.addActionListener(e -> chartPanel.setFitTarget(ChartPanel.FitTarget.B));
        fitTargetGroup.add(fitTargetB);
        menuFitTarget.add(fitTargetB);

        fitTargetBoth = new JRadioButtonMenuItem("Beide Kanäle (A+B)");
        fitTargetBoth.addActionListener(e -> chartPanel.setFitTarget(ChartPanel.FitTarget.BOTH));
        fitTargetGroup.add(fitTargetBoth);
        menuFitTarget.add(fitTargetBoth);

        JMenuItem itemStdDev = new JMenuItem("Standardabweichung...");
        itemStdDev.addActionListener(e -> openStandardDeviationDialog());

        JLabel portLabel = new JLabel(" COM-Port: ");

        JComboBox<String> portSelector = new JComboBox<>();
        portSelector.setEditable(true);
        portSelector.setMaximumSize(new Dimension(140, 25));

        JButton btnRefreshPorts = new JButton("↻");
        btnRefreshPorts.setToolTipText("Ports aktualisieren");
        btnRefreshPorts.setFocusPainted(false);
        btnRefreshPorts.setMargin(new Insets(2, 4, 2, 4));

        // SerialPort.getCommPorts() (in connectionController.listPortNames()) kann unter Windows
        // mit registrierten Bluetooth-SPP-Ports mehrere Sekunden dauern - synchron auf dem Event-
        // Dispatch-Thread aufgerufen fror das bisher beim Start der GUI (hier) und bei jedem Klick
        // auf "↻" die komplette Oberfläche ein. Jetzt beides über SwingWorker im Hintergrund, ganz
        // wie schon beim eigentlichen Verbindungsaufbau (siehe connectButton weiter unten).
        refreshPortsAsync(portSelector, btnRefreshPorts);
        btnRefreshPorts.addActionListener(e -> refreshPortsAsync(portSelector, btnRefreshPorts));

        JButton btnIdentifyPort = new JButton("🔍");
        btnIdentifyPort.setToolTipText("Passenden COM-Port automatisch finden und verbinden");
        btnIdentifyPort.setFocusPainted(false);
        btnIdentifyPort.setMargin(new Insets(2, 4, 2, 4));
        btnIdentifyPort.addActionListener(e -> identifyPortAsync(portSelector, btnIdentifyPort));

        connectButton = new JButton(connectionController.isConnected() ? "Trennen" : "Verbinden");
        connectButton.setFocusPainted(false);
        connectButton.setMargin(new Insets(2, 8, 2, 8));

        connectButton.addActionListener(e -> {
            if (connectionController.isConnected()) {
                connectButton.setEnabled(false);
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() {
                        connectionController.disconnect();
                        return null;
                    }

                    @Override
                    protected void done() {
                        connectButton.setEnabled(true);
                        // updateStatusLabel() (und damit die Beschriftung) läuft bereits über den
                        // in ConnectionController registrierten Listener, ausgelöst direkt aus
                        // DeviceConnection#disconnect - kein weiterer Aufruf hier nötig.
                    }
                }.execute();
                return;
            }

            Object selectedItem = portSelector.getSelectedItem();
            if (selectedItem == null) return;
            // Anzeigetext kann eine angehängte Beschreibung enthalten (siehe
            // DeviceConnection#listPortNames, z. B. "COM7 (PhyLog Bluetooth)") - connect()
            // erwartet aber den reinen Systemnamen.
            String portName = DeviceConnection.stripDescription(selectedItem.toString().trim());
            if (portName.isEmpty()) return;
            connectToPort(portName);
        });

        menuFit.add(itemNone);
        menuFit.add(menuPoly);
        menuFit.add(itemSinus);
        menuFit.add(itemExp);
        menuFit.addSeparator();
        menuFit.add(menuFitTarget);
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
        menuBar.add(btnIdentifyPort);
        menuBar.add(Box.createRigidArea(new Dimension(5, 0)));
        menuBar.add(connectButton);
        menuBar.add(Box.createRigidArea(new Dimension(15, 0)));

        setJMenuBar(menuBar);
    }

    /**
     * Baut die Verbindung zu {@code portName} auf - im Hintergrund, damit ein besonders bei
     * Bluetooth-SPP-Ports langsames {@code openPort()} (siehe {@link DeviceConnection#connect})
     * nicht den Event-Dispatch-Thread blockiert. Gemeinsam genutzt von {@link #connectButton}
     * (manuelle Auswahl) und {@link #identifyPortAsync} (automatisch gefundener Port) - so verhält
     * sich ein per Portsuche gefundener Port beim Verbinden exakt wie ein manuell ausgewählter,
     * ohne zweiten Klick auf "Verbinden".
     */
    private void connectToPort(String portName) {
        connectButton.setEnabled(false);
        connectButton.setText("Verbinde...");
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return connectionController.connect(portName, BAUD_RATE);
            }

            @Override
            protected void done() {
                connectButton.setEnabled(true);
                boolean success;
                try {
                    success = get();
                } catch (Exception ex) {
                    success = false;
                }
                if (!success) {
                    connectButton.setText("Verbinden");
                    // Falls der Aufruf über die Portsuche kam, stand hier noch "Suche nach
                    // passendem Port …" (siehe identifyPortAsync) - zurücksetzen, sonst bliebe das
                    // nach einem fehlgeschlagenen Verbindungsversuch stehen.
                    updateStatusLabel();
                    JOptionPane.showMessageDialog(GUI.this,
                            "Verbindung zu " + portName + " fehlgeschlagen.",
                            "Verbindungsfehler",
                            JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(GUI.this,
                            "Verbunden mit " + portName + ".",
                            "Verbunden",
                            JOptionPane.INFORMATION_MESSAGE);
                }
                // Bei Erfolg setzt updateStatusLabel() (über den ConnectionController-Listener,
                // ausgelöst direkt aus DeviceConnection#connect) den Text auf "Trennen" - kein
                // weiterer Aufruf hier nötig.
            }
        }.execute();
    }

    /** Füllt {@code portSelector} im Hintergrund neu, ohne den Event-Dispatch-Thread zu blockieren
     *  (siehe Kommentar an der Aufrufstelle). {@code button} bleibt währenddessen deaktiviert,
     *  damit kein zweiter Refresh dazwischenfunkt, solange der erste noch läuft. */
    private void refreshPortsAsync(JComboBox<String> portSelector, JButton button) {
        button.setEnabled(false);
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return connectionController.listPortNames();
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    Object previouslySelected = portSelector.getSelectedItem();
                    portSelector.removeAllItems();
                    for (String name : get()) {
                        portSelector.addItem(name);
                    }
                    if (previouslySelected != null) {
                        portSelector.setSelectedItem(previouslySelected);
                    }
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    /**
     * Startet {@link ConnectionController#identifyPhyLogPort} im Hintergrund und verbindet bei
     * Erfolg direkt mit dem gefundenen Port (über {@link #connectToPort}) - kein zweiter Klick auf
     * "Verbinden" nötig. Läuft nicht, solange bereits eine Verbindung aktiv ist (siehe Warnhinweis
     * in {@link DeviceConnection#identifyPhyLogPort}).
     *
     * <p>Die Kandidatenliste kommt bereits priorisiert aus {@link ConnectionController#orderedIdentifyCandidates()}:
     * zuerst als "PhyLog Seriell" erkannte USB-Ports, danach "PhyLog Bluetooth", zuletzt alle
     * übrigen - im Normalfall (genau ein PhyLog-Gerät gepairt/verbunden) meist nur ein einziger
     * Probe-Versuch statt potenziell vieler mit je bis zu {@code IDENTIFY_TIMEOUT_MS} Wartezeit,
     * und bei gleichzeitig per USB und Bluetooth erreichbarem Board bevorzugt die schnellere,
     * ohne Verbindungsaufbau-Verzögerung antwortende serielle Verbindung.</p>
     */
    private void identifyPortAsync(JComboBox<String> portSelector, JButton button) {
        if (connectionController.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte zuerst trennen, bevor die Portsuche startet.",
                    "Noch verbunden", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> candidates = connectionController.orderedIdentifyCandidates();
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Keine Ports gefunden.",
                    "Portsuche", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        button.setEnabled(false);
        button.setToolTipText("Suche läuft (probiert jeden Port kurz per PING aus)...");
        // Zusätzlich zum Tooltip auch im Status-Label sichtbar, das sonst Verbindungs-/Trigger-
        // status zeigt (siehe updateStatusLabel()) - der Tooltip allein fällt leicht nicht auf,
        // gerade weil die Suche je nach Kandidatenzahl mehrere Sekunden dauern kann.
        if (lblTriggerStatus != null) {
            lblTriggerStatus.setText("Suche nach passendem Port …");
            lblTriggerStatus.setForeground(Theme.WARNING);
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return connectionController.identifyPhyLogPort(candidates);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                button.setToolTipText("Passenden COM-Port automatisch finden und verbinden");
                String found;
                try {
                    found = get();
                } catch (Exception ex) {
                    found = null;
                }
                if (found == null) {
                    // Kein Treffer - Status-Label wieder auf den normalen "Nicht verbunden"-Zustand
                    // zurücksetzen, sonst bliebe "Suche nach passendem Port …" stehen.
                    updateStatusLabel();
                    JOptionPane.showMessageDialog(GUI.this,
                            "Kein Port hat mit #HELLO geantwortet. ESP32 eingeschaltet und in Reichweite/gepairt?",
                            "Nichts gefunden", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                for (int i = 0; i < portSelector.getItemCount(); i++) {
                    String item = portSelector.getItemAt(i);
                    if (DeviceConnection.stripDescription(item).equals(found)) {
                        portSelector.setSelectedItem(item);
                        break;
                    }
                }
                connectToPort(found);
            }
        }.execute();
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

        btnSnapshot = new JButton("Momentaufnahme");
        btnSnapshot.setToolTipText("Aktuellen Messwert als einzelne Zeile (Index statt Zeit) übernehmen");
        btnSnapshot.addActionListener(e -> captureSnapshot());
        btnSnapshot.setEnabled(false);

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
        toolBar.add(btnSnapshot);
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

        // Kleines "i" für den Bluetooth-Bandbreiten-Hinweis - per Text statt Icon-Datei, damit
        // kein zusätzliches Ressourcen-/Bildladen nötig ist (siehe auch die vorhandene
        // try/catch-Fallback-Logik fürs Anwendungsicon an anderer Stelle in dieser Klasse). Per
        // Default unsichtbar, siehe updateStatusLabel() - taucht nur bei tatsächlich aktiver
        // Bluetooth-Verbindung auf.
        lblBluetoothInfo = new JLabel(" \u24D8");
        lblBluetoothInfo.setForeground(Theme.MUTED);
        lblBluetoothInfo.setFont(lblBluetoothInfo.getFont().deriveFont(Font.BOLD));
        lblBluetoothInfo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String bluetoothHint = "<html><div style='width:280px;'>Per Bluetooth steht weniger "
                + "Bandbreite zur Verfügung als über USB - die Abtastrate ist deshalb auf "
                + DeviceConnection.BLUETOOTH_MAX_SAMPLE_RATE_HZ + " Hz begrenzt (siehe Sensoren-"
                + "Dialog). Für die volle Auflösung/Abtastrate stattdessen per USB verbinden.</div></html>";
        lblBluetoothInfo.setToolTipText(bluetoothHint);
        lblBluetoothInfo.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // Zusätzlich zum Tooltip auch per Klick anzeigen - ein Tooltip allein wird leicht
                // übersehen bzw. ist z. B. bei Touch-/Trackpad-Bedienung ohne echtes Hover gar
                // nicht erreichbar.
                JOptionPane.showMessageDialog(GUI.this, bluetoothHint, "Bluetooth-Verbindung", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        lblBluetoothInfo.setVisible(false);
        toolBar.add(lblBluetoothInfo);

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
        chartPanel.setBorder(Theme.titledPanelBorder("Diagramm"));

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableContainerPanel, chartPanel);
        mainSplitPane.setResizeWeight(0.25);

        add(mainSplitPane, BorderLayout.CENTER);
    }

    private void initChannelTable(MeasurementChannel ch) {
        ch.tableModel = createTableModel();
        ch.table = new JTable(ch.tableModel);
        ch.table.setFillsViewportHeight(true);
        ch.tableModel.addTableModelListener(e -> {
            chartRefreshPending = true;
            if (e.getType() == TableModelEvent.INSERT) {
                if (ch.id == 'B') scrollPendingB = true; else scrollPendingA = true;
            }
        });
        ch.scrollPane = new JScrollPane(ch.table);
    }

    /** Vom {@link #liveViewRefreshTimer} aufgerufen: führt alle seit dem letzten Tick
     *  angefallenen Oberflächen-Updates gebündelt aus, statt jedes für sich sofort bei jeder
     *  einzelnen Tabellenänderung. */
    private void flushPendingUiUpdates() {
        if (chartRefreshPending) {
            chartRefreshPending = false;
            updateChartData();
        }
        if (scrollPendingA) {
            scrollPendingA = false;
            scrollToLastRow(channelA);
        }
        if (scrollPendingB) {
            scrollPendingB = false;
            scrollToLastRow(channelB);
        }
    }

    private void scrollToLastRow(MeasurementChannel ch) {
        int lastRow = ch.table.getRowCount() - 1;
        if (lastRow >= 0) {
            ch.table.scrollRectToVisible(ch.table.getCellRect(lastRow, 0, true));
        }
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

        // Wird beim Einlesen ein Sensor aus der Kopfzeile erkannt, aktualisiert bereits dessen
        // Callback über updateTableLayout() das Diagramm (siehe dort, ruft am Ende immer entweder
        // renderSpectrumChart() oder updateChartData() auf) - der Flag verhindert in diesem Fall
        // den sonst doppelten updateChartData()-Aufruf danach. Ohne erkannten Sensor (Callback
        // feuert nie) bleibt der Aufruf am Ende die einzige Stelle, die das Diagramm überhaupt
        // aktualisiert, und darf daher nicht ersatzlos entfallen.
        boolean[] sensorDetected = {false};

        try {
            int columnCount = channelA.tableModel.getColumnCount();
            DataFileService.readCsv(file, columnCount,
                    row -> channelA.tableModel.addRow(row),
                    detectedSensor -> {
                        sensorDetected[0] = true;
                        channelA.sensor = detectedSensor;
                        updateTableLayout();
                    });
            if (!sensorDetected[0]) {
                updateChartData();
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Lesen der Datei: " + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openSensorConfigDialog() {
        if (!connectionController.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte zuerst über den COM-Port oben rechts mit dem ESP32 verbinden.",
                    "Keine Verbindung", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Sensor previousA = channelA.sensor;
        Sensor previousB = channelB.sensor;

        // Kein manuelles Start/Stop der Live-Daten mehr nötig: Das Gerät streamt seit dem
        // Verbindungsaufbau bereits durchgehend (siehe DeviceConnection#connect), unabhängig
        // davon, ob gerade eine Aufzeichnung läuft - channelA/B.latestValue ist also immer
        // aktuell, auch für diesen Dialog.
        SensorConfigDialog dialog = new SensorConfigDialog(this, channelA.sensor, channelB.sensor,
                acquisitionEngine.getSampleRateHz(),
                () -> channelA.latestValue, () -> channelB.latestValue,
                this::pushSensorSelectionToFirmware, this::onTareRequested);
        dialog.setVisible(true);

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

    private void pushSensorSelectionToFirmware(char channelId, Sensor sensor) {
        MeasurementChannel ch = channel(channelId);
        if (ch.sensor != sensor) ch.tareOffset = 0.0;
        ch.sensor = sensor;

        if (!connectionController.isConnected()) {
            return;
        }
        connectionController.sendLine("SET," + channelId + "," + sensor.getFirmwareTypeName());
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
        if (!connectionController.isConnected()) {
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
        lblTriggerStatus.setForeground(Theme.WARNING);
    }

    /** {@inheritDoc} Im Gegensatz zu {@link #onDurationLimitReached()} kein regulär erreichtes
     *  Limit, sondern ein Verbindungsabbruch (z. B. abgezogenes Kabel) - deshalb eigene,
     *  unmissverständliche Meldung statt einer stillschweigend beendeten Aufzeichnung. */
    @Override
    public void onConnectionLostDuringRecording() {
        lblTriggerStatus.setText("Verbindung verloren - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.DANGER);
    }

    /** {@inheritDoc} Anders als {@link #onConnectionLostDuringRecording()} bleibt die serielle
     *  Verbindung selbst bestehen - die Firmware konnte nur den Sensor auf einem Kanal wiederholt
     *  nicht auslesen (z. B. abgezogener I2C-Sensor oder HX711-Timeout, siehe
     *  {@code reportSensorError} in phylog_firmware.ino). Eigene Meldung inkl. Kanal und
     *  Fehlerart, damit klar ist, welcher Sensor betroffen ist. */
    @Override
    public void onSensorErrorDuringRecording(char channelId, String errorTag) {
        lblTriggerStatus.setText("Sensorfehler Kanal " + channelId + " (" + errorTag + ") - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.DANGER);
    }

    /** {@inheritDoc} Übernimmt für jeden Kanal mit Spektrum-Sensor das zuletzt empfangene
     *  Spektrum als Momentaufnahme in dessen Tabelle - die live im Diagramm laufende Anzeige
     *  liefert sonst nirgendwo Zeilen, die sich z. B. per CSV exportieren ließen (siehe
     *  {@link #exportCsv}, der ausschließlich aus den Tabellen liest). */
    @Override
    public void onRecordingStopped() {
        importSpectrumIntoTable(channelA, lastSpectrumA, lastSpectrumRateA);
        importSpectrumIntoTable(channelB, lastSpectrumB, lastSpectrumRateB);
    }

    /** Schreibt ein Spektrum als (Frequenz, dB)-Zeilen in die Tabelle des Kanals - ersetzt einen
     *  eventuell vorher enthaltenen älteren Snapshot. Tut nichts, wenn der Kanal aktuell keinen
     *  Spektrum-Sensor hat oder noch kein Spektrum empfangen wurde. */
    private void importSpectrumIntoTable(MeasurementChannel ch, double[] magnitudesDb, int sampleRateHz) {
        if (!ch.producesSpectrum() || magnitudesDb == null) return;

        ch.tableModel.setRowCount(0);
        for (double[] point : toFrequencyPoints(magnitudesDb, sampleRateHz)) {
            ch.tableModel.addRow(new Object[]{point[0], point[1]});
        }
    }

    /** {@inheritDoc} Speichert das neue Spektrum für den jeweiligen Kanal und zeichnet es direkt
     *  ins {@link ChartPanel} - unabhängig von Tabelle/Zeitachse, siehe {@link #renderSpectrumChart()}.
     *  Wird nur während einer laufenden Aufzeichnung aufgerufen ({@link AcquisitionEngine} filtert
     *  das bereits vor der Weitergabe an den Listener), das Diagramm bleibt nach "Stopp" also auf
     *  dem letzten während der Aufzeichnung empfangenen Spektrum stehen - analog zu den Tabellen
     *  normaler Kanäle, die ebenfalls nur während der Aufzeichnung neue Zeilen bekommen. */
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
        boolean connected = connectionController.isConnected();

        if (connectButton != null) {
            connectButton.setText(connected ? "Trennen" : "Verbinden");
        }

        if (configSensorItem != null) {
            configSensorItem.setEnabled(connected);
        }

        updateActionAvailability(connected);

        if (lblBluetoothInfo != null) {
            lblBluetoothInfo.setVisible(connected && connectionController.isBluetoothConnection());
        }

        if (lblTriggerStatus == null) return;

        TriggerDialog.Config triggerConfig = acquisitionEngine.getTriggerConfig();

        if (!connected) {
            lblTriggerStatus.setText("Nicht verbunden");
            lblTriggerStatus.setForeground(Theme.MUTED);
        } else if (acquisitionEngine.isWaitingForTrigger()) {
            lblTriggerStatus.setText("Warte auf Trigger (Kanal " + triggerConfig.channel + ") …");
            lblTriggerStatus.setForeground(Theme.WARNING);
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
        MeasurementChannel triggerChannel = channel(triggerConfig.channel);
        // Ein Spektrum-Kanal sendet nie die für den Schwellenwert-Trigger nötigen Einzelwerte
        // (D-Pakete) - der Trigger würde dort schlicht nie feuern.
        boolean triggerChannelReady = !triggerConfig.thresholdMode
                || (triggerChannel.hasSensor() && !triggerChannel.producesSpectrum());
        boolean alreadyRunning = acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger();

        boolean canStart = connected && hasAnySensor && triggerChannelReady && !alreadyRunning;
        btnStart.setEnabled(canStart);
        btnStart.setToolTipText(startButtonTooltip(connected, hasAnySensor, triggerChannelReady, alreadyRunning, triggerConfig));

        boolean canStop = alreadyRunning;
        btnStop.setEnabled(canStop);
        btnStop.setToolTipText(canStop ? "Laufende Messung stoppen" : "Es läuft aktuell keine Messung.");

        // Eine Momentaufnahme braucht zwar selbst nur einen aktuellen Live-Wert, keinen Zeitbezug
        // (siehe AcquisitionEngine#captureSnapshot) - sie teilt sich aber Tabelle und Spaltenkopf
        // mit einer zeitbasierten Aufzeichnung. Der nötige Wechsel der Kopfzeile auf "Index" leert
        // dabei die Tabelle (siehe #prepareSnapshotHeader), was während einer laufenden Aufzeichnung
        // bzw. während auf den Trigger gewartet wird bereits vorhandene Messwerte zerstören würde -
        // und AcquisitionEngine#ingestSample kennt snapshotMode nicht, würde also anschließend
        // weiterhin zeitbasierte Zeilen unter der jetzt falschen "Index"-Überschrift anhängen.
        // Deshalb hier zusätzlich zur Sensor-Prüfung von alreadyRunning abhängig.
        boolean hasSnapshotableSensor = (channelA.hasSensor() && !channelA.producesSpectrum())
                || (channelB.hasSensor() && !channelB.producesSpectrum());
        boolean canSnapshot = connected && hasSnapshotableSensor && !alreadyRunning;
        btnSnapshot.setEnabled(canSnapshot);
        btnSnapshot.setToolTipText(canSnapshot
                ? "Aktuellen Messwert als einzelne Zeile (Index statt Zeit) übernehmen"
                : (alreadyRunning
                ? "Während einer laufenden Aufzeichnung nicht möglich (würde deren Daten löschen)."
                : "Erst mit dem ESP32 verbinden und einen (nicht-spektralen) Sensor auswählen."));

        // Beides ergibt im Frequenzspektrum-Modus keinen Sinn: die Tabelle bleibt dort ohnehin
        // leer (siehe channelTitle()), und ein Trigger kann auf einem Spektrum-Kanal nicht
        // feuern (siehe triggerChannelReady oben) - klar ausgegraut statt stillschweigend wirkungslos.
        boolean spectrumMode = channelA.producesSpectrum() || channelB.producesSpectrum();

        if (btnClear != null) {
            btnClear.setEnabled(!spectrumMode);
            btnClear.setToolTipText(spectrumMode
                    ? "Im Frequenzspektrum-Modus gibt es keine Tabellendaten zum Leeren."
                    : "Aufgezeichnete Werte löschen");
        }

        if (btnTrigger != null) {
            btnTrigger.setEnabled(!spectrumMode);
            btnTrigger.setToolTipText(spectrumMode
                    ? "Trigger funktioniert nicht mit einem Frequenzspektrum-Kanal."
                    : "Trigger-Bedingung für den Messstart festlegen");
        }
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

    /** Übernimmt für jeden Kanal mit nicht-spektralem Sensor den aktuellen Live-Wert als
     *  einzelne Tabellenzeile (Index statt Zeit, siehe {@link AcquisitionEngine#captureSnapshot()}).
     *  Schaltet die Spaltenüberschrift dafür bei Bedarf zuerst auf "Index" um - vor dem
     *  eigentlichen Einfügen, damit die neue Zeile dabei nicht gleich wieder mitgelöscht wird.
     *  Bricht während einer laufenden Aufzeichnung bzw. während auf den Trigger gewartet wird
     *  früh ab, statt Altdaten zu löschen (siehe {@link #prepareSnapshotHeader}) - eigentlich
     *  bereits durch das Deaktivieren von {@link #btnSnapshot} in {@link #updateActionAvailability}
     *  verhindert, hier zusätzlich als zweite Absicherung, falls der Aufruf je auf anderem Weg
     *  (z. B. Tastenkürzel) erfolgt. */
    private void captureSnapshot() {
        if (acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger()) return;

        prepareSnapshotHeader(channelA);
        prepareSnapshotHeader(channelB);
        acquisitionEngine.captureSnapshot();

        lblTriggerStatus.setText("Momentaufnahme aufgenommen");
        lblTriggerStatus.setForeground(Theme.ACCENT);
    }

    /** Schaltet die Spalte auf "Index" um, falls der Kanal das noch nicht ist - eine dabei
     *  eventuell noch vorhandene, zeitbasierte Aufzeichnung wird dabei verworfen, damit Index und
     *  Zeit nicht in derselben Spalte gemischt werden. Ist der Kanal bereits im Momentaufnahme-
     *  Modus, passiert nichts, damit bereits aufgenommene Momentaufnahmen erhalten bleiben. */
    private void prepareSnapshotHeader(MeasurementChannel ch) {
        if (!ch.hasSensor() || ch.producesSpectrum() || ch.snapshotMode) return;
        ch.snapshotMode = true;
        configureTableModel(ch);
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

        // Zwei unabhängige Y-Achsen ergeben nur mit zwei aktiven Sensoren Sinn. Bewusst nur bei
        // einem tatsächlichen Wechsel (1 <-> 2 aktive Sensoren) automatisch umgeschaltet, nicht
        // bei jedem Aufruf erneut erzwungen - sonst würde eine bewusste manuelle Wahl (z. B.
        // "eine gemeinsame Achse" trotz zwei Sensoren) beim nächsten Öffnen des Sensor-Dialogs
        // wieder verworfen, auch wenn sich gar nichts geändert hat.
        boolean bothActive = channelA.hasSensor() && channelB.hasSensor();
        if (bothActive && !previousBothActive) {
            setDualYAxisMode(true); // zweiter Sensor gerade aktiv geworden - sinnvoller Vorschlag
        } else if (!bothActive && dualYAxisMode) {
            setDualYAxisMode(false); // einer wurde deaktiviert - zwei Achsen ergeben keinen Sinn mehr
        }
        previousBothActive = bothActive;

        // Muss bei jedem Layout-Update laufen, nicht nur beim 1<->2-Sensor-Übergang oben (der
        // setDualYAxisMode() und darüber indirekt auch dies aufruft) - sonst behält das Diagramm
        // z. B. beim Wechsel auf einen Spektrum-Sensor die vorherigen Achsentitel/-einheiten und
        // die farbige Magnitude-Einfärbung (siehe #updateChartUnits) bei.
        updateChartUnits();

        yAxisDual.setEnabled(bothActive);
        yAxisDual.setToolTipText(bothActive ? null : "Nur verfügbar, wenn auf beiden Kanälen ein Sensor aktiv ist.");

        // "Kanal B" bzw. "Beide Kanäle" als Fit-Ziel ergeben nur mit einem aktiven Sensor auf
        // Kanal B Sinn - analog zu yAxisDual oben. War eine der beiden gerade ausgewählt und
        // Kanal B fällt weg, wird bewusst auf "Kanal A" zurückgeschaltet, statt stillschweigend
        // mit einem inzwischen leeren Datensatz weiterzufitten.
        boolean hasBSensor = channelB.hasSensor();
        fitTargetB.setEnabled(hasBSensor);
        fitTargetBoth.setEnabled(hasBSensor);
        String noBTooltip = hasBSensor ? null : "Nur verfügbar, wenn auf Kanal B ein Sensor aktiv ist.";
        fitTargetB.setToolTipText(noBTooltip);
        fitTargetBoth.setToolTipText(noBTooltip);
        if (!hasBSensor && (fitTargetB.isSelected() || fitTargetBoth.isSelected())) {
            fitTargetA.setSelected(true);
            chartPanel.setFitTarget(ChartPanel.FitTarget.A);
        }

        // Nur die Tabelle(n) der Kanäle anzeigen, die tatsächlich einen Sensor haben - bisher
        // wurde die Split-Ansicht schon gezeigt, sobald Kanal B einen Sensor hatte, unabhängig
        // davon, ob Kanal A überhaupt aktiv war (Bug: bei nur Kanal B erschienen trotzdem beide
        // Tabellen, die von A leer/mit "-- Kein Sensor --"). Ist gar kein Sensor aktiv, bleibt
        // Kanal A als Platzhalter sichtbar (bisheriges Verhalten) - für diesen Fall verweigert
        // {@link #startMeasurement} ohnehin das Starten.
        boolean hasA = channelA.hasSensor();
        boolean hasB = channelB.hasSensor();

        if (hasA) {
            channelA.scrollPane.setBorder(Theme.titledPanelBorder(channelTitle('A', channelA)));
        }
        if (hasB) {
            channelB.scrollPane.setBorder(Theme.titledPanelBorder(channelTitle('B', channelB)));
        }

        if (hasA && hasB) {
            JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, channelA.scrollPane, channelB.scrollPane);
            verticalSplit.setResizeWeight(0.5);
            tableContainerPanel.add(verticalSplit, BorderLayout.CENTER);
        } else if (hasB) {
            tableContainerPanel.add(channelB.scrollPane, BorderLayout.CENTER);
        } else {
            // Weder A noch B aktiv: Kanal A bleibt als Platzhalter sichtbar (bisheriges Verhalten
            // ohne Sensorauswahl); ist nur A aktiv, ist es ohnehin die richtige Tabelle.
            if (!hasA) {
                channelA.scrollPane.setBorder(Theme.titledPanelBorder(channelTitle('A', channelA)));
            }
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
     *  hin, dass die Anzeige während der Aufnahme im Diagramm läuft und die Tabelle erst nach
     *  dem Stoppen das letzte Spektrum als Momentaufnahme erhält (siehe {@link #onRecordingStopped}). */
    private String channelTitle(char id, MeasurementChannel ch) {
        String base = "Sensor " + id + ": " + ch.sensor.getName();
        return ch.producesSpectrum() ? base + " - live im Diagramm, letztes Bild nach Stopp hier" : base;
    }

    /** Setzt die Spaltenköpfe der Tabelle passend zum aktuellen Sensor. Löscht bestehende Zeilen
     *  bewusst nur, wenn sich die Spaltenköpfe dadurch tatsächlich ändern - sonst würde ein
     *  gerade erst nach {@link #onRecordingStopped} importiertes Spektrum bei jedem erneuten
     *  Aufruf (z. B. weil im Sensor-Dialog nur der jeweils andere Kanal geändert wurde) sofort
     *  wieder verworfen. */
    private void configureTableModel(MeasurementChannel ch) {
        List<Sensor.Quantity> quantities = ch.sensor.getQuantities();
        String yHeader = quantities.isEmpty() ? "Messwert" : quantities.getFirst().getColumnHeader();
        String xHeader = ch.producesSpectrum() ? "Frequenz (Hz)" : (ch.snapshotMode ? "Index" : "Zeit (s)");

        boolean columnsChanged = ch.tableModel.getColumnCount() != 2
                || !xHeader.equals(ch.tableModel.getColumnName(0))
                || !yHeader.equals(ch.tableModel.getColumnName(1));

        if (columnsChanged) {
            ch.tableModel.setRowCount(0);
            ch.tableModel.setColumnIdentifiers(new Object[]{xHeader, yHeader});
        }
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
            chartPanel.setColorByMagnitude(true);
            return;
        }

        chartPanel.setColorByMagnitude(false);
        chartPanel.setXAxisTitle("Zeit");

        boolean hasA = channelA.hasSensor();
        boolean hasB = channelB.hasSensor();

        if (!hasA && hasB) {
            // Nur Kanal B aktiv: dessen konkrete Messgröße wird direkt zum Achsentitel - der
            // generische "Messwerte"-Titel (siehe unten) ist nur nötig, um Platz für zwei
            // gleichzeitig dargestellte Größen zu lassen, deren jeweilige Bezeichnung sonst über
            // die Legende läuft. Mit nur einer Größe ist eine Legende dafür redundant (siehe auch
            // {@link ChartPanel#legendVisible()}) - die Bezeichnung gehört direkt an die Achse.
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String axisLabel = quantitiesB.isEmpty() ? "Messwert" : quantitiesB.get(0).getColumnHeader();
            chartPanel.setUnits("s", axisLabel);
            chartPanel.setMainLabel(quantitiesB.isEmpty() ? "Kanal B" : "Kanal B: " + quantitiesB.get(0).getColumnHeader());
            return;
        }

        List<Sensor.Quantity> quantitiesA = channelA.sensor.getQuantities();
        boolean useSpecificAxisLabels = dualYAxisMode && hasB;

        String axisLabel = (hasB && !useSpecificAxisLabels)
                ? "Messwerte"
                : (quantitiesA.isEmpty() ? "Messwert" : quantitiesA.get(0).getColumnHeader());
        chartPanel.setUnits("s", axisLabel);

        String labelA = quantitiesA.isEmpty() ? "Kanal A" : "Kanal A: " + quantitiesA.get(0).getColumnHeader();
        chartPanel.setMainLabel(labelA);

        if (hasB) {
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
        channelA.snapshotMode = false;
        channelB.snapshotMode = false;
        configureTableModel(channelA);
        configureTableModel(channelB);
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