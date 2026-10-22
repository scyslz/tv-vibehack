package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style lighter band behind the first (dock) row.
 *
 * <p>The dock is the first row of the same list as the app tiles, so its band is faded out as soon
 * as the user moves down into the app rows: at that point the row stops looking like a dock and the
 * screen shows a plain two-row grid. See {@link MainActivity#setExpanded(boolean, boolean)}.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float alpha = 1f;

    DockDecoration(float density) {
        this.density = density;
        band.setColor(0x5C0E1014);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x24FFFFFF);
    }

    /** 1 = full dock band, 0 = no band at all (the row is just a grid row). */
    void setAlpha(float value) {
        alpha = Math.max(0f, Math.min(1f, value));
    }

    float getAlpha() {
        return alpha;
    }

    private int dockCount(RecyclerView parent) {
        RecyclerView.Adapter<?> a = parent.getAdapter();
        return a instanceof AppAdapter ? ((AppAdapter) a).dockCount() : 0;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (alpha < 0.01f) return;
        int dockCount = dockCount(parent);
        if (dockCount == 0) return;

        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockCount) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;

        // Must stay inside the row's own 8dp padding, otherwise the band needs extra window height
        // and the next row would peek out under the dock.
        float pad = 8 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;

        band.setAlpha((int) (0x5C * alpha));
        edge.setAlpha((int) (0x24 * alpha));
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
