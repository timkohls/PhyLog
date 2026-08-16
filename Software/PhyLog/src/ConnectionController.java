import java.util.List;
import java.util.function.Consumer;

/**
 * Bündelt den Zugriff eines Fensters (GUI/Terminal) auf die geräteweit geteilte
 * {@link DeviceConnection}. Jede Instanz hält ihren eigenen Statuslistener.
 */
class ConnectionController {

    private final DeviceConnection connection = DeviceConnection.getInstance();
    private final Runnable statusListener;

    /**
     * Registriert {@code onStatusChanged} sofort bei {@link DeviceConnection}.
     * {@link #dispose()} muss beim Schließen des Fensters aufgerufen werden.
     */
    ConnectionController(Runnable onStatusChanged) {
        this.statusListener = onStatusChanged;
        connection.addConnectionListener(statusListener);
    }

    boolean isConnected() {
        return connection.isConnected();
    }

    boolean isBluetoothConnection() {
        return connection.isBluetoothConnection();
    }

    String getActivePortName() {
        return connection.getActivePortName();
    }

    List<String> listPortNames() {
        return connection.listPortNames();
    }

    /** Blockierender Aufruf - der Aufrufer muss selbst für einen Hintergrund-Thread sorgen. */
    String identifyPhyLogPort(List<String> candidatePortNames) {
        return connection.identifyPhyLogPort(candidatePortNames);
    }

    List<String> orderedIdentifyCandidates() {
        return connection.orderedIdentifyCandidates();
    }

    boolean connect(String portName, int baudRate) {
        return connection.connect(portName, baudRate);
    }

    void disconnect() {
        connection.disconnect();
    }

    void sendLine(String command) {
        connection.sendLine(command);
    }

    void addLineListener(Consumer<String> listener) {
        connection.addLineListener(listener);
    }

    /** Muss beim Schließen des Fensters aufgerufen werden, analog zu {@link #dispose()}. */
    void removeLineListener(Consumer<String> listener) {
        connection.removeLineListener(listener);
    }

    /** Meldet den Statuslistener wieder ab; ohne diesen Aufruf bleibt er über die Lebensdauer
     *  des Fensters hinaus registriert. */
    void dispose() {
        connection.removeConnectionListener(statusListener);
    }
}
