import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.TableModelEvent;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hauptfenster von PhyLog: Menüleiste, Werkzeugleiste, Messwerttabellen für Kanal A/B mit
 * automatischem Scrollen, das {@link ChartPanel} sowie die Entgegennahme und Filterung der
 * Live-Messdaten vom ESP32 (siehe {@link #handleIncomingLine}).
 */
public class GUI extends JFrame {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    /** Farbe, in der Kanal B im Diagramm dargestellt wird (Kanal A nutzt {@link Theme#POINT}). */
    private static final Color CHANNEL_B_COLOR = new Color(46, 204, 113);

    /**
     * Bündelt alles, was pro Messkanal (A oder B) getrennt gehalten werden muss: Tabelle,
     * aktiver Sensor, letzter Live-Wert, Tara-Offset sowie den kurzen Ringpuffer für den
     * Trigger-Vorlauf. Ersetzt die früher parallel geführten *A/*B-Feld- und Methodenpaare
     * durch je eine gemeinsame, kanal-parametrisierte Stelle.
     */
    private static final class Channel {
        final char id;
        DefaultTableModel tableModel;
        JTable table;
        JScrollPane scrollPane;
        Sensor sensor = SensorRegistry.NO_SENSOR;
        /** Letzter gültiger, tarierter Messwert für die Live-Anzeige im Konfigurationsdialog. */
        volatile Double latestValue = null;
        double tareOffset = 0.0;

        /** Rollierender Puffer der letzten Samples (Millis, Wert) für den Trigger-Vorlauf
         *  (siehe {@link #bufferForPreTrigger}) - unabhängig davon, ob dieser Kanal selbst der
         *  Trigger-Kanal ist, da im Trigger-Moment beide Kanäle mit Vorlauf befüllt werden. */
        final Deque<double[]> preTriggerBuffer = new ArrayDeque<>();
        /** Vorheriger Wert des Trigger-Kanals, um eine Schwellenwert-Überschreitung als
         *  Vorzeichenwechsel zu erkennen (siehe {@link #checkTriggerCondition}). */
        Double lastValueForEdge = null;

        Channel(char id) {
            this.id = id;
        }
    }

    private final Channel channelA = new Channel('A');
    private final Channel channelB = new Channel('B');

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

    /** Nullpunkt für die relative Zeitachse in Millisekunden. -1 = noch nicht gesetzt. */
    private long measurementStartMillis = -1;

    /** {@code true} während einer laufenden Aufzeichnung. Der Zeilen-Listener läuft unabhängig
     *  davon dauerhaft - nur das Schreiben in Tabelle/Diagramm hängt an dieser Flagge, damit
     *  Live-Werte auch ohne Aufzeichnung ankommen. */
    private boolean recording = false;

    /** Für beide Kanäle gemeinsam geltende Abtastrate in Hz (siehe {@link SensorConfigDialog}). */
    private int sampleRateHz = 20;

    /** Aktuelle Trigger-Konfiguration (siehe {@link TriggerDialog}), Standard: manueller Start. */
    private TriggerDialog.Config triggerConfig = new TriggerDialog.Config();

    /** {@code true}, wenn Kanal B (sofern aktiv) über eine eigene, unabhängig skalierte zweite
     *  Y-Achse dargestellt wird, statt sich - wie im Standardfall - dieselbe Achse mit Kanal A zu
     *  teilen (siehe Menü "Ansicht" -&gt; "Y-Achsen" sowie {@link ChartPanel#setDualYAxisMode}).
     *  Wirkt sich nur auf Achsenbeschriftung/-skalierung aus, nicht auf Fit, Zoom oder Chi². */
    private boolean dualYAxisMode = false;

    /** {@code true}, nachdem Start gedrückt wurde, solange im Schwellenwert-Modus noch auf die
     *  Trigger-Bedingung gewartet wird - es wird noch nichts aufgezeichnet (siehe {@link #recording}). */
    private boolean waitingForTrigger = false;

    /** @return den Kanal 'A' oder 'B'; alles andere fällt auf Kanal A zurück. */
    private Channel channel(char id) {
        return (id == 'B') ? channelB : channelA;
    }

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

        // Läuft dauerhaft, nicht nur während einer Aufzeichnung - siehe ingestSample.
        DeviceConnection.getInstance().addLineListener(this::handleIncomingLine);

        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            if (mainSplitPane != null) {
                mainSplitPane.setDividerLocation(0.25);
            }
        });

        updateTableLayout();
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

        JMenuItem connectionItem = new JMenuItem("Verbindung (Bluetooth)...");
        connectionItem.addActionListener(e -> openConnectionDialog());
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

    private void initChannelTable(Channel ch) {
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

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                boolean isFirstLine = true;

                clearData();

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("[;,]");

                    if (isFirstLine) {
                        isFirstLine = false;
                        if (line.toLowerCase().contains("zeit") || (parts.length >= 2 && !isNumeric(parts[0]))) {
                            if (parts.length >= 2) {
                                detectAndApplySensorFromHeader(parts[1]);
                            }
                            continue;
                        }
                    }

                    if (parts.length >= 2) {
                        int columnCount = channelA.tableModel.getColumnCount();
                        if (parts.length < columnCount) continue;

                        try {
                            Object[] row = new Object[columnCount];
                            for (int c = 0; c < columnCount; c++) {
                                row[c] = Double.parseDouble(parts[c].replace(",", ".").trim());
                            }
                            channelA.tableModel.addRow(row);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Fehler beim Lesen der Datei: " + e.getMessage(),
                        "Fehler", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void detectAndApplySensorFromHeader(String headerCol2) {
        Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(headerCol2);

        if (matcher.find()) {
            String extractedUnit = matcher.group(1).trim();
            Sensor matchedSensor = SensorRegistry.findByUnit(extractedUnit);

            if (matchedSensor != null) {
                channelA.sensor = matchedSensor;
                updateTableLayout();
            }
        }
    }

    private boolean isNumeric(String str) {
        if (str == null) return false;
        try {
            Double.parseDouble(str.replace(",", ".").trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void openConnectionDialog() {
        JDialog connectionDialog = new JDialog(this, "Verbindung (Bluetooth)", true);
        connectionDialog.setSize(350, 200);
        connectionDialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        panel.add(new JLabel("Bluetooth-Verbindungstests...", SwingConstants.CENTER), BorderLayout.CENTER);

        JButton btnClose = new JButton("Schließen");
        btnClose.addActionListener(e -> connectionDialog.dispose());
        panel.add(btnClose, BorderLayout.SOUTH);

        connectionDialog.add(panel);
        connectionDialog.setVisible(true);
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

        SensorConfigDialog dialog = new SensorConfigDialog(this, channelA.sensor, channelB.sensor, sampleRateHz,
                () -> channelA.latestValue, () -> channelB.latestValue,
                this::pushSensorSelectionToFirmware, this::onTareRequested);
        dialog.setVisible(true);

        if (liveMonitoringStartedByDialog) {
            DeviceConnection.getInstance().sendLine("STOP");
        }

        if (dialog.isApplied()) {
            channelA.sensor = dialog.getSelectedSensorA();
            channelB.sensor = dialog.getSelectedSensorB();
            sampleRateHz = dialog.getSampleRate();
            updateTableLayout();
            applySampleRateToFirmware();
        } else {
            pushSensorSelectionToFirmware('A', previousA);
            pushSensorSelectionToFirmware('B', previousB);
        }

        updateStatusLabel();
    }

    private boolean ensureLiveDataFlowing() {
        if (!DeviceConnection.getInstance().isConnected() || recording) {
            return false;
        }
        DeviceConnection.getInstance().sendLine("START");
        return true;
    }

    private void pushSensorSelectionToFirmware(char channelId, Sensor sensor) {
        Channel ch = channel(channelId);
        if (ch.sensor != sensor) ch.tareOffset = 0.0;
        ch.sensor = sensor;

        if (!DeviceConnection.getInstance().isConnected()) {
            return;
        }
        DeviceConnection.getInstance().sendLine("SET," + channelId + "," + sensor.getFirmwareTypeName());
    }

    private void onTareRequested(char channelId) {
        Channel ch = channel(channelId);
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

        boolean isSensorASelected = channelA.sensor != null && channelA.sensor != SensorRegistry.NO_SENSOR;
        boolean isSensorBSelected = channelB.sensor != null && channelB.sensor != SensorRegistry.NO_SENSOR;

        if (!isSensorASelected && !isSensorBSelected) {
            JOptionPane.showMessageDialog(
                    this,
                    "Es ist kein Sensor ausgewählt! Bitte wähle über 'Sensor -> Sensor konfigurieren...' mindestens einen Sensor aus.",
                    "Fehler: Kein Sensor gewählt",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        if (channelA.sensor == SensorRegistry.NO_SENSOR && channelB.sensor == SensorRegistry.NO_SENSOR) {
            JOptionPane.showMessageDialog(this,
                    "Kein Sensor ausgewählt! Bitte unter Sensor, Sensor konfigurieren.", "Kein Sensor konfiguriert", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (triggerConfig.thresholdMode && channel(triggerConfig.channel).sensor == SensorRegistry.NO_SENSOR) {
            JOptionPane.showMessageDialog(this,
                    "Für den Trigger-Kanal " + triggerConfig.channel + " ist kein Sensor konfiguriert.",
                    "Trigger nicht konfigurierbar", JOptionPane.WARNING_MESSAGE);
            return;
        }

        measurementStartMillis = -1;
        channelA.latestValue = null;
        channelB.latestValue = null;
        channelA.preTriggerBuffer.clear();
        channelB.preTriggerBuffer.clear();
        channelA.lastValueForEdge = null;
        channelB.lastValueForEdge = null;

        if (triggerConfig.thresholdMode) {
            waitingForTrigger = true;
            recording = false;
        } else {
            waitingForTrigger = false;
            recording = true;
        }
        updateStatusLabel();

        DeviceConnection.getInstance().sendLine("START");
    }

    private void stopMeasurement() {
        recording = false;
        waitingForTrigger = false;
        updateStatusLabel();
        DeviceConnection.getInstance().sendLine("STOP");
    }

    private void updateStatusLabel() {
        boolean connected = DeviceConnection.getInstance().isConnected();

        if (configSensorItem != null) {
            configSensorItem.setEnabled(connected);
        }

        updateActionAvailability(connected);

        if (lblTriggerStatus == null) return;

        if (!connected) {
            lblTriggerStatus.setText("Nicht verbunden");
            lblTriggerStatus.setForeground(Color.LIGHT_GRAY);
        } else if (waitingForTrigger) {
            lblTriggerStatus.setText("Warte auf Trigger (Kanal " + triggerConfig.channel + ") …");
            lblTriggerStatus.setForeground(Theme.POINT);
        } else if (recording) {
            lblTriggerStatus.setText(triggerConfig.thresholdMode ? "Aufnahme läuft (getriggert)" : "Aufnahme läuft");
            lblTriggerStatus.setForeground(new Color(46, 204, 113));
        } else {
            lblTriggerStatus.setText("Bereit");
            lblTriggerStatus.setForeground(Theme.ACCENT);
        }
    }

    private void updateActionAvailability(boolean connected) {
        if (btnStart == null || btnStop == null) return;

        boolean hasAnySensor = hasSensor(channelA) || hasSensor(channelB);
        boolean triggerChannelReady = !triggerConfig.thresholdMode
                || channel(triggerConfig.channel).sensor != SensorRegistry.NO_SENSOR;
        boolean alreadyRunning = recording || waitingForTrigger;

        boolean canStart = connected && hasAnySensor && triggerChannelReady && !alreadyRunning;
        btnStart.setEnabled(canStart);
        btnStart.setToolTipText(startButtonTooltip(connected, hasAnySensor, triggerChannelReady, alreadyRunning));

        boolean canStop = alreadyRunning;
        btnStop.setEnabled(canStop);
        btnStop.setToolTipText(canStop ? "Laufende Messung stoppen" : "Es läuft aktuell keine Messung.");
    }

    private String startButtonTooltip(boolean connected, boolean hasAnySensor, boolean triggerChannelReady, boolean alreadyRunning) {
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

    private void handleIncomingLine(String line) {
        if (!line.startsWith("D,")) {
            return;
        }

        String[] parts = line.split(",");
        if (parts.length < 5) return;

        try {
            long millis = Long.parseLong(parts[1].trim());
            char channelId = parts[2].trim().charAt(0);
            int slot = Integer.parseInt(parts[3].trim());
            long rawValue = Long.parseLong(parts[4].trim());

            if (channelId == 'A' || channelId == 'B') {
                ingestSample(channel(channelId), slot, rawValue, millis);
            }
        } catch (NumberFormatException e) {
        }
    }

    private void ingestSample(Channel ch, int slot, long rawValue, long millis) {
        Sensor sensor = ch.sensor;
        if (sensor == null || sensor == SensorRegistry.NO_SENSOR) {
            return;
        }

        List<Sensor.Quantity> quantities = sensor.getQuantities();
        if (quantities.isEmpty() || quantities.getFirst().slot != slot) {
            return;
        }

        double rawDecoded = sensor.decode(slot, rawValue);
        if (Double.isNaN(rawDecoded) || Double.isInfinite(rawDecoded)) {
            return;
        }

        double value = rawDecoded - ch.tareOffset;
        ch.latestValue = value;
        bufferForPreTrigger(ch, millis, value);

        if (waitingForTrigger) {
            checkTriggerCondition(ch, millis, value);
            return;
        }

        if (!recording) return;

        if (measurementStartMillis < 0) {
            measurementStartMillis = millis;
        }
        double timeSeconds = (millis - measurementStartMillis) / 1000.0;
        ch.tableModel.addRow(new Object[]{timeSeconds, value});

        if (triggerConfig.maxDurationMs > 0 && timeSeconds * 1000.0 >= triggerConfig.maxDurationMs) {
            stopMeasurementDueToDurationLimit();
        }
    }

    private void stopMeasurementDueToDurationLimit() {
        stopMeasurement();
        lblTriggerStatus.setText("Maximale Messdauer erreicht - Aufnahme gestoppt");
        lblTriggerStatus.setForeground(Theme.POINT);
    }

    private void bufferForPreTrigger(Channel ch, long millis, double value) {
        if (triggerConfig.preTriggerMs <= 0) return;

        ch.preTriggerBuffer.addLast(new double[]{millis, value});
        long cutoff = millis - triggerConfig.preTriggerMs;
        while (!ch.preTriggerBuffer.isEmpty() && ch.preTriggerBuffer.peekFirst()[0] < cutoff) {
            ch.preTriggerBuffer.removeFirst();
        }
    }

    private void checkTriggerCondition(Channel ch, long millis, double value) {
        if (ch.id != triggerConfig.channel) return;

        Double previous = ch.lastValueForEdge;
        ch.lastValueForEdge = value;
        if (previous == null) return;

        double threshold = triggerConfig.threshold;
        boolean crossed = triggerConfig.risingEdge
                ? (previous < threshold && value >= threshold)
                : (previous > threshold && value <= threshold);

        if (crossed) {
            fireTrigger(millis);
        }
    }

    private void fireTrigger(long triggerMillis) {
        waitingForTrigger = false;
        recording = true;
        measurementStartMillis = triggerMillis - triggerConfig.preTriggerMs;

        backfillPreTriggerData(channelA);
        backfillPreTriggerData(channelB);

        updateStatusLabel();
    }

    private void backfillPreTriggerData(Channel ch) {
        for (double[] sample : ch.preTriggerBuffer) {
            long sampleMillis = (long) sample[0];
            if (sampleMillis < measurementStartMillis) continue;
            double timeSeconds = (sampleMillis - measurementStartMillis) / 1000.0;
            ch.tableModel.addRow(new Object[]{timeSeconds, sample[1]});
        }
        ch.preTriggerBuffer.clear();
    }

    private void applySampleRateToFirmware() {
        if (!DeviceConnection.getInstance().isConnected() || sampleRateHz <= 0) {
            return;
        }
        DeviceConnection.getInstance().sendLine("RATE," + Math.min(sampleRateHz, 1000));
    }

    private void openTriggerDialog() {
        TriggerDialog dialog = new TriggerDialog(this, triggerConfig);
        dialog.setVisible(true);

        if (dialog.isApplied()) {
            triggerConfig = dialog.getConfig();
            updateStatusLabel();
        }
    }

    private void updateTableLayout() {
        tableContainerPanel.removeAll();

        configureTableModel(channelA);
        configureTableModel(channelB);

        // Wenn 2 Sensoren ausgewählt sind, wird standardmäßig der Modus mit zwei Y-Achsen gesetzt.
        boolean shouldBeDual = hasSensor(channelA) && hasSensor(channelB);
        setDualYAxisMode(shouldBeDual);

        channelA.scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                "Sensor A: " + channelA.sensor.getName(),
                0, 0, null, Theme.TEXT));

        if (hasSensor(channelB)) {
            channelB.scrollPane.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    "Sensor B: " + channelB.sensor.getName(),
                    0, 0, null, Theme.TEXT));

            JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, channelA.scrollPane, channelB.scrollPane);
            verticalSplit.setResizeWeight(0.5);
            tableContainerPanel.add(verticalSplit, BorderLayout.CENTER);
        } else {
            tableContainerPanel.add(channelA.scrollPane, BorderLayout.CENTER);
        }

        tableContainerPanel.revalidate();
        tableContainerPanel.repaint();
    }

    private boolean hasSensor(Channel ch) {
        return ch.sensor != null && ch.sensor != SensorRegistry.NO_SENSOR;
    }

    private void configureTableModel(Channel ch) {
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

        List<Sensor.Quantity> quantitiesA = channelA.sensor.getQuantities();
        boolean hasSecondSensor = hasSensor(channelB);
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

        chartPanel.setData(extractDataFromTable(channelA.tableModel, 1));

        List<ChartPanel.Series> extras = new ArrayList<>();
        if (hasSensor(channelB)) {
            List<Sensor.Quantity> quantitiesB = channelB.sensor.getQuantities();
            String labelB = "Kanal B: " + (quantitiesB.isEmpty() ? channelB.sensor.getName() : quantitiesB.get(0).getColumnHeader());
            extras.add(new ChartPanel.Series(labelB, CHANNEL_B_COLOR, extractDataFromTable(channelB.tableModel, 1)));
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
            for (Channel ch : new Channel[]{channelA, channelB}) {
                if (ch.tableModel.getRowCount() == 0) continue;
                File file = bothActive ? withSuffix(selectedFile, "Kanal" + ch.id) : selectedFile;
                writeCsv(file, ch.tableModel);
                writtenFileNames.add(file.getName());
            }
            JOptionPane.showMessageDialog(this, "CSV erfolgreich gespeichert: " + String.join(", ", writtenFileNames),
                    "Erfolg", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Speichern der CSV: " + e.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private File withSuffix(File base, String suffix) {
        String path = base.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        String withoutExt = (dot >= 0) ? path.substring(0, dot) : path;
        String ext = (dot >= 0) ? path.substring(dot) : "";
        return new File(withoutExt + "_" + suffix + ext);
    }

    private void writeCsv(File file, DefaultTableModel model) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            int columnCount = model.getColumnCount();

            StringBuilder header = new StringBuilder();
            for (int c = 0; c < columnCount; c++) {
                if (c > 0) header.append(";");
                header.append(model.getColumnName(c));
            }
            writer.println(header);

            for (int i = 0; i < model.getRowCount(); i++) {
                StringBuilder row = new StringBuilder();
                for (int c = 0; c < columnCount; c++) {
                    if (c > 0) row.append(";");
                    row.append(model.getValueAt(i, c));
                }
                writer.println(row);
            }
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
                BufferedImage image = new BufferedImage(
                        chartPanel.getWidth(),
                        chartPanel.getHeight(),
                        BufferedImage.TYPE_INT_RGB
                );
                Graphics2D g2 = image.createGraphics();
                chartPanel.paint(g2);
                g2.dispose();

                ImageIO.write(image, "png", file);
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