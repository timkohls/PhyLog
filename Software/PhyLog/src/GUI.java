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
import java.util.Map;

/**
 * Hauptfenster von PhyLog: Menüleiste, Werkzeugleiste, Messwerttabellen für Kanal A/B sowie
 * das {@link ChartPanel}. Verdrahtet die UI mit {@link AcquisitionEngine} (Datenaufnahme) und
 * {@link DataFileService} (CSV-/PNG-Export) und hält den pro Kanal nötigen UI-Zustand
 * ({@link MeasurementChannel}).
 */
public class GUI extends JFrame implements AcquisitionEngine.Listener {
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    /** Muss zur Firmware passen (siehe BAUD_RATE in phylog_firmware.ino). */
    private static final int BAUD_RATE = 460800;

    private final MeasurementChannel channelA = new MeasurementChannel('A');
    private final MeasurementChannel channelB = new MeasurementChannel('B');
    private final AcquisitionEngine acquisitionEngine = new AcquisitionEngine(channelA, channelB, this);

    private JPanel tableContainerPanel;
    private ChartPanel chartPanel;
    private JSplitPane mainSplitPane;

    private JButton btnStart;
    private JButton btnStop;
    private JButton btnSnapshot;
    private JButton btnTrigger;
    private JButton btnClear;
    private JButton connectButton;
    private JLabel lblTriggerStatus;

    /** Bandbreiten-Hinweis, nur sichtbar bei aktiver Bluetooth-Verbindung (siehe {@link #updateStatusLabel()}). */
    private JLabel lblBluetoothInfo;

    /** Zugriff auf die geteilte {@link DeviceConnection}, inkl. Verbindungsstatus-Listener. */
    private final ConnectionController connectionController = new ConnectionController(this::onConnectionStatusChanged);

    /** Nur aktivierbar, solange eine Verbindung zum ESP32 besteht (siehe {@link #updateStatusLabel()}). */
    private JMenuItem configSensorItem;

    private JRadioButtonMenuItem yAxisShared;
    private JRadioButtonMenuItem yAxisDual;

    private JRadioButtonMenuItem fitTargetA;
    private JRadioButtonMenuItem fitTargetB;
    private JRadioButtonMenuItem fitTargetBoth;

    private Terminal terminalWindow;

    /** {@code true}, wenn Kanal B eine eigene, unabhängig skalierte Y-Achse bekommt statt sich
     *  die Achse mit Kanal A zu teilen (siehe {@link ChartPanel#setDualYAxisMode}). */
    private boolean dualYAxisMode = false;

    /** Ob beim letzten {@link #updateTableLayout()} beide Kanäle einen Sensor hatten - erkennt
     *  den Übergang 1 &lt;-&gt; 2 aktive Sensoren. */
    private boolean previousBothActive = false;

    /** {@code true}, solange das Diagramm im Frequenzspektrum-Modus zeigt - dient dem Erkennen
     *  eines Wechsels zwischen Zeit- und Frequenzachse, um den Zoom zurückzusetzen. */
    private boolean spectrumModeActive = false;

    /** Zuletzt empfangenes Spektrum je Kanal (dB je Bin), {@code null} ohne aktiven Spektrum-Sensor. */
    private double[] lastSpectrumA, lastSpectrumB;
    private int lastSpectrumRateA = 16000, lastSpectrumRateB = 16000;

    /** Bündelt häufige Tabellen-Updates auf eine feste Bildwiederholrate statt Diagramm und
     *  Auto-Scroll bei jeder einzelnen Zeile neu zu berechnen (siehe {@link #flushPendingUiUpdates()}). */
    private final Timer liveViewRefreshTimer = new Timer(50, _ -> flushPendingUiUpdates());
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

        updateStatusLabel();

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
        return acquisitionEngine.channel(id);
    }

    /** Lädt das Fenster-Icon über den Klassenpfad, damit es auch aus einem gepackten JAR
     *  heraus gefunden wird (nicht nur beim Start aus der IDE mit Projektordner als CWD). */
    private void loadWindowIcon() {
        java.net.URL iconUrl = getClass().getResource("/assets/icon.png");
        if (iconUrl == null) {
            System.err.println("Warnung: Fenster-Icon nicht gefunden (Klassenpfad-Ressource /assets/icon.png fehlt - "
                    + "liegt icon.png im Ressourcenverzeichnis, z. B. src/main/resources/assets/icon.png?)");
            return;
        }
        setIconImage(new ImageIcon(iconUrl).getImage());
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // --- Datei-Menü ---
        JMenu menuFile = new JMenu("Datei");

        JMenuItem openItem = new JMenuItem("Öffnen...");
        openItem.addActionListener(_ -> openCsv());
        menuFile.add(openItem);

        menuFile.addSeparator();

        JMenuItem exportCsvItem = new JMenuItem("Als CSV exportieren...");
        exportCsvItem.addActionListener(_ -> exportCsv());
        menuFile.add(exportCsvItem);

        JMenuItem exportPngItem = new JMenuItem("Diagramm als PNG exportieren...");
        exportPngItem.addActionListener(_ -> exportPng());
        menuFile.add(exportPngItem);

        menuFile.addSeparator();
        JMenuItem exitItem = new JMenuItem("Beenden");
        exitItem.addActionListener(_ -> System.exit(0));
        menuFile.add(exitItem);

        // --- Sensor-Menü ---
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
        configSensorItem.addActionListener(_ -> openSensorConfigDialog());
        configSensorItem.setEnabled(connectionController.isConnected());
        configSensorItem.setToolTipText("Erst mit dem ESP32 verbinden, dann Sensoren auswählen.");
        menuSensor.add(configSensorItem);

        // --- Terminal-Menü ---
        JMenu menuTerminal = new JMenu("Terminal");

        JMenuItem terminalItem = new JMenuItem("Terminal öffnen...");
        terminalItem.addActionListener(_ -> openTerminal());
        menuTerminal.add(terminalItem);

        // --- Ansicht-Menü ---
        JMenu menuView = new JMenu("Ansicht");

        JMenuItem resetLayoutItem = new JMenuItem("Ansicht zurücksetzen");
        resetLayoutItem.addActionListener(_ -> resetLayout());
        menuView.add(resetLayoutItem);

        JCheckBoxMenuItem showPoints = new JCheckBoxMenuItem("Messpunkte anzeigen", true);
        showPoints.addActionListener(_ -> chartPanel.setShowPoints(showPoints.isSelected()));

        JMenu menuLineMode = new JMenu("Linienart");
        Theme.radioMenuGroup(menuLineMode, 0,
                Map.entry("Keine Linie", _ -> chartPanel.setLineMode(ChartPanel.LineMode.NONE)),
                Map.entry("Verbindungslinie (gerade)", _ -> chartPanel.setLineMode(ChartPanel.LineMode.STRAIGHT)),
                Map.entry("Spline (glatt)", _ -> chartPanel.setLineMode(ChartPanel.LineMode.SPLINE)));

        menuView.addSeparator();
        menuView.add(showPoints);
        menuView.add(menuLineMode);

        // --- Y-Achsen-Untermenü ---
        JMenu menuYAxis = new JMenu("Y-Achsen");
        JRadioButtonMenuItem[] yAxisItems = Theme.radioMenuGroup(menuYAxis, 0,
                Map.entry("Eine gemeinsame Y-Achse", _ -> setDualYAxisMode(false)),
                Map.entry("Zwei unabhängige Y-Achsen (je Kanal skaliert)", _ -> setDualYAxisMode(true)));
        yAxisShared = yAxisItems[0];
        yAxisDual = yAxisItems[1];

        menuView.addSeparator();
        menuView.add(menuYAxis);

        // --- Funktions-Fit-Menü ---
        JMenu menuFit = new JMenu("Funktions-Fit");
        ButtonGroup fitButtonGroup = new ButtonGroup();

        JRadioButtonMenuItem itemNone = new JRadioButtonMenuItem("Kein Fit", true);
        itemNone.addActionListener(_ -> chartPanel.setFitMode(ChartPanel.FitMode.NONE));
        fitButtonGroup.add(itemNone);

        JMenu menuPoly = new JMenu("Polynomfunktion");

        JRadioButtonMenuItem itemDeg1 = new JRadioButtonMenuItem("Grad 1 (Lineare Funktion)");
        itemDeg1.addActionListener(_ -> chartPanel.setFitMode(ChartPanel.FitMode.LINEAR));
        fitButtonGroup.add(itemDeg1);
        menuPoly.add(itemDeg1);

        for (int degree = 2; degree <= 6; degree++) {
            int deg = degree;
            String label = (deg == 2) ? "Grad 2 (Parabel)" : "Grad " + deg;
            JRadioButtonMenuItem itemDeg = new JRadioButtonMenuItem(label);
            itemDeg.addActionListener(_ -> {
                chartPanel.setPolynomialDegree(deg);
                chartPanel.setFitMode(ChartPanel.FitMode.POLYNOMIAL);
            });
            fitButtonGroup.add(itemDeg);
            menuPoly.add(itemDeg);
        }

        JRadioButtonMenuItem itemSinus = new JRadioButtonMenuItem("Sinusfunktion");
        itemSinus.addActionListener(_ -> chartPanel.setFitMode(ChartPanel.FitMode.SINUS));
        fitButtonGroup.add(itemSinus);

        JRadioButtonMenuItem itemExp = new JRadioButtonMenuItem("Exponentialfunktion");
        itemExp.addActionListener(_ -> chartPanel.setFitMode(ChartPanel.FitMode.EXPONENTIAL));
        fitButtonGroup.add(itemExp);

        // --- Fit-Bezug: auf welche Messgröße(n) sich Fit & Chi² beziehen ---
        JMenu menuFitTarget = new JMenu("Fit bezieht sich auf");
        JRadioButtonMenuItem[] fitTargetItems = Theme.radioMenuGroup(menuFitTarget, 0,
                Map.entry("Kanal A", _ -> chartPanel.setFitTarget(ChartPanel.FitTarget.A)),
                Map.entry("Kanal B", _ -> chartPanel.setFitTarget(ChartPanel.FitTarget.B)),
                Map.entry("Beide Kanäle (A+B)", _ -> chartPanel.setFitTarget(ChartPanel.FitTarget.BOTH)));
        fitTargetA = fitTargetItems[0];
        fitTargetB = fitTargetItems[1];
        fitTargetBoth = fitTargetItems[2];

        JMenuItem itemStdDev = new JMenuItem("Standardabweichung...");
        itemStdDev.addActionListener(_ -> openStandardDeviationDialog());

        JLabel portLabel = new JLabel(" COM-Port: ");

        JComboBox<String> portSelector = new JComboBox<>();
        portSelector.setEditable(true);
        portSelector.setMaximumSize(new Dimension(140, 25));

        JButton btnRefreshPorts = Theme.compactButton("↻", "Ports aktualisieren", false);

        // Portliste im Hintergrund laden (Bluetooth-SPP-Aufzählung kann Sekunden dauern).
        refreshPortsAsync(portSelector, btnRefreshPorts);
        btnRefreshPorts.addActionListener(_ -> refreshPortsAsync(portSelector, btnRefreshPorts));

        JButton btnIdentifyPort = Theme.compactButton("🔍", "Passenden COM-Port automatisch finden und verbinden", false);
        btnIdentifyPort.addActionListener(_ -> identifyPortAsync(portSelector, btnIdentifyPort));

        connectButton = new JButton(connectionController.isConnected() ? "Trennen" : "Verbinden");
        connectButton.setFocusPainted(false);
        connectButton.setMargin(new Insets(2, 8, 2, 8));

        connectButton.addActionListener(_ -> {
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
                    }
                }.execute();
                return;
            }

            Object selectedItem = portSelector.getSelectedItem();
            if (selectedItem == null) return;

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

    /** Baut die Verbindung zu {@code portName} im Hintergrund auf (blockierendes {@code openPort()},
     *  siehe {@link DeviceConnection#connect}). Gemeinsam genutzt von {@link #connectButton} und
     *  {@link #identifyPortAsync}. */
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
            }
        }.execute();
    }

    /** Füllt {@code portSelector} im Hintergrund neu; {@code button} bleibt währenddessen deaktiviert. */
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

    /** Sucht im Hintergrund über {@link ConnectionController#identifyPhyLogPort} nach dem
     *  passenden Port und verbindet bei Erfolg direkt (siehe {@link #connectToPort}). */
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
        btnStart.addActionListener(_ -> startMeasurement());
        btnStop.addActionListener(_ -> stopMeasurement());
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);

        btnSnapshot = new JButton("Momentaufnahme");
        btnSnapshot.setToolTipText("Aktuellen Messwert als einzelne Zeile (Index statt Zeit) übernehmen");
        btnSnapshot.addActionListener(_ -> captureSnapshot());
        btnSnapshot.setEnabled(false);

        JButton btnZoomIn = Theme.compactButton(" + ", "Hineinzoomen", true);
        btnZoomIn.addActionListener(_ -> chartPanel.zoomIn());

        JButton btnZoomOut = Theme.compactButton(" − ", "Herauszoomen", true);
        btnZoomOut.addActionListener(_ -> chartPanel.zoomOut());

        JButton btnResetZoom = new JButton("Reset");
        btnResetZoom.setToolTipText("Gesamten Graphen anzeigen");
        btnResetZoom.addActionListener(_ -> chartPanel.resetZoom());

        btnTrigger = new JButton("Trigger");
        btnClear = new JButton("Leeren");

        btnTrigger.addActionListener(_ -> openTriggerDialog());
        btnClear.addActionListener(_ -> clearData());

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

        lblBluetoothInfo = new JLabel(" ⓘ");
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
     *  angefallenen Oberflächen-Updates gebündelt aus. */
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

    /** Öffnet eine CSV-Datei und importiert sie gezielt in den zuvor abgefragten Kanal
     *  (siehe {@link #askImportChannel}), sodass zwei getrennte Dateien nacheinander sauber
     *  auf A und B verteilt werden können. */
    private void openCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("CSV-Datei öffnen");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV-Dateien (*.csv)", "csv"));

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        Character targetChannelId = askImportChannel(file);
        if (targetChannelId == null) return;

        importCsvIntoChannel(file, channel(targetChannelId));
    }

    /** Fragt per Auswahldialog, in welchen Kanal die Datei importiert werden soll.
     *
     * @return {@code 'A'}/{@code 'B'}, oder {@code null} bei Abbruch. */
    private Character askImportChannel(File file) {
        Object[] options = {"Kanal A", "Kanal B", "Abbrechen"};
        int choice = JOptionPane.showOptionDialog(this,
                "\"" + file.getName() + "\" importieren als …",
                "Zielkanal wählen",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 0) return 'A';
        if (choice == 1) return 'B';
        return null;
    }

    /** Importiert eine CSV-Datei in genau einen Kanal, der andere bleibt unangetastet. Leert
     *  vorher nur dessen Tabelle und schaltet sie von einem eventuellen Momentaufnahme-Modus
     *  zurück auf die zeitbasierte Spalte. */
    private void importCsvIntoChannel(File file, MeasurementChannel ch) {
        ch.tableModel.setRowCount(0);
        ch.snapshotMode = false;
        configureTableModel(ch);

        // Wird ein Sensor aus der Kopfzeile erkannt, aktualisiert dessen Callback bereits über
        // updateTableLayout() das Diagramm - der Flag verhindert dann den doppelten Aufruf unten.
        boolean[] sensorDetected = {false};

        try {
            int columnCount = ch.tableModel.getColumnCount();
            DataFileService.readCsv(file, columnCount,
                    row -> ch.tableModel.addRow(row),
                    detectedSensor -> {
                        sensorDetected[0] = true;
                        ch.sensor = detectedSensor;
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

    /** Öffnet den Sensor-Auswahldialog. Das Gerät streamt bereits seit dem Verbindungsaufbau
     *  durchgehend (siehe {@link DeviceConnection#connect}), sodass {@code latestValue} für
     *  Vorschau/Tara auch ohne laufende Aufzeichnung aktuell ist. */
    private void openSensorConfigDialog() {
        if (!connectionController.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte zuerst über den COM-Port oben rechts mit dem ESP32 verbinden.",
                    "Keine Verbindung", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Sensor previousA = channelA.sensor;
        Sensor previousB = channelB.sensor;

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
        if (terminalWindow == null || !terminalWindow.isDisplayable()) {
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

        // Neue Aufzeichnung nutzt wieder die "Zeit (s)"-Spalte statt einer evtl. vorherigen
        // Momentaufnahme-Spalte "Index".
        resetSnapshotHeaderIfNeeded(channelA);
        resetSnapshotHeaderIfNeeded(channelB);

        // Ein aktives Zoom-Fenster bezieht sich auf den X-Bereich der vorherigen Aufzeichnung;
        // die neue beginnt wieder bei Zeit 0 und würde sonst außerhalb des alten Fensters landen
        // (wirkt wie "Trigger reagiert nicht", obwohl die Aufzeichnung läuft).
        if (chartPanel != null) {
            chartPanel.resetZoom();
        }

        acquisitionEngine.start();
    }

    /** Schaltet die Spalte zurück auf "Zeit (s)" (leert dabei die Tabelle), falls der Kanal im
     *  Momentaufnahme-Modus ist - Gegenstück zu {@link #prepareSnapshotHeader}. */
    private void resetSnapshotHeaderIfNeeded(MeasurementChannel ch) {
        if (!ch.snapshotMode) return;
        ch.snapshotMode = false;
        configureTableModel(ch);
    }

    private void stopMeasurement() {
        acquisitionEngine.stop();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} Anders als {@link #onDurationLimitReached()}: Verbindungsabbruch statt
     *  regulär erreichtem Limit. */
    @Override
    public void onConnectionLostDuringRecording() {
        lblTriggerStatus.setText("Verbindung verloren - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.DANGER);
    }

    /** {@inheritDoc} Die serielle Verbindung selbst bleibt bestehen - nur der Sensor auf
     *  {@code channelId} konnte wiederholt nicht ausgelesen werden. */
    @Override
    public void onSensorErrorDuringRecording(char channelId, String errorTag) {
        lblTriggerStatus.setText("Sensorfehler Kanal " + channelId + " (" + errorTag + ") - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.DANGER);
    }

    /** {@inheritDoc} Übernimmt für jeden Kanal mit Spektrum-Sensor das zuletzt empfangene
     *  Spektrum als Momentaufnahme-Zeilen in dessen Tabelle (für CSV-Export). */
    @Override
    public void onRecordingStopped() {
        importSpectrumIntoTable(channelA, lastSpectrumA, lastSpectrumRateA);
        importSpectrumIntoTable(channelB, lastSpectrumB, lastSpectrumRateB);
    }

    /** Schreibt ein Spektrum als (Frequenz, dB)-Zeilen in die Tabelle des Kanals; tut nichts
     *  ohne Spektrum-Sensor oder ohne empfangenes Spektrum. */
    private void importSpectrumIntoTable(MeasurementChannel ch, double[] magnitudesDb, int sampleRateHz) {
        if (!ch.producesSpectrum() || magnitudesDb == null) return;

        ch.tableModel.setRowCount(0);
        for (double[] point : toFrequencyPoints(magnitudesDb, sampleRateHz)) {
            ch.tableModel.addRow(new Object[]{point[0], point[1]});
        }
    }

    /** {@inheritDoc} Speichert das Spektrum je Kanal und zeichnet es direkt ins
     *  {@link ChartPanel}. Wird nur während einer laufenden Aufzeichnung aufgerufen. */
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

    /** Zeichnet die zwischengespeicherten Spektren der Kanäle mit aktivem Spektrum-Sensor -
     *  als Haupt-Serie (A, oder B falls A keines hat) bzw. als Extra-Serie. */
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
                mainSeries = pointsB;
            } else {
                extras.add(new ChartPanel.Series("Kanal B: Frequenzspektrum", Theme.POINT_B, pointsB));
            }
        }

        chartPanel.setData(mainSeries);
        chartPanel.setExtraSeries(extras);
    }

    /** Wandelt ein Spektrum (dB je Bin) in (Frequenz, dB)-Punkte um; die Bin-Breite ergibt sich
     *  aus Abtastrate und FFT-Größe (doppelte Bin-Anzahl, siehe {@code captureAndSendSpectrum}
     *  in phylog_firmware.ino). */
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

    /** Reagiert auf jeden Verbindungsauf-/-abbau: sendet bei neuem Aufbau die gewählten
     *  Sensoren und die Abtastrate erneut an die Firmware, da diese nach einem Neustart des
     *  ESP32 keine Kanalzuweisung mehr kennt (siehe {@code setup()} in phylog_firmware.ino). */
    private void onConnectionStatusChanged() {
        if (connectionController.isConnected()) {
            pushSensorSelectionToFirmware('A', channelA.sensor);
            pushSensorSelectionToFirmware('B', channelB.sensor);
            acquisitionEngine.pushSampleRateToFirmware();
        }
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        boolean connected = connectionController.isConnected();

        if (connectButton != null) {
            connectButton.setText(connected ? "Trennen" : "Verbinden");
        }

        boolean measurementActive = acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger();

        if (configSensorItem != null) {
            // Sensorwechsel während laufender Aufzeichnung/Trigger-Wartezeit wäre inkonsistent.
            configSensorItem.setEnabled(connected && !measurementActive);
            configSensorItem.setToolTipText(measurementActive
                    ? "Während einer laufenden Aufzeichnung nicht möglich - erst stoppen."
                    : "Erst mit dem ESP32 verbinden, dann Sensoren auswählen.");
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

    /** Aktualisiert Enabled-Status und Tooltip von Start/Stop/Momentaufnahme/Leeren/Trigger
     *  anhand von Verbindung, Sensorwahl und laufender Aufzeichnung. */
    private void updateActionAvailability(boolean connected) {
        if (btnStart == null || btnStop == null) return;

        TriggerDialog.Config triggerConfig = acquisitionEngine.getTriggerConfig();
        boolean hasAnySensor = channelA.hasSensor() || channelB.hasSensor();
        MeasurementChannel triggerChannel = channel(triggerConfig.channel);

        // Ein Spektrum-Kanal sendet nie Einzelwerte (D-Pakete) - der Schwellenwert-Trigger würde
        // dort nie feuern.
        boolean triggerChannelReady = !triggerConfig.thresholdMode
                || (triggerChannel.hasSensor() && !triggerChannel.producesSpectrum());
        boolean alreadyRunning = acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger();

        boolean canStart = connected && hasAnySensor && triggerChannelReady && !alreadyRunning;
        btnStart.setEnabled(canStart);
        btnStart.setToolTipText(startButtonTooltip(connected, hasAnySensor, triggerChannelReady, alreadyRunning, triggerConfig));

        btnStop.setEnabled(alreadyRunning);
        btnStop.setToolTipText(alreadyRunning ? "Laufende Messung stoppen" : "Es läuft aktuell keine Messung.");

        boolean hasSnapshotableSensor = (channelA.hasSensor() && !channelA.producesSpectrum())
                || (channelB.hasSensor() && !channelB.producesSpectrum());
        boolean canSnapshot = connected && hasSnapshotableSensor && !alreadyRunning;
        btnSnapshot.setEnabled(canSnapshot);
        btnSnapshot.setToolTipText(canSnapshot
                ? "Aktuellen Messwert als einzelne Zeile (Index statt Zeit) übernehmen"
                : (alreadyRunning
                ? "Während einer laufenden Aufzeichnung nicht möglich (würde deren Daten löschen)."
                : "Erst mit dem ESP32 verbinden und einen (nicht-spektralen) Sensor auswählen."));

        boolean spectrumMode = channelA.producesSpectrum() || channelB.producesSpectrum();

        if (btnClear != null) {
            btnClear.setEnabled(!spectrumMode);
            btnClear.setToolTipText(spectrumMode
                    ? "Im Frequenzspektrum-Modus gibt es keine Tabellendaten zum Leeren."
                    : "Aufgezeichnete Werte löschen");
        }

        if (btnTrigger != null) {
            boolean triggerEditable = !spectrumMode && !alreadyRunning;
            btnTrigger.setEnabled(triggerEditable);
            btnTrigger.setToolTipText(spectrumMode
                    ? "Trigger funktioniert nicht mit einem Frequenzspektrum-Kanal."
                    : (alreadyRunning
                    ? "Während einer laufenden Aufzeichnung nicht möglich - erst stoppen."
                    : "Trigger-Bedingung für den Messstart festlegen"));
        }
    }

    private String startButtonTooltip(boolean connected, boolean hasAnySensor, boolean triggerChannelReady,
                                      boolean alreadyRunning, TriggerDialog.Config triggerConfig) {
        if (!connected) {
            return "Erst mit dem ESP32 verbinden.";
        }
        if (!hasAnySensor) {
            return "Erst unter 'Sensor → Sensor konfigurieren...' mindestens einen Sensor auswählen.";
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
     *  Tabellenzeile (Index statt Zeit), siehe {@link AcquisitionEngine#captureSnapshot()}. */
    private void captureSnapshot() {
        if (acquisitionEngine.isRecording() || acquisitionEngine.isWaitingForTrigger()) return;

        prepareSnapshotHeader(channelA);
        prepareSnapshotHeader(channelB);
        acquisitionEngine.captureSnapshot();

        lblTriggerStatus.setText("Momentaufnahme aufgenommen");
        lblTriggerStatus.setForeground(Theme.ACCENT);
    }

    /** Schaltet die Spalte auf "Index" um, falls der Kanal das noch nicht ist (verwirft dabei
     *  eine evtl. laufende zeitbasierte Aufzeichnung). */
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

    /** Baut die Tabellenansicht (ein oder zwei Kanäle) neu auf und hält Y-Achsen-/Fit-Ziel-
     *  Menüeinträge sowie Diagramm-Einheiten mit dem aktuellen Sensor-Zustand synchron. */
    private void updateTableLayout() {
        tableContainerPanel.removeAll();

        configureTableModel(channelA);
        configureTableModel(channelB);

        // Veraltete Spektren verwerfen, sobald der Kanal kein Spektrum-Sensor mehr ist.
        if (!channelA.producesSpectrum()) lastSpectrumA = null;
        if (!channelB.producesSpectrum()) lastSpectrumB = null;

        // Zeit- und Frequenzachse haben grundverschiedene Wertebereiche - Zoom beim Wechsel zurücksetzen.
        boolean nowSpectrumMode = channelA.producesSpectrum() || channelB.producesSpectrum();
        if (nowSpectrumMode != spectrumModeActive) {
            spectrumModeActive = nowSpectrumMode;
            chartPanel.resetZoom();
        }

        // Nur beim tatsächlichen Übergang 1 <-> 2 aktive Sensoren automatisch umschalten, damit
        // eine bewusste manuelle Wahl nicht bei jedem Aufruf verworfen wird.
        boolean bothActive = channelA.hasSensor() && channelB.hasSensor();
        if (bothActive && !previousBothActive) {
            setDualYAxisMode(true);
        } else if (!bothActive && dualYAxisMode) {
            setDualYAxisMode(false);
        }
        previousBothActive = bothActive;

        updateChartUnits();

        yAxisDual.setEnabled(bothActive);
        yAxisDual.setToolTipText(bothActive ? null : "Nur verfügbar, wenn auf beiden Kanälen ein Sensor aktiv ist.");

        // "Kanal B"/"Beide Kanäle" als Fit-Ziel ergeben nur mit Sensor auf Kanal B Sinn.
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
            // Weder A noch B aktiv: Kanal A bleibt als Platzhalter sichtbar.
            if (!hasA) {
                channelA.scrollPane.setBorder(Theme.titledPanelBorder(channelTitle('A', channelA)));
            }
            tableContainerPanel.add(channelA.scrollPane, BorderLayout.CENTER);
        }

        tableContainerPanel.revalidate();
        tableContainerPanel.repaint();

        if (nowSpectrumMode) {
            renderSpectrumChart();
        } else {
            updateChartData();
        }
    }

    /** Titel für die Tabellen-Umrahmung eines Kanals; weist bei Spektrum-Sensoren darauf hin,
     *  dass die Tabelle erst nach dem Stoppen das letzte Spektrum erhält. */
    private String channelTitle(char id, MeasurementChannel ch) {
        String base = "Sensor " + id + ": " + ch.sensor.getName();
        return ch.producesSpectrum() ? base + " - live im Diagramm, letztes Bild nach Stopp hier" : base;
    }

    /** Setzt die Spaltenköpfe passend zum aktuellen Sensor; leert die Tabelle und setzt den
     *  Diagramm-Zoom nur zurück, wenn sich die Köpfe dabei tatsächlich ändern. */
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

            if (chartPanel != null) {
                chartPanel.resetZoom();
            }
        }
    }

    /** Setzt den Y-Achsen-Modus und hält die Menü-RadioButtons synchron. */
    private void setDualYAxisMode(boolean dualYAxisMode) {
        this.dualYAxisMode = dualYAxisMode;
        if (chartPanel != null) {
            chartPanel.setDualYAxisMode(dualYAxisMode);
        }

        if (dualYAxisMode && yAxisDual != null) {
            yAxisDual.setSelected(true);
        } else if (!dualYAxisMode && yAxisShared != null) {
            yAxisShared.setSelected(true);
        }

        updateChartUnits();
    }

    /** Setzt Achsentitel, Einheiten und Legendenbeschriftung passend zum aktuellen Sensor-
     *  Zustand (Zeitreihe vs. Frequenzspektrum, ein vs. zwei aktive Kanäle). */
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
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String axisLabel = quantitiesB.isEmpty() ? "Messwert" : quantitiesB.getFirst().getColumnHeader();
            chartPanel.setUnits("s", axisLabel);
            chartPanel.setMainLabel(quantitiesB.isEmpty() ? "Kanal B" : "Kanal B: " + quantitiesB.getFirst().getColumnHeader());
            return;
        }

        List<Sensor.Quantity> quantitiesA = channelA.sensor.getQuantities();
        boolean useSpecificAxisLabels = dualYAxisMode && hasB;

        String axisLabel = (hasB && !useSpecificAxisLabels)
                ? "Messwerte"
                : (quantitiesA.isEmpty() ? "Messwert" : quantitiesA.getFirst().getColumnHeader());
        chartPanel.setUnits("s", axisLabel);

        String labelA = quantitiesA.isEmpty() ? "Kanal A" : "Kanal A: " + quantitiesA.getFirst().getColumnHeader();
        chartPanel.setMainLabel(labelA);

        if (hasB) {
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String secondaryLabel = quantitiesB.isEmpty()
                    ? channelB.sensor.getName()
                    : quantitiesB.getFirst().getColumnHeader();
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

    /** Überträgt die Tabellendaten (Zeitreihen-Modus) ins {@link ChartPanel}; im Spektrum-Modus
     *  liefert stattdessen {@link #onSpectrumFrame} die Anzeige. Fällt A aus, übernimmt Kanal B
     *  die Hauptgröße, damit Zoom/Fit nicht mangels Daten in A wirkungslos bleiben. */
    private void updateChartData() {
        if (chartPanel == null) return;

        if (channelA.producesSpectrum() || channelB.producesSpectrum()) return;

        boolean onlyBActive = !channelA.hasSensor() && channelB.hasSensor();
        MeasurementChannel mainChannel = onlyBActive ? channelB : channelA;
        chartPanel.setData(extractDataFromTable(mainChannel.tableModel, 1));

        List<ChartPanel.Series> extras = new ArrayList<>();
        if (!onlyBActive && channelB.hasSensor()) {
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String labelB = "Kanal B: " + (quantitiesB.isEmpty() ? channelB.sensor.getName() : quantitiesB.getFirst().getColumnHeader());
            extras.add(new ChartPanel.Series(labelB, Theme.POINT_B, extractDataFromTable(channelB.tableModel, 1)));
        }
        chartPanel.setExtraSeries(extras);

        chartPanel.repaint();
    }

    /** Exportiert die Tabellendaten als CSV; bei aktiven Daten auf beiden Kanälen werden zwei
     *  Dateien mit Kanal-Suffix geschrieben. */
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

    /** Exportiert das aktuell gezeichnete Diagramm als PNG. */
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

    /** Leert beide Kanaltabellen und setzt einen evtl. aktiven Momentaufnahme-Modus zurück. */
    public void clearData() {
        channelA.tableModel.setRowCount(0);
        channelB.tableModel.setRowCount(0);
        channelA.snapshotMode = false;
        channelB.snapshotMode = false;
        configureTableModel(channelA);
        configureTableModel(channelB);
    }

    /** Liest Spalte 0 (X) und {@code valueColumnIndex} (Y) einer Tabelle als (x, y)-Paare aus;
     *  überspringt nicht-numerische Zellen. */
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
