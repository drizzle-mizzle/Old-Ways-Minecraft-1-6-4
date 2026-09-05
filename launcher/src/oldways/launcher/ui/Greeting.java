package oldways.launcher.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Строка «Вы вошли как ник»: имя выделено насыщенным начертанием.
 *
 * Сделана отдельным компонентом, а не подписью с HTML: у наших шрифтов задан
 * разрядка через атрибуты, и Swing при разборе HTML их теряет.
 */
public class Greeting extends JComponent {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private String prefix = "";
    private String name = "";

    public Greeting(double scale) {
        this.scale = scale;
    }

    public void set(String prefix, String name) {
        this.prefix = prefix == null ? "" : prefix;
        this.name = name == null ? "" : name;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        java.awt.Font plain = Theme.font((float) (16 * scale), false);
        java.awt.Font heavy = Theme.font((float) (16 * scale), true);

        FontMetrics plainMetrics = g.getFontMetrics(plain);
        FontMetrics heavyMetrics = g.getFontMetrics(heavy);
        int width = plainMetrics.stringWidth(prefix) + heavyMetrics.stringWidth(name);
        int x = (getWidth() - width) / 2;
        int y = (getHeight() + plainMetrics.getAscent() - plainMetrics.getDescent()) / 2;

        g.setFont(plain);
        g.setColor(Theme.TEXT);
        g.drawString(prefix, x, y);
        g.setFont(heavy);
        g.setColor(Color.WHITE);
        g.drawString(name, x + plainMetrics.stringWidth(prefix), y);
        g.dispose();
    }
}
