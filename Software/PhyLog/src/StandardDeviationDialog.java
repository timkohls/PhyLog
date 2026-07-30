import javax.swing.*;
import java.awt.*;

/**
 * Dialog zum Einstellen der als konstant angenommenen Messunsicherheit (Standardabweichung
 * sigma), die {@link ChartPanel} für Chi²-Berechnung und Toleranzband verwendet.
 */
public class StandardDeviationDialog extends JDialog {
    private final JTextField tfValue;
    private boolean confirmed = false;
    private double standardDeviation;

    public StandardDeviationDialog(JFrame parent, double currentVal) {
        super(parent, "Standardabweichung einstellen", true);
        setSize(300, 150);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        JPanel panel = new JPanel(new GridLayout(2, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        panel.add(new JLabel("Standardabweichung (s):"));
        tfValue = new JTextField(String.valueOf(currentVal));
        panel.add(tfValue);
        add(panel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel();
        JButton btnOk = new JButton("Übernehmen");
        JButton btnCancel = new JButton("Abbrechen");

        btnOk.addActionListener(e -> {
            try {
                double val = Double.parseDouble(tfValue.getText().replace(",", "."));
                if (val < 0) {
                    JOptionPane.showMessageDialog(this, "Der Wert darf nicht negativ sein.", "Fehler", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                standardDeviation = val;
                confirmed = true;
                dispose();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Bitte geben Sie eine gültige Zahl ein.", "Ungültige Eingabe", JOptionPane.ERROR_MESSAGE);
            }
        });
        btnCancel.addActionListener(e -> dispose());

        btnPanel.add(btnOk);
        btnPanel.add(btnCancel);
        add(btnPanel, BorderLayout.SOUTH);
    }

    /** @return {@code true}, wenn über "Übernehmen" mit gültigem Wert bestätigt wurde */
    public boolean isConfirmed() {
        return confirmed;
    }

    /** @return die bestätigte Standardabweichung (nur gültig, wenn {@link #isConfirmed()}) */
    public double getStandardDeviation() {
        return standardDeviation;
    }
}
