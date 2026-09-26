import java.util.List;
import java.util.stream.Collectors;

/**
 * Basisklasse für Sensoren an einem 1-Wire-Bus.
 *
 * <p>Seit v9.2 kann ein Sensor zusätzlich {@link #getInitWrites()} überschreiben, um vor der
 * ersten Konversion einmalig eine Kommandosequenz auf den Bus zu schreiben (z. B. "Write
 * Scratchpad" (0x4E) beim DS18B20 zur Auflösungseinstellung, siehe {@code DS18B20Sensor} in
 * SensorImplementations.java) - analog zu {@link I2CSensor#getInitWrites()}. Ohne Override bleibt
 * die Liste leer und es ändert sich nichts am bisherigen Verhalten.</p>
 */
public abstract class OneWireSensor extends Sensor {

    public OneWireSensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /** @return Kommando-Schreibvorgänge, die die Firmware einmalig beim Umschalten auf diesen
     *  Sensor ausführt, jeweils mit eigener Reset+Skip-ROM-Sequenz vorangestellt (z. B. "Write
     *  Scratchpad" zur Auflösungseinstellung). Leere Liste (Standard), falls der Sensor ohne
     *  Init auskommt - wie bisher bei den meisten 1-Wire-Sensoren. */
    public List<Write> getInitWrites() {
        return List.of();
    }

    /** @return Kommandobyte, das die Konversion/Messung anstößt (z. B. {@code 0x44} "Convert T"
     *  beim DS18B20). */
    public abstract int getConvertCommand();

    /** @return Wartezeit in ms zwischen Konversionsstart und -ende, bevor das Ergebnis gelesen
     *  werden darf (z. B. 750ms für den DS18B20 bei 12-Bit-Auflösung, weniger bei reduzierter
     *  Auflösung - siehe {@link #getInitWrites()}). */
    public abstract long getConversionDelayMs();

    /** @return Kommandobyte, das das Ergebnis zum Lesen bereitstellt (z. B. {@code 0xBE}
     *  "Read Scratchpad" beim DS18B20). */
    public abstract int getReadCommand();

    /** @return Anzahl Byte, die nach {@link #getReadCommand()} gelesen werden (z. B. 9 beim
     *  DS18B20-Scratchpad inkl. CRC-Byte). */
    public abstract int getReadLength();

    /** @return Byte-Offset des Rohwerts innerhalb der gelesenen Bytes. */
    public abstract int getValueOffset();

    /** @return Länge des Rohwerts in Byte, ab {@link #getValueOffset()}. */
    public abstract int getValueLength();

    /** @return {@code true}, falls der Rohwert LSB-zuerst kodiert ist (wie beim DS18B20). */
    public abstract boolean isLittleEndian();

    /** @return {@code true}, falls das letzte gelesene Byte eine Dallas-CRC8-Prüfsumme über die
     *  vorherigen Bytes ist und die Firmware bei einem Mismatch einen Fehler statt eines
     *  (potenziell verfälschten) Werts melden soll. */
    public abstract boolean isCrcChecked();

    @Override
    public final String getFirmwareTypeName() {
        return "ONEWIRE";
    }

    @Override
    public final String getFirmwareSetPayload() {
        Sensor.Quantity first = getQuantities().isEmpty() ? null : getQuantities().getFirst();
        int slot = (first != null) ? first.slot : 0;

        return "ONEWIRE," + encodeWrites(getInitWrites()) + "," + toHex(getConvertCommand()) + ","
                + getConversionDelayMs() + "," + toHex(getReadCommand()) + "," + getReadLength() + ","
                + getValueOffset() + "," + getValueLength() + "," + (isLittleEndian() ? "L" : "B") + ","
                + (isCrcChecked() ? "1" : "0") + "," + slot;
    }

    private static String encodeWrites(List<Write> writes) {
        if (writes.isEmpty()) return "-";
        return writes.stream().map(OneWireSensor::encodeWrite).collect(Collectors.joining(";"));
    }

    private static String encodeWrite(Write w) {
        StringBuilder sb = new StringBuilder(toHex(w.command));
        for (int b : w.data) {
            sb.append(':').append(toHex(b));
        }
        return sb.toString();
    }

    private static String toHex(int value) {
        return Integer.toHexString(value & 0xFF);
    }

    /** Ein einzelner Kommando-Schreibvorgang während der Init-Sequenz (z. B. "Write Scratchpad"
     *  beim DS18B20). Anders als bei {@link I2CSensor.Write} ist {@code command} kein
     *  Registeroffset, sondern ein 1-Wire-Kommandobyte - die Firmware stellt jedem Eintrag eine
     *  eigene Reset+Skip-ROM-Sequenz voran, siehe {@code configureOneWireSensor} in der
     *  Firmware. */
    public static final class Write {
        public final int command;
        public final int[] data;

        public Write(int command, int... data) {
            this.command = command;
            this.data = data;
        }
    }
}