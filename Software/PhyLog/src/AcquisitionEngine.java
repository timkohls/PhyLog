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

        /** Eine laufende Aufzeichnung wurde gerade gestoppt (siehe {@link #stop()}) - im
         *  Gegensatz zu {@link #onStatusChanged()} nur genau dann, nicht auch bei Start oder
         *  Trigger-Auslösung, damit z. B. ein einmaliges "letztes Spektrum in die Tabelle
         *  übernehmen" nicht bei jeder Statusänderung erneut passiert. */
        void onRecordingStopped();

        /** Eine laufende Aufzeichnung bzw. das Warten auf den Trigger wurde abgebrochen, weil die
         *  Verbindung zum Gerät verloren ging (siehe {@link #onConnectionStatusChanged}) - wird
         *  zusätzlich zu, und unmittelbar nach, {@link #onStatusChanged()} und
         *  {@link #onRecordingStopped()} aufgerufen, damit die UI den Grund des Abbruchs anzeigen
         *  kann (anders als beim regulären Stopp-Knopf oder {@link #onDurationLimitReached()}). */
        void onConnectionLostDuringRecording();

        /** Eine laufende Aufzeichnung bzw. das Warten auf den Trigger wurde abgebrochen, weil die
         *  Firmware für einen Kanal wiederholt einen Sensorfehler gemeldet hat (siehe
         *  {@link #onErrorLine}, {@code #ERR,<Tag>,<Kanal>} in phylog_firmware.ino - z. B. eine
         *  ausbleibende I2C-Antwort oder ein HX711-Timeout). Wird, wie
         *  {@link #onConnectionLostDuringRecording()}, zusätzlich zu und unmittelbar nach
         *  {@link #onStatusChanged()} und {@link #onRecordingStopped()} aufgerufen.
         *
         * @param channelId betroffener Kanal ('A' oder 'B')
         * @param errorTag  von der Firmware gemeldete Fehlerart (z. B. "I2C", "HX711")
         */
        void onSensorErrorDuringRecording(char channelId, String errorTag);
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

    /** Bricht eine laufende Aufzeichnung bzw. das Warten auf den Trigger ab, sobald die
     *  Verbindung zum Gerät verloren geht (siehe {@link #onConnectionStatusChanged}) - sonst
     *  bliebe der Zustand unbemerkt für immer auf "läuft" stehen, ohne dass je wieder ein
     *  Messwert eintrifft, bis jemand von Hand auf Stopp klickt. */
    private final Runnable connectionListener = this::onConnectionStatusChanged;

    /** Anzahl aufeinanderfolgender, von der Firmware gemeldeter Sensorfehler (siehe
     *  {@link #onErrorLine}) je Kanal, ohne dazwischen eine erfolgreiche Datenzeile für diesen
     *  Kanal. Wird bei jeder erfolgreich verarbeiteten Datenzeile dieses Kanals zurückgesetzt
     *  (siehe {@link #onDataLine}) - ein einzelner, vereinzelter Fehler (z. B. kurzer
     *  I2C-Wackelkontakt, den die Firmware ohnehin selbst per automatischem Bus-Reset behebt,
     *  siehe {@code noteI2CResult} in phylog_firmware.ino) soll die Aufzeichnung nicht
     *  gleich abbrechen. */
    private int sensorErrorStreakA = 0;
    private int sensorErrorStreakB = 0;

    /** Ab wie vielen aufeinanderfolgenden Fehlermeldungen für denselben Kanal (siehe
     *  {@link #sensorErrorStreakA}/{@link #sensorErrorStreakB}) eine laufende Aufzeichnung
     *  abgebrochen wird. Die Firmware meldet Fehler je Kanal höchstens einmal pro Sekunde (siehe
     *  {@code reportSensorError} in phylog_firmware.ino) - der Schwellenwert entspricht also
     *  grob so vielen Sekunden andauerndem Fehler. */
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

        // Kein sendLine("START") mehr hier: Das Gerät streamt seit dem Verbindungsaufbau bereits
        // durchgehend (siehe DeviceConnection#connect) - hier wird nur noch lokal umgeschaltet,
        // ob eingehende Werte in die Tabelle geschrieben werden (siehe #ingestSample).
    }

    /** Fügt für jeden Kanal mit aktivem, nicht-spektralem Sensor und vorliegendem Live-Wert genau
     *  eine Zeile mit dem aktuellen Messwert in dessen Tabelle ein - unabhängig davon, ob gerade
     *  eine reguläre Aufzeichnung läuft. Anders als bei {@link #ingestSample} steht in der ersten
     *  Spalte dabei nicht die vergangene Zeit, sondern der fortlaufende Tabellenindex, da
     *  einzelne, per Knopfdruck ausgelöste Momentaufnahmen (z. B. an mehreren manuell
     *  eingestellten Positionen) keinen gemeinsamen Zeitbezug haben. */
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

    /** Stoppt eine laufende Aufzeichnung bzw. das Warten auf den Trigger. */
    public void stop() {
        recording = false;
        waitingForTrigger = false;
        listener.onStatusChanged();
        listener.onRecordingStopped();

        // Kein sendLine("STOP") mehr hier: Das würde das Gerät komplett verstummen lassen (siehe
        // DeviceConnection#connect/#disconnect) und damit MeasurementChannel#latestValue
        // einfrieren - Live-Anzeigen und die Momentaufnahme-Funktion sollen aber gerade auch
        // funktionieren, wenn gerade keine Aufzeichnung läuft.
    }

    /** Reagiert auf jede Verbindungsstatusänderung (siehe {@link DeviceConnection#addConnectionListener}),
     *  aber nur ein Verbindungsverlust während laufender Aufzeichnung bzw. während auf den
     *  Trigger gewartet wird löst hier etwas aus - ein Verbindungsaufbau selbst hat auf eine
     *  bereits laufende Aufzeichnung keinen Einfluss und muss deshalb auch nichts abbrechen. */
    private void onConnectionStatusChanged() {
        if (DeviceConnection.getInstance().isConnected()) return;
        if (!recording && !waitingForTrigger) return;

        stop();
        listener.onConnectionLostDuringRecording();
    }

    /** Verarbeitet eine vom Gerät empfangene Zeile: Datenzeilen ("D,millis,Kanal,Slot,Rohwert")
     *  für normale Sensoren, {@code #SPEC}-Pakete für Spektrum-Sensoren (siehe
     *  {@link Sensor#producesSpectrum()}), {@code #ERR}-Pakete für gemeldete Sensorfehler (siehe
     *  {@link #onErrorLine}); alles andere wird ignoriert. */
    public void onLineReceived(String line) {
        if (line.startsWith("D,")) {
            onDataLine(line);
        } else if (line.startsWith("#SPEC,")) {
            onSpectrumLine(line);
        } else if (line.startsWith("#ERR,")) {
            onErrorLine(line);
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
                resetSensorErrorStreak(channelId);
                ingestSample(channel(channelId), slot, rawValue, millis);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** Parst ein Fehlerpaket {@code #ERR,<Tag>,<Kanal>} (siehe {@code reportSensorError} in
     *  phylog_firmware.ino, z. B. für eine ausbleibende I2C-Antwort oder einen HX711-Timeout) und
     *  bricht eine laufende Aufzeichnung bzw. das Warten auf den Trigger ab, sobald sich für
     *  denselben Kanal {@link #SENSOR_ERROR_STREAK_THRESHOLD} solcher Meldungen in Folge häufen,
     *  ohne dass dazwischen wieder eine gültige Datenzeile für diesen Kanal ankam (siehe
     *  {@link #onDataLine}) - ein einzelner, vorübergehender Fehler bricht also noch nichts ab. */
    private void onErrorLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 3) return;

        String errorTag = parts[1].trim();
        char channelId = parts[2].trim().charAt(0);
        if (channelId != 'A' && channelId != 'B') return;

        MeasurementChannel ch = channel(channelId);
        if (!ch.hasSensor()) return;   // Kanal ist gar nicht konfiguriert -> Fehler ignorieren

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

    /** Parst ein Spektrum-Paket {@code #SPEC,<Kanal>,<Bins>,<Abtastrate>,<mag_0>,<mag_1>,...}
     *  (siehe {@code captureAndSendSpectrum} in phylog_firmware.ino) und reicht es an
     *  {@link Listener#onSpectrumFrame} weiter. Magnituden kommen als dBFS·10 (int) an, um
     *  Bandbreite zu sparen - hier wieder auf dB zurückgerechnet.
     *
     *  <p>Wie bei normalen Messwerten (siehe {@link #ingestSample}) wird nur während einer
     *  laufenden Aufzeichnung weitergereicht - die Firmware streamt Spektrum-Pakete zwar
     *  durchgehend ab dem Verbindungsaufbau (siehe {@link DeviceConnection#connect}), ohne diese
     *  Prüfung würde das Diagramm also auch dann live mit Spektren aktualisiert, wenn nie "Start"
     *  gedrückt wurde. Einen {@code waitingForTrigger}-Fall wie in {@link #ingestSample} gibt es
     *  hier nicht, da Spektrum-Sensoren keinen Trigger unterstützen (siehe {@code spectrumMode}
     *  in {@link GUI}, das den Trigger-Button dafür sperrt).</p> */
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