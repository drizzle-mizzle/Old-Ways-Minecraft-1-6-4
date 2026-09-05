package oldways.launcher.ui;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Матовая панель из макета: размытый фон под собой, тёмная заливка, световой
 * градиент и тонкая рамка.
 *
 * Порядок слоёв взят из CSS: сначала размытая подложка (backdrop-filter),
 * поверх неё сплошной цвет, а градиент — самым верхним, потому что в
 * сокращённой записи background первый слой лежит сверху.
 */
public class Glass extends JPanel {

    private static final long serialVersionUID = 1L;

    private final boolean frosted;
    private final Color fill;

    /** frosted=true — стекло с размытием (окно входа); false — плотное меню. */
    public Glass(boolean frosted) {
        this.frosted = frosted;
        this.fill = frosted ? Theme.GLASS_FILL : Theme.MENU_FILL;
        setOpaque(false);
        setLayout(null);
    }

    private double scale() {
        Background back = (Background) SwingUtilities.getAncestorOfClass(Background.class, this);
        return back == null ? 1.0 : back.scale();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        double scale = scale();
        int width = getWidth();
        int height = getHeight();
        int radius = (int) Math.round(7 * scale) * 2;   // CSS border-radius 7px

        Theme.shadow(g, 0, 0, width - 1, height - 1, radius, scale);

        RoundRectangle2D shape = new RoundRectangle2D.Float(
                0.5f, 0.5f, width - 1f, height - 1f, radius, radius);
        Graphics2D inside = (Graphics2D) g.create();
        inside.clip(shape);

        if (frosted) {
            Background back =
                    (Background) SwingUtilities.getAncestorOfClass(Background.class, this);
            BufferedImage blurred = back == null ? null : back.frosted();
            if (blurred != null) {
                java.awt.Point origin = SwingUtilities.convertPoint(this, 0, 0, back);
                inside.drawImage(blurred, -origin.x, -origin.y, null);
            }
        }

        inside.setColor(fill);
        inside.fillRect(0, 0, width, height);

        // linear-gradient(to bottom left, ...): свет идёт из правого верхнего угла
        inside.setPaint(new GradientPaint(width, 0, Theme.GLASS_TOP, 0, height, Theme.GLASS_BOTTOM));
        inside.fillRect(0, 0, width, height);
        inside.dispose();

        g.setColor(Theme.INNER_LIGHT);   // inset 0 1px 0 — блик по верхней кромке
        g.drawLine(radius / 3, 1, width - radius / 3, 1);
        g.setColor(Theme.BORDER);
        g.draw(shape);
        g.dispose();
    }
}
