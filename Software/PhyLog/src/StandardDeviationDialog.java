import javax.swing.*;
import java.awt.*;

/**
 * Modaler Dialog, mit dem die als konstant angenommene Messunsicherheit (Standardabweichung
 * sigma) eingestellt wird, die {@link ChartPanel} für die Chi²-Berechnung und die Breite des
 * Toleranzbands um eine Fit-Kurve verwendet.
 */
public class StandardDeviationDialog extends JDialog {
    private JTextField tfValue;
    private boolean confirmed = false;
    private double standardDeviation;

    /**
     * @param parent     Eigentümerfenster (für Modalität und Positionierung)
     * @param currentVal aktuell eingestellte Standardabweichung, wird als Startwert vorbelegt
     */
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

    /** @return {@code true}, wenn der Dialog über "Übernehmen" mit einem gültigen Wert geschlossen wurde */
    public boolean isConfirmed() {
        return confirmed;
    }

    /** @return die vom Nutzer bestätigte Standardabweichung (nur gültig, wenn {@link #isConfirmed()} true ist) */
    public double getStandardDeviation() {
        return standardDeviation;
    }
}