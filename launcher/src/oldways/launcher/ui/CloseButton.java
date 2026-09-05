package oldways.launcher.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Крестик в углу карточки: закрывает её и возвращает на главный экран.
 *
 * Рисуется двумя линиями, а не значком из набора: крест — единственная
 * фигура, которую дешевле начертить, чем разбирать путь SVG.
 */
public class CloseButton extends JComponent {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private boolean hot;
    private ActionListener listener;

    public CloseButton(double scale) {
        this.scale = scale;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hot = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hot = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (listener != null) {
                    listener.actionPerformed(new ActionEvent(
                            CloseButton.this, ActionEvent.ACTION_PERFORMED, "close"));
                }
            }
        });
    }

    public void onClick(ActionListener listener) {
        this.listener = listener;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        if (hot) {   // тот же прирост, что и у прочих кнопок
            g.translate(getWidth() / 2.0, getHeight() / 2.0);
            g.scale(Theme.HOVER, Theme.HOVER);
            g.translate(-getWidth() / 2.0, -getHeight() / 2.0);
        }

        double arm = Math.min(getWidth(), getHeight()) * 0.30;
        double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
        g.setStroke(new BasicStroke((float) Math.max(1.4, 1.6 * scale),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(hot ? Color.WHITE : new Color(255, 255, 255, 150));
        g.drawLine((int) (cx - arm), (int) (cy - arm), (int) (cx + arm), (int) (cy + arm));
        g.drawLine((int) (cx + arm), (int) (cy - arm), (int) (cx - arm), (int) (cy + arm));
        g.dispose();
    }
}
