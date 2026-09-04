import java.util.List;
import java.util.stream.Collectors;

/**
 * Basisklasse für Sensoren an einem I2C-Bus. Die Firmware kennt seit v8.7 kein einzelnes
 * I2C-Sensormodell mehr namentlich (kein TYPE_INA219/TYPE_VEML7700) - sie kann nur noch generisch
 * "I2C-Gerät an Adresse X, mit dieser Init-Sequenz, mit diesen Leseregistern" ansteuern (siehe
 * {@code TYPE_I2C} und {@code parseI2CSetPayload} in phylog_firmware.ino). Alles Modellspezifische
 * (Register-Layout, Kalibrierwerte, physikalische Umrechnung) bleibt vollständig hier auf der
 * Software-Seite - {@link #decode} ändert sich dadurch nicht, nur die Art, wie der Rohwert
 * überhaupt beschafft wird, wandert von der Firmware hierher.
 *
 * <p>Konkrete Sensoren (z. B. {@code INA219CurrentSensor}, {@code VEML7700Sensor}) müssen nur
 * {@link #getI2CAddress()}, {@link #getInitWrites()} und {@link #getReads()} implementieren -
 * {@link #getFirmwareTypeName()} und {@link #getFirmwareSetPayload()} sind hier bereits final
 * erledigt.</p>
 */
public abstract class I2CSensor extends Sensor {

    public I2CSensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /** @return 7-Bit-I2C-Adresse des Sensors (z. B. {@code 0x40} für den INA219). */
    public abstract int getI2CAddress();

    /** @return Register-Schreibvorgänge, die die Firmware einmalig beim Umschalten auf diesen
     *  Sensor ausführt (z. B. Config-/Kalibrierregister). Leere Liste, falls der Sensor ohne
     *  Init auskommt. */
    public abstract List<Write> getInitWrites();

    /** @return Register-Lesevorgänge, die die Firmware bei jedem Abtastzyklus ausführt; jeder
     *  Eintrag erzeugt ein eigenes Datenpaket mit dem angegebenen {@code slot} (siehe
     *  {@link Sensor.Quantity#slot}). */
    public abstract List<Read> getReads();

    @Override
    public final String getFirmwareTypeName() {
        return "I2C";
    }

    @Override
    public final String getFirmwareSetPayload() {
        return "I2C," + toHex(getI2CAddress()) + "," + encodeWrites(getInitWrites()) + "," + encodeReads(getReads());
    }

    private static String encodeWrites(List<Write> writes) {
        if (writes.isEmpty()) return "-";
        return writes.stream().map(I2CSensor::encodeWrite).collect(Collectors.joining(";"));
    }

    private static String encodeWrite(Write w) {
        StringBuilder sb = new StringBuilder(toHex(w.register));
        for (int b : w.data) {
            sb.append(':').append(toHex(b));
        }
        return sb.toString();
    }

    private static String encodeReads(List<Read> reads) {
        return reads.stream().map(r -> toHex(r.register) + ":" + r.length + ":"
                + (r.bigEndian ? "B" : "L") + ":" + r.slot).collect(Collectors.joining(";"));
    }

    private static String toHex(int value) {
        return Integer.toHexString(value & 0xFF);
    }

    /** Ein einzelner Registerschreibvorgang während der Init-Sequenz (z. B. Config-Register). */
    public static final class Write {
        public final int register;
        public final int[] data;

        public Write(int register, int... data) {
            this.register = register;
            this.data = data;
        }
    }

    /** Ein einzelner Register-Lesevorgang pro Abtastzyklus, dessen Ergebnis als Rohwert für
     *  {@code slot} an {@link Sensor#decode} weitergereicht wird. */
    public static final class Read {
        public final int register;
        public final int length;
        public final boolean bigEndian;
        public final int slot;

        /**
         * @param register  Registeradresse
         * @param length    Anzahl zu lesender Bytes (1-4)
         * @param bigEndian {@code true} für MSB-zuerst (z. B. Texas-Instruments-Bausteine wie
         *                  der INA219), {@code false} für LSB-zuerst (z. B. Vishay-Bausteine wie
         *                  der VEML7700)
         * @param slot      Ziel-Slot, unter dem der Rohwert an die Software geschickt wird
         */
        public Read(int register, int length, boolean bigEndian, int slot) {
            this.register = register;
            this.length = length;
            this.bigEndian = bigEndian;
            this.slot = slot;
        }
    }
}
