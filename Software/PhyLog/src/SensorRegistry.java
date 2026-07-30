import java.util.List;

/** Verwaltet alle verfügbaren Sensor-Implementierungen. */
public class SensorRegistry {

    public static final Sensor NO_SENSOR = new NoSensor();

    private static final List<Sensor> REGISTERED_SENSORS = List.of(
            NO_SENSOR,
            new INA219VoltageSensor(),
            new INA219CurrentSensor(),
            new VEML7700Sensor(),
            new HX711Sensor(),
            new MicrophoneSensor()
    );

    /** @return unveränderliche Liste aller verfügbaren Sensoren. */
    public static List<Sensor> getAvailableSensors() {
        return REGISTERED_SENSORS;
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
