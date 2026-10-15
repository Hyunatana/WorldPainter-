package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.App;
import org.pepsoft.worldpainter.Dimension;

import javax.swing.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First milestone of the in-editor terrain assistant.
 * Deliberately uses a local, strict command grammar: no model output is executed as code.
 */
public final class TerrainAssistantDialog extends JDialog {
    private static final Pattern HILL = Pattern.compile(
            "^create a hill at (-?\\d+),?\\s+(-?\\d+) radius (\\d+) height (\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private final JTextField prompt = new JTextField("Create a hill at 0, 0 radius 64 height 20");
    private final JTextArea preview = new JTextArea(5, 45);
    private final App app;
    private Hill planned;

    public TerrainAssistantDialog(App app) {
        super(app, "AI Terrain Assistant (prototype)", false);
        this.app = app;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        JButton planButton = new JButton("Preview command");
        JButton applyButton = new JButton("Apply to current dimension");
        applyButton.setEnabled(false);
        planButton.addActionListener(e -> {
            planned = parse(prompt.getText());
            if (planned == null) {
                preview.setText("Unsupported command. Try: Create a hill at 0, 0 radius 64 height 20\nRadius: 1-128, height: 1-100.");
            } else {
                preview.setText("Hill center (" + planned.x + ", " + planned.y + "), radius " + planned.radius
                        + " blocks, peak height increase " + planned.height
                        + " blocks. Only existing tiles will be changed. Save a backup before applying.");
            }
            applyButton.setEnabled(planned != null && app.getDimension() != null);
        });
        applyButton.addActionListener(e -> {
            if (planned == null || app.getDimension() == null) return;
            if (JOptionPane.showConfirmDialog(this, "Apply this terrain change?", "Confirm edit",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            Dimension dimension = app.getDimension();
            int changed = applyHill(dimension, planned);
            preview.setText("Changed " + changed + " terrain columns. Save the world to persist changes.");
            applyButton.setEnabled(false);
            planned = null;
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(planButton);
        buttons.add(applyButton);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(prompt, BorderLayout.NORTH);
        content.add(new JScrollPane(preview), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(app);
    }

    private static Hill parse(String text) {
        Matcher m = HILL.matcher(text.trim());
        if (!m.matches()) return null;
        try {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2));
            int radius = Integer.parseInt(m.group(3)), height = Integer.parseInt(m.group(4));
            if (radius < 1 || radius > 128 || height < 1 || height > 100) return null;
            return new Hill(x, y, radius, height);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int applyHill(Dimension dimension, Hill hill) {
        int count = 0;
        for (int dy = -hill.radius; dy <= hill.radius; dy++) {
            for (int dx = -hill.radius; dx <= hill.radius; dx++) {
                long distanceSquared = (long) dx * dx + (long) dy * dy;
                if (distanceSquared > (long) hill.radius * hill.radius) continue;
                long wx = (long) hill.x + dx, wy = (long) hill.y + dy;
                if (wx < Integer.MIN_VALUE || wx > Integer.MAX_VALUE
                        || wy < Integer.MIN_VALUE || wy > Integer.MAX_VALUE) continue;
                int x = (int) wx, y = (int) wy;
                // WorldPainter tiles are 128 x 128 blocks. Do not create missing tiles.
                if (dimension.getTile(x >> 7, y >> 7) == null) continue;
                float current = dimension.getHeightAt(x, y);
                double falloff = 1.0 - (double) distanceSquared / ((long) hill.radius * hill.radius);
                float next = (float) Math.min(dimension.getMaxHeight() - 1,
                        current + hill.height * falloff * falloff);
                if (next > current) {
                    dimension.setHeightAt(x, y, next);
                    count++;
                }
            }
        }
        return count;
    }

    private static final class Hill {
        final int x, y, radius, height;
        Hill(int x, int y, int radius, int height) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.height = height;
        }
    }
}
