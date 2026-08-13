import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import javax.swing.SwingUtilities;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** Reagiert auf Verbindungsauf-/-abbau, unabhängig davon, über welches Fenster ({@link GUI}
     *  oder {@link Terminal}) er ausgelöst wurde - so kann jedes Fenster seine Button-Beschriftung
     *  am tatsächlichen, gemeinsamen Zustand ausrichten statt an einer lokal mitgeführten Kopie. */
    private final List<Runnable> connectionListeners = new ArrayList<>();

    /** Muss exakt zu {@code BT_DEVICE_NAME} in phylog_firmware.ino passen - so heißt der ESP32 in
     *  der Bluetooth-Geräteliste, und diese Zeichenkette taucht deshalb (je nach Betriebssystem
     *  meist vollständig) in {@code getDescriptivePortName()} des daraus entstehenden virtuellen
     *  COM-Ports auf. Ändert sich der Firmware-Name, muss diese Konstante mitgeändert werden -
     *  sonst wird der Bluetooth-Port nicht mehr erkannt (fällt dann einfach auf die
     *  unbeschriftete "COM7"-Anzeige zurück, kein Absturz). */
    private static final String BLUETOOTH_DEVICE_NAME = "PhyLog Bluetooth";
    private static final String SERIAL_LABEL = "PhyLog Seriell";

    /** USB-Vendor:Produkt-IDs bekannter USB-Seriell-Brückenchips auf gängigen (klassischen)
     *  ESP32-Boards - CP2102/CP2104 (Silicon Labs), CH340 sowie das neuere CH9102, das seit ein
     *  paar Jahren auf vielen ESP32-DevKitC-Nachbauten statt CH340 verbaut ist. Rein heuristisch:
     *  Wir können ohne tatsächliche Verbindung nicht wissen, ob am anderen Ende wirklich PhyLog-
     *  Firmware läuft - nur, dass der USB-Chip zu einem der auf ESP32-Boards üblichen Typen passt.
     *  Bei mehreren gleichzeitig angeschlossenen Boards mit demselben Chip werden entsprechend
     *  alle als "PhyLog Seriell" beschriftet. Nutzt euer Board einen anderen Chip, hier ergänzen -
     *  die ID steht z. B. im Windows-Geräte-Manager unter den Anschlusseigenschaften. */
    private static final Set<String> KNOWN_USB_SERIAL_VID_PID = Set.of(
            "10C4:EA60", // Silicon Labs CP2102/CP2104
            "1A86:7523", // WCH CH340
            "1A86:55D4"  // WCH CH9102
    );

    /** Bluetooth-SPP-Ports sind virtuelle serielle Ports ohne echte physikalische Baudrate (RFCOMM
     *  überträgt einfach einen Bytestrom über den Funklink) - die für USB gedachte, sehr hohe
     *  {@code BAUD_RATE} aus GUI.java (460800) lehnen manche Bluetooth-Treiber beim Öffnen des
     *  virtuellen Ports ab bzw. brechen die gerade erst aufgebaute Verbindung wenige Sekunden
     *  später wieder ab, weil sie eine "reguläre" UART-Baudrate erwarten. Ein konservativer
     *  Standard-Wert hier wirkt sich nicht auf die tatsächliche Datenrate über den Funklink aus -
     *  die bestimmt einzig die Bluetooth-Verbindung selbst (siehe {@link #BLUETOOTH_MAX_SAMPLE_RATE_HZ}). */
    private static final int BLUETOOTH_SAFE_BAUD_RATE = 115200;

    /** Konservative Obergrenze für die Abtastrate über eine Bluetooth-Verbindung, siehe
     *  {@link SensorConfigDialog#refreshSampleRateOptions()} und den Bluetooth-Hinweis in
     *  {@link GUI}. Klassisches Bluetooth-SPP schafft je nach Stack/Umgebung oft nur einen
     *  Bruchteil der über USB möglichen ~46 KB/s (460800 Baud) - eine feste Zahl statt einer
     *  Messung zur Laufzeit, bewusst konservativ geschätzt und nicht durch eigene Messungen an
     *  echter Hardware abgesichert; falls sich in der Praxis zeigt, dass mehr stabil geht (oder
     *  auch das schon zu viel ist), hier anpassen. */
    static final int BLUETOOTH_MAX_SAMPLE_RATE_HZ = 100;

    /** Zeitfenster für die Antwort eines einzelnen Ports in {@link #identifyPhyLogPort} - lang
     *  genug für einen frisch geöffneten Bluetooth-SPP-Link (siehe Zeitproblem im Kommentar bei
     *  {@link #connect}), kurz genug, um mehrere Ports in vertretbarer Gesamtzeit durchzuprobieren. */
    private static final long IDENTIFY_TIMEOUT_MS = 1500;

    /** Eigener Hintergrund-Thread für alle über {@link #sendLine} verschickten Kommandos. Ohne das
     *  würde ein blockierender {@code OutputStream#write()} (siehe {@code TIMEOUT_WRITE_BLOCKING}
     *  in {@link #connect}, bis zu 3s bei einer gerade stockenden Bluetooth-Verbindung) direkt den
     *  aufrufenden Thread einfrieren - und das ist für so gut wie alle {@link #sendLine}-Aufrufer
     *  (Terminal-Kommandos, Sensorwechsel im SensorConfigDialog, RATE-Änderung) der Event-Dispatch-
     *  Thread, also genau der Thread, der niemals blockieren darf. Ein einzelner Thread statt eines
     *  Pools, damit mehrere kurz hintereinander abgeschickte Kommandos in der richtigen Reihenfolge
     *  beim Gerät ankommen. {@link #connect}/{@link #disconnect} schreiben START/STOP bewusst
     *  weiterhin synchron über {@link #writeBlocking} statt über diese Warteschlange - sie laufen
     *  ohnehin schon in einem eigenen Hintergrund-Thread (siehe GUI.java/Terminal.java, SwingWorker)
     *  und STOP muss garantiert vor dem anschließenden {@code closePort()} abgeschickt sein, nicht
     *  nur "irgendwann später" in der Warteschlange landen. */
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PhyLog-DeviceWriter");
        t.setDaemon(true);
        return t;
    });

    private DeviceConnection() {
    }

    /**
     * Ordnet einem Port, sofern er wahrscheinlich zu PhyLog gehört, eine Anzeigebeschriftung zu.
     * Für Bluetooth zweistufig: Enthält die Beschreibung tatsächlich {@link #BLUETOOTH_DEVICE_NAME},
     * ist es sicher der PhyLog-ESP32. In der Praxis (siehe Erfahrungsbericht, Windows) liefert der
     * Bluetooth-SPP-Treiber dort aber meist nur eine generische Beschreibung wie "Standard Serial
     * over Bluetooth link" - der beim Pairing vergebene Gerätename taucht darin gar nicht auf,
     * jSerialComm hat keinen Zugriff auf die eigentliche Bluetooth-API, um das nachzuschlagen. In
     * diesem (häufigeren) Fall erkennen wir zwar zuverlässig "das ist irgendein Bluetooth-Port",
     * aber nicht sicher, ob es wirklich PhyLog ist (falls mehrere Bluetooth-Geräte gepairt sind,
     * z. B. Kopfhörer) - deshalb dann nur "Bluetooth" statt "PhyLog Bluetooth", um nichts
     * fälschlich zu behaupten. Nachteil: nach Aktivieren von Bluetooth am PC können dadurch auch
     * mit PhyLog nichts zu tun habende Ports ein "(Bluetooth)" bekommen - eine zuverlässigere
     * Unterscheidung bräuchte Zugriff auf die native Windows-Bluetooth-API (welches Gerät hinter
     * welchem COM-Port steckt), was jSerialComm nicht bietet.
     *
     * <p>{@link #SERIAL_LABEL} für ein USB-verbundenes Board mit bekanntem Brückenchip (siehe
     * {@link #KNOWN_USB_SERIAL_VID_PID}), sonst {@code null} - alle anderen Ports bleiben dann
     * unbeschriftet.</p>
     *
     * @return Anzeigebeschriftung oder {@code null}, falls der Port nicht als PhyLog/Bluetooth erkannt wurde.
     */
    private static String detectPhyLogLabel(SerialPort port) {
        String description = port.getDescriptivePortName();
        if (description != null && description.contains(BLUETOOTH_DEVICE_NAME)) {
            return BLUETOOTH_DEVICE_NAME;
        }
        if (looksLikeBluetooth(port)) {
            return "Bluetooth";
        }
        String vidPid = String.format("%04X:%04X", port.getVendorID(), port.getProductID());
        if (KNOWN_USB_SERIAL_VID_PID.contains(vidPid)) {
            return SERIAL_LABEL;
        }
        return null;
    }

    /** Generische Bluetooth-Erkennung (unabhängig davon, ob es sich um PhyLog handelt) - prüft
     *  sowohl {@code getDescriptivePortName()} als auch {@code getPortDescription()}, da je nach
     *  Betriebssystem/jSerialComm-Version das eine oder andere Feld die aussagekräftigere
     *  Treiberbeschreibung liefert. Bewusst {@code "bluetooth"} klein und ohne Ländercode-Prüfung -
     *  der Markenname "Bluetooth" bleibt auch in lokalisierten (z. B. deutschen) Windows-
     *  Treiberbeschreibungen normalerweise unübersetzt. */
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
     * Liefert die Anzeigenamen aller verfügbaren COM-Ports - im Format
     * {@code "<Systemname> (<Beschriftung>)"} für als PhyLog erkannte Ports (siehe
     * {@link #detectPhyLogLabel}), sonst nur der reine Systemname. Damit lassen sich der per
     * Bluetooth-SPP gepairte ESP32 und ein per USB verbundenes Board auf einen Blick erkennen,
     * ohne die - insbesondere nach Aktivieren von Bluetooth am PC oft zahlreichen - Ports zu
     * verwirrenden, mit PhyLog nichts zu tun habenden System-/Treiberbeschreibungen zu überladen.
     *
     * <p>{@link #connect} erwartet weiterhin den reinen Systemnamen (z. B. "COM5") - siehe
     * {@link #stripDescription}, das ein Aufrufer (aktuell {@link GUI} und {@link Terminal}) auf
     * den ausgewählten Eintrag anwenden muss, bevor er ihn an {@link #connect} übergibt.</p>
     *
     * @return Anzeigenamen aller verfügbaren COM-Ports.
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
     * Entfernt eine von {@link #listPortNames} angehängte Beschreibung wieder von einem
     * Anzeigenamen, sodass nur der reine Systemname übrig bleibt, den {@link #connect} erwartet.
     * Auf einen Namen ohne Klammerzusatz (z. B. weil er manuell in ein editierbares Auswahlfeld
     * eingetippt wurde, siehe {@link GUI}) angewendet, liefert diese Methode ihn unverändert
     * zurück.
     *
     * @param displayName Anzeigename, ggf. inkl. Beschreibung (siehe {@link #listPortNames}).
     * @return Reiner Systemname, wie ihn {@link #connect} erwartet.
     */
    public static String stripDescription(String displayName) {
        int idx = displayName.indexOf(" (");
        return (idx > 0) ? displayName.substring(0, idx) : displayName;
    }

    /**
     * @return {@code true}, wenn die aktuell aktive Verbindung über Bluetooth läuft (erkannt an
     *         der Treiberbeschreibung des Ports, siehe {@link #looksLikeBluetooth}) - {@code false}
     *         sowohl bei USB-Verbindung als auch ganz ohne aktive Verbindung.
     */
    public boolean isBluetoothConnection() {
        return isConnected() && isBluetoothPort(activePort);
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

        // Siehe BLUETOOTH_SAFE_BAUD_RATE: die vom Aufrufer übergebene USB-Baudrate an einem
        // Bluetooth-SPP-Port zu setzen, hat bei uns dazu geführt, dass die Verbindung wenige
        // Sekunden nach dem Verbinden von selbst wieder abbricht - vermutlich lehnt der
        // Bluetooth-Treiber die ungewöhnlich hohe Baudrate ab. Wirkt sich nicht auf die
        // tatsächliche Datenrate über den Funklink aus, die kennt gar keine Baudrate im
        // eigentlichen Sinn.
        int effectiveBaud = isBluetoothPort(activePort) ? BLUETOOTH_SAFE_BAUD_RATE : baud;
        activePort.setBaudRate(effectiveBaud);
        activePort.setNumDataBits(8);
        activePort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        activePort.setParity(SerialPort.NO_PARITY);

        // Ohne explizite Timeouts können Lese-/Schreibzugriffe (siehe #sendLine) im ungünstigen
        // Fall unbegrenzt blockieren - z. B. wenn ein frisch verbundener Bluetooth-SPP-Port laut
        // openPort() zwar erfolgreich geöffnet ist, aber tatsächlich noch nicht schreibbereit,
        // weil die zugrundeliegende Funkverbindung noch nicht vollständig steht. Genau das dürfte
        // dazu geführt haben, dass die komplette Oberfläche beim Verbindungsaufbau eingefroren
        // ist - #connect lief bisher direkt im Swing-Event-Dispatch-Thread (siehe GUI.java/
        // Terminal.java, dort jetzt in einem Hintergrund-Thread). Ein Timeout verwandelt ein
        // unbegrenztes Hängenbleiben wenigstens in ein fehlschlagendes read()/write() nach
        // spätestens ein paar Sekunden.
        activePort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 3000);

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

        // Aktiviert das kontinuierliche Streamen von Messwerten für die gesamte Dauer der
        // Verbindung (siehe processCommand()/isStreaming in phylog_firmware.ino) - unabhängig
        // von einer laufenden Aufzeichnung. AcquisitionEngine schreibt eingehende Werte je nach
        // Aufzeichnungsstatus zwar nur bedingt in die Tabelle, aktualisiert aber immer
        // MeasurementChannel#latestValue; Live-Anzeigen (Kalibrierdialog, Momentaufnahme-Knopf)
        // brauchen dafür einen durchgehenden Strom vom Gerät, nicht nur während Start/Stop.
        // Synchron statt über sendLine()/writeExecutor, siehe Kommentar bei writeExecutor -
        // #connect läuft ohnehin schon in einem eigenen Hintergrund-Thread der Aufrufer.
        writeBlocking(activePort, "START");

        notifyConnectionListeners();
        return true;
    }

    /**
     * Probiert nacheinander jeden der übergebenen Ports kurz aus: öffnet ihn, schickt PING und
     * prüft, ob innerhalb von {@link #IDENTIFY_TIMEOUT_MS} eine Zeile mit "#HELLO" zurückkommt.
     * Anders als {@link #detectPhyLogLabel} verlässt sich das nicht auf die (bei Bluetooth-SPP
     * unter Windows oft nutzlose, siehe dortiger Kommentar) Treiberbeschreibung, sondern auf eine
     * echte Handshake-Probe mit dem tatsächlichen PhyLog-Protokoll - funktioniert deshalb auch,
     * wenn mehrere generisch als "Bluetooth" gelistete Ports zur Auswahl stehen.
     *
     * <p>Läuft rein sequenziell und blockierend (mehrere Ports x {@link #IDENTIFY_TIMEOUT_MS}
     * können sich zu mehreren Sekunden summieren) - der Aufrufer (aktuell nur {@link GUI}) muss
     * das selbst in einen Hintergrund-Thread auslagern, ganz wie bei {@link #connect}. Darf nicht
     * aufgerufen werden, während bereits eine Verbindung aktiv ist ({@link #isConnected()}), da
     * sonst der gerade aktive Port hier mit angefasst würde - das muss der Aufrufer vorher
     * prüfen.</p>
     *
     * @param candidatePortNames reine Systemnamen (kein Klammerzusatz), z. B. aus
     *                           {@link #listPortNames()} nach Anwendung von {@link #stripDescription}
     * @return Systemname des ersten Ports, der mit "#HELLO" geantwortet hat, oder {@code null},
     *         falls keiner geantwortet hat
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
     *  {@link #identifyPhyLogPort}. Nutzt für Bluetooth-Ports dieselbe {@link #BLUETOOTH_SAFE_BAUD_RATE}
     *  wie {@link #connect}, aus demselben Grund (siehe dortiger Kommentar). */
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
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        } finally {
            port.closePort();
        }
    }

    /**
     * Schließt den aktuell geöffneten Port.
     */
    public void disconnect() {
        if (activePort != null) {
            // Synchron statt über sendLine()/writeExecutor, siehe Kommentar bei writeExecutor:
            // STOP muss nachweislich abgeschickt sein, bevor der Port direkt im Anschluss
            // geschlossen wird - über die asynchrone Warteschlange wäre die Reihenfolge (bzw. ob
            // die Zeile überhaupt noch vor dem Schließen rausgeht) nicht mehr garantiert.
            writeBlocking(activePort, "STOP");
            activePort.closePort();
            activePort = null;
            notifyConnectionListeners();
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
     * Registriert einen Listener für Verbindungsauf- und -abbau. Der Listener bekommt nur die
     * Information "Zustand hat sich geändert" - {@link #isConnected()} liefert den aktuellen
     * Stand, ein zusätzlicher Boolean-Parameter wäre daher redundant.
     *
     * @param listener Callback, der bei jeder Zustandsänderung aufgerufen wird.
     */
    public void addConnectionListener(Runnable listener) {
        connectionListeners.add(listener);
    }

    /**
     * Entfernt einen registrierten Verbindungsstatus-Listener.
     *
     * @param listener Zu entfernender Callback.
     */
    public void removeConnectionListener(Runnable listener) {
        connectionListeners.remove(listener);
    }

    /**
     * Benachrichtigt alle registrierten Verbindungsstatus-Listener über eine Zustandsänderung.
     * Über {@link SwingUtilities#invokeLater} statt direktem Aufruf, weil {@link #connect} und
     * {@link #disconnect} inzwischen aus einem Hintergrund-Thread heraus laufen können (siehe
     * GUI.java/Terminal.java, SwingWorker) - die Listener selbst ({@code GUI#updateStatusLabel},
     * {@code Terminal#updateConnectButtonLabel}) fassen aber Swing-Komponenten an und müssen
     * deshalb auf dem Event-Dispatch-Thread laufen, unabhängig davon, aus welchem Thread heraus
     * die Verbindungsänderung ausgelöst wurde.
     */
    private void notifyConnectionListeners() {
        for (Runnable listener : new ArrayList<>(connectionListeners)) {
            SwingUtilities.invokeLater(listener);
        }
    }

    /**
     * Sendet einen Befehl inklusive Zeilenumbruch an das Gerät - asynchron über
     * {@link #writeExecutor}, damit ein aufrufender Event-Dispatch-Thread (der übliche Fall,
     * siehe Kommentar bei {@link #writeExecutor}) nie auf einen blockierenden seriellen
     * Schreibzugriff wartet. Fängt {@code activePort} beim Aufruf statt erst im Hintergrund-Thread
     * ab, damit ein zwischenzeitliches {@link #disconnect} (das {@code activePort} auf
     * {@code null} setzt) hier nicht zu einer stillen {@code NullPointerException} im
     * Hintergrund-Thread führt.
     *
     * @param command Zu sendender Text.
     */
    public void sendLine(String command) {
        if (!isConnected()) return;
        SerialPort port = activePort;
        writeExecutor.submit(() -> writeBlocking(port, command));
    }

    /** Tatsächlicher, blockierender Schreibzugriff - von {@link #sendLine} (über
     *  {@link #writeExecutor}, also nie auf dem Event-Dispatch-Thread) sowie von {@link #connect}/
     *  {@link #disconnect} für START/STOP direkt (synchron, siehe dortige Kommentare) genutzt. */
    private void writeBlocking(SerialPort port, String command) {
        if (port == null) return;
        try {
            OutputStream out = port.getOutputStream();
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