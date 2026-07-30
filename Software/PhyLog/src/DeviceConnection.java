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
 * Zentrale, geteilte serielle Verbindung zum ESP32. {@link Terminal} und {@link GUI} sprechen
 * beide über diese eine Instanz mit der Hardware, statt je einen eigenen {@link SerialPort} zu
 * öffnen - zwei gleichzeitig geöffnete Verbindungen zum selben COM-Port würden sich gegenseitig
 * blockieren.
 *
 * <p>Übernimmt außerdem das Zeilen-Buffering: eingehende Daten kommen in beliebig geschnittenen
 * Chunks an, Listener werden erst benachrichtigt, sobald eine vollständige, mit '\n'
 * abgeschlossene Zeile zusammengesetzt wurde.</p>
 */
public class DeviceConnection {

    private static final DeviceConnection INSTANCE = new DeviceConnection();

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

    public boolean isConnected() {
        return activePort != null && activePort.isOpen();
    }

    /** Öffnet den angegebenen Port (schließt zuvor einen ggf. offenen anderen Port).
     *  @return {@code true}, wenn das Öffnen erfolgreich war */
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
                    // Empfang läuft im Hintergrund-Thread von jSerialComm - Swing darf nur
                    // vom Event-Dispatch-Thread aus angefasst werden.
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

    /** Registriert einen Listener, der für jede vollständige empfangene Zeile aufgerufen wird
     *  (ohne abschließenden Zeilenumbruch). */
    public void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

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
            // Sendefehler bewusst verschluckt - Aufrufer prüfen ggf. vorher isConnected().
        }
    }

    /** Puffert eingehenden Text und gibt jede vollständige Zeile an alle Listener weiter. */
    private void feed(String chunk) {
        receiveBuffer.append(chunk);

        int newlineIndex;
        while ((newlineIndex = receiveBuffer.indexOf("\n")) >= 0) {
            String line = receiveBuffer.substring(0, newlineIndex).replace("\r", "");
            receiveBuffer.delete(0, newlineIndex + 1);

            if (!line.isEmpty()) {
                for (Consumer<String> listener : new ArrayList<>(lineListeners)) {
                    listener.accept(line);
                }
            }
        }
    }
}
