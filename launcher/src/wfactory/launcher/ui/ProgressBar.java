package wfactory.launcher.ui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Тонкая полоса хода работ — в тон дорожке ползунка памяти.
 *
 * Неопределённый режим (когда объём заранее неизвестен: вход, распаковка,
 * патч клиента) показывает бегущий отрезок, чтобы окно не выглядело
 * замершим на долгих шагах.
 */
public class ProgressBar extends JComponent {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private final Timer timer;
    private double fraction;
    private boolean running;
    private int phase;

    public ProgressBar(double scale) {
        this.scale = scale;
        this.timer = new Timer(40, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                phase = (phase + 4) % 200;
                repaint();
            }
        });
    }

    public void setFraction(double value) {
        running = false;
        timer.stop();
        fraction = Math.max(0, Math.min(1, value));
        repaint();
    }

    public void setRunning(boolean value) {
        running = value;
        if (value) timer.start();
        else timer.stop();
        repaint();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible) timer.stop();
        else if (running) timer.start();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int height = Math.max(2, (int) Math.round(4 * scale));
        int top = (getHeight() - height) / 2;
        int width = getWidth();

        g.setColor(Theme.FIELD_BORDER);
        g.fillRoundRect(0, top, width, height, height, height);

        g.setColor(new Color(255, 255, 255, 190));
        if (running) {
            int span = width / 4;
            int x = (int) (width * (phase / 200.0)) - span;
            g.fillRoundRect(Math.max(0, x), top,
                    Math.min(span, width - Math.max(0, x)), height, height, height);
        } else {
            g.fillRoundRect(0, top, (int) Math.round(width * fraction), height, height, height);
        }
        g.dispose();
    }
}
