import java.util.List;

/**
 * Basisklasse für Sensoren an einem 1-Wire-Bus. Die Firmware kennt seit v8.8 kein einzelnes
 * 1-Wire-Sensormodell mehr namentlich (kein TYPE_DS18B20) - sie kann nur noch generisch
 * "Konversion mit diesem Kommando anstoßen, so lange warten, mit jenem Kommando so viele Byte
 * lesen, Rohwert an dieser Stelle herausschneiden" (siehe {@code TYPE_ONEWIRE} und
 * {@code parseOneWireSetPayload} in phylog_firmware.ino). "Skip ROM" (0xCC) nimmt die Firmware
 * weiterhin selbst an - unterstützt wird ohnehin nur ein Sensor pro Bus.
 *
 * <p>Wie bei {@link I2CSensor} bleibt alles Modellspezifische (Scratchpad-Layout,
 * Kalibrierwerte, physikalische Umrechnung) vollständig hier auf der Software-Seite -
 * {@link #decode} ändert sich dadurch nicht.</p>
 */
public abstract class OneWireSensor extends Sensor {

    public OneWireSensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /** @return Kommandobyte, das die Konversion/Messung anstößt (z. B. {@code 0x44} "Convert T"
     *  beim DS18B20). */
    public abstract int getConvertCommand();

    /** @return Wartezeit in ms zwischen Konversionsstart und -ende, bevor das Ergebnis gelesen
     *  werden darf (z. B. 750ms für den DS18B20 bei 12-Bit-Auflösung). */
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

        return "ONEWIRE," + toHex(getConvertCommand()) + "," + getConversionDelayMs() + ","
                + toHex(getReadCommand()) + "," + getReadLength() + "," + getValueOffset() + ","
                + getValueLength() + "," + (isLittleEndian() ? "L" : "B") + ","
                + (isCrcChecked() ? "1" : "0") + "," + slot;
    }

    private static String toHex(int value) {
        return Integer.toHexString(value & 0xFF);
    }
}
