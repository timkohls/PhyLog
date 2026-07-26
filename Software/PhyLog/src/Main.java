import javax.swing.*;
import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Einstiegspunkt der LibrePhysics-Anwendung.
 */
public class Main {

    /**
     * Richtet das Dark-Theme ein und öffnet das Hauptfenster auf dem Swing-Event-Dispatch-Thread.
     *
     * @param args Kommandozeilenargumente (werden aktuell nicht ausgewertet)
     */
    public static void main(String[] args) {

        Theme.setup();

        SwingUtilities.invokeLater(() -> {
            new GUI().setVisible(true);
        });
    }

}