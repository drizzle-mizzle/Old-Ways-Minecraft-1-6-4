package wfactory.launcher.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Подложка окна: картинка на весь кадр и её размытая копия для стекла.
 *
 * Размытие считается один раз на размер окна и на тему, а не на каждую
 * перерисовку: коробочный фильтр по миллиону точек в цикле отрисовки дал бы
 * заметные подтормаживания при наборе текста.
 */
public class Background extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Color FALLBACK = new Color(0x6f8ea3);   // цвет фона из макета

    private boolean day;
    private BufferedImage scaled;
    private BufferedImage frosted;
    private int builtFor = -1;
    private boolean builtDay;

    public Background(boolean day) {
        this.day = day;
        setLayout(null);
        setOpaque(true);
    }

    public boolean isDay() {
        return day;
    }

    public void setDay(boolean day) {
        if (this.day == day) return;
        this.day = day;
        rebuild(true);
        repaint();
    }

    /** Во сколько раз окно отличается от макета 1280x800. */
    public double scale() {
        return getWidth() > 0 ? (double) getWidth() / Theme.DESIGN_W : 1.0;
    }

    /** Размер из макета в точках этого окна. */
    public int s(double design) {
        return (int) Math.round(design * scale());
    }

    public BufferedImage frosted() {
        return frosted;
    }

    private void rebuild(boolean force) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        int key = width * 31 + height;
        if (!force && key == builtFor && day == builtDay) return;
        scaled = Theme.cover(Theme.background(day), width, height);
        frosted = Theme.frost(scaled, Math.max(2, s(5)), 1.6);
        builtFor = key;
        builtDay = day;
    }

    @Override
    public void doLayout() {
        rebuild(false);
        layoutContent();
    }

    /** Наследник расставляет содержимое по пропорциям макета. */
    protected void layoutContent() {
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        rebuild(false);
        if (scaled != null) {
            g.drawImage(scaled, 0, 0, null);
        } else {
            g.setColor(FALLBACK);      // картинку не прочитали — хотя бы не белое
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        g.dispose();
    }
}
