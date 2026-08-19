import javax.swing.*;

/** Einstiegspunkt der PhyLog-Anwendung. */
void main() {
    Theme.setup();
    SwingUtilities.invokeLater(() -> new GUI().setVisible(true));
}
