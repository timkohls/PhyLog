import java.util.List;

/**
 * Repräsentiert einen unbelegten Sensorkanal.
 */
class NoSensor extends Sensor {
    /**
     * Erstellt einen Platzhalter-Sensor für unbelegte Kanäle.
     */
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

/**
 * Basisklasse für INA219-Sensoren zur Dekodierung der Registerwerte.
 */
abstract class AbstractINA219Sensor extends Sensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit

    /**
     * Initialisiert den INA219-Sensor.
     *
     * @param name        Anzeigename des Sensors.
     * @param unit        Standard-Einheit.
     * @param unitAliases Liste alternativer Einheitenbezeichnungen.
     */
    AbstractINA219Sensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /**
     * Dekodiert den Rohwert für die Busspannung in Volt.
     *
     * @param rawValue Der empfangene Rohwert.
     * @return Spannung in Volt.
     */
    static double decodeVoltage(long rawValue) {
        long masked = rawValue & 0xFFFF;
        return ((masked >> 3) & 0x1FFF) * 0.004; // Bus-Spannung, 4 mV LSB
    }

    /**
     * Dekodiert den Rohwert für den Strom in Ampere.
     *
     * @param rawValue Der empfangene Rohwert.
     * @return Stromstärke in Ampere.
     */
    static double decodeCurrent(long rawValue) {
        short signedRaw = (short) (rawValue & 0xFFFF);
        return signedRaw * CURRENT_LSB;
    }

    @Override
    public String getFirmwareTypeName() {
        return "INA219";
    }
}

/**
 * INA219-Sensorprofil für Spannungsmessungen.
 */
class INA219VoltageSensor extends AbstractINA219Sensor {
    /**
     * Erstellt einen INA219-Spannungssensor.
     */
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

/**
 * INA219-Sensorprofil für Strommessungen.
 */
class INA219CurrentSensor extends AbstractINA219Sensor {
    /**
     * Erstellt einen INA219-Stromsensor.
     */
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

/**
 * VEML7700-Sensor zur Beleuchtungsstärkemessung in Lux.
 */
class VEML7700Sensor extends Sensor {
    /**
     * Erstellt einen VEML7700-Lichtsensor.
     */
    public VEML7700Sensor() {
        super("VEML7700 (Licht / Lux)", "lx", List.of("LX", "LUX"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        // Skalierung auf Lux bei Gain 1x / IT 25ms (siehe configureSensorOnBus in der Firmware) -
        // der Lux/Count-Faktor ist umgekehrt proportional zur Integrationszeit: bei 100ms wären
        // es 0.0576 (kürzere Integration sammelt weniger Licht pro Count, ein Count entspricht
        // also mehr Lux) - hier 4x kürzere Integrationszeit als früher, daher 4x höherer Faktor.
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
        return 40; // Integrationszeit 25ms in der Firmware -> max. 40 Hz neue Messwerte
    }
}

/**
 * HX711-Sensor zur Kraft- und Gewichtsmessung via Wägezelle.
 */
class HX711Sensor extends Sensor {
    private double calibrationFactor = 10000.0;

    /**
     * Erstellt einen HX711-Kraftsensor.
     */
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
        // Der HX711-Chip liefert selbst nur 10 oder 80 neue Werte/Sekunde, fest per RATE-Pin auf
        // dem jeweiligen Breakout-Board verdrahtet - das ist keine Firmware-Einstellung, sondern
        // eine Hardware-Wahl auf dem Modul. 80 als Obergrenze angenommen (der schnellere der
        // beiden gängigen Werte); bei den meisten Boards ohne verbundenen RATE-Pin sind es in
        // Wirklichkeit nur 10 Hz - schnelleres Abfragen läuft dort ins Leere (siehe
        // {@link #readHX711} in der Firmware, die ohnehin auf das nächste DRDY-Signal wartet).
        return 80;
    }
}

/**
 * INMP441-Mikrofon als Frequenzspektrum statt einzelnem dB-Wert (siehe {@link MicrophoneSensor}
 * für die klassische Variante). Liefert selbst keine Zeitreihen-Messwerte - {@code decode} wird
 * nie aufgerufen, da die Firmware für diesen Sensortyp ausschließlich {@code #SPEC}-Pakete
 * schickt (siehe {@link AcquisitionEngine}), keine regulären Datenpakete.
 */
class MicrophoneSpectrumSensor extends Sensor {
    /**
     * Erstellt einen INMP441-Sensor im Spektrum-Modus.
     */
    public MicrophoneSpectrumSensor() {
        super("INMP441 (Audio-Frequenzspektrum)", "dB", List.of("DB"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0; // ungenutzt, siehe Klassenkommentar
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

/**
 * KY-003-Hall-Sensor-Modul: ein digitaler Schalter, der 1 liefert, wenn ein Magnetfeld erkannt
 * wird, sonst 0. Typischer Einsatz in der Physik: Drehzahl- oder Periodendauer-Messung, indem
 * ein kleiner Magnet an einem rotierenden oder schwingenden Objekt bei jedem Durchgang den
 * Sensor kurz auslöst - in Kombination mit dem Trigger (Schwellenwert 0,5) lässt sich damit
 * z. B. automatisch bei jedem Durchgang eine Messung starten oder die Zeit zwischen zwei
 * Durchgängen aus dem Diagramm ablesen.
 */
class HallEffectSensor extends Sensor {
    /**
     * Erstellt einen KY-003-Hall-Sensor.
     */
    public HallEffectSensor() {
        super("KY-003 (Hall-Sensor)", "", List.of());
    }

    @Override
    public double decode(int slot, long rawValue) {
        // Das Modul ist active-low (zieht den Ausgang bei erkanntem Magnetfeld auf LOW) - hier
        // invertiert, damit 1 intuitiv "Magnetfeld erkannt" bedeutet statt technisch korrekt,
        // aber beim Anschauen des Diagramms verwirrend, 0.
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

/**
 * INMP441 I2S-Mikrofon zur Schätzung des Schalldruckpegels in dB.
 */
class MicrophoneSensor extends Sensor {
    private static final double FULL_SCALE = 8_388_607.0; // 2^23 - 1, größter 24-Bit-Betrag
    private static final double REFERENCE_SPL_DB = 94.0;

    private double sensitivityDbfsAt94db = 12.0;

    /**
     * Erstellt einen INMP441-Mikrofonsensor.
     */
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
        return 1000; // Firmware liest dafür dynamisch so wenig I2S-Samples wie nötig, siehe
        // microphoneReadSampleCount() in phylog_firmware.ino
    }
}

/**
 * Generisches 0-25V-Spannungsteiler-Modul (Teilerverhältnis 5:1, Ausgang "S" für einen
 * Arduino-ADC mit 5V-Referenz) an einem ESP32-Analogeingang. Nutzt den generischen
 * {@code TYPE_ANALOG}-Rohwert-Sensortyp der Firmware (siehe {@code sampleChannel} in
 * phylog_firmware.ino) - die Firmware liefert nur den rohen {@code analogRead()}-Zählerwert,
 * die komplette Umrechnung in Volt passiert hier.
 *
 * <p><b>Wichtiger Hardware-Hinweis (unbedingt vor dem Anschließen prüfen):</b> Der ESP32-
 * Analogeingang ist auf ca. 3,3V ausgelegt (Standard-Dämpfung ADC_11db), das absolute Maximum
 * laut Datenblatt liegt bei VDD+0,3V (also ca. 3,6V) - deutlich unter den 5V, die dieses Modul
 * bei einer Eingangsspannung von 25V an "S" ausgibt (25V / 5 = 5V). Direkt an den ESP32
 * angeschlossen ist der volle 0-25V-Bereich damit NICHT unbedenklich nutzbar: sicher ist nur
 * eine Eingangsspannung bis ca. 16,5V (= 3,3V x 5). Für den vollen 25V-Bereich braucht es vor
 * dem ESP32-Pin einen weiteren Spannungsteiler (bzw. einen Levelshifter bzw. eine Clamp-Diode
 * auf 3,3V) - ohne das riskiert man, den ADC-Eingang oder den ganzen Chip zu beschädigen.</p>
 */
class VoltageDividerSensor extends Sensor {

    /** Referenzspannung des ESP32-ADC bei Standard-Dämpfung (ADC_11db) - eine feste
     *  Chip-Eigenschaft, kein Kalibrierwert, daher hier Konstante statt {@link CalibrationParameter}.
     *  Siehe Hardware-Hinweis im Klassenkommentar. */
    static final double ADC_REFERENCE_VOLTAGE = 3.3;
    /** Auflösung des ESP32-ADC (12 Bit -> 0..4095), ebenfalls Chip-Eigenschaft statt Kalibrierwert. */
    static final double ADC_MAX_COUNT = 4095.0;

    /** Teilerverhältnis Eingangsspannung/Ausgangsspannung des Moduls - beim verbreiteten
     *  "0-25V"-Modul (R1=30k, R2=7,5k) ergibt sich rechnerisch genau 5,0; weicht das konkrete
     *  Exemplar wegen Bauteiltoleranzen leicht ab, hier über den Kalibrierdialog (mit einem
     *  bekannten Referenzspannungswert, z. B. einem Multimeter) feinjustierbar. */
    private double dividerRatio = 3.3;

    /**
     * Erstellt einen 0-25V-Spannungsteiler-Sensor.
     */
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
 * DS18B20-Digitalthermometer (Dallas/Maxim) am 1-Wire-Bus. Anders als der zuvor genutzte
 * NTC-Spannungsteiler-Aufbau liest die Firmware hier keinen rohen ADC-Zählerwert mehr, sondern
 * das bereits im Sensor-IC selbst temperaturkompensierte 16-Bit-Register aus dem "Scratchpad"
 * über das 1-Wire-Protokoll (siehe {@code TYPE_DS18B20} in der Firmware) - {@code decode} muss
 * den Rohwert daher nur noch durch die feste Registerauflösung teilen, keine eigene
 * Spannungsteiler-/Steinhart-Hart-Rechnung wie zuvor beim NTC-Aufbau mehr durchführen.
 *
 * <p>Registerformat bei der (in der Firmware fest eingestellten) 12-Bit-Auflösung: vorzeichen-
 * behafteter 16-Bit-Wert in 1/16°C-Schritten (siehe DS18B20-Datenblatt, "Temperature Register
 * Format") - Rohwert 0x0191 entspricht z. B. 25,0625°C.</p>
 *
 * <p>Die Konversionszeit bei 12-Bit-Auflösung beträgt laut Datenblatt bis zu 750ms - schnelleres
 * Abfragen liefert nur denselben, noch nicht aktualisierten Wert erneut (siehe
 * {@link #getMaxSampleRateHz}).</p>
 *
 * <p><b>Hardware-Hinweis:</b> Datenleitung braucht einen Pull-up-Widerstand nach 3,3V (typisch
 * 4,7kΩ, beim verbreiteten wasserdichten DS18B20-Modul oft bereits auf der Platine verbaut) -
 * ohne den bleibt der Bus permanent LOW und die Firmware findet beim Adress-Scan keinen Sensor.</p>
 */
class DS18B20Sensor extends Sensor {

    /** Registerauflösung bei 12-Bit-Modus: 1 LSB = 1/16°C (siehe Klassenkommentar). */
    private static final double REGISTER_LSB = 1.0 / 16.0;

    /** Additiver Korrekturwert, z. B. für eine Abweichung gegenüber einem Referenzthermometer -
     *  der DS18B20 selbst ist werkseitig auf ±0,5°C kalibriert, ein multiplikativer Faktor wie
     *  beim vorherigen NTC-Aufbau (Teilerverhältnis, Beta-Koeffizient) ergibt hier keinen Sinn,
     *  ein fester Offset genügt. */
    private double calibrationOffsetC = 0.0;

    /**
     * Erstellt einen DS18B20-Temperatursensor.
     */
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
        // 750ms Konversionszeit bei 12-Bit-Auflösung (siehe Klassenkommentar) -> max. ~1,3 Hz;
        // abgerundet auf 1 Hz als sichere Obergrenze, damit nicht wiederholt derselbe (noch
        // nicht aktualisierte) Registerwert als vermeintlich neuer Messwert gezählt wird.
        return 1;
    }
}