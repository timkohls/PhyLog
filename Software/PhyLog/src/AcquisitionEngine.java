import java.util.List;

/**
 * Verarbeitet die vom ESP32 über {@link DeviceConnection} empfangenen Zeilen (Messwerte,
 * Spektren, Sensorfehler) für beide Kanäle und steuert Aufzeichnung, Trigger und
 * Vor-Trigger-Puffer. Kennt keine Swing-Komponenten - Ergebnisse gehen ausschließlich über
 * {@link Listener} an die Oberfläche ({@link GUI}).
 */
public class AcquisitionEngine {

    /** Rückkanal zur Oberfläche für Ereignisse, die dort angezeigt werden müssen. */
    public interface Listener {
        /** Verbindungs- oder Aufzeichnungsstatus hat sich geändert (Start/Stopp/Trigger-Warten). */
        void onStatusChanged();

        /** Die in {@link TriggerDialog.Config#maxDurationMs} gesetzte Höchstdauer wurde erreicht. */
        void onDurationLimitReached();

        /** Ein neues Frequenzspektrum ist für {@code channelId} eingetroffen. */
        void onSpectrumFrame(char channelId, double[] magnitudesDb, int sampleRateHz);

        /** Die Aufzeichnung wurde beendet (regulär, per Limit, oder durch einen Fehler). */
        void onRecordingStopped();

        /** Die serielle/Bluetooth-Verbindung brach während einer laufenden Aufzeichnung ab. */
        void onConnectionLostDuringRecording();

        /** Der Sensor auf {@code channelId} konnte wiederholt nicht ausgelesen werden
         *  (siehe {@link #SENSOR_ERROR_STREAK_THRESHOLD}); die Aufzeichnung wurde gestoppt. */
        void onSensorErrorDuringRecording(char channelId, String errorTag);
    }

    private final MeasurementChannel channelA;
    private final MeasurementChannel channelB;
    private final Listener listener;

    private TriggerDialog.Config triggerConfig = new TriggerDialog.Config();
    private int sampleRateHz = 20;

    /** Zeitstempel (ms, Gerätezeit) des ersten Messwerts der laufenden Aufzeichnung - Basis für
     *  die "Zeit (s)"-Spalte. {@code -1}, solange noch kein Wert eingetroffen ist. */
    private long measurementStartMillis = -1;

    private boolean recording = false;
    /** {@code true}, während bei aktivem Schwellenwert-Trigger auf die Flanke gewartet wird -
     *  schließt sich mit {@link #recording} gegenseitig aus. */
    private boolean waitingForTrigger = false;

    /** Reagiert auf Verbindungsauf-/-abbau, siehe {@link #onConnectionStatusChanged()}. */
    private final Runnable connectionListener = this::onConnectionStatusChanged;

    /** Zählt aufeinanderfolgende #ERR-Zeilen je Kanal; wird bei jedem gültigen Messwert auf 0
     *  zurückgesetzt (siehe {@link #resetSensorErrorStreak}). */
    private int sensorErrorStreakA = 0;
    private int sensorErrorStreakB = 0;

    /** Anzahl aufeinanderfolgender Sensorfehler, ab der eine laufende Aufzeichnung gestoppt
     *  wird - einzelne Ausreißer sollen die Messung nicht sofort abbrechen. */
    private static final int SENSOR_ERROR_STREAK_THRESHOLD = 3;

    public AcquisitionEngine(MeasurementChannel channelA, MeasurementChannel channelB, Listener listener) {
        this.channelA = channelA;
        this.channelB = channelB;
        this.listener = listener;
        DeviceConnection.getInstance().addConnectionListener(connectionListener);
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

    /** Sendet die aktuelle Abtastrate an die Firmware (Obergrenze 1000 Hz), sofern verbunden. */
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

    /** Startet eine neue Aufzeichnung: setzt beide Kanäle zurück und beginnt je nach
     *  {@link TriggerDialog.Config#thresholdMode} sofort oder erst nach Trigger-Flanke. */
    public void start() {
        measurementStartMillis = -1;
        for (MeasurementChannel ch : new MeasurementChannel[]{channelA, channelB}) {
            ch.latestValue = null;
            ch.preTriggerBuffer.clear();
            ch.lastValueForEdge = null;
            ch.snapshotMode = false;
        }

        if (triggerConfig.thresholdMode) {
            waitingForTrigger = true;
            recording = false;
        } else {
            waitingForTrigger = false;
            recording = true;
        }
        listener.onStatusChanged();
    }

    /** Übernimmt für jeden Kanal mit nicht-spektralem Sensor den aktuellen Live-Wert als
     *  Tabellenzeile (Index statt Zeit) - siehe {@link GUI#captureSnapshot()}. */
    public void captureSnapshot() {
        captureSnapshotForChannel(channelA);
        captureSnapshotForChannel(channelB);
    }

    private void captureSnapshotForChannel(MeasurementChannel ch) {
        if (!ch.hasSensor() || ch.producesSpectrum()) return;

        Double value = ch.latestValue;
        if (value == null) return;

        ch.snapshotMode = true;
        int index = ch.tableModel.getRowCount();
        ch.tableModel.addRow(new Object[]{(double) index, value});
    }

    /** Beendet eine laufende oder auf Trigger wartende Aufzeichnung und benachrichtigt den Listener. */
    public void stop() {
        recording = false;
        waitingForTrigger = false;
        listener.onStatusChanged();
        listener.onRecordingStopped();
    }

    /** Stoppt automatisch, wenn während einer laufenden Aufzeichnung die Verbindung abbricht. */
    private void onConnectionStatusChanged() {
        if (DeviceConnection.getInstance().isConnected()) return;
        if (!recording && !waitingForTrigger) return;

        stop();
        listener.onConnectionLostDuringRecording();
    }

    /** Wertet eine vom ESP32 empfangene Zeile aus dem entsprechenden Protokollpräfix aus. */
    public void onLineReceived(String line) {
        if (line.startsWith("D,")) {
            onDataLine(line);
        } else if (line.startsWith("#SPEC,")) {
            onSpectrumLine(line);
        } else if (line.startsWith("#ERR,")) {
            onErrorLine(line);
        }
    }

    /** Verarbeitet eine "D,millis,kanal,slot,rohwert"-Zeile (Einzelmesswert). */
    private void onDataLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) return;

        try {
            long millis = Long.parseLong(parts[1].trim());
            char channelId = parts[2].trim().charAt(0);
            int slot = Integer.parseInt(parts[3].trim());
            long rawValue = Long.parseLong(parts[4].trim());

            if (channelId == 'A' || channelId == 'B') {
                resetSensorErrorStreak(channelId);
                ingestSample(channel(channelId), slot, rawValue, millis);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** Verarbeitet eine "#ERR,tag,kanal"-Zeile; stoppt eine laufende Aufzeichnung erst nach
     *  {@link #SENSOR_ERROR_STREAK_THRESHOLD} aufeinanderfolgenden Fehlern auf demselben Kanal. */
    private void onErrorLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 3) return;

        String errorTag = parts[1].trim();
        char channelId = parts[2].trim().charAt(0);
        if (channelId != 'A' && channelId != 'B') return;

        MeasurementChannel ch = channel(channelId);
        if (!ch.hasSensor()) return;

        int streak = incrementSensorErrorStreak(channelId);
        if (streak < SENSOR_ERROR_STREAK_THRESHOLD) return;

        resetSensorErrorStreak(channelId);
        if (!recording && !waitingForTrigger) return;

        stop();
        listener.onSensorErrorDuringRecording(channelId, errorTag);
    }

    private int incrementSensorErrorStreak(char channelId) {
        if (channelId == 'A') return ++sensorErrorStreakA;
        return ++sensorErrorStreakB;
    }

    private void resetSensorErrorStreak(char channelId) {
        if (channelId == 'A') sensorErrorStreakA = 0;
        else sensorErrorStreakB = 0;
    }

    /** Verarbeitet eine "#SPEC,kanal,bins,abtastrate,mag0,mag1,..."-Zeile (Frequenzspektrum);
     *  Magnitude je Bin ist als Zehntel-dB kodiert. Wird nur während laufender Aufzeichnung
     *  ausgewertet - Spektren ohne aktive Messung sind für die Anzeige uninteressant. */
    private void onSpectrumLine(String line) {
        if (!recording) return;

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

    /** Dekodiert einen Rohmesswert (nur wenn er zur ersten Messgröße des aktiven Sensors passt,
     *  siehe {@code slot}), wendet die Tara an und leitet ihn je nach Zustand an Trigger-Prüfung
     *  oder Tabellen-Aufzeichnung weiter. Verwirft NaN/unendlich als ungültige Dekodierung. */
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

    /** Hält für {@link TriggerDialog.Config#preTriggerMs} Millisekunden Messwerte im Ringpuffer
     *  vor, damit nach einem Trigger auch der Zeitraum davor rekonstruiert werden kann (siehe
     *  {@link #backfillPreTriggerData}). Ohne Vor-Trigger-Zeit ({@code preTriggerMs <= 0}) tut
     *  diese Methode nichts. */
    private void bufferForPreTrigger(MeasurementChannel ch, long millis, double value) {
        if (triggerConfig.preTriggerMs <= 0) return;

        ch.preTriggerBuffer.addLast(new double[]{millis, value});
        long cutoff = millis - triggerConfig.preTriggerMs;
        while (!ch.preTriggerBuffer.isEmpty() && ch.preTriggerBuffer.peekFirst()[0] < cutoff) {
            ch.preTriggerBuffer.removeFirst();
        }
    }

    /** Prüft, ob der Messwert die konfigurierte Schwelle in der konfigurierten Richtung
     *  überschreitet (Flankenerkennung anhand des vorherigen Werts); nur der als Trigger-Kanal
     *  konfigurierte Kanal löst aus. */
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

    /** Schaltet von Trigger-Wartezeit auf laufende Aufzeichnung um und füllt beide Kanäle
     *  rückwirkend mit den gepufferten Vor-Trigger-Werten auf. */
    private void fireTrigger(long triggerMillis) {
        waitingForTrigger = false;
        recording = true;
        measurementStartMillis = triggerMillis - triggerConfig.preTriggerMs;

        backfillPreTriggerData(channelA);
        backfillPreTriggerData(channelB);

        listener.onStatusChanged();
    }

    /** Überträgt die im Vor-Trigger-Puffer gehaltenen Werte als reguläre Zeilen in die Tabelle. */
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
