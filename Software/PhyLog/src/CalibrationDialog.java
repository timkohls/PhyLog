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
public class CalibrationDialog extends JDialog {

    private final List<Sensor.CalibrationParameter> parameters;
    private final List<JTextField> fields = new ArrayList<>();

    public CalibrationDialog(Window owner, Sensor sensor) {
        super(owner, "Kalibrieren: " + sensor.getName(), ModalityType.APPLICATION_MODAL);
        this.parameters = sensor.getCalibrationParameters();
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.weightx = 1.0;

        if (parameters.isEmpty()) {
            formPanel.add(new JLabel("Dieser Sensor hat keine Kalibrierwerte."), gbc);
        } else {
            int row = 0;
            for (Sensor.CalibrationParameter param : parameters) {
                gbc.gridx = 0;
                gbc.gridy = row;
                gbc.weightx = 0.5;
                formPanel.add(new JLabel(param.label + ":"), gbc);

                JTextField field = new JTextField(String.valueOf(param.get()));
                fields.add(field);
                gbc.gridx = 1;
                gbc.weightx = 0.3;
                formPanel.add(field, gbc);

                gbc.gridx = 2;
                gbc.weightx = 0.2;
                formPanel.add(new JLabel(param.unit), gbc);

                row++;
            }
        }

        add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Abbrechen");
        btnCancel.addActionListener(e -> dispose());
        buttonPanel.add(btnCancel);

        if (!parameters.isEmpty()) {
            JButton btnApply = new JButton("Übernehmen");
            btnApply.addActionListener(e -> applyAndClose());
            buttonPanel.add(btnApply);
        }
        add(buttonPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner());
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
