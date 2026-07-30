### Ports für Sensor A und Sensor B

Diese Tabelle zeigt, wie die ESP-Daten-Pins auf die RJ45-Pins der ersten beiden Ports verteilt sind.

| **RJ45-Pin** | **Sensor A (ESP-Pin)** | **Sensor B (ESP-Pin)** |
| ------------ | ---------------------- | ---------------------- |
| **1**        | Ground                 | Ground                 |
| **2**        | D13                    | D27                    |
| **3**        | D12                    | D26                    |
| **4**        | D14                    | D25                    |
| **5**        | *leer*                 | *leer*                 |
| **6**        | *leer*                 | *leer*                 |
| **7**        | *leer*                 | *leer*                 |
| **8**        | VCC                    | VCC                    |
| **9**        | *leer*                 | *leer*                 |

### Spezifische Sensortypen

Diese Tabelle zeigt die genaue Pin-Belegung je nach angeschlossenem Sensortyp (I2C, I2S, HX711, Analog).

| **RJ45-Pin** | **I2C-Sensor** | **I2S-Sensor** | **2WS-Sensor** | **Analog-Sensor** |
| ------------ | -------------- | -------------- | -------------- | ----------------- |
| **1**        | Ground         | Ground         | Ground         | Ground            |
| **2**        | SDA            | WS             | DOUT           | Analog            |
| **3**        | SCL            | BCLK           | SCK            | *leer*            |
| **4**        | *leer*         | SD             | *leer*         | *leer*            |
| **5**        | *leer*         | *leer*         | *leer*         | *leer*            |
| **6**        | *leer*         | *leer*         | *leer*         | *leer*            |
| **7**        | *leer*         | *leer*         | *leer*         | *leer*            |
| **8**        | VCC            | VCC            | VCC            | VCC               |
| **9**        | *leer*         | *leer*         | *leer*         | *leer*            |
