import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Serielles Terminal-Fenster zur Kommunikation mit der Hardware.
 */
public class Terminal extends JFrame {

    private JComboBox<String> comboPort;
    private JTextField txtBaud;
    private JButton btnConnect;
    private JTextArea txtLog;
    private JTextField txtCommand;

    private final Consumer<String> lineListener = this::appendLog;
    /** Bündelt Verbindungsauf-/-abbau, Portliste, Befehlsversand sowie den Verbindungsstatus-
     *  Listener an einer Stelle (siehe GUI, das dieselbe Klasse nutzt) - Terminal griff zuvor an
     *  gut einem Dutzend Stellen direkt auf DeviceConnection.getInstance() zu. */
    private final ConnectionController connectionController = new ConnectionController(this::onConnectionStatusChanged);
    /**
     * Erstellt das Terminal-Fenster und initialisiert die UI.
     */
    public Terminal() {
        super("Terminal");
        setSize(560, 440);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(Theme.BG);

        add(buildConnectionPanel(), BorderLayout.NORTH);
        add(buildLogPanel(), BorderLayout.CENTER);
        add(buildCommandPanel(), BorderLayout.SOUTH);

        refreshPorts();
        connectionController.addLineListener(lineListener);
        updateConnectButtonLabel();

        if (connectionController.isConnected()) {
            appendLog("# Bereits verbunden (geteilte Verbindung mit dem Hauptfenster).");
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                connectionController.removeLineListener(lineListener);
                connectionController.dispose();
            }
        });
    }

    private void updateConnectButtonLabel() {
        btnConnect.setText(connectionController.isConnected() ? "Trennen" : "Verbinden");
    }

    /**
     * Baut das Panel für Portauswahl, Baudrate und Verbindungsaufbau auf.
     *
     * @return Das Steuerungspanel.
     */
    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(new EmptyBorder(8, 8, 0, 8));
        panel.setBackground(Theme.BG);

        comboPort = new JComboBox<>();
        comboPort.setPreferredSize(new Dimension(160, 26));

        JButton btnRefresh = new JButton("Aktualisieren");
        btnRefresh.addActionListener(e -> refreshPorts());

        txtBaud = new JTextField("460800", 7);

        btnConnect = new JButton("Verbinden");
        btnConnect.addActionListener(e -> toggleConnection());

        panel.add(new JLabel("Port:"));
        panel.add(comboPort);
        panel.add(btnRefresh);
        panel.add(new JLabel("Baud:"));
        panel.add(txtBaud);
        panel.add(btnConnect);
        return panel;
    }

    /**
     * Baut den Scrollbereich für die Log-Ausgabe auf.
     *
     * @return Die ScrollPane mit Textbereich.
     */
    private JScrollPane buildLogPanel() {
        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        txtLog.setBackground(Theme.PANEL);
        txtLog.setForeground(Theme.TEXT);

        JScrollPane scrollPane = new JScrollPane(txtLog);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return scrollPane;
    }

    /**
     * Baut das Panel für Befehlseingabe und Quick-Buttons auf.
     *
     * @return Das Eingabepanel.
     */
    private JPanel buildCommandPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(new EmptyBorder(0, 8, 8, 8));
        outer.setBackground(Theme.BG);

        JPanel quickButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        quickButtons.setBackground(Theme.BG);
        quickButtons.add(quickButton("PING"));
        quickButtons.add(quickButton("START"));
        quickButtons.add(quickButton("STOP"));

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBackground(Theme.BG);

        txtCommand = new JTextField();
        txtCommand.addActionListener(e -> sendCurrentCommand());

        JButton btnSend = new JButton("Senden");
        btnSend.addActionListener(e -> sendCurrentCommand());

        inputRow.add(txtCommand, BorderLayout.CENTER);
        inputRow.add(btnSend, BorderLayout.EAST);

        outer.add(quickButtons, BorderLayout.NORTH);
        outer.add(inputRow, BorderLayout.CENTER);
        return outer;
    }

    /**
     * Erstellt einen Button zum Senden eines vordefinierten Befehls.
     *
     * @param command Der zu sendende Befehl textuell.
     * @return Der fertig konfigurierte Button.
     */
    private JButton quickButton(String command) {
        JButton button = new JButton(command);
        button.addActionListener(e -> sendLine(command));
        return button;
    }

    private void refreshPorts() {
        Object previouslySelected = comboPort.getSelectedItem();
        comboPort.removeAllItems();
        for (String name : connectionController.listPortNames()) {
            comboPort.addItem(name);
        }
        selectActivePortIfConnected(previouslySelected);
    }

    /** Wählt im Port-Auswahlfeld den tatsächlich verbundenen Port aus, falls eine Verbindung
     *  besteht (z. B. über {@link GUI} hergestellt, bevor dieses Fenster geöffnet wurde) - sonst
     *  bleibt die vorherige Auswahl erhalten, sofern sie noch in der aktualisierten Liste vorkommt. */
    private void selectActivePortIfConnected(Object previouslySelected) {
        String activePort = connectionController.getActivePortName();
        if (activePort != null) {
            for (int i = 0; i < comboPort.getItemCount(); i++) {
                String item = comboPort.getItemAt(i);
                if (DeviceConnection.stripDescription(item).equals(activePort)) {
                    comboPort.setSelectedItem(item);
                    return;
                }
            }
        }
        if (previouslySelected != null) {
            comboPort.setSelectedItem(previouslySelected);
        }
    }

    /** Wird bei jeder Verbindungsstatusänderung aufgerufen (siehe {@link #connectionController}) -
     *  egal ob hier oder über {@link GUI} ausgelöst. Aktualisiert Button-Beschriftung UND welcher
     *  Port markiert ist - sonst bliebe nach einer über GUI hergestellten Verbindung der hier zuvor
     *  (u. U. falsch) ausgewählte Port stehen. */
    private void onConnectionStatusChanged() {
        updateConnectButtonLabel();
        selectActivePortIfConnected(comboPort.getSelectedItem());
    }

    private void toggleConnection() {
        btnConnect.setEnabled(false);
        boolean wasConnected = connectionController.isConnected();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (wasConnected) {
                    disconnectBlocking();
                } else {
                    connectBlocking();
                }
                return null;
            }

            @Override
            protected void done() {
                btnConnect.setEnabled(true);
            }
        }.execute();
    }

    /**
     * Stellt die Verbindung zum ausgewählten Port her. Läuft im Hintergrund-Thread von
     * {@link #toggleConnection} - {@code openPort()} und das anschließende Senden von "START"
     * (siehe {@link DeviceConnection#connect}) können, besonders bei einem frisch verbundenen
     * Bluetooth-SPP-Port, mehrere Sekunden dauern; direkt im Event-Dispatch-Thread ausgeführt
     * würde das die komplette Oberfläche für diese Zeit einfrieren. {@link #appendLog} springt
     * selbst auf den Event-Dispatch-Thread um, der Aufruf hier aus dem Hintergrund-Thread ist
     * also unproblematisch.
     */
    private void connectBlocking() {
        String portName = (String) comboPort.getSelectedItem();
        if (portName == null) {
            appendLog("# Kein Port ausgewählt.");
            return;
        }
        portName = DeviceConnection.stripDescription(portName);

        int baud;
        try {
            baud = Integer.parseInt(txtBaud.getText().trim());
        } catch (NumberFormatException ex) {
            appendLog("# Ungültige Baudrate.");
            return;
        }

        if (connectionController.connect(portName, baud)) {
            appendLog("# Verbunden mit " + portName + " @ " + baud + " Baud");
        } else {
            appendLog("# Verbindung zu " + portName + " fehlgeschlagen.");
        }
    }

    /**
     * Trennt die serielle Verbindung. Läuft im Hintergrund-Thread von {@link #toggleConnection},
     * siehe dortigen Kommentar sowie {@link #connectBlocking}.
     */
    private void disconnectBlocking() {
        connectionController.disconnect();
        appendLog("# Verbindung getrennt.");
    }

    /**
     * Liest den Text aus dem Eingabefeld und sendet ihn.
     */
    private void sendCurrentCommand() {
        String command = txtCommand.getText();
        if (!command.isEmpty()) {
            sendLine(command);
            txtCommand.setText("");
        }
    }

    /**
     * Sendet eine Befehlszeile an das Gerät und gibt sie im Log aus.
     *
     * @param command Der zu sendende Befehl.
     */
    private void sendLine(String command) {
        if (!connectionController.isConnected()) {
            appendLog("# Nicht verbunden - zuerst einen Port auswählen und 'Verbinden' klicken.");
            return;
        }
        connectionController.sendLine(command);
        appendLog("> " + command);
    }

    /**
     * Fügt eine Zeile zum Text-Log hinzu und scrollt automatisch nach unten. Über
     * {@link SwingUtilities#invokeLater} statt direktem Zugriff, damit dieser Aufruf auch aus
     * einem Hintergrund-Thread heraus sicher ist - siehe {@link #connectBlocking}/
     * {@link #disconnectBlocking}, die inzwischen nicht mehr im Event-Dispatch-Thread laufen.
     *
     * @param text Der auszugebende Text.
     */
    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(text.endsWith("\n") ? text : text + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }
}