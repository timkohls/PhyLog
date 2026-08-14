import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Generischer Kalibrierdialog: zeigt genau die {@link Sensor.CalibrationParameter}, die der
 * übergebene Sensor liefert, je eine Zeile mit Label und Eingabefeld. Ein neuer Sensor mit
 * Kalibrierbedarf muss dafür nur {@link Sensor#getCalibrationParameters()} überschreiben - der
 * Dialog selbst kennt keine sensorspezifischen Details.
 */
public class CalibrationDialog extends FormDialog {

    private final List<Sensor.CalibrationParameter> parameters;
    private final List<JTextField> fields = new ArrayList<>();

    public CalibrationDialog(Window owner, Sensor sensor) {
        super(owner, "Kalibrieren: " + sensor.getName());
        this.parameters = sensor.getCalibrationParameters();
        initUI();
    }

    private void initUI() {
        if (parameters.isEmpty()) {
            addFullWidthRow(labelComponent("Dieser Sensor hat keine Kalibrierwerte."));
        } else {
            for (Sensor.CalibrationParameter param : parameters) {
                JTextField field = new JTextField(String.valueOf(param.get()));
                fields.add(field);
                addRow(labelComponent(param.label + ":"), field, labelComponent(param.unit));
            }
        }

        JButton btnCancel = new JButton("Abbrechen");
        btnCancel.addActionListener(e -> dispose());

        if (parameters.isEmpty()) {
            finishLayout(btnCancel);
        } else {
            JButton btnApply = new JButton("\u00dcbernehmen");
            btnApply.addActionListener(e -> applyAndClose());
            finishLayout(btnCancel, btnApply);
        }
    }

    /** Parst alle Eingabefelder und übernimmt sie erst, wenn keines einen Fehler hat - so
     *  bleiben bei einer ungültigen Eingabe alle bisherigen Kalibrierwerte unverändert. */
    private void applyAndClose() {
        double[] parsedValues = new double[parameters.size()];

        for (int i = 0; i < parameters.size(); i++) {
            try {
                parsedValues[i] = Double.parseDouble(fields.get(i).getText().replace(",", "."));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "Ungültiger Wert bei \"" + parameters.get(i).label + "\".",
                        "Ungültige Eingabe", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        for (int i = 0; i < parameters.size(); i++) {
            parameters.get(i).set(parsedValues[i]);
        }
        dispose();
    }
}
