import javax.imageio.ImageIO;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lesen/Schreiben von Messdaten als CSV sowie PNG-Export des Diagramms. */
public final class DataFileService {

    private DataFileService() {
    }

    /** Callback für eine einzelne, aus einer CSV-Zeile geparste Zahlen-Zeile. */
    @FunctionalInterface
    public interface RowConsumer {
        void accept(Object[] row);
    }

    public static boolean isNumeric(String str) {
        if (str == null) return false;
        try {
            Double.parseDouble(str.replace(",", ".").trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Liefert den zu einer Kopfzeilenspalte wie "Spannung (V)" passenden Sensor anhand der
     *  enthaltenen Einheit, oder {@code null} falls keiner passt. */
    public static Sensor detectSensorFromHeader(String headerColumn) {
        Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(headerColumn);
        if (matcher.find()) {
            return SensorRegistry.findByUnit(matcher.group(1).trim());
        }
        return null;
    }

    /**
     * Liest eine CSV-Datei zeilenweise ein. Eine erkennbare Kopfzeile wird übersprungen und zur
     * Sensor-Erkennung genutzt; jede gültige Zahlen-Zeile mit mindestens {@code columnCount}
     * Spalten wird an {@code rowConsumer} übergeben.
     *
     * @param onSensorDetected Callback, falls aus der Kopfzeile ein Sensor erkannt wurde (optional)
     */
    public static void readCsv(File file, int columnCount, RowConsumer rowConsumer,
                                Consumer<Sensor> onSensorDetected) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("[;,]");

                if (isFirstLine) {
                    isFirstLine = false;
                    if (line.toLowerCase().contains("zeit") || (parts.length >= 2 && !isNumeric(parts[0]))) {
                        if (parts.length >= 2 && onSensorDetected != null) {
                            Sensor detected = detectSensorFromHeader(parts[1]);
                            if (detected != null) onSensorDetected.accept(detected);
                        }
                        continue;
                    }
                }

                if (parts.length < columnCount) continue;

                try {
                    Object[] row = new Object[columnCount];
                    for (int c = 0; c < columnCount; c++) {
                        row[c] = Double.parseDouble(parts[c].replace(",", ".").trim());
                    }
                    rowConsumer.accept(row);
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** Schreibt eine Tabelle als Semikolon-getrennte CSV-Datei (Spaltennamen als Kopfzeile). */
    public static void writeCsv(File file, DefaultTableModel model) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            int columnCount = model.getColumnCount();

            StringBuilder header = new StringBuilder();
            for (int c = 0; c < columnCount; c++) {
                if (c > 0) header.append(";");
                header.append(model.getColumnName(c));
            }
            writer.println(header);

            for (int i = 0; i < model.getRowCount(); i++) {
                StringBuilder row = new StringBuilder();
                for (int c = 0; c < columnCount; c++) {
                    if (c > 0) row.append(";");
                    row.append(model.getValueAt(i, c));
                }
                writer.println(row);
            }
        }
    }

    /** Hängt {@code suffix} vor die Dateiendung an (z. B. für getrennte Kanal-A/B-Exporte). */
    public static File withSuffix(File base, String suffix) {
        String path = base.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        String withoutExt = (dot >= 0) ? path.substring(0, dot) : path;
        String ext = (dot >= 0) ? path.substring(dot) : "";
        return new File(withoutExt + "_" + suffix + ext);
    }

    /** Rendert eine Komponente (das Diagramm) als PNG-Datei. */
    public static void exportPng(Component component, File file) throws IOException {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        component.paint(g2);
        g2.dispose();
        ImageIO.write(image, "png", file);
    }
}
