import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/** Serielles Terminal-Fenster zur Kommunikation mit der Hardware. */
public class Terminal extends JFrame {

    private JComboBox<String> comboPort;
    private JTextField txtBaud;
    private JButton btnConnect;
    private JTextArea txtLog;
    private JTextField txtCommand;

    private final Consumer<String> lineListener = this::appendLog;

    /** Muss beim Schließen des Fensters über {@link #dispose()} wieder abgemeldet werden. */
    private final ConnectionController connectionController = new ConnectionController(this::onConnectionStatusChanged);

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

    /** Wird bei jeder Verbindungsstatusänderung aufgerufen, egal ob hier oder über {@link GUI}
     *  ausgelöst - aktualisiert Button-Beschriftung und markierten Port. */
    private void onConnectionStatusChanged() {
        updateConnectButtonLabel();
        selectActivePortIfConnected(comboPort.getSelectedItem());
    }

    private void updateConnectButtonLabel() {
        btnConnect.setText(connectionController.isConnected() ? "Trennen" : "Verbinden");
    }

    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(new EmptyBorder(8, 8, 0, 8));
        panel.setBackground(Theme.BG);

        comboPort = new JComboBox<>();
        comboPort.setPreferredSize(new Dimension(160, 26));

        JButton btnRefresh = new JButton("Aktualisieren");
        btnRefresh.addActionListener(_ -> refreshPorts());

        txtBaud = new JTextField("460800", 7);

        btnConnect = new JButton("Verbinden");
        btnConnect.addActionListener(_ -> toggleConnection());

        panel.add(new JLabel("Port:"));
        panel.add(comboPort);
        panel.add(btnRefresh);
        panel.add(new JLabel("Baud:"));
        panel.add(txtBaud);
        panel.add(btnConnect);
        return panel;
    }

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
        txtCommand.addActionListener(_ -> sendCurrentCommand());

        JButton btnSend = new JButton("Senden");
        btnSend.addActionListener(_ -> sendCurrentCommand());

        inputRow.add(txtCommand, BorderLayout.CENTER);
        inputRow.add(btnSend, BorderLayout.EAST);

        outer.add(quickButtons, BorderLayout.NORTH);
        outer.add(inputRow, BorderLayout.CENTER);
        return outer;
    }

    private JButton quickButton(String command) {
        JButton button = new JButton(command);
        button.addActionListener(_ -> sendLine(command));
        return button;
    }

    /** Aktualisiert die Liste der verfügbaren seriellen Ports und markiert danach den
     *  tatsächlich verbundenen Port, falls eine Verbindung besteht. */
    private void refreshPorts() {
        Object previouslySelected = comboPort.getSelectedItem();
        comboPort.removeAllItems();
        for (String name : connectionController.listPortNames()) {
            comboPort.addItem(name);
        }
        selectActivePortIfConnected(previouslySelected);
    }

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

    /** Öffnet oder schließt die Verbindung je nach Zustand, im Hintergrund-Thread. */
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

    /** Stellt die Verbindung zum ausgewählten Port her; läuft im Hintergrund-Thread von
     *  {@link #toggleConnection}, da der Verbindungsaufbau mehrere Sekunden dauern kann. */
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

    private void disconnectBlocking() {
        connectionController.disconnect();
        appendLog("# Verbindung getrennt.");
    }

    private void sendCurrentCommand() {
        String command = txtCommand.getText();
        if (!command.isEmpty()) {
            sendLine(command);
            txtCommand.setText("");
        }
    }

    private void sendLine(String command) {
        if (!connectionController.isConnected()) {
            appendLog("# Nicht verbunden - zuerst einen Port auswählen und 'Verbinden' klicken.");
            return;
        }
        connectionController.sendLine(command);
        appendLog("> " + command);
    }

    /** Fügt eine Zeile zum Log hinzu; über {@link SwingUtilities#invokeLater}, da dieser Aufruf
     *  auch aus einem Hintergrund-Thread heraus erfolgen kann. */
    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(text.endsWith("\n") ? text : text + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }
}
