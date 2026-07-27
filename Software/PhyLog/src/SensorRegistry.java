import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Verwaltet alle verfügbaren Sensor-Implementierungen. */
public class SensorRegistry {

    private static final List<Sensor> REGISTERED_SENSORS = new ArrayList<>();
    public static final Sensor NO_SENSOR = new NoSensor();

    static {
        REGISTERED_SENSORS.add(NO_SENSOR);
        REGISTERED_SENSORS.add(new INA219VoltageSensor());
        REGISTERED_SENSORS.add(new INA219CurrentSensor());
        REGISTERED_SENSORS.add(new VEML7700Sensor());
    }

    /** @return unveränderliche Liste aller verfügbaren Sensoren. */
    public static List<Sensor> getAvailableSensors() {
        return Collections.unmodifiableList(REGISTERED_SENSORS);
    }

    /** Sucht einen registrierten Sensor anhand eines Einheiten-Strings, oder {@code null}. */
    public static Sensor findByUnit(String unitStr) {
        for (Sensor s : REGISTERED_SENSORS) {
            if (s != NO_SENSOR && s.matchesUnit(unitStr)) {
                return s;
            }
        }
        return null;
    }
}
