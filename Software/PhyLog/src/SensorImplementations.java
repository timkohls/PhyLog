import java.util.List;

/** Platzhalter-Sensor für unbelegte Kanäle. */
class NoSensor extends Sensor {
    public NoSensor() {
        super("-- Kein Sensor --", "", List.of());
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of();
    }

    @Override
    public String getFirmwareTypeName() {
        return "NONE";
    }
}

/** Basisklasse für INA219-Sensoren zur Dekodierung der Registerwerte. */
abstract class AbstractINA219Sensor extends Sensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit

    AbstractINA219Sensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /** Dekodiert den Rohwert für die Busspannung in Volt (4 mV LSB). */
    static double decodeVoltage(long rawValue) {
        long masked = rawValue & 0xFFFF;
        return ((masked >> 3) & 0x1FFF) * 0.004;
    }

    /** Dekodiert den Rohwert für den Strom in Ampere. */
    static double decodeCurrent(long rawValue) {
        short signedRaw = (short) (rawValue & 0xFFFF);
        return signedRaw * CURRENT_LSB;
    }

    @Override
    public String getFirmwareTypeName() {
        return "INA219";
    }
}

/** INA219-Sensorprofil für Spannungsmessungen. */
class INA219VoltageSensor extends AbstractINA219Sensor {
    public INA219VoltageSensor() {
        super("INA219 (Spannung)", "V", List.of("V", "VOLT"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return decodeVoltage(rawValue);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Spannung", "V", 0));
    }
}

/** INA219-Sensorprofil für Strommessungen. */
class INA219CurrentSensor extends AbstractINA219Sensor {
    public INA219CurrentSensor() {
        super("INA219 (Strom)", "A", List.of("A", "AMP", "MA"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return decodeCurrent(rawValue);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Strom", "A", 1));
    }
}

/** VEML7700-Sensor zur Beleuchtungsstärkemessung in Lux. */
class VEML7700Sensor extends Sensor {
    public VEML7700Sensor() {
        super("VEML7700 (Licht / Lux)", "lx", List.of("LX", "LUX"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        // Skalierung auf Lux bei Gain 1x / Integrationszeit 25ms.
        return (rawValue & 0xFFFF) * 0.2304;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Beleuchtungsstärke", "lx", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "VEML7700";
    }

    @Override
    public int getMaxSampleRateHz() {
        return 40; // Integrationszeit 25ms -> max. 40 Hz neue Messwerte
    }
}

/** HX711-Sensor zur Kraft- und Gewichtsmessung via Wägezelle. */
class HX711Sensor extends Sensor {
    private double calibrationFactor = 10000.0;

    public HX711Sensor() {
        super("HX711 (Kraft / Gewicht)", "N", List.of("N", "G", "KG"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return rawValue / calibrationFactor;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Kraft", "N", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "HX711";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Kalibrierfaktor", "Counts/N",
                () -> calibrationFactor, v -> calibrationFactor = v));
    }

    @Override
    public int getMaxSampleRateHz() {
        // Der HX711-Chip liefert je nach RATE-Pin-Verdrahtung des Breakout-Boards nur 10 oder
        // 80 neue Werte/Sekunde; 80 als Obergrenze angenommen.
        return 80;
    }
}

/** INMP441-Mikrofon als Frequenzspektrum statt einzelnem dB-Wert, siehe {@link MicrophoneSensor}
 *  für die klassische Variante. {@code decode} wird nie aufgerufen, da die Firmware für diesen
 *  Sensortyp ausschließlich Spektrum-Pakete schickt. */
class MicrophoneSpectrumSensor extends Sensor {
    public MicrophoneSpectrumSensor() {
        super("INMP441 (Audio-Frequenzspektrum)", "dB", List.of("DB"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Frequenzspektrum", "dB", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "MICSPEC";
    }

    @Override
    public boolean producesSpectrum() {
        return true;
    }
}

/** KY-003-Hall-Sensor-Modul: digitaler Schalter, der 1 liefert, wenn ein Magnetfeld erkannt
 *  wird, sonst 0. Typischer Einsatz: Drehzahl- oder Periodendauer-Messung. */
class HallEffectSensor extends Sensor {
    public HallEffectSensor() {
        super("KY-003 (Hall-Sensor)", "", List.of());
    }

    @Override
    public double decode(int slot, long rawValue) {
        // Modul ist active-low; hier invertiert, damit 1 "Magnetfeld erkannt" bedeutet.
        return (rawValue == 0) ? 1.0 : 0.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Magnetfeld erkannt", "", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "HALL";
    }
}

/** INMP441 I2S-Mikrofon zur Schätzung des Schalldruckpegels in dB. */
class MicrophoneSensor extends Sensor {
    private static final double FULL_SCALE = 8_388_607.0; // 2^23 - 1
    private static final double REFERENCE_SPL_DB = 94.0;

    private double sensitivityDbfsAt94db = 0.0;

    public MicrophoneSensor() {
        super("INMP441 (Mikrofon)", "dB", List.of("DB", "DBSPL"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        double amplitude = Math.max(rawValue, 1) / FULL_SCALE;
        double dbFullScale = 20.0 * Math.log10(amplitude);
        return REFERENCE_SPL_DB + (dbFullScale - sensitivityDbfsAt94db);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Schalldruckpegel (geschätzt)", "dB", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "MIC";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Empfindlichkeit @ 94 dB SPL", "dBFS",
                () -> sensitivityDbfsAt94db, v -> sensitivityDbfsAt94db = v));
    }

    @Override
    public int getMaxSampleRateHz() {
        return 1000;
    }
}

/**
 * Generisches 0-25V-Spannungsteiler-Modul (Teilerverhältnis 5:1) an einem ESP32-Analogeingang.
 *
 * <p><b>Wichtiger Hardware-Hinweis:</b> Der ESP32-Analogeingang ist auf ca. 3,3V ausgelegt, das
 * absolute Maximum liegt bei ca. 3,6V - deutlich unter den 5V, die dieses Modul bei 25V Eingang
 * an "S" ausgibt. Direkt angeschlossen ist sicher nur eine Eingangsspannung bis ca. 16,5V nutzbar;
 * für den vollen 25V-Bereich braucht es einen weiteren Spannungsteiler bzw. Levelshifter.</p>
 */
class VoltageDividerSensor extends Sensor {

    /** Referenzspannung des ESP32-ADC bei Standard-Dämpfung (ADC_11db). */
    static final double ADC_REFERENCE_VOLTAGE = 3.3;
    /** Auflösung des ESP32-ADC (12 Bit -> 0..4095). */
    static final double ADC_MAX_COUNT = 4095.0;

    /** Teilerverhältnis Eingangsspannung/Ausgangsspannung; über den Kalibrierdialog feinjustierbar. */
    private double dividerRatio = 3.3;

    public VoltageDividerSensor() {
        super("Spannungssensor", "V", List.of("V", "VOLT"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        double adcVoltage = (rawValue / ADC_MAX_COUNT) * ADC_REFERENCE_VOLTAGE;
        return adcVoltage * dividerRatio;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Spannung", "V", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "ANALOG";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Teilerverhältnis", "Vin/Vout",
                () -> dividerRatio, v -> dividerRatio = v));
    }
}

/**
 * DS18B20-Digitalthermometer (Dallas/Maxim) am 1-Wire-Bus.
 *
 * <p>Registerformat bei 12-Bit-Auflösung: vorzeichenbehafteter 16-Bit-Wert in 1/16°C-Schritten.
 * Konversionszeit bis zu 750ms, siehe {@link #getMaxSampleRateHz}.</p>
 *
 * <p><b>Hardware-Hinweis:</b> Datenleitung braucht einen Pull-up-Widerstand nach 3,3V (typisch
 * 4,7kΩ) - ohne den bleibt der Bus permanent LOW und die Firmware findet keinen Sensor.</p>
 */
class DS18B20Sensor extends Sensor {

    private static final double REGISTER_LSB = 1.0 / 16.0;

    /** Additiver Korrekturwert gegenüber einem Referenzthermometer. */
    private double calibrationOffsetC = 0.0;

    public DS18B20Sensor() {
        super("DS18B20 (Temperatur)", "°C", List.of("C", "CELSIUS", "GRAD"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        short signedRaw = (short) (rawValue & 0xFFFF);
        return signedRaw * REGISTER_LSB + calibrationOffsetC;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Temperatur", "°C", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "DS18B20";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Offset", "°C",
                () -> calibrationOffsetC, v -> calibrationOffsetC = v));
    }

    @Override
    public int getMaxSampleRateHz() {
        return 1; // 750ms Konversionszeit -> abgerundet auf 1 Hz als sichere Obergrenze
    }
}
