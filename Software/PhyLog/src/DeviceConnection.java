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
 * Singleton zur Verwaltung der seriellen Verbindung zum ESP32 und Pufferung von Zeilen.
 */
public class DeviceConnection {

    private static final DeviceConnection INSTANCE = new DeviceConnection();

    /**
     * @return Die globale Instanz der Verbindung.
     */
    public static DeviceConnection getInstance() {
        return INSTANCE;
    }

    private SerialPort activePort;
    private final StringBuilder receiveBuffer = new StringBuilder();
    private final List<Consumer<String>> lineListeners = new ArrayList<>();

    private DeviceConnection() {
    }

    /**
     * @return Liste der Namen aller verfügbaren COM-Ports.
     */
    public List<String> listPortNames() {
        List<String> names = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    /**
     * @return {@code true}, wenn eine serielle Verbindung aktiv ist.
     */
    public boolean isConnected() {
        return activePort != null && activePort.isOpen();
    }

    /**
     * Öffnet den angegebenen seriellen Port.
     *
     * @param portName Name des Ports (z. B. "COM5").
     * @param baud     Baudrate.
     * @return {@code true}, wenn die Verbindung erfolgreich hergestellt wurde.
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
                    SwingUtilities.invokeLater(() -> feed(text));
                }
            }
        });

        return true;
    }

    /**
     * Schließt den aktuell geöffneten Port.
     */
    public void disconnect() {
        if (activePort != null) {
            activePort.closePort();
            activePort = null;
        }
    }

    /**
     * Registriert einen Listener für empfangene Datenzeilen.
     *
     * @param listener Callback für vollständige Zeilen.
     */
    public void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

    /**
     * Entfernt einen registrierten Datenzeilen-Listener.
     *
     * @param listener Zu entfernender Callback.
     */
    public void removeLineListener(Consumer<String> listener) {
        lineListeners.remove(listener);
    }

    /**
     * Sendet einen Befehl inklusive Zeilenumbruch an das Gerät.
     *
     * @param command Zu sendender Text.
     */
    public void sendLine(String command) {
        if (!isConnected()) return;
        try {
            OutputStream out = activePort.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    /**
     * Fügt Text zum Puffer hinzu und verteilt vollständige Zeilen an Listener.
     */
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