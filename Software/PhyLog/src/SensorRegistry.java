import java.util.List;

/**
 * Verwaltet alle verfügbaren Sensor-Implementierungen.
 */
public class SensorRegistry {

    /** Instanz für "kein Sensor gewählt". */
    public static final Sensor NO_SENSOR = new NoSensor();

    private static final List<Sensor> REGISTERED_SENSORS = List.of(
            NO_SENSOR,
            new INA219VoltageSensor(),
            new INA219CurrentSensor(),
            new VEML7700Sensor(),
            new HX711Sensor(),
            new MicrophoneSensor(),
            new MicrophoneSpectrumSensor(),
            new HallEffectSensor()
    );

    /**
     * Gibt alle registrierten Sensoren zurück.
     *
     * @return Unveränderliche Liste aller Sensoren.
     */
    public static List<Sensor> getAvailableSensors() {
        return REGISTERED_SENSORS;
    }

    /**
     * Sucht einen Sensor anhand seiner Einheit.
     *
     * @param unitStr Der zu suchende Einheiten-String.
     * @return Passender {@link Sensor} oder {@code null}.
     */
    public static Sensor findByUnit(String unitStr) {
        for (Sensor s : REGISTERED_SENSORS) {
            if (s != NO_SENSOR && s.matchesUnit(unitStr)) {
                return s;
            }
        }
        return null;
    }
}