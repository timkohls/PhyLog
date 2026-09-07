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

/**
 * INA219-Sensorprofil für Strommessungen. Die Firmware kennt seit v8.7 kein "INA219" mehr,
 * sondern nur noch generisches I2C (siehe {@link I2CSensor}) - Adresse, Init-Register
 * (Config + Kalibrierung) und die beiden Leseregister (Bus-Spannung, Strom) liefert allein diese
 * Klasse. Slot 0 (Spannung) wird zwar mitgelesen, aber (wie schon vor dem Umbau) von
 * {@link AcquisitionEngine} verworfen, da {@link #getQuantities()} nur Slot 1 (Strom) meldet.
 */
class INA219CurrentSensor extends I2CSensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit
    private static final int ADDRESS = 0x40;

    public INA219CurrentSensor() {
        super("INA219 (Strom)", "A", List.of("A", "AMP", "MA"));
    }

    /** Dekodiert den Rohwert für den Strom in Ampere. */
    @Override
    public double decode(int slot, long rawValue) {
        short signedRaw = (short) (rawValue & 0xFFFF);
        return signedRaw * CURRENT_LSB;
    }
    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Strom", "A", 1));
    }

    @Override
    public int getI2CAddress() {
        return ADDRESS;
    }

    @Override
    public List<Write> getInitWrites() {
        return List.of(
                new Write(0x00, 0x39, 0x9F), // Config-Register: 32V, Gain 8, 12-Bit-ADC
                new Write(0x05, 0x10, 0x00)  // Kalibrierregister
        );
    }

    @Override
    public List<Read> getReads() {
        return List.of(
                new Read(0x02, 2, true, 0), // Bus-Spannungsregister -> Slot 0 (ungenutzt, s. o.)
                new Read(0x04, 2, true, 1)  // Stromregister -> Slot 1
        );
    }
}

/**
 * VEML7700-Sensor zur Beleuchtungsstärkemessung in Lux. Wie {@link INA219CurrentSensor} seit
 * v8.7 ein generischer {@link I2CSensor} - die Firmware sieht nur noch "I2C-Gerät an 0x10 mit
 * dieser Init-/Lesekonfiguration", nicht mehr "VEML7700".
 */
class VEML7700Sensor extends I2CSensor {
    private static final int ADDRESS = 0x10;

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
    public int getMaxSampleRateHz() {
        return 40; // Integrationszeit 25ms -> max. 40 Hz neue Messwerte
    }

    @Override
    public int getI2CAddress() {
        return ADDRESS;
    }

    @Override
    public List<Write> getInitWrites() {
        // ALS_CONF-Register: Gain 1x, Integrationszeit 25ms (kürzeste verfügbare Einstellung
        // statt der 100ms im Reset-Zustand) - siehe getMaxSampleRateHz().
        return List.of(new Write(0x00, 0x00, 0x03));
    }

    @Override
    public List<Read> getReads() {
        // VEML7700 sendet 16-Bit-Register LSB-zuerst (little-endian), anders als der INA219.
        return List.of(new Read(0x04, 2, false, 0));
    }
}

/**
 * HX711-Sensor zur Kraft- und Gewichtsmessung via Wägezelle.
 *
 * <p>Anders als z. B. der DS18B20 (CRC8, siehe {@link OneWireSensor#isCrcChecked()}) hat das
 * bit-gebangte HX711-Protokoll (siehe {@code sampleHX711} in phylog_firmware.ino) keinerlei
 * eigene Datenintegritätsprüfung. Ein früherer Versuch, das softwareseitig über einen
 * Median/MAD-basierten Ausreißerfilter abzufangen, wurde wieder entfernt: Da abgelehnte Werte
 * nie ins Vergleichsfenster übernommen wurden, blieb der Filter nach jeder echten, größeren
 * Kraftänderung dauerhaft auf dem alten Wert hängen und verwarf danach praktisch jeden weiteren
 * Messwert (verstärkt dadurch, dass diese Sensor-Instanz als Singleton über die gesamte
 * Programmlaufzeit wiederverwendet wird, siehe {@link SensorRegistry}). Echtes Rauschen bzw.
 * Störimpulse sollten stattdessen hardwareseitig (Erdung/Schirmung der Wägezelle) angegangen
 * werden.</p>
 */
class HX711Sensor extends Sensor {
    private double calibrationFactor = 200000.0;

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
        return 10;
    }
}

/** INMP441-Mikrofon als Frequenzspektrum statt einzelnem dB-Wert, siehe {@link MicrophoneSensor}
 *  für die klassische Variante. {@code decode} wird nie aufgerufen, da die Firmware für diesen
 *  Sensortyp ausschließlich Spektrum-Pakete schickt. Firmware-seitig seit v9.1 generisches
 *  {@link I2SSensor} wie {@link MicrophoneSensor}, nur mit {@link #producesSpectrum()}
 *  {@code true} - die I2S-Hardwarekonfiguration (Abtastrate, Slot, Bit-Ausrichtung) ist identisch,
 *  nur die Ausgabeform unterscheidet sich. */
class MicrophoneSpectrumSensor extends I2SSensor {
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
    public int getSampleRateHz() {
        return 16000;
    }

    @Override
    public int getShiftBits() {
        return 8; // 24 gültige Bits linksbündig in einem 32-Bit-I2S-Wort, siehe MicrophoneSensor
    }

    @Override
    public boolean producesSpectrum() {
        return true;
    }
}

/** KY-003-Hall-Sensor-Modul: digitaler Schalter, der 1 liefert, wenn ein Magnetfeld erkannt
 *  wird, sonst 0. Typischer Einsatz: Drehzahl- oder Periodendauer-Messung. Firmware-seitig seit
 *  v8.8 generisches "DIGITAL" statt "HALL" - ein reiner Pin-Lesevorgang braucht (anders als I2C
 *  oder 1-Wire) keine weitere Konfiguration, deshalb hier keine eigene Basisklasse wie
 *  {@link I2CSensor}/{@link OneWireSensor}. */
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
        return "DIGITAL";
    }
}

/** INMP441 I2S-Mikrofon zur Schätzung des Schalldruckpegels in dB. Firmware-seitig seit v9.1
 *  generisches {@link I2SSensor} im Einzelwert-Modus - siehe {@link MicrophoneSpectrumSensor} für
 *  den Spektrum-Modus derselben I2S-Hardware. Ein Sensor mit anderer Abtastrate, anderem I2S-Slot
 *  oder anderer Bit-Tiefe (z. B. ein SPH0645 oder ICS-43434 statt des INMP441) braucht dank der
 *  generischen Firmware-Konfiguration keine Firmware-Änderung mehr - nur eine eigene Unterklasse
 *  von {@link I2SSensor} mit den passenden Werten für {@link #getSampleRateHz()}/
 *  {@link #getShiftBits()} und eigener {@link #decode}-Umrechnung. */
class MicrophoneSensor extends I2SSensor {
    private static final double FULL_SCALE = 8_388_607.0; // 2^23 - 1
    private static final double REFERENCE_SPL_DB = 94.0;
    /** Zeitkonstante der Glättung in ms, angelehnt an die "Fast"-Zeitkonstante (125ms) für
     *  Schallpegelmesser nach IEC 61672. Bewusst als Zeitkonstante statt fester Sample-Anzahl:
     *  eine feste Anzahl Pakete hätte bei niedriger Abtastrate eine viel zu träge (z. B. 1s bei
     *  20 Hz statt der gewünschten 125ms), bei hoher Abtastrate eine viel zu hektische Anzeige
     *  zur Folge - siehe {@link #decode}. */
    private static final double TIME_CONSTANT_MS = 125.0;

    private double sensitivityDbfsAt94db = 0.0;
    /** Exponentiell geglättetes mittleres Leistungssignal (Quadrat des Effektivwerts), Basis
     *  für den ausgegebenen Pegel. */
    private double meanSquare = 0.0;
    /** Zeitpunkt (siehe {@link System#nanoTime}) des letzten {@link #decode}-Aufrufs, für die
     *  tatsächlich vergangene Zeit zwischen zwei Paketen (siehe {@link #TIME_CONSTANT_MS}).
     *  {@code < 0}, solange noch kein Aufruf stattfand. */
    private long lastUpdateNanos = -1;

    public MicrophoneSensor() {
        super("INMP441 (Mikrofon)", "dB", List.of("DB", "DBSPL"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        // rawValue ist der Spitzenbetrag eines vorzeichenbehafteten 24-Bit-I2S-Fensters (siehe
        // sampleI2SRaw in der Firmware) - schon eine Art Momentanpegel, kein Rohsample mehr.
        // Trotzdem noch zu unruhig für eine direkte Anzeige, deshalb zusätzliche Glättung der
        // Leistung (Quadrat) über die Zeit statt über eine feste Paketanzahl (siehe
        // TIME_CONSTANT_MS-Kommentar).
        double sample = rawValue / FULL_SCALE;
        double instantaneousPower = sample * sample;

        long now = System.nanoTime();
        if (lastUpdateNanos < 0) {
            meanSquare = instantaneousPower;
        } else {
            double deltaMs = (now - lastUpdateNanos) / 1_000_000.0;
            double alpha = 1.0 - Math.exp(-deltaMs / TIME_CONSTANT_MS);
            meanSquare += alpha * (instantaneousPower - meanSquare);
        }
        lastUpdateNanos = now;

        double rms = Math.sqrt(Math.max(meanSquare, 1e-12));
        double dbFullScale = 20.0 * Math.log10(rms);
        return REFERENCE_SPL_DB + (dbFullScale - sensitivityDbfsAt94db);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Schalldruckpegel (geschätzt)", "dB", 0));
    }

    @Override
    public int getSampleRateHz() {
        return 16000;
    }

    @Override
    public int getShiftBits() {
        return 8; // 24 gültige Bits linksbündig in einem 32-Bit-I2S-Wort, siehe FULL_SCALE oben
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
 * Konversionszeit bis zu 750ms, siehe {@link #getMaxSampleRateHz}. Die Firmware kennt seit v8.8
 * kein "DS18B20" mehr, sondern nur noch generisches 1-Wire (siehe {@link OneWireSensor}) - alle
 * hier implementierten Getter beschreiben Konversions-/Lesekommando und Scratchpad-Layout, die
 * Firmware führt sie nur noch generisch aus.</p>
 *
 * <p><b>Hardware-Hinweis:</b> Datenleitung braucht einen Pull-up-Widerstand nach 3,3V (typisch
 * 4,7kΩ) - ohne den bleibt der Bus permanent LOW und die Firmware findet keinen Sensor.</p>
 */
class DS18B20Sensor extends OneWireSensor {

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
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Offset", "°C",
                () -> calibrationOffsetC, v -> calibrationOffsetC = v));
    }

    @Override
    public int getMaxSampleRateHz() {
        return 1; // 750ms Konversionszeit -> abgerundet auf 1 Hz als sichere Obergrenze
    }

    @Override
    public int getConvertCommand() {
        return 0x44; // Convert T
    }

    @Override
    public long getConversionDelayMs() {
        return 750; // 12-Bit-Auflösung, siehe getMaxSampleRateHz()
    }

    @Override
    public int getReadCommand() {
        return 0xBE; // Read Scratchpad
    }

    @Override
    public int getReadLength() {
        return 9; // vollständiges Scratchpad inkl. CRC8-Byte
    }

    @Override
    public int getValueOffset() {
        return 0; // Temperaturregister: Byte 0 (LSB) + Byte 1 (MSB)
    }

    @Override
    public int getValueLength() {
        return 2;
    }

    @Override
    public boolean isLittleEndian() {
        return true;
    }

    @Override
    public boolean isCrcChecked() {
        return true;
    }
}