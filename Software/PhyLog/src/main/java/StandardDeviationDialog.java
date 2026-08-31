import javax.swing.*;

/** Dialog zur Konfiguration der Messunsicherheit (Standardabweichung/Sigma). */
public class StandardDeviationDialog extends FormDialog {

    private final JRadioButton rbConstant;
    private final JRadioButton rbAutoGaussian;
    private final JRadioButton rbAutoLocal;
    private final JRadioButton rbAutoDifference;
    private final JTextField tfValue;
    private final JSpinner spNeighbors;

    private boolean confirmed = false;
    private double standardDeviation;
    private GoodnessOfFit.SigmaMode sigmaMode;
    private int localSigmaNeighbors;

    public StandardDeviationDialog(JFrame parent, double currentVal, GoodnessOfFit.SigmaMode currentMode, int currentNeighbors) {
        super(parent, "Standardabweichung einstellen");

        ButtonGroup group = new ButtonGroup();

        JLabel lblIntro = hintLabel("Schau dir die Punkte um deine Fit-Kurve an - ihre Streuung entscheidet, "
                + "welcher Modus passt:", 380);
        lblIntro.setForeground(Theme.TEXT);
        addFullWidthRow(lblIntro);

        rbConstant = new JRadioButton("Konstant (manueller Wert)");
        group.add(rbConstant);
        addFullWidthRow(rbConstant);
        addFullWidthRow(hintLabel("Nimm das, wenn: du die Messunsicherheit deines Sensors kennst (Datenblatt, "
                + "Kalibrierschein) - unabhängig davon, wie die Daten aussehen.", 380));

        tfValue = new JTextField(String.valueOf(currentVal));
        addRow("     Wert (s):", tfValue);

        rbAutoDifference = new JRadioButton("Automatisch – differenzbasiert");
        group.add(rbAutoDifference);
        addFullWidthRow(rbAutoDifference);
        addFullWidthRow(hintLabel("Nimm das, wenn: du die Streuung der "
                + "Punkte um die Kurve über den ganzen Bereich etwa gleich breit aussieht (gleichmäßiges "
                + "Rauschband). Im Zweifel die einfachste automatische Wahl.", 380));

        rbAutoLocal = new JRadioButton("Automatisch – lokal, stufig");
        group.add(rbAutoLocal);
        addFullWidthRow(rbAutoLocal);
        addFullWidthRow(hintLabel("Nimm das, wenn: die Streuung der Punkte sich sichtbar über den Messbereich "
                + "ändert (z. B. am Anfang eng um die Kurve, später breiter) und die Messpunkte einigermaßen "
                + "gleichmäßig verteilt sind.", 380));

        rbAutoGaussian = new JRadioButton("Automatisch – lokal, weich");
        group.add(rbAutoGaussian);
        addFullWidthRow(rbAutoGaussian);
        addFullWidthRow(hintLabel("Nimm das, wenn: wie oben (Streuung ändert sich über den Bereich), aber die "
                + "Messpunkte ungleichmäßig verteilt sind (Lücken, Häufungen) oder du keinen stufigen, sondern "
                + "einen glatten Verlauf der Fehlerbreite willst.", 380));

        spNeighbors = new JSpinner(new SpinnerNumberModel(Math.max(2, currentNeighbors), 2, 100, 1));
        addRow("     Nachbarschaftsgröße (k) für beide 'lokal'-Modi:", spNeighbors);

        JLabel lblFallback = hintLabel("Die drei automatischen Modi außer 'differenzbasiert' benötigen einen "
                + "Funktions-Fit; ohne Fit gilt der konstante Wert.", 380);
        lblFallback.setForeground(Theme.ACCENT);
        addFullWidthRow(lblFallback);

        sigmaMode = (currentMode != null) ? currentMode : GoodnessOfFit.SigmaMode.CONSTANT;
        switch (sigmaMode) {
            case RESIDUAL_LOCAL_GAUSSIAN -> rbAutoGaussian.setSelected(true);
            case RESIDUAL_LOCAL -> rbAutoLocal.setSelected(true);
            case DIFFERENCE_BASED -> rbAutoDifference.setSelected(true);
            default -> rbConstant.setSelected(true);
        }
        updateFieldStates();

        rbConstant.addActionListener(_ -> updateFieldStates());
        rbAutoGaussian.addActionListener(_ -> updateFieldStates());
        rbAutoLocal.addActionListener(_ -> updateFieldStates());
        rbAutoDifference.addActionListener(_ -> updateFieldStates());

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
                : rbAutoDifference.isSelected() ? GoodnessOfFit.SigmaMode.DIFFERENCE_BASED
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