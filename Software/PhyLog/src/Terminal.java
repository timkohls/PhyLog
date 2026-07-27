import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Einfaches serielles Terminal-Fenster für die ESP32-Firmware (phylog_firmware.ino,
 * Digital-only Version): PING, START, STOP, RATE,&lt;hz&gt;.
 *
 * <p>Nutzt die geteilte {@link DeviceConnection}, damit dieselbe Verbindung auch vom
 * Hauptfenster ({@link GUI}) für Start/Stop der Live-Messung verwendet werden kann.</p>
 */
public class Terminal extends JFrame {

    private JComboBox<String> comboPort;
    private JTextField txtBaud;
    private JButton btnConnect;
    private JButton btnRefresh;
    private JTextArea txtLog;
    private JTextField txtCommand;
    private JButton btnSend;

    private final Consumer<String> lineListener = this::appendLog;

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

        DeviceConnection.getInstance().addLineListener(lineListener);

        if (DeviceConnection.getInstance().isConnected()) {
            btnConnect.setText("Trennen");
            appendLog("# Bereits verbunden (geteilte Verbindung mit dem Hauptfenster).");
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                DeviceConnection.getInstance().removeLineListener(lineListener);
            }
        });
    }

    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(new EmptyBorder(8, 8, 0, 8));
        panel.setBackground(Theme.BG);

        comboPort = new JComboBox<>();
        comboPort.setPreferredSize(new Dimension(160, 26));

        btnRefresh = new JButton("Aktualisieren");
        btnRefresh.addActionListener(e -> refreshPorts());

        txtBaud = new JTextField("115200", 7);

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

    /** Nur noch die vier Befehle, die die vereinfachte Digital-only Firmware kennt. */
    private JPanel buildCommandPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(new EmptyBorder(0, 8, 8, 8));
        outer.setBackground(Theme.BG);

        JPanel quickButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        quickButtons.setBackground(Theme.BG);
        quickButtons.add(quickButton("PING"));
        quickButtons.add(quickButton("START"));
        quickButtons.add(quickButton("STOP"));
        quickButtons.add(quickButton("RATE,50"));

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBackground(Theme.BG);

        txtCommand = new JTextField();
        txtCommand.addActionListener(e -> sendCurrentCommand());

        btnSend = new JButton("Senden");
        btnSend.addActionListener(e -> sendCurrentCommand());

        inputRow.add(txtCommand, BorderLayout.CENTER);
        inputRow.add(btnSend, BorderLayout.EAST);

        outer.add(quickButtons, BorderLayout.NORTH);
        outer.add(inputRow, BorderLayout.CENTER);
        return outer;
    }

    private JButton quickButton(String command) {
        JButton button = new JButton(command);
        button.addActionListener(e -> sendLine(command));
        return button;
    }

    private void refreshPorts() {
        comboPort.removeAllItems();
        for (String name : DeviceConnection.getInstance().listPortNames()) {
            comboPort.addItem(name);
        }
    }

    private void toggleConnection() {
        if (DeviceConnection.getInstance().isConnected()) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String portName = (String) comboPort.getSelectedItem();
        if (portName == null) {
            appendLog("# Kein Port ausgewählt.");
            return;
        }

        int baud;
        try {
            baud = Integer.parseInt(txtBaud.getText().trim());
        } catch (NumberFormatException ex) {
            appendLog("# Ungültige Baudrate.");
            return;
        }

        if (DeviceConnection.getInstance().connect(portName, baud)) {
            appendLog("# Verbunden mit " + portName + " @ " + baud + " Baud");
            btnConnect.setText("Trennen");
        } else {
            appendLog("# Verbindung zu " + portName + " fehlgeschlagen.");
        }
    }

    private void disconnect() {
        DeviceConnection.getInstance().disconnect();
        appendLog("# Verbindung getrennt.");
        btnConnect.setText("Verbinden");
    }

    private void sendCurrentCommand() {
        String command = txtCommand.getText();
        if (!command.isEmpty()) {
            sendLine(command);
            txtCommand.setText("");
        }
    }

    private void sendLine(String command) {
        if (!DeviceConnection.getInstance().isConnected()) {
            appendLog("# Nicht verbunden - zuerst einen Port auswählen und 'Verbinden' klicken.");
            return;
        }
        DeviceConnection.getInstance().sendLine(command);
        appendLog("> " + command);
    }

    private void appendLog(String text) {
        txtLog.append(text.endsWith("\n") ? text : text + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
}