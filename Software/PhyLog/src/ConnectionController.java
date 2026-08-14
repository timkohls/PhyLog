import java.util.List;
import java.util.function.Consumer;

/**
 * Kapselt {@link GUI}s Zugriff auf die (geräteweit geteilte) {@link DeviceConnection}: Verbindungs-
 * auf-/-abbau, Portliste, Befehlsversand sowie die Lebensdauer des Verbindungsstatus-Listeners.
 * Bündelt damit die zuvor über gut ein Dutzend Stellen in {@link GUI} verstreuten direkten
 * {@code DeviceConnection.getInstance()}-Aufrufe an einer Stelle - {@link GUI} kennt dadurch nur
 * noch diese Klasse, nicht mehr das Singleton selbst.
 *
 * <p>Reine Verbindungsverwaltung: Wie sich ein Statuswechsel auf Buttons, Menüeinträge oder das
 * Trigger-Status-Label auswirkt, bleibt bewusst in {@link GUI#updateStatusLabel()} - das ist
 * UI-spezifische Logik, keine Verbindungslogik, und gehört deshalb nicht hierher. {@link Terminal}
 * verwaltet seinen (kleineren) Listener weiterhin direkt über {@link DeviceConnection}, da es dort
 * nur um die Beschriftung eines einzelnen Buttons geht - eine gemeinsame Nutzung dieser Klasse
 * durch beide Fenster wäre ein sinnvoller nächster Schritt, sprengt aber den aktuellen Rahmen.</p>
 */
class ConnectionController {

    private final DeviceConnection connection = DeviceConnection.getInstance();
    private final Runnable statusListener;

    /**
     * Registriert {@code onStatusChanged} sofort bei {@link DeviceConnection}. Der Aufrufer muss
     * beim Schließen des Fensters {@link #dispose()} aufrufen, um sich wieder abzumelden - siehe
     * {@link GUI}s {@code WindowListener}.
     *
     * @param onStatusChanged wird bei jedem Verbindungsauf-/-abbau aufgerufen, unabhängig davon,
     *                        ob er über diese Instanz oder z. B. über das {@link Terminal}-Fenster
     *                        ausgelöst wurde (siehe {@link DeviceConnection#addConnectionListener})
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

    List<String> listPortNames() {
        return connection.listPortNames();
    }

    /** Durchreiche zu {@link DeviceConnection#identifyPhyLogPort(List)} - siehe dort. Läuft
     *  blockierend, der Aufrufer ({@link GUI}) muss selbst für einen Hintergrund-Thread sorgen. */
    String identifyPhyLogPort(List<String> candidatePortNames) {
        return connection.identifyPhyLogPort(candidatePortNames);
    }

    /** Durchreiche zu {@link DeviceConnection#orderedIdentifyCandidates()} - siehe dort. */
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

    /** Durchreiche zu {@link DeviceConnection#removeLineListener(Consumer)} - siehe dort. */
    void removeLineListener(Consumer<String> listener) {
        connection.removeLineListener(listener);
    }

    /** Durchreiche zu {@link DeviceConnection#getActivePortName()} - siehe dort. */
    String getActivePortName() {
        return connection.getActivePortName();
    }

    /** Meldet den Statuslistener wieder ab - muss beim Schließen des Fensters aufgerufen werden,
     *  sonst bleibt {@link GUI} über die Lebensdauer des Fensters hinaus bei {@link DeviceConnection}
     *  registriert. */
    void dispose() {
        connection.removeConnectionListener(statusListener);
    }
}