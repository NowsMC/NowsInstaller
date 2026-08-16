package space.nows.mcnows.installer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class NowsInstallerGui {
    private final JFrame frame = new JFrame("Nows Installer");
    private final JLabel nowsVersion = new JLabel(NowsInstaller.defaultNowsVersion());
    private final JComboBox<String> minecraftVersion = new JComboBox<>(
            NowsInstaller.supportedMinecraftVersions().toArray(new String[0]));
    private final JTextField minecraftDir = new JTextField(defaultMinecraftDir());
    private final JTextField gameDir = new JTextField(defaultGameDir());
    private final JTextField manifest = new JTextField();
    private final JTextField artifactDir = new JTextField();
    private final JCheckBox offline = new JCheckBox("Offline install");
    private final JCheckBox profileGameDir = new JCheckBox("Use a separate game folder for this Nows profile");
    private final JButton gameDirBrowse = new JButton("Browse");
    private final JButton manifestBrowse = new JButton("Browse");
    private final JButton artifactDirBrowse = new JButton("Browse");
    private final JButton install = new JButton("Install");
    private final JButton advanced = squareButton("...");
    private final JLabel minecraftDirStatus = new JLabel(" ");
    private final JLabel installHint = new JLabel(" ");
    private final JLabel offlineHint = new JLabel(" ");
    private final JTextArea log = new JTextArea();

    private NowsInstallerGui() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(720, 460));
        frame.setLayout(new BorderLayout(12, 12));
        setWindowIcon();
        frame.add(form(), BorderLayout.NORTH);
        frame.add(logPane(), BorderLayout.CENTER);
        frame.add(actions(), BorderLayout.SOUTH);
        frame.getRootPane().setDefaultButton(install);

        offline.addActionListener(event -> updateOfflineFields());
        profileGameDir.addActionListener(event -> updateGameDirFields());
        gameDirBrowse.addActionListener(event -> choose(gameDir, JFileChooser.DIRECTORIES_ONLY));
        manifestBrowse.addActionListener(event -> choose(manifest, JFileChooser.FILES_ONLY));
        artifactDirBrowse.addActionListener(event -> choose(artifactDir, JFileChooser.DIRECTORIES_ONLY));
        minecraftVersion.addActionListener(event -> updateDefaultGameDir());
        minecraftDir.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update() {
                updateMinecraftDirStatus();
                updateDefaultGameDir();
                updateInstallHint();
            }
        });
        updateOfflineFields();
        updateGameDirFields();
        updateMinecraftDirStatus();
        updateInstallHint();
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Swing falls back to the default look and feel.
                }
                new NowsInstallerGui().show();
            }
        });
    }

    private void show() {
        frame.setVisible(true);
    }

    private JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 0, 14));

        addRow(panel, 0, "Nows version", nowsVersion, null);
        addRow(panel, 1, "Minecraft version", minecraftVersion, null);
        addRow(panel, 2, "Minecraft folder", minecraftDir, minecraftDirButtons());

        GridBagConstraints statusGbc = constraints(3, 1);
        statusGbc.gridwidth = 2;
        statusGbc.fill = GridBagConstraints.HORIZONTAL;
        minecraftDirStatus.setVerticalAlignment(SwingConstants.TOP);
        panel.add(minecraftDirStatus, statusGbc);

        GridBagConstraints hintGbc = constraints(4, 1);
        hintGbc.gridwidth = 2;
        hintGbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(installHint, hintGbc);
        return panel;
    }

    private JScrollPane logPane() {
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JScrollPane pane = new JScrollPane(log);
        pane.setPreferredSize(new Dimension(680, 230));
        pane.setBorder(BorderFactory.createEmptyBorder(12, 14, 0, 14));
        return pane;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
        install.addActionListener(event -> runInstall());
        panel.add(install, BorderLayout.EAST);
        return panel;
    }

    private void runInstall() {
        install.setEnabled(false);
        log.setText("");

        Path minecraftPath = selectedMinecraftDir();
        if (minecraftPath == null || !confirmMinecraftDir(minecraftPath)) {
            install.setEnabled(true);
            return;
        }
        if (offline.isSelected() && !validateOfflineInputs()) {
            install.setEnabled(true);
            return;
        }

        final String[] args = installerArgs();
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                NowsInstaller.install(NowsInstaller.Options.parse(args), new NowsInstaller.InstallerListener() {
                    @Override
                    public void log(String message) {
                        publish(message);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    append(line);
                }
            }

            @Override
            protected void done() {
                install.setEnabled(true);
                try {
                    get();
                    append("[NowsInstaller] Done");
                    JOptionPane.showMessageDialog(frame, "Nows installation finished.", "Install complete",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = friendlyError(cause);
                    append("[NowsInstaller] Failed: " + message);
                    JOptionPane.showMessageDialog(frame, message, "Install failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private String[] installerArgs() {
        List<String> args = new ArrayList<>();
        addOption(args, "--nows", nowsVersion.getText());
        addOption(args, "--minecraft", selectedMinecraftVersion());
        addOption(args, "--minecraftDir", minecraftDir.getText());
        if (offline.isSelected()) {
            args.add("--offline");
            addOption(args, "--manifest", manifest.getText());
            addOption(args, "--artifactDir", artifactDir.getText());
        }
        if (profileGameDir.isSelected()) {
            addOption(args, "--gameDir", gameDir.getText());
        }
        return args.toArray(new String[0]);
    }

    private static void addOption(List<String> args, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            args.add(name);
            args.add(value.trim());
        }
    }

    private void append(String line) {
        log.append(line);
        log.append(System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static void addRow(JPanel panel, int row, String label, JComponent field, JPanel buttons) {
        GridBagConstraints labelConstraints = constraints(row, 0);
        labelConstraints.weightx = 0;
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = constraints(row, 1);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);

        if (buttons != null) {
            GridBagConstraints buttonConstraints = constraints(row, 2);
            buttonConstraints.weightx = 0;
            panel.add(buttons, buttonConstraints);
        }
    }

    private static GridBagConstraints constraints(int row, int column) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = column;
        gbc.gridy = row;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private JPanel minecraftDirButtons() {
        JPanel buttons = buttonPanel();
        JButton browse = new JButton("Browse");
        JButton detect = new JButton("Auto-detect");
        JButton defaults = new JButton("Default");
        browse.addActionListener(event -> choose(minecraftDir, JFileChooser.DIRECTORIES_ONLY));
        detect.addActionListener(event -> detectMinecraftDir());
        defaults.addActionListener(event -> minecraftDir.setText(NowsInstaller.defaultMinecraftDir().toString()));
        advanced.setToolTipText("Advanced profile and offline options");
        advanced.addActionListener(event -> showAdvancedOptions());
        buttons.add(browse);
        buttons.add(detect);
        buttons.add(defaults);
        buttons.add(advanced);
        return buttons;
    }

    private JPanel buttonPanel(JButton button) {
        JPanel buttons = buttonPanel();
        buttons.add(button);
        return buttons;
    }

    private JPanel buttonPanel() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    }

    private static JButton squareButton(String text) {
        JButton button = new JButton(text);
        Dimension size = new Dimension(28, 28);
        button.setMinimumSize(size);
        button.setPreferredSize(size);
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
    }

    private void showAdvancedOptions() {
        final JDialog dialog = new JDialog(frame, "Advanced options", true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints profileGbc = constraints(0, 0);
        profileGbc.gridwidth = 3;
        fields.add(profileGameDir, profileGbc);
        addRow(fields, 1, "Profile game folder", gameDir, buttonPanel(gameDirBrowse));

        GridBagConstraints offlineGbc = constraints(2, 0);
        offlineGbc.gridwidth = 3;
        fields.add(offline, offlineGbc);
        addRow(fields, 3, "Manifest file", manifest, buttonPanel(manifestBrowse));
        addRow(fields, 4, "Artifact folder", artifactDir, buttonPanel(artifactDirBrowse));

        GridBagConstraints hintGbc = constraints(5, 1);
        hintGbc.gridwidth = 2;
        hintGbc.fill = GridBagConstraints.HORIZONTAL;
        fields.add(offlineHint, hintGbc);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton done = new JButton("Done");
        done.addActionListener(event -> dialog.dispose());
        actions.add(done);

        panel.add(fields, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        updateOfflineFields();
        updateGameDirFields();
        dialog.pack();
        dialog.setMinimumSize(new Dimension(620, dialog.getHeight()));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void choose(JTextField target, int mode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(mode);
        if (target.getText() != null && !target.getText().trim().isEmpty()) {
            File selected = new File(target.getText().trim());
            if (selected.isDirectory()) {
                chooser.setCurrentDirectory(selected);
            }
            chooser.setSelectedFile(selected);
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void detectMinecraftDir() {
        List<Path> candidates = NowsInstaller.minecraftDirectoryCandidates();
        for (Path candidate : candidates) {
            if (NowsInstaller.looksLikeMinecraftDir(candidate)) {
                minecraftDir.setText(candidate.toString());
                append("[NowsInstaller] Detected Minecraft folder: " + candidate);
                return;
            }
        }
        minecraftDir.setText(candidates.get(0).toString());
        append("[NowsInstaller] No existing Minecraft folder found. Using default path: " + candidates.get(0));
    }

    private void updateOfflineFields() {
        boolean enabled = offline.isSelected();
        manifest.setEnabled(enabled);
        artifactDir.setEnabled(enabled);
        manifestBrowse.setEnabled(enabled);
        artifactDirBrowse.setEnabled(enabled);
        offlineHint.setText(enabled
                ? "Offline install uses the selected local manifest and artifacts folder."
                : "Online install downloads the release manifest and artifacts over HTTPS.");
    }

    private void updateGameDirFields() {
        boolean enabled = profileGameDir.isSelected();
        gameDir.setEnabled(enabled);
        gameDirBrowse.setEnabled(enabled);
        if (enabled && (gameDir.getText() == null || gameDir.getText().trim().isEmpty())) {
            gameDir.setText(defaultGameDir());
        }
    }

    private void updateDefaultGameDir() {
        if (!profileGameDir.isSelected()) {
            gameDir.setText(defaultGameDir());
        }
    }

    private void updateMinecraftDirStatus() {
        Path path = selectedMinecraftDirQuietly();
        if (path == null) {
            minecraftDirStatus.setText("Choose the main Minecraft game folder.");
        } else if (NowsInstaller.looksLikeMinecraftDir(path)) {
            minecraftDirStatus.setText("Detected an existing Minecraft folder.");
        } else if (Files.exists(path)) {
            minecraftDirStatus.setText("Folder exists, but no launcher data was detected.");
        } else {
            minecraftDirStatus.setText("Folder will be created if you install here.");
        }
    }

    private void updateInstallHint() {
        Path path = selectedMinecraftDirQuietly();
        String folder = path == null ? "the selected Minecraft folder" : path.toString();
        installHint.setText("Normal install uses " + folder + " as the game folder. Profile folder is optional.");
    }

    private Path selectedMinecraftDir() {
        Path path = selectedMinecraftDirQuietly();
        if (path == null) {
            JOptionPane.showMessageDialog(frame, "Choose a Minecraft folder before installing.",
                    "Minecraft folder required", JOptionPane.WARNING_MESSAGE);
        }
        return path;
    }

    private Path selectedMinecraftDirQuietly() {
        String value = minecraftDir.getText();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }

    private boolean confirmMinecraftDir(Path path) {
        if (NowsInstaller.looksLikeMinecraftDir(path)) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(
                frame,
                "This does not look like an existing Minecraft folder:\n" + path
                        + "\n\nInstall here anyway?",
                "Confirm Minecraft folder",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        return choice == JOptionPane.YES_OPTION;
    }

    private boolean validateOfflineInputs() {
        String manifestText = manifest.getText();
        if (manifestText == null || manifestText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Choose a local manifest file for offline install.",
                    "Manifest required", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        Path manifestPath = Paths.get(manifestText.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifestPath)) {
            JOptionPane.showMessageDialog(frame, "Manifest file was not found:\n" + manifestPath,
                    "Manifest not found", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        String artifactText = artifactDir.getText();
        if (artifactText != null && !artifactText.trim().isEmpty()) {
            Path artifactsPath = Paths.get(artifactText.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(artifactsPath)) {
                JOptionPane.showMessageDialog(frame, "Artifact folder was not found:\n" + artifactsPath,
                        "Artifact folder not found", JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        return true;
    }

    private String friendlyError(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.toString();
        }
        if (cause instanceof SSLException) {
            return "HTTPS connection failed. Check your network, system clock, Java certificate store, "
                    + "or try an offline install from Advanced options.\n\n" + message;
        }
        return message;
    }

    private void setWindowIcon() {
        try (InputStream input = NowsInstallerGui.class.getResourceAsStream("/META-INF/nows/branding/nows.png")) {
            if (input != null) {
                Image image = ImageIO.read(input);
                if (image != null) {
                    frame.setIconImage(image);
                }
            }
        } catch (IOException ignored) {
            // The installer still works without a window icon.
        }
    }

    private static String defaultMinecraftDir() {
        return NowsInstaller.defaultMinecraftDir().toString();
    }

    private String selectedMinecraftVersion() {
        Object selected = minecraftVersion.getSelectedItem();
        return selected == null ? NowsInstaller.defaultMinecraftVersion() : selected.toString();
    }

    private String defaultGameDir() {
        Path base = selectedMinecraftDirQuietly();
        if (base == null) {
            base = NowsInstaller.defaultMinecraftDir();
        }
        return base
                .resolve("nows")
                .resolve("profiles")
                .resolve("nows-" + nowsVersion.getText().trim() + "-" + selectedMinecraftVersion())
                .toString();
    }

    private abstract static class SimpleDocumentListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent event) {
            update();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            update();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            update();
        }

        public abstract void update();
    }
}
