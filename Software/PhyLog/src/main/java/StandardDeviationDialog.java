import javax.swing.*;

/** Dialog zur Konfiguration der Messunsicherheit (Standardabweichung/Sigma). */
public class StandardDeviationDialog extends FormDialog {

    private final JRadioButton rbConstant;
    private final JRadioButton rbAutoGaussian;
    private final JRadioButton rbAutoLocal;
    private final JTextField tfValue;
    private final JSpinner spNeighbors;

    private boolean confirmed = false;
    private double standardDeviation;
    private GoodnessOfFit.SigmaMode sigmaMode;
    private int localSigmaNeighbors;

    public StandardDeviationDialog(JFrame parent, double currentVal, GoodnessOfFit.SigmaMode currentMode, int currentNeighbors) {
        super(parent, "Standardabweichung einstellen");

        ButtonGroup group = new ButtonGroup();

        rbConstant = new JRadioButton("Konstant (manueller Wert)");
        group.add(rbConstant);
        addFullWidthRow(rbConstant);

        tfValue = new JTextField(String.valueOf(currentVal));
        addRow("     Wert (s):", tfValue);

        rbAutoGaussian = new JRadioButton("Automatisch – gewichtet (Gauß-Kernel)");
        group.add(rbAutoGaussian);
        addFullWidthRow(rbAutoGaussian);
        addFullWidthRow(hintLabel("Wie 'lokal', aber mit weichem statt hartem Übergang: nahe Punkte gehen "
                + "stärker, entfernte schwächer ein (Gewicht nimmt mit dem Abstand gaußförmig ab) - dadurch "
                + "verläuft die Fehlerbreite glatt statt stufig, auch bei ungleichmäßig verteilten "
                + "Messpunkten.", 380));

        rbAutoLocal = new JRadioButton("Automatisch – lokal, hartes Fenster (nächste Nachbarn)");
        group.add(rbAutoLocal);
        addFullWidthRow(rbAutoLocal);
        addFullWidthRow(hintLabel("Ein eigener Wert je Punkt, aus dessen nächsten Nachbarn geschätzt - passt "
                + "sich ungleichmäßig verteiltem Rauschen entlang der Messreihe an.", 380));

        spNeighbors = new JSpinner(new SpinnerNumberModel(Math.max(2, currentNeighbors), 2, 100, 1));
        addRow("     Nachbarschaftsgröße (k):", spNeighbors);

        JLabel lblFallback = hintLabel("Alle automatischen Modi benötigen einen Funktions-Fit; ohne Fit gilt "
                + "der konstante Wert.", 380);
        lblFallback.setForeground(Theme.ACCENT);
        addFullWidthRow(lblFallback);

        sigmaMode = (currentMode != null) ? currentMode : GoodnessOfFit.SigmaMode.CONSTANT;
        switch (sigmaMode) {
            case RESIDUAL_LOCAL_GAUSSIAN -> rbAutoGaussian.setSelected(true);
            case RESIDUAL_LOCAL -> rbAutoLocal.setSelected(true);
            default -> rbConstant.setSelected(true);
        }
        updateFieldStates();

        rbConstant.addActionListener(_ -> updateFieldStates());
        rbAutoGaussian.addActionListener(_ -> updateFieldStates());
        rbAutoLocal.addActionListener(_ -> updateFieldStates());

        JButton btnOk = new JButton("Übernehmen");
        JButton btnCancel = new JButton("Abbrechen");

        btnOk.addActionListener(_ -> tryApplyAndClose());
        btnCancel.addActionListener(_ -> dispose());

        finishLayout(btnCancel, btnOk);
    }

    private void updateFieldStates() {
        tfValue.setEnabled(rbConstant.isSelected());
        spNeighbors.setEnabled(rbAutoLocal.isSelected() || rbAutoGaussian.isSelected());
    }

    private void tryApplyAndClose() {
        double val;
        try {
            val = Double.parseDouble(tfValue.getText().replace(",", "."));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Bitte geben Sie eine gültige Zahl ein.", "Ungültige Eingabe", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (val < 0) {
            JOptionPane.showMessageDialog(this, "Der Wert darf nicht negativ sein.", "Fehler", JOptionPane.ERROR_MESSAGE);
            return;
        }

        standardDeviation = val;
        localSigmaNeighbors = (int) spNeighbors.getValue();
        sigmaMode = rbAutoGaussian.isSelected() ? GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL_GAUSSIAN
                : rbAutoLocal.isSelected() ? GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL
                : GoodnessOfFit.SigmaMode.CONSTANT;

        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public double getStandardDeviation() {
        return standardDeviation;
    }

    public GoodnessOfFit.SigmaMode getSigmaMode() {
        return sigmaMode;
    }

    public int getLocalSigmaNeighbors() {
        return localSigmaNeighbors;
    }
}
