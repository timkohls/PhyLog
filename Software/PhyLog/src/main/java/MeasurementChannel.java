import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bündelt alles, was pro Messkanal (A oder B) getrennt gehalten werden muss: Tabelle, aktiver
 * Sensor, letzter Live-Wert, Tara-Offset sowie der Ringpuffer für den Trigger-Vorlauf.
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

    /** Rollierender Puffer der letzten Samples (Millis, Wert) für den Trigger-Vorlauf. */
    public final Deque<double[]> preTriggerBuffer = new ArrayDeque<>();
    /** Vorheriger Wert des Trigger-Kanals, um eine Schwellenwert-Überschreitung als
     *  Vorzeichenwechsel zu erkennen. */
    public Double lastValueForEdge = null;

    /** {@code true}, solange die erste Tabellenspalte den fortlaufenden Index einzelner
     *  Momentaufnahmen enthält statt der vergangenen Zeit einer laufenden Aufzeichnung. */
    public boolean snapshotMode = false;

    public MeasurementChannel(char id) {
        this.id = id;
    }

    public boolean hasSensor() {
        return sensor != null && sensor != SensorRegistry.NO_SENSOR;
    }

    /** @return ob der aktuelle Sensor ein Frequenzspektrum statt Zeitreihen-Messwerte liefert. */
    public boolean producesSpectrum() {
        return hasSensor() && sensor.producesSpectrum();
    }
}
