/*
 * PhyLog ESP32 Firmware v8.3
 *
 * Sensortypen: I2C (INA219, VEML7700), HX711 (DOUT/SCK, kein I2C), Analog-Pin, ein
 * INMP441-Mikrofon (I2S), ein KY-003-Hall-Modul (digital) sowie ein DS18B20-Digitalthermometer
 * (1-Wire, siehe v8.1-Hinweis unten). Welcher Sensortyp auf Kanal A/B aktiv ist, wird
 * ausschließlich über das serielle Kommando SET,<Kanal>,<Typ> gesetzt (siehe
 * GUI.pushSensorSelectionToFirmware). Beide Kanäle starten bei TYPE_NONE, bis die Software eine
 * Auswahl sendet.
 *
 * <p>Jeder Kanal-Port (RJ45, 9 Pins) hat genau drei Signal-Pins (Pin 2, 3, 4 - siehe
 * pinout.md), deren Rolle sich erst beim SET-Kommando aus dem gewählten Sensortyp ergibt (siehe
 * {@link #configureChannelHardware}):
 *   I2C:       Pin2=SDA,  Pin3=SCL
 *   HX711:     Pin2=DOUT, Pin3=SCK
 *   I2S (Mic): Pin2=WS,   Pin3=BCLK, Pin4=SD
 *   Analog:    Pin2=Eingang
 *   1-Wire (DS18B20): Pin2=Datenleitung (braucht externen Pull-up nach 3,3V, siehe DS18B20Sensor.java)
 * Nie mehr als drei Adern pro Sensor, unabhängig vom Typ.</p>
 *
 * <p>Das Mikrofon nutzt bewusst den NEUEN I2S-Standardtreiber (driver/i2s_std.h), nicht den
 * alten driver/i2s.h: der alte Treiber bringt intern noch den Legacy-ADC-Treiber mit, der auf
 * aktuellen arduino-esp32-Versionen mit dem von analogRead() genutzten ADC-Treiber
 * ("driver_ng") kollidiert - das führte zu einem abort() beim Start, sobald irgendein Kanal auf
 * TYPE_ANALOG stand. Der neue Treiber betrifft den ADC-Pfad nicht.</p>
 *
 * <p>Kanal A und Kanal B hängen an physisch getrennten Bussen (I2C: Wire/Wire1, I2S: Port 0/1) -
 * das ist notwendig, sobald beide Kanäle gleichzeitig einen Sensor betreiben, auch denselben
 * Typ zweimal.</p>
 *
 * <p>v7.4: Kanal A, Pin 2 (SCL/SCK/BCLK) lag bisher auf GPIO12 - einem Strapping-Pin, der beim
 * Reset die Flash-Spannung festlegt. War beim Reset bereits ein Sensor angeschlossen, konnte der
 * ESP32 dadurch mit falscher Flash-Spannung booten und landete in einer Watchdog-Reset-Schleife
 * (TG0WDT_SYS_RESET), noch bevor setup() lief - nur per Reflash (zufällig verbunden mit einem
 * sauberen Reset) zu beheben. Kanal A liegt jetzt komplett auf GPIO 17/16/4 (Pin 2/3/4) - eine
 * auf dem 38-Pin-DevKitC zusammenhängende Dreiergruppe ohne Strapping-Pins, analog zu Kanal B
 * auf 27/26/25. Zusätzlich: automatischer I2C-Bus-Reset nach mehreren Fehlern in Folge (siehe
 * {@link #resetI2CBus}). (Stand v7.4 - diese Belegung wurde in v8.0 wegen fehlender ADC-Hardware
 * auf GPIO17/16 nochmal geändert, siehe dortiger Hinweis.)</p>
 *
 * <p>v7.5: Neuer Live-Modus fürs Mikrofon - statt (bzw. zusätzlich zu) dem einzelnen dB-Wert pro
 * Zyklus liefert das SPEC-Kommando laufend ein 512-Bin-Amplitudenspektrum (siehe
 * {@link #captureAndSendSpectrum}), berechnet über eine selbst geschriebene Radix-2-FFT
 * ({@link #computeFFT}) auf 1024 Samples je Bild.</p>
 *
 * <p>v7.6: Das separate SPEC-Kommando ist wieder weg - das Spektrum ist jetzt schlicht ein
 * eigener Sensortyp (TYPE_MIC_SPECTRUM, GUI-seitig "MICSPEC"), der sich wie jeder andere Sensor
 * über SET auswählen lässt und ganz normal an START/STOP hängt, statt eine eigene
 * Start/Stopp-Logik zu brauchen. captureAndSendSpectrum() läuft dabei weiterhin mit eigener
 * Taktung (SPECTRUM_INTERVAL_MS), unabhängig von der für normale Sensoren gedachten Abtastrate.</p>
 *
 * <p>v7.7: Baudrate auf 460800 vervierfacht (siehe BAUD_RATE) und SPECTRUM_INTERVAL_MS von
 * 250ms auf 60ms verkürzt - das Spektrum ist damit an der tatsächlich erreichbaren Grenze statt
 * an einer für die alte, langsamere Baudrate konservativ gewählten Konstante. GUI-seitig
 * erzwingt der Sensor-Dialog jetzt außerdem, dass ein Kanal automatisch auf "kein Sensor"
 * zurückfällt, sobald der andere Kanal ein Frequenzspektrum aufnimmt - zwei Mikrofon-Captures
 * gleichzeitig ergäben ohnehin nur unnötige Konkurrenz um dieselbe, jetzt knappere Bandbreite.</p>
 *
 * <p>v7.8: Neuer Sensortyp TYPE_HALL für das KY-003-Hall-Sensor-Modul - ein einfacher digitaler
 * Eingang (nur Pin 0 genutzt), Rohwert ist direkt digitalRead(). Wie bei allen anderen Sensoren
 * übernimmt die Java-Seite (HallEffectSensor) die Interpretation des Rohwerts, hier inklusive
 * der Invertierung, da das Modul active-low ist.</p>
 *
 * <p>v7.9: Zwei Sensoren konnten die eingestellte Abtastrate faktisch nicht einhalten, egal wie
 * hoch sie in der GUI gewählt wurde: Das Mikrofon (dB-Modus) las immer 256 I2S-Samples pro
 * Zyklus, was allein schon 16ms dauert und die Rate hart auf ~62 Hz deckelte - jetzt liest
 * {@link #microphoneReadSampleCount} dynamisch so viele Samples, wie in das aktuell eingestellte
 * Intervall passen. Der VEML7700 hatte eine Integrationszeit von 100ms (max. 10 Hz) - jetzt auf
 * die kürzeste verfügbare Einstellung (25ms, max. 40 Hz) reduziert. Java-seitig kennt jeder
 * Sensor jetzt seine realistische Obergrenze ({@link Sensor#getMaxSampleRateHz}), damit die GUI
 * gar nicht erst höhere Raten anbietet, als der jeweilige Sensor tatsächlich liefern kann.</p>
 *
 * <p>v8.0: TYPE_ANALOG las auf Kanal A immer 0 statt eines echten Messwerts - dessen Pin 2 lag
 * auf GPIO17, das hat auf dem klassischen ESP32 schlicht keine ADC-Hardware, {@code analogRead()}
 * darauf liefert deshalb unabhängig vom tatsächlich angelegten Signal konstant 0. Kanal B (Pin 2
 * = GPIO27, ein regulärer ADC2-Kanal) war davon nie betroffen - deshalb lag es nicht an
 * Wackelkontakt oder Spannungspegel, sondern schlicht an einem für Analog-Sensoren ungeeigneten
 * Pin. Statt das nur für TYPE_ANALOG per Sonderfall zu umgehen (und Kanal A/B dadurch dauerhaft
 * unterschiedliche Pin-Belegungen für Analog-Sensoren zu geben), liegt Kanal A jetzt komplett auf
 * GPIO 32/33/35 statt 17/16/4 (siehe {@link #PINS_CHANNEL_A}) - alle drei ADC1-fähig (Kanal B auf
 * 27/26/25 bleibt unverändert, dort war ohnehin schon jeder Pin ADC-fähig). Pin 2 ist damit auf
 * beiden Kanälen gleichermaßen der Analog-Eingang, ohne Sonderfall in {@link #sampleChannel} - nur
 * die interne Verdrahtung zwischen ESP32 und der Kanal-A-Buchse ändert sich (GPIO17->32,
 * GPIO16->33, GPIO4->35), an der Steckerbelegung selbst (Pin 2/3/4) ändert sich nichts. 35 statt
 * dem anfangs gewählten 34, weil 34/35/32/33 auf den gängigen 38-Pin-DevKitC-Boards als
 * zusammenhängender Viererblock direkt nebeneinander auf der Buchsenleiste liegen (34 am Rand,
 * dann 35/32/33) - mit 32/33/35 landen die drei tatsächlich genutzten Pins ohne Lücke
 * nebeneinander, statt mit 34 einen Pin mittendrin ungenutzt zu lassen. Genaue Reihenfolge je
 * nach Board-Variante im Zweifel gegen den eigenen Bestückungsplan/Aufdruck prüfen.</p>
 *
 * <p>v8.1: Neuer Sensortyp TYPE_DS18B20 für das digitale DS18B20-Thermometer am 1-Wire-Bus
 * (ersetzt den bisherigen NTC-Spannungsteiler-Aufbau über TYPE_ANALOG). Anders als bei allen
 * bisherigen Sensoren braucht eine einzelne Messung hier bis zu 750ms Konversionszeit (12-Bit-
 * Auflösung, siehe DS18B20-Datenblatt) - ein direktes {@code delay(750)} mitten in {@link #loop}
 * hätte in dieser Zeit auch Serial-Kommandos (inkl. STOP) und die Abtastung des jeweils anderen
 * Kanals eingefroren. {@link #sampleDS18B20} löst die Konversion deshalb nur an und holt das
 * Ergebnis erst im nächsten Aufruf ab, sobald genug Zeit vergangen ist - keine Blockade, aber
 * dadurch auch kein Datenpaket bei jedem einzelnen Zyklus (analog zu TYPE_MIC_SPECTRUM, das mit
 * SPECTRUM_INTERVAL_MS ebenfalls eigenem statt dem regulären Abtasttakt folgt). Das 1-Wire-
 * Protokoll selbst ist wie beim HX711 ({@link #readHX711}) von Hand bit-gebangt, keine externe
 * OneWire-Bibliothek nötig.</p>
 *
 * <p>v8.2: {@link #oneWireReset}/{@link #oneWireWriteBit}/{@link #oneWireReadBit} nutzten bisher
 * die normalen Arduino-Funktionen {@code pinMode()}/{@code digitalWrite()}/{@code digitalRead()}.
 * Auf dem ESP32 kosten diese (anders als auf AVR) jeweils mehrere hundert ns bis über 1µs
 * (IO-MUX-Rekonfiguration, Bounds-Checks) - bei den hier genutzten 1-2µs-Zeitfenstern verschob
 * das den tatsächlichen Abtast-/Flankenzeitpunkt gegenüber dem 1-Wire-Timing so stark, dass die
 * Kommunikation mit einem angeschlossenen DS18B20 fast durchgehend an der CRC8-Prüfung scheiterte
 * (dauerhafte {@code #ERR,DS18B20}-Meldungen bei korrekt beschaltetem externen Pull-up). Ohne
 * Pull-up blieb der Bus dagegen dauerhaft auf LOW - dabei "passt" der CRC8 von neun gelesenen
 * Nullbytes zufällig gegen die ebenfalls gelesene Null-CRC, was eine augenscheinlich gültige,
 * aber falsche 0°C-Messung ergab, statt eines Fehlers. Fix: {@link #owLow}/{@link #owRelease}/
 * {@link #owRead} greifen direkt auf die GPIO-Register zu (wenige Taktzyklen statt hunderte ns)
 * und ersetzen die drei Arduino-HAL-Aufrufe in den zeitkritischen 1-Wire-Grundoperationen.</p>
 *
 * <p>v8.3: Zwei Nachbesserungen an v8.2. Erstens ein Tippfehler in {@link #owLow}/{@link #owRelease}:
 * Für Pins &ge; 32 (GPIO32-39, zweiter Registersatz - auf Kanal A betrifft das bereits den
 * DS18B20-Datenpin selbst, GPIO32) wurde für die Schreib-Register fälschlich {@code .data} statt
 * {@code .val} verwendet - dadurch schaltete der Bus auf Kanal A faktisch nie sauber um, mit
 * demselben "0°C ohne Fehler"-Symptom wie beim fehlenden Pull-up (siehe v8.2-Absatz); Kanal B
 * (Pins 27/26/25, alle &lt; 32) war nie betroffen. Zweitens ein Zeitverhalten-Bug in
 * {@link #sampleDS18B20}: die nächste Konversion wurde bisher erst beim übernächsten Aufruf
 * gestartet statt direkt im Anschluss ans Lesen der vorherigen - dadurch kam bei einem
 * Abtastintervall knapp über der Konversionszeit (z. B. 1 Hz, 1000ms &gt; 750ms) nur alle zwei
 * Intervalle ein Datenpaket statt bei jedem.</p>
 *
 * <p>v8.4: Drei Nachbesserungen, keine davon durch konkret beobachtete Fehlfunktion ausgelöst,
 * sondern beim Review gefunden.</p>
 * <p>Erstens dieselbe Schwachstellen-Klasse wie beim 1-Wire-Timing (siehe v8.2) jetzt auch bei
 * {@link #readHX711} behoben: Die 24 Taktflanken liefen bisher über {@code digitalWrite()}/
 * {@code digitalRead()} (mehrere hundert ns IO-MUX-Overhead je Aufruf, siehe dort) und ohne
 * {@code noInterrupts()} um die Schleife - ein dazwischenfunkender Interrupt (z. B. der
 * Millis-Timer) konnte SCK im ungünstigsten Fall über die ~60µs-Sleep-Schwelle des HX711 hinaus
 * verzögern und den Chip mitten im Auslesen in den Sleep-Modus schicken. Jetzt per neuer
 * {@link #gpioWriteFast}/{@link #gpioReadFast}-Hilfsfunktionen (direkter Registerzugriff wie bei
 * {@link #owLow} & Co., aber als reiner Push-Pull-Zugriff ohne dessen Open-Drain-Semantik - SCK
 * ist ein regulärer Ausgang) und mit der gesamten 24-Bit-Schleife innerhalb von
 * {@code noInterrupts()}/{@code interrupts()}, analog zu den 1-Wire-Grundoperationen.</p>
 * <p>Zweitens {@link #sendSpectrumPacket} auf einen einzigen {@code Serial.write()} pro Bild
 * umgestellt statt bisher 512 einzelner {@code Serial.print()}-Aufrufe für die Magnituden (plus
 * Header) - jeder einzelne Aufruf formatiert eine Zahl in ASCII und schreibt sie separat raus,
 * bei 512 Bins und ~16 Bildern/Sekunde (siehe {@link #SPECTRUM_INTERVAL_MS}) waren das über 8000
 * Print-Aufrufe pro Sekunde, die spürbar CPU-Zeit kosteten - Zeit, die für die zeitkritische
 * Abtastung des jeweils anderen Kanals fehlte. Jetzt wird das Paket in einen statischen Puffer
 * ({@link #SPECTRUM_PACKET_BUF_SIZE}) formatiert und als ein einziger {@code Serial.write()}
 * verschickt.</p>
 * <p>Drittens die {@code i2s_channel_read()}-Timeouts in {@link #sampleMicrophone} (50ms) und
 * {@link #captureAndSendSpectrum} (200ms) verkleinert (auf 20ms bzw. 100ms) - beide lagen mit
 * deutlichem Sicherheitsabstand über der tatsächlich benötigten Zeit, um die jeweils angeforderte
 * Samplezahl bei {@link #MIC_SAMPLE_RATE_HZ} zu sammeln (max. 32ms bzw. 64ms). Ein hängendes oder
 * getrenntes Mikrofon blockiert {@code loop()} - und damit auch die Abtastung des jeweils anderen
 * Kanals, siehe {@link #sampleChannel} - dadurch spürbar kürzer. Behebt das Problem nicht
 * grundsätzlich: Bei sehr hohen Abtastraten auf dem Nicht-Mikrofon-Kanal bleibt eine denkbare,
 * wenn auch kleinere, Verzögerung. Eine echte Entkopplung bräuchte einen eigenen FreeRTOS-Task
 * für die I2S-Lesevorgänge (mit Ringpuffer statt direktem Aufruf aus {@code loop()}) - das ist ein
 * größerer struktureller Umbau und hier bewusst nicht mitgemacht.</p>
 *
 * <p>v8.5: Bluetooth Classic (SPP) als zweiter, gleichberechtigter Weg zum PC - zusätzlich zur
 * bisherigen USB-Verbindung, nicht als Ersatz. Der klassische ESP32 kann beides gleichzeitig
 * betreiben (anders als ESP32-S2/S3/C3, die kein Classic-Bluetooth haben, nur BLE). Ein per SPP
 * gepairtes Gerät erscheint auf dem PC als ganz normaler virtueller COM-Port - GUI.java/
 * DeviceConnection.java brauchten dafür praktisch keine strukturelle Änderung (siehe dortiger
 * Kommentar), da beide ohnehin nur mit einem Portnamen + Baudrate arbeiten, ohne zwischen "echtem"
 * USB-COM-Port und einem SPP-COM-Port zu unterscheiden.</p>
 * <p>Firmware-seitig lief bisher jede Ausgabe direkt über {@code Serial.print}/{@code println}/
 * {@code write}, verstreut über {@link #reportSensorError}, {@link #sendDataPacket},
 * {@link #sendSpectrumPacket} und {@link #processCommand}. Alle Stellen jetzt über die neuen
 * {@link #hostWrite}/{@link #hostPrint}-Hilfsfunktionen, die konsequent an beide Schnittstellen
 * schreiben (USB immer, Bluetooth nur falls {@link #SerialBT}.hasClient()) - so bekommt die
 * Software dieselben Pakete unabhängig davon, über welchen der beiden Wege sie gerade verbunden
 * ist, ohne dass die Firmware wissen müsste, welche der beiden Verbindungen gerade "die aktive"
 * ist. Eingehende Kommandos entsprechend aus {@link #handleSerialCommunication} jetzt aus beiden
 * Quellen in denselben Zeilenpuffer gelesen - bewusst kein getrennter Puffer je Schnittstelle: In
 * der Praxis ist ohnehin nur eine der beiden Verbindungen tatsächlich benutzt, ein Client an
 * beiden gleichzeitig aktiv sendend würde die Kommandos ineinander mischen, das wird hier nicht
 * abgefangen.</p>
 * <p>Bewusst nicht gemacht: Ein eigener "USB"/"Bluetooth"-Unterschied im seriellen Protokoll oder
 * in der Portbezeichnung, die die Software sieht - das wäre reine GUI-Kosmetik (siehe
 * DeviceConnection.java: {@code getDescriptivePortName()} statt {@code getSystemPortName()} für
 * die Anzeige) und hat mit der Firmware nichts zu tun.</p>
 * <p><b>Hardware-/Board-Hinweis:</b> Braucht in der Arduino-IDE unter Werkzeuge -&gt;
 * "Partition Scheme" ein Schema mit genug Platz für den Bluetooth-Stack (z. B. "Default" statt
 * eines reinen "No OTA (2MB APP...)"-Schemas) - der Bluedroid-Stack braucht deutlich mehr
 * Flash/RAM als nur WLAN oder nur USB-Serial. Bei "Bluetooth is not enabled"-Kompilierfehlern:
 * Board-Paket zu alt bzw. Bluetooth im Menü "Tools -&gt; Zusätzliche Boardinformationen" o.ä.
 * deaktiviert.</p>
 */

#include <Wire.h>
#include <driver/i2s_std.h>
#include <math.h>
#include "soc/gpio_struct.h"
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth ist im Board-/Menuconfig-Profil deaktiviert - siehe Hardware-/Board-Hinweis \
       zu v8.5 im Dateikopf (Partition Scheme mit Bluetooth-Unterstützung wählen).
#endif

enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_INA219 = 2,
  TYPE_VEML7700 = 3,
  TYPE_HX711 = 4,
  TYPE_MICROPHONE = 5,
  TYPE_MIC_SPECTRUM = 6,
  TYPE_HALL = 7,
  TYPE_DS18B20 = 8
};

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

/** Die drei Signal-Pins eines Kanal-Ports (Steckerposition 2, 3, 4 - siehe pinout.md), deren
 *  Rolle vom gewählten Sensortyp abhängt (siehe {@link #configureChannelHardware}):
 *  I2C: [0]=SDA, [1]=SCL   HX711: [0]=DOUT, [1]=SCK   I2S: [0]=WS, [1]=BCLK, [2]=SD
 *  Analog: [0]=Eingang (auf beiden Kanälen, siehe v8.0-Hinweis oben)   Hall (KY-003): [0]=Signal
 *  DS18B20 (1-Wire): [0]=Datenleitung (braucht externen Pull-up nach 3,3V)
 *
 *  ACHTUNG bei künftigen Pin-Änderungen: GPIO 0, 2, 5, 12 und 15 sind beim ESP32
 *  "Strapping-Pins" - ihr Pegel wird nur im Moment des Resets ausgelesen und beeinflusst u. a.
 *  Boot-Modus bzw. Flash-Spannung. Hängt hier bereits ein Sensor (z. B. ein I2C-Pull-up) an
 *  einem solchen Pin, wenn der ESP32 einen Reset macht, kann der Chip mit falscher
 *  Flash-Spannung booten und landet in einer Watchdog-Reset-Schleife (TG0WDT_SYS_RESET), noch
 *  bevor setup() überhaupt läuft - genau das Bild "geht nur nach Reflash weg, tritt vor allem
 *  auf, wenn Sensoren schon beim Einstecken angeschlossen sind". Deshalb bewusst KEINER dieser
 *  Pins hier verwendet.
 *
 *  Kanal A liegt seit v8.0 auf GPIO 32/33/35 statt vormals 17/16/4 - alle drei ADC1-fähig (32/33
 *  zusätzlich normale, bidirektionale GPIOs für SDA/SCK/WS-Rollen; 35 ist ADC1-fähig, aber
 *  Eingang-only, was für die dort einzige benötigte Rolle - I2S "din" - ausreicht). GPIO16/17
 *  wurden verlassen, weil sie auf dem klassischen ESP32 gar keine ADC-Hardware besitzen und
 *  TYPE_ANALOG dort immer 0 lieferte, unabhängig vom angelegten Signal (siehe v8.0-Hinweis oben).
 *  Kanal B auf 27/26/25 bleibt unverändert, dort war ohnehin schon jeder der drei Pins ADC-fähig
 *  (Bonus: 32/33/35 hängen an ADC1, das anders als ADC2 - auf dem 27/26/25 von Kanal B - nicht mit
 *  WiFi kollidiert, hier aktuell aber ungenutzt, da diese Firmware kein WiFi verwendet). */
const int PINS_CHANNEL_A[3] = {32, 33, 35};
const int PINS_CHANNEL_B[3] = {27, 26, 25};

const uint8_t ADDR_INA219   = 0x40;
const uint8_t ADDR_VEML7700 = 0x10;

/** Maximale Wartezeit in ms auf ein bereites HX711-Modul, bevor der Zyklus als Fehler gilt. */
const unsigned long HX711_TIMEOUT_MS = 100;

/** I2S-Abtastrate für das INMP441-Mikrofon. Wie viele Rohsamples je Zyklus für den Spitzenwert
 *  gelesen werden, ist NICHT fest, sondern richtet sich dynamisch nach der eingestellten
 *  Abtastrate (siehe {@link #microphoneReadSampleCount}) - eine feste Anzahl hätte bei hoher
 *  Abtastrate selbst zur Bremse werden können: 256 Samples brauchen bei 16kHz allein schon 16ms
 *  Lesezeit, was die erreichbare Rate unabhängig von der GUI-Einstellung auf ca. 62 Hz gedeckelt
 *  hätte. {@link #MIC_MIN_READ_SAMPLES} sorgt dafür, dass bei sehr hoher Abtastrate trotzdem noch
 *  mindestens ein paar Samples für den Spitzenwert bleiben, {@link #MIC_MAX_READ_SAMPLES} dafür,
 *  dass ein einzelner Lesevorgang bei niedriger Abtastrate nicht unnötig lange blockiert. */
const int MIC_SAMPLE_RATE_HZ = 16000;
const int MIC_MIN_READ_SAMPLES = 16;
const int MIC_MAX_READ_SAMPLES = 512;

/** Serielle Baudrate zum PC. War lange 115200 - das begrenzte das Frequenzspektrum auf
 *  ~4 Bilder/Sekunde, da 512 Bins pro Bild schon ein paar KB sind (siehe SPECTRUM_INTERVAL_MS).
 *  460800 ist auf allen gängigen USB-Seriell-Chips (CP210x, CH340, native USB-CDC) zuverlässig
 *  nutzbar und vervierfacht die Übertragungsgeschwindigkeit. Muss mit dem Baudrate-Wert in
 *  GUI.java (DeviceConnection.connect-Aufruf) und dem Vorgabewert in Terminal.java übereinstimmen -
 *  sonst verbindet sich nichts mehr. Bei zuverlässiger Verbindung kann versuchsweise auch
 *  921600 probiert werden (weitere Verdopplung), das ist aber chipabhängig weniger garantiert. */
const long BAUD_RATE = 460800;

/** Name, unter dem der ESP32 beim Pairing in der Bluetooth-Geräteliste des PCs auftaucht -
 *  landet je nach Betriebssystem/Treiber meist auch in der Beschreibung des daraus entstehenden
 *  virtuellen COM-Ports (z. B. Windows: "Standard Serial over Bluetooth link (COMx)" plus
 *  Gerätename in der Systemsteuerung; macOS/Linux oft direkt im Portnamen selbst) - siehe
 *  {@code getDescriptivePortName()}-Hinweis in DeviceConnection.java. Bewusst unterscheidbar vom
 *  reinen USB-Verbindungsnamen gewählt (der vom USB-Seriell-Chip vorgegeben wird, z. B. "CP2102
 *  USB to UART Bridge", und sich firmware-seitig nicht umbenennen lässt). */
const char *BT_DEVICE_NAME = "PhyLog Bluetooth";

BluetoothSerial SerialBT;

/** FFT-Größe für den Live-Frequenzspektrum-Modus (siehe {@link #captureAndSendSpectrum}) - eine
 *  Zweierpotenz, wie sie die iterative Radix-2-FFT ({@link #computeFFT}) voraussetzt. Ein reelles
 *  Signal liefert nur n/2 unabhängige Frequenz-Bins (die obere Hälfte ist bei reellem Eingang nur
 *  das gespiegelte Konjugat), 1024 Punkte ergeben also die gewünschten 512 nutzbaren Bins. */
const int SPECTRUM_FFT_SIZE = 1024;
const int SPECTRUM_OUTPUT_BINS = SPECTRUM_FFT_SIZE / 2;

/** Puffergröße für ein komplettes, im BSS-Bereich statisch gehaltenes Spektrum-Paket (siehe
 *  {@link #sendSpectrumPacket}): Header ("#SPEC,X,512,16000") plus je Bin bis zu 6 Byte
 *  (",-1234") plus etwas Marge - großzügig genug, ohne bei jedem Bild neu berechnet werden zu
 *  müssen. */
const size_t SPECTRUM_PACKET_BUF_SIZE = 32 + (size_t) SPECTRUM_OUTPUT_BINS * 7;

/** Mindestabstand zwischen zwei gesendeten Spektren. 512 Bins als kompakte Ganzzahlen sind
 *  trotzdem noch rund 2,5 KB pro Bild - bei BAUD_RATE=460800 (~46 KB/s) dauert allein die
 *  Übertragung davon schon knapp 55ms, die FFT selbst nur wenige ms. 60ms liegt knapp darüber
 *  (Sicherheitsspielraum für FFT-Zeit und Schleifen-Overhead) und ergibt damit ~16 Bilder/Sekunde -
 *  spürbar "live" statt der ~4 Bilder/Sekunde, die bei der alten Baudrate (115200) das Maximum
 *  waren. Absichtlich keine feste Wartezeit weit über dem physikalischen Minimum: Serial.print()
 *  blockiert ohnehin, sobald der Sende-Puffer voll ist, ein zu kleiner Wert würde also nicht zu
 *  einem Rückstau führen, sondern höchstens ungenutzt bleiben. */
const unsigned long SPECTRUM_INTERVAL_MS = 60;

/** Letzter Zeitpunkt eines gesendeten Spektrums je Kanal, um dessen Taktung ({@link #SPECTRUM_INTERVAL_MS})
 *  unabhängig von der (für normale Sensoren gedachten, ggf. viel höheren) Abtastrate zu halten. */
unsigned long lastSpectrumTimeMsA = 0;
unsigned long lastSpectrumTimeMsB = 0;

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

/** Letzter Zeitpunkt einer gemeldeten Fehlermeldung je Kanal, um das serielle Log bei
 *  dauerhaften Fehlern nicht mit Meldungen zu fluten (siehe {@link #reportSensorError}). */
unsigned long lastErrorReportMsA = 0;
unsigned long lastErrorReportMsB = 0;

/** Anzahl aufeinanderfolgender I2C-Fehler je Kanal, um einen dauerhaft "hängenden" Bus (z. B.
 *  nach einem Wackelkontakt) automatisch neu zu initialisieren, statt nur endlos Fehler zu
 *  loggen (siehe {@link #noteI2CResult}). Wird bei jedem erfolgreichen I2C-Zugriff zurückgesetzt. */
int i2cFailStreakA = 0;
int i2cFailStreakB = 0;
const int I2C_FAIL_STREAK_RESET_THRESHOLD = 20;

// --- Host-Kommunikation (USB + Bluetooth), siehe v8.5-Hinweis im Dateikopf ---
//
// Ab hier läuft jede Ausgabe an den PC über hostWrite()/hostPrint() statt direkter Serial.*-
// Aufrufe: beide schreiben immer auf die USB-Verbindung und zusätzlich auf SerialBT, sofern
// dort gerade ein Client (die PhyLog-Software) verbunden ist - die Firmware unterscheidet nicht,
// über welchen Weg sie tatsächlich gerade "benutzt" wird, sondern schickt konsequent an beide.

/** Schreibt {@code len} Bytes ab {@code data} auf die USB-Verbindung sowie, falls verbunden, auf
 *  Bluetooth. {@code SerialBT.write()} ohne verbundenen Client kostet nur die interne
 *  hasClient()-Prüfung und blockiert nicht - das explizite Prüfen hier spart trotzdem den
 *  (unnötigen) Aufruf in den Bluetooth-Stack im reinen USB-Betrieb. */
void hostWrite(const char *data, size_t len) {
  Serial.write((const uint8_t *) data, len);
  if (SerialBT.hasClient()) {
    SerialBT.write((const uint8_t *) data, len);
  }
}

/** Wie {@link #hostWrite}, aber für einen nullterminierten String (spart an den Aufrufstellen das
 *  explizite Mitführen einer Länge für Konstanten wie {@code "#OK,START\n"}). */
void hostPrint(const char *s) {
  hostWrite(s, strlen(s));
}

/** Meldet einen fehlgeschlagenen Sensorzugriff auf einen Kanal, höchstens einmal pro Sekunde je
 *  Kanal, statt einen solchen Fehler stillschweigend zu verschlucken.
 *
 * @param channelName betroffener Kanal ('A' oder 'B')
 * @param errorTag    Fehlerart für das Log, z. B. "I2C", "HX711" oder "I2S"
 */
void reportSensorError(char channelName, const char *errorTag) {
  unsigned long &lastReport = (channelName == 'A') ? lastErrorReportMsA : lastErrorReportMsB;
  unsigned long now = millis();
  if (now - lastReport >= 1000) {
    lastReport = now;
    char buf[48];
    int len = snprintf(buf, sizeof(buf), "#ERR,%s,%c\n", errorTag, channelName);
    hostWrite(buf, len);
  }
}

/** @return den I2C-Bus, der physisch zu diesem Kanal gehört. */
TwoWire &busForChannel(char channelName) {
  return (channelName == 'A') ? Wire : Wire1;
}

/** @return den I2S-Port, der physisch zu diesem Kanal gehört (analog zu {@link #busForChannel}). */
i2s_port_t i2sPortForChannel(char channelName) {
  return (channelName == 'A') ? I2S_NUM_0 : I2S_NUM_1;
}

/** Channel-Handle des neuen I2S-Treibers (driver/i2s_std.h) je Kanal - {@code NULL}, solange
 *  kein Mikrofon konfiguriert ist. Der alte, mit driver/i2s.h installierte Legacy-Treiber
 *  kollidiert auf aktuellen arduino-esp32-Versionen mit dem für analogRead() genutzten
 *  ADC-Treiber ("driver_ng") und führt zu einem Absturz beim Start - der neue Treiber betrifft
 *  den ADC-Pfad nicht und ist deshalb mit TYPE_ANALOG auf dem jeweils anderen Kanal kombinierbar. */
i2s_chan_handle_t micHandleA = NULL;
i2s_chan_handle_t micHandleB = NULL;

/** @return das I2S-Channel-Handle, das physisch zu diesem Kanal gehört. */
i2s_chan_handle_t &micHandleForChannel(char channelName) {
  return (channelName == 'A') ? micHandleA : micHandleB;
}

/**
 * Liest ein 16-Bit-I2C-Register MSB-zuerst (big-endian, TI-Konvention - passend für den
 * INA219). Gibt bei Erfolg true zurück und schreibt den Rohwert nach outValue; bei einem
 * Übertragungsfehler wird false zurückgegeben und outValue nicht verändert.
 *
 * <p>outValue ist bewusst uint16_t (nicht int16_t): der Rohwert wird unverändert als 0..65535
 * über die serielle Schnittstelle geschickt, die softwareseitige Sensor-Klasse entscheidet dann
 * je nach physikalischer Größe, ob und wie er vorzeichenbehaftet zu interpretieren ist.</p>
 */
bool readI2CRegister16(TwoWire &bus, uint8_t addr, uint8_t reg, uint16_t &outValue) {
  bus.beginTransmission(addr);
  bus.write(reg);
  if (bus.endTransmission(false) != 0) return false;

  if (bus.requestFrom((int)addr, 2) != 2) return false;

  uint8_t msb = bus.read();
  uint8_t lsb = bus.read();
  outValue = ((uint16_t)msb << 8) | lsb;
  return true;
}

/**
 * Wie {@link #readI2CRegister16}, aber LSB-zuerst (little-endian). Vishay-Bausteine wie der
 * VEML7700 senden ihre 16-Bit-Register in dieser Reihenfolge, anders als der INA219.
 */
bool readI2CRegister16LE(TwoWire &bus, uint8_t addr, uint8_t reg, uint16_t &outValue) {
  bus.beginTransmission(addr);
  bus.write(reg);
  if (bus.endTransmission(false) != 0) return false;

  if (bus.requestFrom((int)addr, 2) != 2) return false;

  uint8_t lsb = bus.read();
  uint8_t msb = bus.read();
  outValue = ((uint16_t)msb << 8) | lsb;
  return true;
}

/** Direkter Registerzugriff für reguläre Push-Pull-Pins (anders als {@link #owLow}/
 *  {@link #owRelease}, die bewusst nur nach LOW treiben und für HIGH in den hochohmigen
 *  Eingangszustand wechseln - passend für den Open-Drain-Charakter von 1-Wire, aber falsch für
 *  einen Pin wie HX711-SCK, der aktiv auf HIGH UND LOW getrieben werden muss). Setzt voraus, dass
 *  der Pin bereits per {@code pinMode(pin, OUTPUT)} als Ausgang konfiguriert ist (siehe
 *  {@link #configureChannelHardware}, Fall {@code TYPE_HX711}) - hier wird nur noch der
 *  Ausgangspegel selbst geschrieben, ohne bei jedem Aufruf erneut die Pin-Richtung anzufassen.
 *  Gleicher Grund wie bei {@link #owLow}: {@code digitalWrite()} kostet auf dem ESP32 mehrere
 *  hundert ns bis über 1µs (IO-MUX-Rekonfiguration, Bounds-Checks), direkter Registerzugriff nur
 *  wenige Taktzyklen - bei den hier genutzten 1µs-Zeitfenstern (siehe {@link #readHX711}) macht
 *  das den Unterschied zwischen einer sauberen und einer verzerrten Taktflanke. */
static inline void gpioWriteFast(int pin, bool high) {
  if (pin < 32) {
    if (high) GPIO.out_w1ts = (1U << pin);
    else      GPIO.out_w1tc = (1U << pin);
  } else {
    if (high) GPIO.out1_w1ts.val = (1U << (pin - 32));
    else      GPIO.out1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1) - inhaltlich identisch zu {@link #owRead},
 *  hier als eigener Name, damit {@link #readHX711} nicht von einer für 1-Wire benannten Funktion
 *  abhängt, obwohl beide Stellen rein technisch dasselbe Zustandsregister lesen. */
static inline int gpioReadFast(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}

/**
 * Liest einen 24-Bit-Rohwert vom HX711 per Bit-Banging (kein I2C, sondern eigenes DOUT/SCK-
 * Protokoll). Wartet zunächst, bis das Modul über DOUT LOW signalisiert, dass ein Wert bereit
 * ist; ist das nach {@link #HX711_TIMEOUT_MS} nicht der Fall, gilt der Zyklus als fehlgeschlagen
 * (kein angeschlossenes Modul oder noch nicht bereit), statt einen falschen Wert zu senden.
 *
 * <p>Die eigentlichen 24 Taktflanken (inkl. der 25., Gain-wählenden) laufen komplett innerhalb
 * von {@code noInterrupts()}/{@code interrupts()} und nutzen {@link #gpioWriteFast}/
 * {@link #gpioReadFast} statt {@code digitalWrite()}/{@code digitalRead()} - siehe v8.4-Hinweis
 * im Dateikopf. Der HX711 geht in Sleep, sobald SCK länger als ~60µs am Stück HIGH bleibt; ohne
 * diese Absicherung konnte sowohl der IO-MUX-Overhead der Arduino-HAL-Aufrufe als auch ein
 * dazwischenfunkender Interrupt (z. B. der Millis-Timer) dieses Fenster im ungünstigen Fall
 * reißen. Die gesamte Schleife (24 Bits + 25. Flanke, je 2µs) bleibt mit rund 50µs klar unter der
 * Sleep-Schwelle und ist kurz genug, um das System nicht spürbar zu blockieren.</p>
 *
 * <p>Die 25. Taktflanke (nach den 24 Datenbits) wählt Kanal A mit Gain 128 für den nächsten
 * Messzyklus - die in dieser Firmware fest verwendete Standardkonfiguration des HX711.</p>
 *
 * @param doutPin  GPIO, an dem das Modul die Daten ausgibt
 * @param sckPin   GPIO, über den der Takt an das Modul gesendet wird
 * @param outValue Ziel für den auf 32 Bit vorzeichenrichtig erweiterten Rohwert
 * @return {@code true} bei Erfolg, {@code false} bei Timeout
 */
bool readHX711(int doutPin, int sckPin, long &outValue) {
  unsigned long waitStart = millis();
  while (digitalRead(doutPin) == HIGH) {
    if (millis() - waitStart > HX711_TIMEOUT_MS) return false;
  }

  long value = 0;
  noInterrupts();
  for (int i = 0; i < 24; i++) {
    gpioWriteFast(sckPin, true);
    delayMicroseconds(1);
    value = (value << 1) | gpioReadFast(doutPin);
    gpioWriteFast(sckPin, false);
    delayMicroseconds(1);
  }

  gpioWriteFast(sckPin, true); // 25. Flanke: Gain 128 / Kanal A für den nächsten Zyklus
  delayMicroseconds(1);
  gpioWriteFast(sckPin, false);
  delayMicroseconds(1);
  interrupts();

  if (value & 0x800000) { // 24-Bit-Zweierkomplement auf 32 Bit vorzeichenrichtig erweitern
    value |= 0xFF000000;
  }
  outValue = value;
  return true;
}

// --- DS18B20 (1-Wire), TYPE_DS18B20 ---
//
// Von Hand bit-gebangtes 1-Wire-Protokoll nach den Timing-Vorgaben aus dem DS18B20-Datenblatt -
// keine externe OneWire-Bibliothek, analog zur bereits manuell implementierten HX711-Anbindung
// oben. Alle drei Grundoperationen (Reset, Bit schreiben, Bit lesen) laufen mit kurzzeitig
// deaktivierten Interrupts: die engsten hier genutzten Zeitfenster liegen bei nur 1-2µs, leicht
// zu reißen z. B. durch die I2S-DMA-ISR des Mikrofons auf dem jeweils anderen Kanal.
//
// owLow()/owRelease()/owRead() ersetzen dafür pinMode()/digitalWrite()/digitalRead() durch
// direkten Zugriff auf die GPIO-Register: Die Arduino-HAL-Funktionen kosten auf dem ESP32
// (anders als auf AVR) jeweils mehrere hundert ns bis über 1µs (IO-MUX-Rekonfiguration,
// Bounds-Checks) - bei den hier genutzten 1-2µs-Zeitfenstern verschiebt allein das
// Umschalten von Pinrichtung/Pegel den eigentlichen Abtast-/Flankenzeitpunkt erheblich und
// zerstört damit praktisch jede Übertragung (siehe v8.2-Hinweis im Dateikopf). Direkter
// Registerzugriff kostet dagegen nur wenige CPU-Taktzyklen. GPIO32-39 hängen an einem zweiten
// Register-Satz (GPIO.out1/enable1/in1 statt .../in), daher die Fallunterscheidung nach Pin 32.

/** Zieht {@code pin} aktiv auf LOW (Open-Drain-Charakter des 1-Wire-Busses: nur Treiben nach
 *  LOW, niemals aktiv nach HIGH - siehe {@link #owRelease}).
 *
 *  <p>ACHTUNG bei den GPIO32-39-Registern (Pin &ge; 32): Die Schreib-Register {@code out1_w1ts}/
 *  {@code out1_w1tc}/{@code enable1_w1ts}/{@code enable1_w1tc} sind Unions mit Feld {@code .val}
 *  - anders als das reine Zustandsregister {@link #owRead}s {@code in1}, das tatsächlich
 *  {@code .data} heißt. Eine frühere Version dieser Funktion griff hier fälschlich überall auf
 *  {@code .data} zu; betraf ausschließlich Pins &ge; 32 (auf Kanal A z. B. Pin 32 selbst) und
 *  äußerte sich wie ein dauerhaft feststehender Bus - Kanal B (Pins 27/26/25, alle &lt; 32) war
 *  nie betroffen.</p> */
static inline void owLow(int pin) {
  if (pin < 32) {
    GPIO.out_w1tc = (1U << pin);
    GPIO.enable_w1ts = (1U << pin);
  } else {
    GPIO.out1_w1tc.val = (1U << (pin - 32));
    GPIO.enable1_w1ts.val = (1U << (pin - 32));
  }
}

/** Gibt {@code pin} wieder als Eingang frei - der externe Pull-up zieht den Bus auf HIGH, kein
 *  aktives Treiben nach HIGH nötig (und für einen Open-Drain-Bus wie 1-Wire auch nicht zulässig,
 *  falls mehrere Teilnehmer gleichzeitig senden könnten). Zum Feldnamen-Hinweis siehe {@link #owLow}. */
static inline void owRelease(int pin) {
  if (pin < 32) {
    GPIO.enable_w1tc = (1U << pin);
  } else {
    GPIO.enable1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1). Nutzt bewusst {@code .data} (nicht
 *  {@code .val} wie die Schreib-Register in {@link #owLow}) - {@code in1} ist das reine
 *  Zustandsregister, dessen Payload-Bitfeld tatsächlich so heißt. */
static inline int owRead(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}

/** Sendet den 1-Wire-Reset-Puls und wertet den Presence-Puls des Sensors aus.
 *
 * @return {@code true}, wenn ein Gerät geantwortet hat.
 */
bool oneWireReset(int pin) {
  owLow(pin);
  delayMicroseconds(480);

  noInterrupts();
  owRelease(pin); // Bus loslassen - der externe Pull-up zieht ihn wieder auf HIGH
  delayMicroseconds(70);
  bool presence = (owRead(pin) == 0); // Gerät antwortet mit einem kurzen LOW-Puls
  interrupts();

  delayMicroseconds(410); // Rest des insgesamt >=480µs breiten Resetfensters abwarten
  return presence;
}

/** Schreibt ein einzelnes Bit per 1-Wire-Zeitschlitz (Länge des LOW-Pulses codiert 0/1). */
void oneWireWriteBit(int pin, uint8_t bitValue) {
  noInterrupts();
  owLow(pin);
  delayMicroseconds(bitValue ? 6 : 60);
  owRelease(pin);
  interrupts();
  delayMicroseconds(bitValue ? 64 : 10); // Zeitschlitz auf insgesamt >=70µs auffüllen
}

/** Liest ein einzelnes Bit per 1-Wire-Zeitschlitz: kurz selbst LOW ziehen, dann loslassen und
 *  innerhalb des vom Sensor ggf. verlängerten LOW-Fensters abtasten. */
uint8_t oneWireReadBit(int pin) {
  noInterrupts();
  owLow(pin);
  delayMicroseconds(2);
  owRelease(pin);
  delayMicroseconds(10);
  uint8_t bitValue = owRead(pin);
  interrupts();
  delayMicroseconds(50); // Zeitschlitz auf insgesamt >=60µs auffüllen
  return bitValue;
}

void oneWireWriteByte(int pin, uint8_t value) {
  for (int i = 0; i < 8; i++) {
    oneWireWriteBit(pin, value & 0x01);
    value >>= 1;
  }
}

uint8_t oneWireReadByte(int pin) {
  uint8_t value = 0;
  for (int i = 0; i < 8; i++) {
    value |= (oneWireReadBit(pin) << i);
  }
  return value;
}

/** Dallas/Maxim-CRC8 (Polynom x^8+x^5+x^4+1, reflektiert) über das Scratchpad, zur Absicherung
 *  gegen durch Störungen verfälschte 1-Wire-Übertragungen - anders als bei I2C (siehe
 *  {@link #readI2CRegister16}) gibt es hier keine Hardware-Bestätigung auf Byte-Ebene. */
uint8_t oneWireCRC8(const uint8_t *data, uint8_t len) {
  uint8_t crc = 0;
  for (uint8_t i = 0; i < len; i++) {
    uint8_t inByte = data[i];
    for (uint8_t bit = 0; bit < 8; bit++) {
      uint8_t mix = (crc ^ inByte) & 0x01;
      crc >>= 1;
      if (mix) crc ^= 0x8C;
      inByte >>= 1;
    }
  }
  return crc;
}

/** Konversionszeit bei 12-Bit-Auflösung (Werkseinstellung des DS18B20 nach jedem Power-on/Reset,
 *  hier nie umkonfiguriert) - siehe DS18B20-Datenblatt sowie DS18B20Sensor.java, das denselben
 *  Wert für {@link Sensor#getMaxSampleRateHz} zugrunde legt. */
const unsigned long DS18B20_CONVERSION_MS = 750;

/** Ob für den jeweiligen Kanal aktuell eine Konversion läuft, deren Ergebnis noch nicht
 *  abgeholt wurde - siehe {@link #sampleDS18B20}. Bei einem Kanalwechsel weg von TYPE_DS18B20
 *  über {@link #releaseChannelHardware} zurückgesetzt, damit ein späteres erneutes Einschalten
 *  nicht versucht, das Scratchpad einer nie gestarteten (oder eines ganz anderen Sensors
 *  zugehörigen) Konversion zu lesen. */
bool ds18b20ConversionPendingA = false;
bool ds18b20ConversionPendingB = false;
unsigned long ds18b20ConversionStartMsA = 0;
unsigned long ds18b20ConversionStartMsB = 0;

/** Liest (ohne selbst eine neue Konversion anzustoßen) das Scratchpad einer bereits
 *  abgeschlossenen DS18B20-Konversion aus und prüft dessen CRC8.
 *
 * @param outValue Ziel für das vorzeichenbehaftete 16-Bit-Temperaturregister (1/16°C-Schritte,
 *                 siehe DS18B20Sensor.decode)
 * @return {@code true} bei Erfolg (Presence-Puls und gültige CRC8)
 */
bool readDS18B20Scratchpad(int pin, long &outValue) {
  if (!oneWireReset(pin)) return false;
  oneWireWriteByte(pin, 0xCC); // Skip ROM - setzt genau einen Sensor an diesem Pin voraus
  oneWireWriteByte(pin, 0xBE); // Read Scratchpad

  uint8_t scratchpad[9];
  for (int i = 0; i < 9; i++) {
    scratchpad[i] = oneWireReadByte(pin);
  }
  if (oneWireCRC8(scratchpad, 8) != scratchpad[8]) return false;

  outValue = (int16_t) ((scratchpad[1] << 8) | scratchpad[0]);
  return true;
}

/**
 * Tastet einen DS18B20 nicht-blockierend ab. Anders als bei allen anderen Sensortypen liefert
 * ein Aufruf hier nicht bei jedem Zyklus ein Datenpaket: Die bis zu {@link #DS18B20_CONVERSION_MS}
 * dauernde Konversion würde als direktes {@code delay()} mitten in {@link #loop} auch Serial-
 * Kommandos (inkl. STOP) und die Abtastung des jeweils anderen Kanals blockieren (siehe
 * Klassenkommentar, v8.1-Absatz). Stattdessen: Bei einer laufenden Konversion wird zuerst geprüft,
 * ob genug Zeit vergangen ist - falls ja, wird das Scratchpad gelesen und ein Datenpaket verschickt
 * (bzw. bei einem CRC-/Presence-Fehler {@link #reportSensorError} aufgerufen). Direkt im Anschluss
 * (noch im selben Aufruf, ohne auf den nächsten Zyklus zu warten) wird sofort die nächste
 * Konversion gestartet - das hält den Abstand zwischen zwei Datenpaketen bei genau einem
 * Abtastintervall, sofern dieses &ge; {@link #DS18B20_CONVERSION_MS} ist. (v8.2 startete die
 * nächste Konversion erst beim nachfolgenden Aufruf statt sofort - das verdoppelte den
 * tatsächlichen Abstand zwischen zwei Werten gegenüber dem in der GUI eingestellten Intervall,
 * z. B. nur noch 1 Wert alle 2s bei eingestellter 1 Hz statt jede Sekunde einer.)
 */
void sampleDS18B20(char channelName, int pin) {
  bool &pending = (channelName == 'A') ? ds18b20ConversionPendingA : ds18b20ConversionPendingB;
  unsigned long &startMs = (channelName == 'A') ? ds18b20ConversionStartMsA : ds18b20ConversionStartMsB;

  if (pending) {
    if (millis() - startMs < DS18B20_CONVERSION_MS) return; // Konversion läuft noch

    long rawTemp;
    bool ok = readDS18B20Scratchpad(pin, rawTemp);
    pending = false;

    if (ok) {
      sendDataPacket(channelName, 0, rawTemp);
    } else {
      reportSensorError(channelName, "DS18B20");
    }
  }

  // Nächste Konversion sofort anstoßen, statt erst beim nächsten Aufruf (siehe Methodenkommentar).
  if (!oneWireReset(pin)) {
    reportSensorError(channelName, "DS18B20");
    return;
  }
  oneWireWriteByte(pin, 0xCC); // Skip ROM
  oneWireWriteByte(pin, 0x44); // Convert T
  startMs = millis();
  pending = true;
}

/** Schreibt die Init-Konfiguration eines I2C-Sensortyps auf den angegebenen (bereits über
 *  {@link #configureChannelHardware} gestarteten) Bus. Meldet einen Fehler, falls einer der
 *  Schreibvorgänge fehlschlägt. */
void configureSensorOnBus(TwoWire &bus, SensorType type, char channelName) {
  bool ok = true;

  if (type == TYPE_INA219) {
    bus.beginTransmission(ADDR_INA219);
    bus.write(0x00); // Config Register
    bus.write(0x39); bus.write(0x9F); // 32V, Gain 8, 12-bit ADC
    ok &= (bus.endTransmission() == 0);

    bus.beginTransmission(ADDR_INA219);
    bus.write(0x05); // Calibration Register
    bus.write(0x10); bus.write(0x00);
    ok &= (bus.endTransmission() == 0);
  } else if (type == TYPE_VEML7700) {
    bus.beginTransmission(ADDR_VEML7700);
    bus.write(0x00); // ALS_CONF Register
    // Gain 1x, Integrationszeit 25ms (kürzeste verfügbare Einstellung statt der 100ms im
    // Standard-Reset-Zustand) - der Sensor liefert dadurch maximal alle 25ms (~40 Hz) einen
    // neuen Messwert; schnelleres Abfragen würde nur wiederholt denselben Wert zurückgeben.
    // Kürzere Integrationszeit bedeutet weniger gesammeltes Licht pro Messung, also etwas mehr
    // Rauschen bzw. geringere Empfindlichkeit bei sehr wenig Licht - Tausch von Genauigkeit
    // gegen Reaktionsgeschwindigkeit.
    bus.write(0x00); bus.write(0x03);
    ok &= (bus.endTransmission() == 0);
  }

  if (!ok) {
    reportSensorError(channelName, "I2C");
  }
}

/** Initialisiert den I2C-Bus eines Kanals neu (Bus schließen, kurz warten, neu starten und den
 *  Sensor neu konfigurieren). Wird nach mehreren I2C-Fehlern in Folge aufgerufen, um einen durch
 *  einen Wackelkontakt "hängen gebliebenen" Bus wieder freizubekommen, statt dass der Kanal bis
 *  zum nächsten manuellen Reset dauerhaft Fehler meldet. */
void resetI2CBus(char channelName, SensorType type) {
  TwoWire &bus = busForChannel(channelName);
  bus.end();
  delay(5);
  const int *pins = (channelName == 'A') ? PINS_CHANNEL_A : PINS_CHANNEL_B;
  bus.begin(pins[0], pins[1], 400000);
  configureSensorOnBus(bus, type, channelName);
}

/** Zählt aufeinanderfolgende I2C-Fehler je Kanal und stößt ab {@link #I2C_FAIL_STREAK_RESET_THRESHOLD}
 *  einen automatischen Bus-Reset an (siehe {@link #resetI2CBus}). Nach jedem I2C-Zugriff
 *  (erfolgreich oder nicht) aufzurufen. */
void noteI2CResult(char channelName, SensorType type, bool success) {
  int &streak = (channelName == 'A') ? i2cFailStreakA : i2cFailStreakB;
  if (success) {
    if (streak > 0) {
      streak = 0;
      resetI2CBus(channelName, type);
    }
    return;
  }
  streak++;
  if (streak >= I2C_FAIL_STREAK_RESET_THRESHOLD) {
    streak = 0;
    resetI2CBus(channelName, type);
  }
}

/** Startet den I2S-Kanal im Empfangsmodus für das INMP441 (Philips-I2S, mono, 32-Bit-Slot -
 *  das Modul liefert 24 gültige Datenbits linksbündig in einem 32-Bit-Wort) über den neuen
 *  I2S-Standardtreiber (siehe Kommentar bei {@link #micHandleA} zum Grund). */
void configureMicrophone(char channelName, const int pins[3]) {
  i2s_chan_handle_t &handle = micHandleForChannel(channelName);

  i2s_chan_config_t chanConfig = I2S_CHANNEL_DEFAULT_CONFIG(i2sPortForChannel(channelName), I2S_ROLE_MASTER);
  if (i2s_new_channel(&chanConfig, NULL, &handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
    return;
  }

  i2s_std_config_t stdConfig = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(MIC_SAMPLE_RATE_HZ),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_MONO),
      .gpio_cfg = {
          .mclk = I2S_GPIO_UNUSED,
          .bclk = (gpio_num_t) pins[1],
          .ws   = (gpio_num_t) pins[0],
          .dout = I2S_GPIO_UNUSED,
          .din  = (gpio_num_t) pins[2],
          .invert_flags = {
              .mclk_inv = false,
              .bclk_inv = false,
              .ws_inv = false
          }
      }
  };

  if (i2s_channel_init_std_mode(handle, &stdConfig) != ESP_OK || i2s_channel_enable(handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
  }
}

/** Gibt die Hardware frei, die {@code oldType} auf diesem Kanal belegt hat, damit die drei
 *  gemeinsam genutzten Signal-Pins (siehe {@link #PINS_CHANNEL_A}) anschließend für einen
 *  anderen Sensortyp neu konfiguriert werden können. Für HX711/Analog/NONE ist nichts
 *  freizugeben - die neue Konfiguration überschreibt deren Pin-Modi einfach direkt. */
void releaseChannelHardware(char channelName, SensorType oldType) {
  if (oldType == TYPE_INA219 || oldType == TYPE_VEML7700) {
    busForChannel(channelName).end();
  } else if (oldType == TYPE_MICROPHONE || oldType == TYPE_MIC_SPECTRUM) {
    i2s_chan_handle_t &handle = micHandleForChannel(channelName);
    if (handle != NULL) {
      i2s_channel_disable(handle);
      i2s_del_channel(handle);
      handle = NULL;
    }
  } else if (oldType == TYPE_DS18B20) {
    // Siehe Kommentar bei ds18b20ConversionPendingA/B: eine noch laufende Konversion wird beim
    // Wegschalten verworfen, statt ihr Ergebnis später fälschlich einem neuen Sensor zuzuordnen.
    (channelName == 'A' ? ds18b20ConversionPendingA : ds18b20ConversionPendingB) = false;
  }
}

/** Konfiguriert die drei Kanal-Pins für den neu gewählten Sensortyp (siehe Klassenkommentar zur
 *  Pin-Belegung). Vorher muss die alte Hardware über {@link #releaseChannelHardware}
 *  freigegeben worden sein. */
void configureChannelHardware(char channelName, SensorType newType, const int pins[3]) {
  switch (newType) {
    case TYPE_ANALOG:
      break; // analogRead() braucht keine explizite pinMode()
    case TYPE_INA219:
    case TYPE_VEML7700: {
      TwoWire &bus = busForChannel(channelName);
      bus.begin(pins[0], pins[1], 400000);
      configureSensorOnBus(bus, newType, channelName);
      break;
    }
    case TYPE_HX711:
      pinMode(pins[0], INPUT);
      pinMode(pins[1], OUTPUT);
      digitalWrite(pins[1], LOW);
      break;
    case TYPE_HALL:
      // Nur Pin 0 genutzt (Signal-Pin des KY-003) - der interne Pull-up ist redundant, falls das
      // Modul bereits einen eigenen besitzt, schadet aber nicht und macht die Beschaltung
      // robuster gegen Module ohne eigenen Pull-up.
      pinMode(pins[0], INPUT_PULLUP);
      break;
    case TYPE_DS18B20:
      // Bus in Ruhestellung: oneWireReset()/-WriteBit()/-ReadBit() schalten pinMode() für die
      // eigentliche Kommunikation ohnehin bei jedem Zugriff selbst um (siehe dort) - hier nur
      // der definierte Ausgangszustand. Kein interner Pull-up wie bei TYPE_HALL: der 1-Wire-Bus
      // braucht einen externen Pull-up nach 3,3V (typisch 4,7kΩ), der interne ESP32-Pull-up ist
      // dafür in der Praxis zu hochohmig (siehe Hardware-Hinweis in DS18B20Sensor.java).
      pinMode(pins[0], INPUT);
      break;
    case TYPE_MICROPHONE:
    case TYPE_MIC_SPECTRUM:
      // Identische I2S-Hardware für beide Mikrofon-"Sensoren" - TYPE_MIC_SPECTRUM liefert nur
      // ein anderes Ausgabeformat (Spektrum statt Einzelwert), siehe sampleChannel()/loop().
      configureMicrophone(channelName, pins);
      break;
    case TYPE_NONE:
    default:
      break;
  }
}

void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    hostPrint("#HELLO,PhyLog-ESP32,fw=8.5\n");
  } else if (command.equalsIgnoreCase("START")) {
    isStreaming = true;
    hostPrint("#OK,START\n");
  } else if (command.equalsIgnoreCase("STOP")) {
    isStreaming = false;
    hostPrint("#OK,STOP\n");
  } else if (command.startsWith("RATE,")) {
    long rateHz = command.substring(5).toInt();
    if (rateHz >= 1 && rateHz <= 1000) {
      sampleIntervalMs = 1000 / rateHz;
      char buf[32];
      int len = snprintf(buf, sizeof(buf), "#OK,RATE,%ld\n", rateHz);
      hostWrite(buf, len);
    }
  } else if (command.startsWith("SET,")) {
    // Format: SET,<Kanal>,<SensorTyp> (z.B. SET,A,INA219)
    int firstComma = command.indexOf(',');
    int secondComma = command.indexOf(',', firstComma + 1);

    if (firstComma != -1 && secondComma != -1) {
      char targetChannel = command.charAt(firstComma + 1);
      String sensorTypeName = command.substring(secondComma + 1);

      SensorType newType = TYPE_NONE;
      if (sensorTypeName.equalsIgnoreCase("INA219")) newType = TYPE_INA219;
      else if (sensorTypeName.equalsIgnoreCase("VEML7700")) newType = TYPE_VEML7700;
      else if (sensorTypeName.equalsIgnoreCase("ANALOG")) newType = TYPE_ANALOG;
      else if (sensorTypeName.equalsIgnoreCase("HX711")) newType = TYPE_HX711;
      else if (sensorTypeName.equalsIgnoreCase("MIC")) newType = TYPE_MICROPHONE;
      else if (sensorTypeName.equalsIgnoreCase("MICSPEC")) newType = TYPE_MIC_SPECTRUM;
      else if (sensorTypeName.equalsIgnoreCase("HALL")) newType = TYPE_HALL;
      else if (sensorTypeName.equalsIgnoreCase("DS18B20")) newType = TYPE_DS18B20;

      if (targetChannel == 'A') {
        releaseChannelHardware('A', configChannelA);
        configChannelA = newType;
        configureChannelHardware('A', newType, PINS_CHANNEL_A);
      } else if (targetChannel == 'B') {
        releaseChannelHardware('B', configChannelB);
        configChannelB = newType;
        configureChannelHardware('B', newType, PINS_CHANNEL_B);
      }

      char buf[64];
      int len = snprintf(buf, sizeof(buf), "#OK,SET,%c,%s\n", targetChannel, sensorTypeName.c_str());
      hostWrite(buf, len);
    }
  }
}

/** Liest ein einzelnes Kommandozeichen aus einer der beiden Host-Schnittstellen (USB oder
 *  Bluetooth) in {@code inputBuffer} ein und stößt bei einem Zeilenumbruch die Verarbeitung an -
 *  gemeinsame Zeilen-Puffer-Logik für {@link #handleSerialCommunication}, das denselben Puffer
 *  für beide Quellen nacheinander füttert (siehe v8.5-Hinweis im Dateikopf: kein getrennter
 *  Puffer je Schnittstelle, ein gleichzeitig aktiv sendender Client auf beiden Wegen würde die
 *  Kommandos ineinander mischen - in der Praxis ist ohnehin nur eine der beiden Verbindungen
 *  tatsächlich benutzt). */
void feedCommandChar(String &inputBuffer, char incomingChar) {
  if (incomingChar == '\n' || incomingChar == '\r') {
    if (inputBuffer.length() > 0) {
      processCommand(inputBuffer);
      inputBuffer = "";
    }
  } else {
    inputBuffer += incomingChar;
  }
}

void handleSerialCommunication() {
  static String inputBuffer = "";
  while (Serial.available() > 0) {
    feedCommandChar(inputBuffer, (char) Serial.read());
  }
  while (SerialBT.available() > 0) {
    feedCommandChar(inputBuffer, (char) SerialBT.read());
  }
}

void sendDataPacket(char channel, int slot, long rawValue) {
  char buf[48];
  int len = snprintf(buf, sizeof(buf), "D,%lu,%c,%d,%ld\n", millis(), channel, slot, rawValue);
  hostWrite(buf, len);
}

/** Kehrt die Bit-Reihenfolge eines {@code bitCount}-Bit-Wertes um - Hilfsfunktion für die
 *  Bit-Reversal-Permutation am Anfang der FFT (siehe {@link #computeFFT}). */
uint16_t reverseBits(uint16_t value, int bitCount) {
  uint16_t result = 0;
  for (int i = 0; i < bitCount; i++) {
    result = (result << 1) | (value & 1);
    value >>= 1;
  }
  return result;
}

/**
 * Iterative, in-place Radix-2-Cooley-Tukey-FFT über {@code n} (Zweierpotenz) komplexe Werte,
 * ergebnis in {@code real}/{@code imag} zurückgeschrieben. Bewusst selbst geschrieben statt eine
 * FFT-Bibliothek einzubinden, da nur eine einzige feste Größe ({@link #SPECTRUM_FFT_SIZE})
 * benötigt wird und damit keine zusätzliche Abhängigkeit im Projekt nötig ist.
 */
void computeFFT(float *real, float *imag, int n) {
  int bitCount = 0;
  while ((1 << bitCount) < n) bitCount++;

  for (int i = 0; i < n; i++) {
    int j = reverseBits(i, bitCount);
    if (j > i) {
      float tempReal = real[i]; real[i] = real[j]; real[j] = tempReal;
      float tempImag = imag[i]; imag[i] = imag[j]; imag[j] = tempImag;
    }
  }

  for (int size = 2; size <= n; size *= 2) {
    int halfSize = size / 2;
    float angleStep = -2.0f * PI / size;
    for (int start = 0; start < n; start += size) {
      for (int k = 0; k < halfSize; k++) {
        float angle = angleStep * k;
        float wr = cosf(angle), wi = sinf(angle);
        int evenIdx = start + k;
        int oddIdx = evenIdx + halfSize;

        float oddReal = real[oddIdx] * wr - imag[oddIdx] * wi;
        float oddImag = real[oddIdx] * wi + imag[oddIdx] * wr;

        real[oddIdx] = real[evenIdx] - oddReal;
        imag[oddIdx] = imag[evenIdx] - oddImag;
        real[evenIdx] += oddReal;
        imag[evenIdx] += oddImag;
      }
    }
  }
}

/** Sendet ein zuvor über {@link #computeFFT} berechnetes Spektrum als ein Paket:
 *  {@code #SPEC,<Kanal>,<Bins>,<Abtastrate>,<mag_0>,<mag_1>,...}. Magnituden als dBFS·10,
 *  auf int gerundet - spart deutlich Bandbreite gegenüber Floats (bei 512 Werten pro Bild sonst
 *  schnell ein Vielfaches an Übertragungszeit).
 *
 * <p>Baut das gesamte Paket in {@link #SPECTRUM_PACKET_BUF_SIZE} zusammen und verschickt es mit
 * einem einzigen {@code hostWrite()} (siehe v8.5-Hinweis im Dateikopf), statt wie zuvor mit 512+
 * einzelnen {@code Serial.print()}
 * -Aufrufen (siehe v8.4-Hinweis im Dateikopf) - jeder einzelne Aufruf formatiert eine Zahl separat
 * und schreibt sie einzeln raus, was bei ~16 Bildern/Sekunde spürbar CPU-Zeit kostete, die dann
 * z. B. für die zeitkritische Abtastung des jeweils anderen Kanals fehlte. Der Puffer ist
 * {@code static}, um bei jedem Aufruf denselben (im BSS-Bereich liegenden) Speicher
 * wiederzuverwenden, statt ~3,6 KB auf dem Stack des Loop-Tasks zu allozieren.</p>
 *
 * <p>Die Abbruchbedingung {@code offset < SPECTRUM_PACKET_BUF_SIZE - 8} in der Schleife ist eine
 * reine Sicherheitsgrenze gegen einen Pufferüberlauf (acht Byte Reserve für den längsten
 * möglichen einzelnen Bin-Eintrag plus Newline) - bei der aktuellen Puffergröße und
 * {@link #SPECTRUM_OUTPUT_BINS}=512 wird sie in der Praxis nie erreicht.</p> */
void sendSpectrumPacket(char channelName, float *real, float *imag) {
  static const float FULL_SCALE = 8388607.0f; // 2^23 - 1, wie in der Software-Sensorklasse
  static char packetBuf[SPECTRUM_PACKET_BUF_SIZE];

  int offset = snprintf(packetBuf, SPECTRUM_PACKET_BUF_SIZE, "#SPEC,%c,%d,%d",
                         channelName, SPECTRUM_OUTPUT_BINS, MIC_SAMPLE_RATE_HZ);

  for (int i = 0; i < SPECTRUM_OUTPUT_BINS && offset < (int) SPECTRUM_PACKET_BUF_SIZE - 8; i++) {
    float magnitude = sqrtf(real[i] * real[i] + imag[i] * imag[i]) / SPECTRUM_FFT_SIZE;
    float amplitude = fmaxf(magnitude / FULL_SCALE, 1e-9f); // Division durch 0 im log10 vermeiden
    int dbTimes10 = (int) roundf(20.0f * log10f(amplitude) * 10.0f);
    offset += snprintf(packetBuf + offset, SPECTRUM_PACKET_BUF_SIZE - offset, ",%d", dbTimes10);
  }

  packetBuf[offset++] = '\n';
  hostWrite(packetBuf, offset);
}

/** Nimmt {@link #SPECTRUM_FFT_SIZE} Samples vom Mikrofon des angegebenen Kanals auf, wendet ein
 *  Hann-Fenster an (reduziert den "Leckeffekt" durch den scharfen Rand des Ausschnitts, der sonst
 *  als zusätzliche, falsche Frequenzanteile im Spektrum erscheinen würde), berechnet per FFT das
 *  Amplitudenspektrum und verschickt es. Wird für Kanäle mit TYPE_MIC_SPECTRUM aufgerufen. */
void captureAndSendSpectrum(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  static int32_t rawBuffer[SPECTRUM_FFT_SIZE];
  size_t bytesRead = 0;
  // Wie bei sampleMicrophone (siehe dort und v8.4-Hinweis im Dateikopf): SPECTRUM_FFT_SIZE
  // Samples bei MIC_SAMPLE_RATE_HZ sind 64ms, 100ms statt vormals 200ms genügt als Marge und
  // begrenzt eine mögliche Verzögerung des jeweils anderen Kanals im Fehlerfall stärker.
  esp_err_t err = i2s_channel_read(handle, rawBuffer, sizeof(rawBuffer), &bytesRead, pdMS_TO_TICKS(100));
  int sampleCount = bytesRead / sizeof(int32_t);
  if (err != ESP_OK || sampleCount < SPECTRUM_FFT_SIZE) {
    reportSensorError(channelName, "I2S");
    return;
  }

  // real/imag als "static" statt lokal: 2 * 1024 * 4 Byte wären auf dem Stack des Loop-Tasks
  // riskant knapp (Standard-Stackgröße bei Arduino-ESP32 8 KB) - im BSS-Bereich unkritisch.
  static float real[SPECTRUM_FFT_SIZE];
  static float imag[SPECTRUM_FFT_SIZE];

  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    int32_t sample = rawBuffer[i] >> 8; // 24 gültige Bits linksbündig, siehe sampleMicrophone
    float window = 0.5f - 0.5f * cosf(2.0f * PI * i / (SPECTRUM_FFT_SIZE - 1)); // Hann-Fenster
    real[i] = sample * window;
    imag[i] = 0;
  }

  computeFFT(real, imag, SPECTRUM_FFT_SIZE);
  sendSpectrumPacket(channelName, real, imag);
}

/** Bestimmt, wie viele I2S-Rohsamples {@link #sampleMicrophone} pro Aufruf liest: so viele, wie
 *  in ein Intervall bei der aktuell eingestellten Abtastrate ({@code sampleIntervalMs}) passen -
 *  mehr Samples ergeben einen über einen größeren Zeitraum gemittelten, "ruhigeren" Spitzenwert,
 *  weniger Samples einen unmittelbareren, aber verrauschteren. Nach unten/oben begrenzt auf
 *  {@link #MIC_MIN_READ_SAMPLES}/{@link #MIC_MAX_READ_SAMPLES}. */
int microphoneReadSampleCount() {
  long samplesPerInterval = ((long) MIC_SAMPLE_RATE_HZ * sampleIntervalMs) / 1000;
  return (int) constrain(samplesPerInterval, MIC_MIN_READ_SAMPLES, MIC_MAX_READ_SAMPLES);
}

/** Liest einen kurzen Block Rohsamples vom INMP441 und bildet daraus den Spitzenbetrag
 *  (Peak-Amplitude) - ein einzelner Wert pro Aufrufzyklus, genau wie bei allen anderen
 *  Sensortypen. Das hält das serielle Protokoll unverändert (ein Datenpaket pro Kanal und
 *  Intervall) - die hohe I2S-Abtastrate bleibt intern und wird nicht Sample für Sample über die
 *  serielle Verbindung geschickt, was bei dieser Baudrate ohnehin nicht möglich wäre. */
void sampleMicrophone(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int samplesToRead = microphoneReadSampleCount();
  int32_t buffer[MIC_MAX_READ_SAMPLES];
  size_t bytesRead = 0;

  // Timeout bewusst knapp über der maximal benötigten Sammelzeit gewählt (MIC_MAX_READ_SAMPLES
  // Samples bei MIC_SAMPLE_RATE_HZ sind 32ms) statt der vorherigen 50ms mit größerer Marge -
  // siehe v8.4-Hinweis im Dateikopf: ein blockierender Lesevorgang hier verzögert auch die
  // Abtastung des jeweils anderen Kanals (siehe #loop/#sampleChannel), ein kleineres Timeout
  // begrenzt diese Verzögerung im Fehlerfall (hängendes/getrenntes Mikrofon), ohne bei normalem
  // Betrieb je greifen zu müssen.
  esp_err_t err = i2s_channel_read(handle, buffer, samplesToRead * sizeof(int32_t), &bytesRead, pdMS_TO_TICKS(20));
  if (err != ESP_OK || bytesRead == 0) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int sampleCount = bytesRead / sizeof(int32_t);
  int32_t peak = 0;
  for (int i = 0; i < sampleCount; i++) {
    int32_t sample = buffer[i] >> 8; // 24 gültige Bits liegen linksbündig im 32-Bit-Wort
    int32_t magnitude = (sample < 0) ? -sample : sample;
    if (magnitude > peak) peak = magnitude;
  }

  sendDataPacket(channelName, 0, peak);
}

/** Tastet den konfigurierten Sensor eines Kanals ab. Bei einem Übertragungsfehler wird für das
 *  betroffene Register kein Datenpaket verschickt (siehe {@link #reportSensorError}), statt
 *  einen falschen 0-Wert zu senden. */
void sampleChannel(char channelName, SensorType type, const int pins[3]) {
  if (type == TYPE_ANALOG) {
    int analogVal = analogRead(pins[0]);
    sendDataPacket(channelName, 0, analogVal);
  } else if (type == TYPE_INA219) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawVoltage, rawCurrent;
    bool okVoltage = readI2CRegister16(bus, ADDR_INA219, 0x02, rawVoltage);
    if (okVoltage) {
      sendDataPacket(channelName, 0, rawVoltage);
    } else {
      reportSensorError(channelName, "I2C");
    }
    bool okCurrent = readI2CRegister16(bus, ADDR_INA219, 0x04, rawCurrent);
    if (okCurrent) {
      sendDataPacket(channelName, 1, rawCurrent);
    } else {
      reportSensorError(channelName, "I2C");
    }
    noteI2CResult(channelName, type, okVoltage && okCurrent);
  } else if (type == TYPE_VEML7700) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawLux;
    bool okLux = readI2CRegister16LE(bus, ADDR_VEML7700, 0x04, rawLux);
    if (okLux) {
      sendDataPacket(channelName, 0, rawLux);
    } else {
      reportSensorError(channelName, "I2C");
    }
    noteI2CResult(channelName, type, okLux);
  } else if (type == TYPE_HX711) {
    long rawWeight;
    if (readHX711(pins[0], pins[1], rawWeight)) {
      sendDataPacket(channelName, 0, rawWeight);
    } else {
      reportSensorError(channelName, "HX711");
    }
  } else if (type == TYPE_HALL) {
    // Kein Übertragungsfehler möglich wie bei I2C/HX711 - digitalRead() liefert immer einen
    // Wert. Die Umrechnung "0/1 -> Magnetfeld ja/nein" (inkl. Invertierung, da das Modul
    // active-low ist) übernimmt bewusst erst die Java-Seite (HallEffectSensor.decode), wie bei
    // allen anderen Sensoren auch - die Firmware kennt nur Rohwerte.
    int rawState = digitalRead(pins[0]);
    sendDataPacket(channelName, 0, rawState);
  } else if (type == TYPE_DS18B20) {
    sampleDS18B20(channelName, pins[0]);
  } else if (type == TYPE_MICROPHONE) {
    sampleMicrophone(channelName);
  } else if (type == TYPE_MIC_SPECTRUM) {
    // Bewusst kein Aufruf hier: das Spektrum braucht eine eigene, von der normalen Abtastrate
    // unabhängige Taktung (SPECTRUM_INTERVAL_MS) und wird deshalb direkt in loop() behandelt.
  }
}

void setup() {
  Serial.begin(BAUD_RATE);
  SerialBT.begin(BT_DEVICE_NAME);
  delay(200);

  // Bewusst KEINE Pin-/Bus-Initialisierung hier: welche Rolle die drei Kanal-Pins spielen,
  // hängt vom gewählten Sensortyp ab und wird erst bei SET über configureChannelHardware()
  // hergestellt - beide Kanäle starten unkonfiguriert bei TYPE_NONE.
  hostPrint("#HELLO,PhyLog-ESP32,fw=8.5\n");
}

void loop() {
  handleSerialCommunication();

  if (!isStreaming) return;

  unsigned long currentTimeMs = millis();
  if (currentTimeMs - lastSampleTimeMs >= sampleIntervalMs) {
    lastSampleTimeMs = currentTimeMs;
    sampleChannel('A', configChannelA, PINS_CHANNEL_A);
    sampleChannel('B', configChannelB, PINS_CHANNEL_B);
  }

  // Das Frequenzspektrum braucht eine eigene, von der (für normale Sensoren gedachten,
  // ggf. viel höheren) Abtastrate unabhängige Taktung - eine einzelne FFT dauert zwar nur
  // Millisekunden, aber 512 Bins pro Bild sind schon einige hundert Byte, die bei dieser Baudrate
  // nicht beliebig oft pro Sekunde übertragen werden können (siehe SPECTRUM_INTERVAL_MS).
  if (configChannelA == TYPE_MIC_SPECTRUM && currentTimeMs - lastSpectrumTimeMsA >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsA = currentTimeMs;
    captureAndSendSpectrum('A');
  }
  if (configChannelB == TYPE_MIC_SPECTRUM && currentTimeMs - lastSpectrumTimeMsB >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsB = currentTimeMs;
    captureAndSendSpectrum('B');
  }
}
