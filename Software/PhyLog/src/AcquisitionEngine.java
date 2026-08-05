import java.util.List;

/**
 * Nimmt vom {@link DeviceConnection} empfangene Datenzeilen entgegen, dekodiert sie über den
 * jeweils aktiven {@link Sensor} und schreibt die Messwerte - abhängig von Trigger-Konfiguration
 * und Aufzeichnungszustand - in die Tabellen der {@link MeasurementChannel}s. Kennt keine
 * UI-Elemente außer der Tabelle selbst; Statusänderungen (Start/Stopp, Dauerlimit erreicht)
 * werden über {@link Listener} an {@link GUI} gemeldet.
 *
 * <p>War früher Teil von {@link GUI}; wurde herausgelöst, damit das Hauptfenster sich auf aus
 * UI-Aufbau und -Verdrahtung beschränken kann, während Aufnahme- und Trigger-Logik unabhängig
 * davon nachvollziehbar bleiben.</p>
 */
public class AcquisitionEngine {

    /** Reagiert auf Zustandsänderungen der Aufzeichnung, die sich in der UI widerspiegeln müssen. */
    public interface Listener {
        /** Aufzeichnung/Trigger-Warten wurde gestartet oder gestoppt. */
        void onStatusChanged();

        /** Die konfigurierte maximale Messdauer wurde erreicht, die Aufzeichnung wurde gestoppt. */
        void onDurationLimitReached();

        /** Ein neues Frequenzspektrum für einen Spektrum-Sensor (siehe {@link Sensor#producesSpectrum()})
         *  ist eingetroffen (siehe {@link #onLineReceived}). */
        void onSpectrumFrame(char channelId, double[] magnitudesDb, int sampleRateHz);
    }

    private final MeasurementChannel channelA;
    private final MeasurementChannel channelB;
    private final Listener listener;

    private TriggerDialog.Config triggerConfig = new TriggerDialog.Config();
    private int sampleRateHz = 20;
    /** Nullpunkt für die relative Zeitachse in Millisekunden. -1 = noch nicht gesetzt. */
    private long measurementStartMillis = -1;

    /** {@code true} während einer laufenden Aufzeichnung. */
    private boolean recording = false;
    /** {@code true}, nachdem Start gedrückt wurde, solange im Schwellenwert-Modus noch auf die
     *  Trigger-Bedingung gewartet wird - es wird noch nichts aufgezeichnet. */
    private boolean waitingForTrigger = false;

    public AcquisitionEngine(MeasurementChannel channelA, MeasurementChannel channelB, Listener listener) {
        this.channelA = channelA;
        this.channelB = channelB;
        this.listener = listener;
    }

    /** @return den Kanal 'A' oder 'B'; alles andere fällt auf Kanal A zurück. */
    public MeasurementChannel channel(char id) {
        return (id == 'B') ? channelB : channelA;
    }

    public TriggerDialog.Config getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(TriggerDialog.Config triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public void setSampleRateHz(int sampleRateHz) {
        this.sampleRateHz = sampleRateHz;
    }

    /** Überträgt die aktuell eingestellte Abtastrate an die Firmware. */
    public void pushSampleRateToFirmware() {
        if (!DeviceConnection.getInstance().isConnected() || sampleRateHz <= 0) {
            return;
        }
        DeviceConnection.getInstance().sendLine("RATE," + Math.min(sampleRateHz, 1000));
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isWaitingForTrigger() {
        return waitingForTrigger;
    }

    /** Startet eine neue Aufzeichnung (bzw. wartet bei Schwellenwert-Trigger zunächst darauf). */
    public void start() {
        measurementStartMillis = -1;
        for (MeasurementChannel ch : new MeasurementChannel[]{channelA, channelB}) {
            ch.latestValue = null;
            ch.preTriggerBuffer.clear();
            ch.lastValueForEdge = null;
        }

        if (triggerConfig.thresholdMode) {
            waitingForTrigger = true;
            recording = false;
        } else {
            waitingForTrigger = false;
            recording = true;
        }
        listener.onStatusChanged();

        DeviceConnection.getInstance().sendLine("START");
    }

    /** Stoppt eine laufende Aufzeichnung bzw. das Warten auf den Trigger. */
    public void stop() {
        recording = false;
        waitingForTrigger = false;
        listener.onStatusChanged();
        DeviceConnection.getInstance().sendLine("STOP");
    }

    /** Verarbeitet eine vom Gerät empfangene Zeile: Datenzeilen ("D,millis,Kanal,Slot,Rohwert")
     *  für normale Sensoren, {@code #SPEC}-Pakete für Spektrum-Sensoren (siehe
     *  {@link Sensor#producesSpectrum()}); alles andere wird ignoriert. */
    public void onLineReceived(String line) {
        if (line.startsWith("D,")) {
            onDataLine(line);
        } else if (line.startsWith("#SPEC,")) {
            onSpectrumLine(line);
        }
    }

    private void onDataLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) return;

        try {
            long millis = Long.parseLong(parts[1].trim());
            char channelId = parts[2].trim().charAt(0);
            int slot = Integer.parseInt(parts[3].trim());
            long rawValue = Long.parseLong(parts[4].trim());

            if (channelId == 'A' || channelId == 'B') {
                ingestSample(channel(channelId), slot, rawValue, millis);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** Parst ein Spektrum-Paket {@code #SPEC,<Kanal>,<Bins>,<Abtastrate>,<mag_0>,<mag_1>,...}
     *  (siehe {@code captureAndSendSpectrum} in phylog_firmware.ino) und reicht es an
     *  {@link Listener#onSpectrumFrame} weiter. Magnituden kommen als dBFS·10 (int) an, um
     *  Bandbreite zu sparen - hier wieder auf dB zurückgerechnet. */
    private void onSpectrumLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 4) return;

        try {
            char channelId = parts[1].charAt(0);
            int bins = Integer.parseInt(parts[2]);
            int sampleRateHz = Integer.parseInt(parts[3]);
            if (parts.length < 4 + bins) return;

            double[] magnitudesDb = new double[bins];
            for (int i = 0; i < bins; i++) {
                magnitudesDb[i] = Integer.parseInt(parts[4 + i]) / 10.0;
            }

            listener.onSpectrumFrame(channelId, magnitudesDb, sampleRateHz);
        } catch (NumberFormatException ignored) {
        }
    }

    private void ingestSample(MeasurementChannel ch, int slot, long rawValue, long millis) {
        Sensor sensor = ch.sensor;
        if (sensor == null || sensor == SensorRegistry.NO_SENSOR) {
            return;
        }

        List<Sensor.Quantity> quantities = sensor.getQuantities();
        if (quantities.isEmpty() || quantities.getFirst().slot != slot) {
            return;
        }

        double rawDecoded = sensor.decode(slot, rawValue);
        if (Double.isNaN(rawDecoded) || Double.isInfinite(rawDecoded)) {
            return;
        }

        double value = rawDecoded - ch.tareOffset;
        ch.latestValue = value;
        bufferForPreTrigger(ch, millis, value);

        if (waitingForTrigger) {
            checkTriggerCondition(ch, millis, value);
            return;
        }

        if (!recording) return;

        if (measurementStartMillis < 0) {
            measurementStartMillis = millis;
        }
        double timeSeconds = (millis - measurementStartMillis) / 1000.0;
        ch.tableModel.addRow(new Object[]{timeSeconds, value});

        if (triggerConfig.maxDurationMs > 0 && timeSeconds * 1000.0 >= triggerConfig.maxDurationMs) {
            stop();
            listener.onDurationLimitReached();
        }
    }

    private void bufferForPreTrigger(MeasurementChannel ch, long millis, double value) {
        if (triggerConfig.preTriggerMs <= 0) return;

        ch.preTriggerBuffer.addLast(new double[]{millis, value});
        long cutoff = millis - triggerConfig.preTriggerMs;
        while (!ch.preTriggerBuffer.isEmpty() && ch.preTriggerBuffer.peekFirst()[0] < cutoff) {
            ch.preTriggerBuffer.removeFirst();
        }
    }

    private void checkTriggerCondition(MeasurementChannel ch, long millis, double value) {
        if (ch.id != triggerConfig.channel) return;

        Double previous = ch.lastValueForEdge;
        ch.lastValueForEdge = value;
        if (previous == null) return;

        double threshold = triggerConfig.threshold;
        boolean crossed = triggerConfig.risingEdge
                ? (previous < threshold && value >= threshold)
                : (previous > threshold && value <= threshold);

        if (crossed) {
            fireTrigger(millis);
        }
    }

    private void fireTrigger(long triggerMillis) {
        waitingForTrigger = false;
        recording = true;
        measurementStartMillis = triggerMillis - triggerConfig.preTriggerMs;

        backfillPreTriggerData(channelA);
        backfillPreTriggerData(channelB);

        listener.onStatusChanged();
    }

    private void backfillPreTriggerData(MeasurementChannel ch) {
        for (double[] sample : ch.preTriggerBuffer) {
            long sampleMillis = (long) sample[0];
            if (sampleMillis < measurementStartMillis) continue;
            double timeSeconds = (sampleMillis - measurementStartMillis) / 1000.0;
            ch.tableModel.addRow(new Object[]{timeSeconds, sample[1]});
        }
        ch.preTriggerBuffer.clear();
    }
}
