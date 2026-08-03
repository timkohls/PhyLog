import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bündelt alles, was pro Messkanal (A oder B) getrennt gehalten werden muss: Tabelle, aktiver
 * Sensor, letzter Live-Wert, Tara-Offset sowie den kurzen Ringpuffer für den Trigger-Vorlauf.
 * Ersetzt die früher in {@link GUI} parallel geführten *A/*B-Feld- und Methodenpaare durch je
 * eine gemeinsame, kanal-parametrisierte Stelle - genutzt sowohl von {@link GUI} (Tabellen,
 * Sensorauswahl) als auch von {@link AcquisitionEngine} (Trigger- und Aufzeichnungslogik).
 */
public class MeasurementChannel {

    public final char id;
    public DefaultTableModel tableModel;
    public JTable table;
    public JScrollPane scrollPane;
    public Sensor sensor = SensorRegistry.NO_SENSOR;
    /** Letzter gültiger, tarierter Messwert für die Live-Anzeige im Konfigurationsdialog. */
    public volatile Double latestValue = null;
    public double tareOffset = 0.0;

    /** Rollierender Puffer der letzten Samples (Millis, Wert) für den Trigger-Vorlauf - unabhängig
     *  davon, ob dieser Kanal selbst der Trigger-Kanal ist, da im Trigger-Moment beide Kanäle mit
     *  Vorlauf befüllt werden (siehe {@link AcquisitionEngine}). */
    public final Deque<double[]> preTriggerBuffer = new ArrayDeque<>();
    /** Vorheriger Wert des Trigger-Kanals, um eine Schwellenwert-Überschreitung als
     *  Vorzeichenwechsel zu erkennen. */
    public Double lastValueForEdge = null;

    public MeasurementChannel(char id) {
        this.id = id;
    }

    public boolean hasSensor() {
        return sensor != null && sensor != SensorRegistry.NO_SENSOR;
    }
}
