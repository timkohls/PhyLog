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
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hauptfenster von LibrePhysics: Menüleiste, Werkzeugleiste, Messwerttabellen für Kanal A/B
 * mit automatischem Scrollen, das {@link ChartPanel} sowie die Entgegennahme und Filterung
 * der Live-Messdaten vom ESP32 (siehe {@link #handleIncomingLine}).
 */
public class GUI extends JFrame {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private DefaultTableModel tableModelA;
    private DefaultTableModel tableModelB;
    private JTable tableA;
    private JTable tableB;

    private JScrollPane scrollPaneA;
    private JScrollPane scrollPaneB;
    private JPanel tableContainerPanel;

    private ChartPanel chartPanel;
    private JSplitPane mainSplitPane;

    private Sensor activeSensorA = SensorRegistry.NO_SENSOR;
    private Sensor activeSensorB = SensorRegistry.NO_SENSOR;

    private JButton btnStart, btnStop, btnTrigger, btnZoomIn, btnZoomOut, btnResetZoom, btnClear;

    /** Referenz auf ein bereits geöffnetes Terminal-Fenster. */
    private Terminal terminalWindow;

    /** Nullpunkt für die relative Zeitachse in Millisekunden. -1 = noch nicht gesetzt. */
    private long measurementStartMillis = -1;

    /** {@code true} während einer laufenden Aufzeichnung (zwischen Start und Stop). Der
     *  Zeilen-Listener läuft unabhängig davon dauerhaft - nur das Schreiben in Tabelle/Diagramm
     *  hängt an dieser Flagge, damit Live-Werte (siehe unten) auch ohne Aufzeichnung ankommen. */
    private boolean recording = false;

    /** Letzter gültiger (nicht-phantomer) Messwert je Kanal, für die Live-Anzeige im
     *  Sensor-Konfigurationsdialog. {@code null} solange kein gültiger Wert vorliegt. */
    private volatile Double latestValueA = null;
    private volatile Double latestValueB = null;

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

        // Läuft dauerhaft, nicht nur während einer Aufzeichnung - siehe processSample().
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
            URL classpathIcon = getClass().getResource("/assets/icon.png");
            if (classpathIcon != null) {
                setIconImage(Toolkit.getDefaultToolkit().getImage(classpathIcon));
                return;
            }

            File fallbackFile = new File("src/pic/icon.png");
            if (fallbackFile.exists()) {
                setIconImage(Toolkit.getDefaultToolkit().getImage(fallbackFile.getAbsolutePath()));
                return;
            }

            System.err.println("Hinweis: Fenster-Icon 'pic/icon.png' wurde weder im Klassenpfad " +
                    "noch unter 'src/pic/icon.png' gefunden. Es wird kein Icon gesetzt.");
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

        JMenuItem connectionItem = new JMenuItem("Verbindung (Bluetooth)...");
        connectionItem.addActionListener(e -> openConnectionDialog());
        menuSensor.add(connectionItem);

        JMenuItem configSensorItem = new JMenuItem("Sensor konfigurieren...");
        configSensorItem.addActionListener(e -> openSensorConfigDialog());
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

        JCheckBoxMenuItem showLine = new JCheckBoxMenuItem("Verbindungslinie", false);
        showLine.addActionListener(e -> chartPanel.setShowLine(showLine.isSelected()));

        menuView.addSeparator();
        menuView.add(showPoints);
        menuView.add(showLine);

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

        setJMenuBar(menuBar);
    }

    private void openStandardDeviationDialog() {
        StandardDeviationDialog dialog = new StandardDeviationDialog(this, chartPanel.getStandardDeviation());
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            double newStdDev = dialog.getStandardDeviation();
            chartPanel.setStandardDeviation(newStdDev);
        }
    }

    private void initToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        btnStart = new JButton("Start");
        btnStop = new JButton("Stop");
        btnStart.addActionListener(e -> startMeasurement());
        btnStop.addActionListener(e -> stopMeasurement());

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

        add(toolBar, BorderLayout.NORTH);
    }

    private void initMainArea() {
        tableModelA = createTableModel();
        tableA = new JTable(tableModelA);
        tableA.setFillsViewportHeight(true);
        tableModelA.addTableModelListener(e -> {
            updateChartData();
            if (e.getType() == TableModelEvent.INSERT) {
                SwingUtilities.invokeLater(() -> {
                    int lastRow = tableA.getRowCount() - 1;
                    if (lastRow >= 0) {
                        tableA.scrollRectToVisible(tableA.getCellRect(lastRow, 0, true));
                    }
                });
            }
        });
        scrollPaneA = new JScrollPane(tableA);

        tableModelB = createTableModel();
        tableB = new JTable(tableModelB);
        tableB.setFillsViewportHeight(true);
        tableModelB.addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.INSERT) {
                SwingUtilities.invokeLater(() -> {
                    int lastRow = tableB.getRowCount() - 1;
                    if (lastRow >= 0) {
                        tableB.scrollRectToVisible(tableB.getCellRect(lastRow, 0, true));
                    }
                });
            }
        });
        scrollPaneB = new JScrollPane(tableB);

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
                        try {
                            double time = Double.parseDouble(parts[0].replace(",", ".").trim());
                            double value = Double.parseDouble(parts[1].replace(",", ".").trim());
                            addMeasurement(time, value);
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
                activeSensorA = matchedSensor;
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
        boolean liveMonitoringStartedByDialog = ensureLiveDataFlowing();

        SensorConfigDialog dialog = new SensorConfigDialog(this, activeSensorA, activeSensorB,
                () -> latestValueA, () -> latestValueB);
        dialog.setVisible(true);

        if (liveMonitoringStartedByDialog) {
            DeviceConnection.getInstance().sendLine("STOP");
        }

        if (dialog.isApplied()) {
            activeSensorA = dialog.getSelectedSensorA();
            activeSensorB = dialog.getSelectedSensorB();
            updateTableLayout();
            applySensorSelectionToFirmware();
            applySampleRateToFirmware(dialog);
        }
    }

    /**
     * Sorgt dafür, dass die Firmware Daten sendet, damit die Live-Anzeige im Sensor-
     * Konfigurationsdialog auch außerhalb einer laufenden Aufzeichnung aktuelle Werte zeigt.
     * Läuft bereits eine Aufzeichnung, passiert nichts (dann fließen ohnehin schon Daten).
     *
     * @return {@code true}, wenn dafür extra gestartet wurde und beim Schließen des Dialogs
     *         wieder gestoppt werden muss
     */
    private boolean ensureLiveDataFlowing() {
        if (!DeviceConnection.getInstance().isConnected() || recording) {
            return false;
        }
        DeviceConnection.getInstance().sendLine("START");
        return true;
    }

    /** Teilt der Firmware mit, welcher Sensortyp aktuell auf Kanal A bzw. B ausgewertet werden soll. */
    private void applySensorSelectionToFirmware() {
        if (!DeviceConnection.getInstance().isConnected()) {
            return;
        }
        DeviceConnection.getInstance().sendLine("SET,A," + activeSensorA.getFirmwareTypeName());
        DeviceConnection.getInstance().sendLine("SET,B," + activeSensorB.getFirmwareTypeName());
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
            JOptionPane.showMessageDialog(this,
                    "Keine serielle Verbindung. Bitte zuerst über Terminal > Terminal öffnen... " +
                            "mit dem ESP32 verbinden.",
                    "Nicht verbunden", JOptionPane.WARNING_MESSAGE);
            return;
        }

        measurementStartMillis = -1;
        latestValueA = null;
        latestValueB = null;
        recording = true;

        DeviceConnection.getInstance().sendLine("START");
    }

    private void stopMeasurement() {
        recording = false;
        DeviceConnection.getInstance().sendLine("STOP");
    }

    /** Verarbeitet eine von der Firmware empfangene Datenzeile ("D,millis,Kanal,Slot,Rohwert"). */
    private void handleIncomingLine(String line) {
        if (!line.startsWith("D,")) {
            return; // Status-/Diagnosemeldungen ("#...") und alles andere ignorieren
        }

        String[] parts = line.split(",");
        if (parts.length < 5) return;

        try {
            long millis = Long.parseLong(parts[1].trim());
            char channel = parts[2].trim().charAt(0);
            int slot = Integer.parseInt(parts[3].trim());
            long rawValue = Long.parseLong(parts[4].trim());

            double timeSeconds = 0.0;
            if (recording) {
                if (measurementStartMillis < 0) {
                    measurementStartMillis = millis;
                }
                timeSeconds = (millis - measurementStartMillis) / 1000.0;
            }

            if (channel == 'A') {
                processSample(activeSensorA, slot, rawValue, timeSeconds, true);
            } else if (channel == 'B') {
                processSample(activeSensorB, slot, rawValue, timeSeconds, false);
            }
        } catch (NumberFormatException e) {
            // Defekte/unvollständige Zeilen stumm verwerfen
        }
    }

    /**
     * Dekodiert einen Rohwert für einen Kanal und verwirft bekannte Phantom-Messwerte (siehe
     * {@link Sensor#isPhantomReading}). Die Live-Anzeige wird immer aktualisiert; in Tabelle
     * und Diagramm landet der Wert nur, wenn {@link #recording} aktiv ist.
     */
    private void processSample(Sensor sensor, int slot, long rawValue, double timeSeconds, boolean isChannelA) {
        if (sensor == null || sensor == SensorRegistry.NO_SENSOR) {
            return;
        }

        double value = sensor.decode(slot, rawValue);
        if (Double.isNaN(value) || Double.isInfinite(value) || sensor.isPhantomReading(slot, value)) {
            return;
        }

        if (isChannelA) {
            latestValueA = value;
            if (recording) addMeasurement(timeSeconds, value);
        } else {
            latestValueB = value;
            if (recording) tableModelB.addRow(new Object[]{timeSeconds, value});
        }
    }

    private void applySampleRateToFirmware(SensorConfigDialog dialog) {
        if (!DeviceConnection.getInstance().isConnected()) {
            return;
        }

        int rateA = (activeSensorA != SensorRegistry.NO_SENSOR) ? dialog.getSampleRateA() : 0;
        int rateB = (activeSensorB != SensorRegistry.NO_SENSOR) ? dialog.getSampleRateB() : 0;

        int effectiveRate = Math.max(rateA, rateB);
        if (effectiveRate <= 0) return;

        effectiveRate = Math.min(effectiveRate, 200);
        DeviceConnection.getInstance().sendLine("RATE," + effectiveRate);
    }

    private void openTriggerDialog() {
        TriggerDialog dialog = new TriggerDialog(this);
        dialog.setVisible(true);
    }

    private void updateTableLayout() {
        tableContainerPanel.removeAll();

        String unit1 = activeSensorA.getUnit();
        String unit2 = activeSensorB.getUnit();

        if (chartPanel != null) {
            chartPanel.setUnits("s", unit1);
        }

        String header1 = unit1.isEmpty() ? "Messwert" : "Messwert (" + unit1 + ")";
        String header2 = unit2.isEmpty() ? "Messwert" : "Messwert (" + unit2 + ")";

        tableModelA.setColumnIdentifiers(new Object[]{"Zeit (s)", header1});
        tableModelB.setColumnIdentifiers(new Object[]{"Zeit (s)", header2});

        scrollPaneA.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                "Sensor A: " + activeSensorA.getName(),
                0, 0, null, Theme.TEXT));

        boolean hasSecondSensor = activeSensorB != null && activeSensorB != SensorRegistry.NO_SENSOR;

        if (hasSecondSensor) {
            scrollPaneB.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    "Sensor B: " + activeSensorB.getName(),
                    0, 0, null, Theme.TEXT));

            JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollPaneA, scrollPaneB);
            verticalSplit.setResizeWeight(0.5);
            tableContainerPanel.add(verticalSplit, BorderLayout.CENTER);
        } else {
            tableContainerPanel.add(scrollPaneA, BorderLayout.CENTER);
        }

        tableContainerPanel.revalidate();
        tableContainerPanel.repaint();
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
        if (chartPanel != null) {
            chartPanel.setData(extractDataFromTable(tableModelA));
            chartPanel.repaint();
        }
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("CSV Exportieren");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV-Dateien (*.csv)", "csv"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }

            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                String unit1 = activeSensorA.getUnit();
                String header = unit1.isEmpty() ? "Messwert" : "Messwert (" + unit1 + ")";
                writer.println("Zeit (s);" + header);

                for (int i = 0; i < tableModelA.getRowCount(); i++) {
                    Object time = tableModelA.getValueAt(i, 0);
                    Object val = tableModelA.getValueAt(i, 1);
                    writer.println(time + ";" + val);
                }
                JOptionPane.showMessageDialog(this, "CSV erfolgreich gespeichert!", "Erfolg", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Fehler beim Speichern der CSV: " + e.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
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

    public void addMeasurement(double time, double value) {
        tableModelA.addRow(new Object[]{time, value});
    }

    public void clearData() {
        tableModelA.setRowCount(0);
        tableModelB.setRowCount(0);
    }

    private List<double[]> extractDataFromTable(DefaultTableModel model) {
        List<double[]> data = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object timeObj = model.getValueAt(i, 0);
            Object valObj = model.getValueAt(i, 1);
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