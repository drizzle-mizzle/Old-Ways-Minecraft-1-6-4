package wfactory.launcher.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Ползунок выделенной памяти: дорожка в 4 точки и белый кружок, как в макете.
 *
 * Значение всегда кратно шагу — в мегабайтах у Java нет смысла в промежуточных
 * величинах, а игроку проще попадать в круглые числа.
 */
public class MemorySlider extends JComponent {

    private static final long serialVersionUID = 1L;

    private final int min;
    private final int max;
    private final int step;
    private final double scale;
    private int value;
    private ActionListener listener;

    public MemorySlider(int min, int max, int step, int value, double scale) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.scale = scale;
        this.value = snap(value);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pick(e.getX());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                pick(e.getX());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void onChange(ActionListener listener) {
        this.listener = listener;
    }

    public int value() {
        return value;
    }

    /** Подпись значения: до гигабайта в мегабайтах, дальше в гигабайтах. */
    public String text() {
        if (value < 1024) return value + " MB";
        double gb = value / 1024.0;
        return (gb == Math.floor(gb) ? String.valueOf((int) gb) : String.valueOf(gb)) + " GB";
    }

    private int snap(int raw) {
        int clamped = Math.max(min, Math.min(max, raw));
        return min + Math.round((clamped - min) / (float) step) * step;
    }

    private int thumbRadius() {
        return (int) Math.round(7 * scale);
    }

    private void pick(int x) {
        int r = thumbRadius();
        int span = Math.max(1, getWidth() - r * 2);
        double part = Math.max(0, Math.min(1, (x - r) / (double) span));
        int next = snap((int) Math.round(min + part * (max - min)));
        if (next == value) return;
        value = next;
        repaint();
        if (listener != null) {
            listener.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, text()));
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int r = thumbRadius();
        int trackHeight = Math.max(2, (int) Math.round(4 * scale));
        int middle = getHeight() / 2;
        int span = Math.max(1, getWidth() - r * 2);
        int filled = (int) Math.round(span * (value - min) / (double) (max - min));

        // дорожка ровная по всей длине, как в макете: слева от кружка её
        // не подсвечиваем
        g.setColor(Theme.FIELD_BORDER);
        g.fillRoundRect(r, middle - trackHeight / 2, span, trackHeight,
                trackHeight, trackHeight);

        g.setColor(Color.WHITE);
        g.fillOval(filled, middle - r, r * 2, r * 2);
        g.dispose();
    }
}
