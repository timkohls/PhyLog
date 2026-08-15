import java.util.List;
import java.util.function.Consumer;

/**
 * Kapselt {@link GUI}s und {@link Terminal}s Zugriff auf die (geräteweit geteilte)
 * {@link DeviceConnection}: Verbindungsauf-/-abbau, Portliste, Befehlsversand sowie die
 * Lebensdauer des Verbindungsstatus-Listeners. Bündelt damit die zuvor über gut ein Dutzend
 * Stellen in {@link GUI} (und separat nochmal in {@link Terminal}) verstreuten direkten
 * {@code DeviceConnection.getInstance()}-Aufrufe an einer Stelle - beide Fenster kennen dadurch
 * nur noch diese Klasse, nicht mehr das Singleton selbst. Jedes Fenster hält seine eigene
 * {@link ConnectionController}-Instanz (eigener Statuslistener), da beide unterschiedlich auf
 * eine Statusänderung reagieren müssen (siehe {@link GUI#updateStatusLabel()} bzw.
 * {@link Terminal#onConnectionStatusChanged()}).
 *
 * <p>Reine Verbindungsverwaltung: Wie sich ein Statuswechsel auf Buttons, Menüeinträge oder das
 * Trigger-Status-Label auswirkt, bleibt bewusst beim jeweiligen Fenster - das ist UI-spezifische
 * Logik, keine Verbindungslogik, und gehört deshalb nicht hierher.</p>
 */
class ConnectionController {

    private final DeviceConnection connection = DeviceConnection.getInstance();
    private final Runnable statusListener;

    /**
     * Registriert {@code onStatusChanged} sofort bei {@link DeviceConnection}. Der Aufrufer muss
     * beim Schließen des Fensters {@link #dispose()} aufrufen, um sich wieder abzumelden - siehe
     * {@link GUI}s und {@link Terminal}s {@code WindowListener}.
     *
     * @param onStatusChanged wird bei jedem Verbindungsauf-/-abbau aufgerufen, unabhängig davon,
     *                        ob er über diese Instanz oder z. B. über das jeweils andere Fenster
     *                        ausgelöst wurde (siehe {@link DeviceConnection#addConnectionListener})
     */
    ConnectionController(Runnable onStatusChanged) {
        this.statusListener = onStatusChanged;
        connection.addConnectionListener(statusListener);
    }

    boolean isConnected() {
        return connection.isConnected();
    }

    /** Durchreiche zu {@link DeviceConnection#isBluetoothConnection()} - siehe dort. Genutzt von
     *  {@link GUI#updateStatusLabel()} für den Bluetooth-Bandbreiten-Hinweis. */
    boolean isBluetoothConnection() {
        return connection.isBluetoothConnection();
    }

    /** Durchreiche zu {@link DeviceConnection#getActivePortName()} - siehe dort. Genutzt von
     *  {@link Terminal}, um die Portauswahl mit einer bereits (z. B. über {@link GUI})
     *  bestehenden Verbindung zu synchronisieren. */
    String getActivePortName() {
        return connection.getActivePortName();
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

    /** Durchreiche zu {@link DeviceConnection#removeLineListener(Consumer)} - siehe dort. Muss
     *  vom Aufrufer beim Schließen des Fensters aufgerufen werden, analog zu {@link #dispose()}
     *  für den Statuslistener. */
    void removeLineListener(Consumer<String> listener) {
        connection.removeLineListener(listener);
    }

    /** Meldet den Statuslistener wieder ab - muss beim Schließen des Fensters aufgerufen werden,
     *  sonst bleibt der Aufrufer über die Lebensdauer des Fensters hinaus bei
     *  {@link DeviceConnection} registriert. */
    void dispose() {
        connection.removeConnectionListener(statusListener);
    }
}