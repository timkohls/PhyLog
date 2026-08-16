import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Singleton zur Verwaltung der seriellen Verbindung zum ESP32 und Pufferung von Zeilen. */
public class DeviceConnection {

    private static final DeviceConnection INSTANCE = new DeviceConnection();

    public static DeviceConnection getInstance() {
        return INSTANCE;
    }

    private SerialPort activePort;
    private final StringBuilder receiveBuffer = new StringBuilder();
    private final List<Consumer<String>> lineListeners = new ArrayList<>();
    private final List<Runnable> connectionListeners = new ArrayList<>();

    /** Muss exakt zu {@code BT_DEVICE_NAME} in der Firmware passen. */
    private static final String BLUETOOTH_DEVICE_NAME = "PhyLog Bluetooth";
    private static final String SERIAL_LABEL = "PhyLog Seriell";

    /** USB-Vendor:Produkt-IDs bekannter USB-Seriell-Brückenchips auf gängigen ESP32-Boards
     *  (CP2102/CP2104, CH340, CH9102). Rein heuristisch - sagt nur, dass der USB-Chip zu einem
     *  üblichen Typ passt, nicht dass tatsächlich PhyLog-Firmware läuft. */
    private static final Set<String> KNOWN_USB_SERIAL_VID_PID = Set.of(
            "10C4:EA60", // Silicon Labs CP2102/CP2104
            "1A86:7523", // WCH CH340
            "1A86:55D4"  // WCH CH9102
    );

    /** Konservative Baudrate für Bluetooth-SPP-Ports: manche Treiber lehnen die für USB gedachte
     *  hohe Baudrate ab bzw. brechen die Verbindung kurz danach wieder ab. Wirkt sich nicht auf
     *  die tatsächliche Datenrate über den Funklink aus. */
    private static final int BLUETOOTH_SAFE_BAUD_RATE = 115200;

    /** Konservative Obergrenze für die Abtastrate über eine Bluetooth-Verbindung. */
    static final int BLUETOOTH_MAX_SAMPLE_RATE_HZ = 100;

    /** Zeitfenster für die Antwort eines einzelnen Ports in {@link #identifyPhyLogPort}. */
    private static final long IDENTIFY_TIMEOUT_MS = 1500;

    /** Wie lange ohne empfangene Daten toleriert wird, bevor eine laut {@code isOpen()} noch
     *  offene Verbindung trotzdem als verloren gilt - Rückfallebene für Fälle, in denen das
     *  Betriebssystem den Port fälschlich weiterhin als vorhanden meldet (z. B. nur die
     *  Stromversorgung des ESP32 gekappt, USB-Kabel bleibt gesteckt). */
    private static final long DATA_TIMEOUT_MS = 5000;

    /** Prüfintervall für {@link #DATA_TIMEOUT_MS}. */
    private static final int DATA_WATCHDOG_INTERVAL_MS = 1000;

    /** Wie lange ohne empfangene Daten gewartet wird, bevor ein PING als Lebenszeichen an die
     *  Firmware geschickt wird - kleiner als {@link #DATA_TIMEOUT_MS}, damit vor einer Trennung
     *  noch mindestens ein PING-Zyklus Zeit hat, eine Antwort zu liefern. Ohne angeschlossenen
     *  Sensor sendet die Firmware von sich aus keine Messwerte; PING/"#HELLO" ist der einzige
     *  Befehl, den sie unabhängig davon jederzeit beantwortet, und dient hier als Ersatz-Lebenszeichen. */
    private static final long PING_KEEPALIVE_MS = 2000;

    /** Läuft über {@link Timer} auf dem Event-Dispatch-Thread, da sowohl die Prüfung selbst als
     *  auch eine etwaige Reaktion darauf (siehe {@link #onPortDisconnected}) unkritisch/EDT-tauglich
     *  sind. Gestartet in {@link #connect}, gestoppt in {@link #onPortDisconnected}/{@link #disconnect}. */
    private Timer dataWatchdog;

    /** Zeitpunkt der zuletzt über {@link #feed} empfangenen Daten, siehe {@link #checkDataTimeout}. */
    private volatile long lastDataReceivedMillis;

    /** Eigener Hintergrund-Thread für alle über {@link #sendLine} verschickten Kommandos, damit
     *  ein blockierender Schreibzugriff nicht den aufrufenden Event-Dispatch-Thread einfriert.
     *  Ein einzelner Thread statt eines Pools, damit mehrere Kommandos in Reihenfolge ankommen.
     *  {@link #connect}/{@link #disconnect} schreiben START/STOP bewusst weiterhin synchron über
     *  {@link #writeBlocking}, da sie ohnehin schon in einem eigenen Hintergrund-Thread laufen und
     *  STOP garantiert vor dem anschließenden Schließen des Ports abgeschickt sein muss. */
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PhyLog-DeviceWriter");
        t.setDaemon(true);
        return t;
    });

    private DeviceConnection() {
    }

    /**
     * Ordnet einem Port, sofern er sicher zu PhyLog gehört, eine Anzeigebeschriftung zu; alle
     * anderen Ports bleiben unbeschriftet, statt mit einer unsicheren Vermutung aufzuwarten. Für
     * Bluetooth reicht die Treiberbeschreibung dafür oft nicht aus (viele Treiber liefern nur
     * eine generische Beschreibung ohne Gerätenamen) - die zuverlässige Unterscheidung übernimmt
     * in diesem Fall die echte Handshake-Probe in {@link #identifyPhyLogPort}.
     *
     * @return {@link #BLUETOOTH_DEVICE_NAME} oder {@link #SERIAL_LABEL}, oder {@code null}, falls
     *         der Port nicht sicher als PhyLog erkannt wurde
     */
    private static String detectPhyLogLabel(SerialPort port) {
        String description = port.getDescriptivePortName();
        if (description != null && description.contains(BLUETOOTH_DEVICE_NAME)) {
            return BLUETOOTH_DEVICE_NAME;
        }
        String vidPid = String.format("%04X:%04X", port.getVendorID(), port.getProductID());
        if (KNOWN_USB_SERIAL_VID_PID.contains(vidPid)) {
            return SERIAL_LABEL;
        }
        return null;
    }

    /** Generische Bluetooth-Erkennung, unabhängig davon, ob es sich um PhyLog handelt. */
    private static boolean looksLikeBluetooth(SerialPort port) {
        return containsIgnoreCase(port.getDescriptivePortName())
                || containsIgnoreCase(port.getPortDescription());
    }

    private static boolean containsIgnoreCase(String text) {
        return text != null && text.toLowerCase().contains("bluetooth");
    }

    private static boolean isBluetoothPort(SerialPort port) {
        return port != null && looksLikeBluetooth(port);
    }

    /**
     * Liefert die Anzeigenamen aller verfügbaren COM-Ports im Format
     * {@code "<Systemname> (<Beschriftung>)"} für sicher als PhyLog erkannte Ports, sonst nur den
     * reinen Systemnamen. {@link #connect} erwartet weiterhin den reinen Systemnamen - der
     * Aufrufer muss dafür {@link #stripDescription} anwenden.
     */
    public List<String> listPortNames() {
        List<String> names = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            String systemName = port.getSystemPortName();
            String label = detectPhyLogLabel(port);
            names.add(label != null ? systemName + " (" + label + ")" : systemName);
        }
        return names;
    }

    /**
     * Liefert die Kandidaten für {@link #identifyPhyLogPort}, priorisiert nach
     * Trefferwahrscheinlichkeit: zuerst erkannte USB-Ports, danach erkannte Bluetooth-Ports,
     * zuletzt alle übrigen. USB-Ports antworten ohne die bei Bluetooth-SPP üblichen Verzögerungen
     * meist binnen Millisekunden - ist ein Board über beide Wege erreichbar, findet die Suche es
     * so deutlich schneller.
     *
     * @return reine Systemnamen in Prioritätsreihenfolge, direkt an {@link #identifyPhyLogPort} übergebbar
     */
    public List<String> orderedIdentifyCandidates() {
        List<String> serial = new ArrayList<>();
        List<String> bluetooth = new ArrayList<>();
        List<String> others = new ArrayList<>();

        for (SerialPort port : SerialPort.getCommPorts()) {
            String systemName = port.getSystemPortName();
            String label = detectPhyLogLabel(port);
            if (SERIAL_LABEL.equals(label)) {
                serial.add(systemName);
            } else if (BLUETOOTH_DEVICE_NAME.equals(label)) {
                bluetooth.add(systemName);
            } else {
                others.add(systemName);
            }
        }

        List<String> ordered = new ArrayList<>(serial.size() + bluetooth.size() + others.size());
        ordered.addAll(serial);
        ordered.addAll(bluetooth);
        ordered.addAll(others);
        return ordered;
    }

    /**
     * Entfernt eine von {@link #listPortNames} angehängte Beschreibung wieder von einem
     * Anzeigenamen. Auf einen Namen ohne Klammerzusatz angewendet, liefert diese Methode ihn
     * unverändert zurück.
     */
    public static String stripDescription(String displayName) {
        int idx = displayName.indexOf(" (");
        return (idx > 0) ? displayName.substring(0, idx) : displayName;
    }

    /** @return {@code true}, wenn die aktuell aktive Verbindung über Bluetooth läuft. */
    public boolean isBluetoothConnection() {
        return isConnected() && isBluetoothPort(activePort);
    }

    public boolean isConnected() {
        return activePort != null && activePort.isOpen();
    }

    /** @return Systemname des aktuell verbundenen Ports, oder {@code null} ohne aktive Verbindung. */
    public String getActivePortName() {
        return isConnected() ? activePort.getSystemPortName() : null;
    }

    /**
     * Öffnet den angegebenen seriellen Port.
     *
     * @return {@code true}, wenn die Verbindung erfolgreich hergestellt wurde
     */
    public boolean connect(String portName, int baud) {
        if (isConnected()) {
            disconnect();
        }

        activePort = SerialPort.getCommPort(portName);

        // Siehe BLUETOOTH_SAFE_BAUD_RATE: die für USB gedachte Baudrate an einem Bluetooth-Port
        // führte dazu, dass die Verbindung kurz danach von selbst wieder abbrach.
        int effectiveBaud = isBluetoothPort(activePort) ? BLUETOOTH_SAFE_BAUD_RATE : baud;
        activePort.setBaudRate(effectiveBaud);
        activePort.setNumDataBits(8);
        activePort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        activePort.setParity(SerialPort.NO_PARITY);

        // Ohne explizite Timeouts können Lese-/Schreibzugriffe unbegrenzt blockieren, z. B. wenn
        // ein Bluetooth-Port zwar erfolgreich geöffnet, aber die Funkverbindung noch nicht
        // schreibbereit ist.
        activePort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 3000);

        if (!activePort.openPort()) {
            activePort = null;
            return false;
        }

        receiveBuffer.setLength(0);

        activePort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    SwingUtilities.invokeLater(DeviceConnection.this::onPortDisconnected);
                    return;
                }
                // bytesAvailable()/readBytes() können bei einem (auch nur kurzzeitigen)
                // Lesefehler auf dem Port -1 liefern, insbesondere unter Bluetooth-SPP. Ohne
                // diese Absicherung riss das new byte[-1] unten eine unbehandelte
                // NegativeArraySizeException auf diesem jSerialComm-eigenen Hintergrund-Thread
                // los - der Port lieferte danach keine weiteren Datenereignisse mehr, und der
                // dataWatchdog (siehe DATA_TIMEOUT_MS) trennte die Verbindung nach wenigen
                // Sekunden Funkstille wieder, obwohl sie physisch weiterhin bestand.
                try {
                    int available = activePort.bytesAvailable();
                    if (available <= 0) return;
                    byte[] chunk = new byte[available];
                    int read = activePort.readBytes(chunk, chunk.length);
                    if (read > 0) {
                        String text = new String(chunk, 0, read, StandardCharsets.UTF_8);
                        SwingUtilities.invokeLater(() -> feed(text));
                    }
                } catch (Exception ignored) {
                    // Ein einzelner missglückter Lesezyklus soll den Listener nicht lahmlegen -
                    // der nächste LISTENING_EVENT_DATA_AVAILABLE kommt regulär wieder.
                }
            }
        });

        // Aktiviert das kontinuierliche Streamen von Messwerten für die gesamte Dauer der
        // Verbindung, unabhängig von einer laufenden Aufzeichnung (Live-Anzeigen brauchen einen
        // durchgehenden Strom). Synchron statt über sendLine()/writeExecutor, siehe dortigen
        // Kommentar - #connect läuft ohnehin schon in einem eigenen Hintergrund-Thread.
        writeBlocking(activePort, "START");

        lastDataReceivedMillis = System.currentTimeMillis();
        startDataWatchdog();

        notifyConnectionListeners();
        return true;
    }

    private void startDataWatchdog() {
        stopDataWatchdog();
        dataWatchdog = new Timer(DATA_WATCHDOG_INTERVAL_MS, _ -> checkDataTimeout());
        dataWatchdog.start();
    }

    private void stopDataWatchdog() {
        if (dataWatchdog != null) {
            dataWatchdog.stop();
            dataWatchdog = null;
        }
    }

    /** Behandelt eine laut {@link #activePort} weiterhin offene, aber seit
     *  {@link #DATA_TIMEOUT_MS} ohne Daten gebliebene Verbindung wie einen physischen Abbruch -
     *  vorher wird ab {@link #PING_KEEPALIVE_MS} Funkstille ein PING als Lebenszeichen geschickt,
     *  damit eine Verbindung ohne angeschlossenen Sensor (die Firmware sendet dann von sich aus
     *  keine Daten) nicht fälschlich als abgebrochen gilt. */
    private void checkDataTimeout() {
        if (activePort == null) return;
        long idleMs = System.currentTimeMillis() - lastDataReceivedMillis;
        if (idleMs >= DATA_TIMEOUT_MS) {
            onPortDisconnected();
        } else if (idleMs >= PING_KEEPALIVE_MS) {
            sendLine("PING");
        }
    }

    /**
     * Probiert nacheinander jeden übergebenen Port kurz aus: öffnet ihn, schickt PING und prüft,
     * ob innerhalb von {@link #IDENTIFY_TIMEOUT_MS} eine Zeile mit "#HELLO" zurückkommt - eine
     * echte Handshake-Probe statt der (bei Bluetooth oft nutzlosen) Treiberbeschreibung.
     *
     * <p>Läuft sequenziell und blockierend; der Aufrufer muss das selbst in einen
     * Hintergrund-Thread auslagern. Darf nicht aufgerufen werden, während bereits eine Verbindung
     * aktiv ist.</p>
     *
     * @return Systemname des ersten antwortenden Ports, oder {@code null}
     */
    public String identifyPhyLogPort(List<String> candidatePortNames) {
        for (String name : candidatePortNames) {
            if (probePortForHello(name)) {
                return name;
            }
        }
        return null;
    }

    /** Öffnet, testet (PING -&gt; "#HELLO"?) und schließt genau einen Port für
     *  {@link #identifyPhyLogPort}. */
    private boolean probePortForHello(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(isBluetoothPort(port) ? BLUETOOTH_SAFE_BAUD_RATE : 460800);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                (int) IDENTIFY_TIMEOUT_MS, 1000);

        if (!port.openPort()) {
            return false;
        }

        try {
            OutputStream out = port.getOutputStream();
            out.write("PING\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder response = new StringBuilder();
            long deadline = System.currentTimeMillis() + IDENTIFY_TIMEOUT_MS;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < deadline) {
                int available = port.bytesAvailable();
                if (available > 0) {
                    int read = port.readBytes(buf, Math.min(buf.length, available));
                    if (read > 0) {
                        response.append(new String(buf, 0, read, StandardCharsets.UTF_8));
                        if (response.indexOf("#HELLO") >= 0) {
                            return true;
                        }
                    }
                } else {
                    // Pause gegen Busy-Polling - eliminiert die CPU-Last praktisch, ohne die
                    // Reaktionszeit spürbar zu verschlechtern.
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        } finally {
            port.closePort();
        }
    }

    /** Reagiert auf einen physischen Verbindungsabbruch (Kabel gezogen, Board aus,
     *  Bluetooth-Link verloren) - ein STOP-Schreibversuch ergibt hier keinen Sinn mehr, das
     *  Schließen des Ports (zur Freigabe des Systemhandles) dagegen schon. */
    private void onPortDisconnected() {
        if (activePort == null) return;
        SerialPort port = activePort;
        activePort = null;
        receiveBuffer.setLength(0);
        stopDataWatchdog();
        try {
            port.closePort();
        } catch (Exception ignored) {
        }
        notifyConnectionListeners();
    }

    /** Schließt den aktuell geöffneten Port. */
    public void disconnect() {
        if (activePort != null) {
            // Synchron statt über sendLine()/writeExecutor: STOP muss nachweislich abgeschickt
            // sein, bevor der Port direkt im Anschluss geschlossen wird.
            writeBlocking(activePort, "STOP");
            activePort.closePort();
            activePort = null;
            stopDataWatchdog();
            notifyConnectionListeners();
        }
    }

    public void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

    public void removeLineListener(Consumer<String> listener) {
        lineListeners.remove(listener);
    }

    /** Der Listener bekommt nur die Information "Zustand hat sich geändert" -
     *  {@link #isConnected()} liefert den aktuellen Stand. */
    public void addConnectionListener(Runnable listener) {
        connectionListeners.add(listener);
    }

    public void removeConnectionListener(Runnable listener) {
        connectionListeners.remove(listener);
    }

    /** Über {@link SwingUtilities#invokeLater}, da {@link #connect}/{@link #disconnect} aus
     *  einem Hintergrund-Thread heraus laufen können, die Listener selbst aber Swing-Komponenten
     *  anfassen und deshalb auf dem Event-Dispatch-Thread laufen müssen. */
    private void notifyConnectionListeners() {
        for (Runnable listener : new ArrayList<>(connectionListeners)) {
            SwingUtilities.invokeLater(listener);
        }
    }

    /**
     * Sendet einen Befehl inklusive Zeilenumbruch an das Gerät - asynchron über
     * {@link #writeExecutor}, damit ein aufrufender Event-Dispatch-Thread nie auf einen
     * blockierenden seriellen Schreibzugriff wartet. Fängt {@code activePort} beim Aufruf ab,
     * damit ein zwischenzeitliches {@link #disconnect} hier nicht zu einer NullPointerException führt.
     */
    public void sendLine(String command) {
        if (!isConnected()) return;
        SerialPort port = activePort;
        writeExecutor.submit(() -> writeBlocking(port, command));
    }

    private void writeBlocking(SerialPort port, String command) {
        if (port == null) return;
        try {
            OutputStream out = port.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    /** Fügt Text zum Puffer hinzu und verteilt vollständige Zeilen an Listener. */
    private void feed(String chunk) {
        lastDataReceivedMillis = System.currentTimeMillis();
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