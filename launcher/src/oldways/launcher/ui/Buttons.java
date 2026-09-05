package oldways.launcher.ui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/** Кнопки макета: полупрозрачная заливка, скругление 5, подсветка под курсором. */
public class Buttons extends JButton {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private final float fontSize;
    private final boolean heavy;

    public Buttons(String text, double scale, float fontSize, boolean heavy) {
        super(text);
        this.scale = scale;
        this.fontSize = fontSize;
        this.heavy = heavy;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setForeground(Color.WHITE);
        setFont(Theme.font((float) (fontSize * scale), heavy));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Главная кнопка входа: та же отделка, но крупнее и жирнее. */
    public static Buttons primary(String text, double scale) {
        return new Buttons(text, scale, 15f, true);
    }

    /** Второстепенная кнопка: мельче, как подписи в меню настроек. */
    public static Buttons small(String text, double scale) {
        return new Buttons(text, scale, 12f, false);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int padY = (int) Math.round((heavy ? 9 : 7) * scale);
        int padX = (int) Math.round((heavy ? 7 : 12) * scale);
        return new Dimension(metrics.stringWidth(getText()) + padX * 2,
                metrics.getHeight() + padY * 2);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int radius = (int) Math.round(5 * scale) * 2;
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, radius, radius);

        boolean hot = getModel().isRollover() || getModel().isPressed();
        g.setColor(!isEnabled() ? Theme.CHOICE_FILL
                : (hot ? Theme.BUTTON_HOVER : Theme.BUTTON_FILL));
        g.fill(shape);
        g.setColor(isEnabled() ? Theme.BUTTON_BORDER : Theme.FIELD_BORDER);
        g.draw(shape);

        g.setFont(getFont());
        g.setColor(isEnabled() ? Color.WHITE : Theme.TEXT_DIM);
        FontMetrics metrics = g.getFontMetrics();
        int x = (getWidth() - metrics.stringWidth(getText())) / 2;
        int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
        g.drawString(getText(), x, y);
        g.dispose();
    }
}
