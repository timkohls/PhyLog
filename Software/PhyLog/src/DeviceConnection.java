import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import javax.swing.SwingUtilities;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Zentrale, geteilte serielle Verbindung zum ESP32.
 *
 * <p>Sowohl {@link Terminal} als auch {@link GUI} sprechen über diese eine Instanz mit der
 * Hardware, statt jeweils einen eigenen {@link SerialPort} zu öffnen - zwei gleichzeitig
 * geöffnete Verbindungen zum selben COM-Port würden sich gegenseitig blockieren bzw.
 * fehlschlagen.</p>
 *
 * <p>Übernimmt außerdem das korrekte Zeilen-Buffering: eingehende USB-Daten kommen in beliebig
 * geschnittenen Chunks an, nicht garantiert zeilenweise. Listener werden deshalb erst
 * benachrichtigt, sobald tatsächlich eine vollständige, mit '\n' abgeschlossene Zeile
 * zusammengesetzt wurde - das vermeidet die mitten in der Zeile zerrissene Ausgabe, die das
 * frühere, rein Terminal-interne Vorgehen zeigen konnte.</p>
 */
public class DeviceConnection {

    private static final DeviceConnection INSTANCE = new DeviceConnection();

    /** @return die einzige, geteilte Instanz dieser Verbindung. */
    public static DeviceConnection getInstance() {
        return INSTANCE;
    }

    private SerialPort activePort;
    private final StringBuilder receiveBuffer = new StringBuilder();
    private final List<Consumer<String>> lineListeners = new ArrayList<>();

    private DeviceConnection() {
    }

    /** @return die Systemnamen aller aktuell verfügbaren seriellen Ports (z. B. "COM5"). */
    public List<String> listPortNames() {
        List<String> names = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    /** @return {@code true}, wenn aktuell ein Port geöffnet ist. */
    public boolean isConnected() {
        return activePort != null && activePort.isOpen();
    }

    /**
     * Öffnet den angegebenen Port. Ist bereits ein anderer Port offen, wird dieser zuerst
     * sauber getrennt.
     *
     * @return {@code true}, wenn das Öffnen erfolgreich war
     */
    public boolean connect(String portName, int baud) {
        if (isConnected()) {
            disconnect();
        }

        activePort = SerialPort.getCommPort(portName);
        activePort.setBaudRate(baud);
        activePort.setNumDataBits(8);
        activePort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        activePort.setParity(SerialPort.NO_PARITY);

        if (!activePort.openPort()) {
            activePort = null;
            return false;
        }

        receiveBuffer.setLength(0);

        activePort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                byte[] chunk = new byte[activePort.bytesAvailable()];
                int read = activePort.readBytes(chunk, chunk.length);
                if (read > 0) {
                    String text = new String(chunk, 0, read, StandardCharsets.UTF_8);
                    // Empfang läuft in einem Hintergrund-Thread von jSerialComm - Swing-
                    // Komponenten (und die Listener, die meist Swing anfassen) dürfen nur vom
                    // Event-Dispatch-Thread aus benachrichtigt werden.
                    SwingUtilities.invokeLater(() -> feed(text));
                }
            }
        });

        return true;
    }

    /** Schließt die aktuelle Verbindung, falls eine offen ist. */
    public void disconnect() {
        if (activePort != null) {
            activePort.closePort();
            activePort = null;
        }
    }

    /**
     * Registriert einen Listener, der für jede vollständige empfangene Zeile aufgerufen wird
     * (ohne das abschließende Zeilenumbruch-Zeichen).
     */
    public void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

    /** Entfernt einen zuvor registrierten Zeilen-Listener wieder. */
    public void removeLineListener(Consumer<String> listener) {
        lineListeners.remove(listener);
    }

    /** Sendet eine Befehlszeile (mit angehängtem Newline) an den verbundenen Port. */
    public void sendLine(String command) {
        if (!isConnected()) return;
        try {
            OutputStream out = activePort.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // Sendefehler werden hier bewusst verschluckt - Aufrufer können isConnected()
            // vorher prüfen; ein hart fehlschlagendes Senden soll die UI nicht abstürzen lassen.
        }
    }

    /** Puffert eingehenden Text und gibt jede vollständige Zeile an alle Listener weiter. */
    private void feed(String chunk) {
        receiveBuffer.append(chunk);

        int newlineIndex;
        while ((newlineIndex = receiveBuffer.indexOf("\n")) >= 0) {
            String line = receiveBuffer.substring(0, newlineIndex);
            receiveBuffer.delete(0, newlineIndex + 1);

            line = line.replace("\r", "");
            if (!line.isEmpty()) {
                for (Consumer<String> listener : new ArrayList<>(lineListeners)) {
                    listener.accept(line);
                }
            }
        }
    }
}