import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Kalibrierdialog, der die {@link Sensor.CalibrationParameter} des übergebenen Sensors als
 * Label-Feld-Zeilen anzeigt.
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
        btnCancel.addActionListener(_ -> dispose());

        if (parameters.isEmpty()) {
            finishLayout(btnCancel);
        } else {
            JButton btnApply = new JButton("Übernehmen");
            btnApply.addActionListener(_ -> applyAndClose());
            finishLayout(btnCancel, btnApply);
        }
    }

    /** Parst alle Eingabefelder und übernimmt sie erst, wenn keines einen Fehler hat. */
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
