package com.keepgen.hulibuld;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 一个 iOS 那种「拨动选择」的滚轮。没有第三方依赖，纯 Canvas + 惯性吸附。
 *
 * 为什么自己写：工程没有 Gradle，接一个 WheelView 库要手动解 AAR、把它的资源并进 aapt2，
 * 成本比写这一百多行还高；而且第三方库的滚动回调在中文机型上常有一两个像素的抖动。
 *
 * 交互要点（和 iOS 一致的用户直觉）：
 *   · 手指拖到哪，松手就吸到最近的一格
 *   · 中间的格子放大加粗变深，两侧渐小渐淡
 *   · 点击上下两侧的格子，直接跳过去
 */
final class Wheel extends View {

    interface OnPick { void onPick(int index); }

    private String[] items = new String[0];
    private int index;
    private final int itemH;
    private final int visible;          // 一次显示几格（奇数）
    private float drag;                 // 拖动中的像素偏移
    private boolean animating;

    private final Paint pBand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pItem = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF band = new RectF();
    private final int nearSize;         // 选中格上下相邻项的文字大小(px)
    private final int farSize;          // 更远项的文字大小(px)

    private OnPick onPick;

    Wheel(Context c, int itemHeightDp, int visibleCount) {
        super(c);
        itemH = Ui.dp(c, itemHeightDp);
        visible = visibleCount % 2 == 0 ? visibleCount + 1 : visibleCount;

        pBand.setStyle(Paint.Style.FILL);

        nearSize = Ui.dp(c, 18);
        farSize = Ui.dp(c, 16);
        pItem.setTextAlign(Paint.Align.CENTER);
        pItem.setTextSize(nearSize);
        pItem.setColor(Ui.TEXT_DIM);

        pSel.setTextAlign(Paint.Align.CENTER);
        pSel.setTextSize(Ui.dp(c, 24));
        pSel.setFakeBoldText(true);
        pSel.setColor(Ui.TEXT);
    }

    void setItems(String[] it, int start) {
        items = it == null ? new String[0] : it;
        index = Math.max(0, Math.min(it == null ? 0 : it.length - 1, start));
        invalidate();
    }

    void setOnPick(OnPick cb) { onPick = cb; }

    int selected() { return index; }

    String selectedText() {
        return (items.length > 0 && index >= 0 && index < items.length) ? items[index] : "";
    }

    @Override
    protected void onMeasure(int wspec, int hspec) {
        setMeasuredDimension(MeasureSpec.getSize(wspec), itemH * visible);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int cx = getWidth() / 2;
        int base = getHeight() / 2;

        // 选中带：浅色淡灰圆角高亮，深色淡白高亮，跟 iOS 日期滚轮一致。
        float inset = Ui.dp(getContext(), 10);
        float radius = Ui.dp(getContext(), 12);
        band.set(inset, base - itemH / 2f, getWidth() - inset, base + itemH / 2f);
        pBand.setColor(Ui.isDark() ? 0x26FFFFFF : 0x14000000);
        cv.drawRoundRect(band, radius, radius, pBand);

        int half = visible / 2;
        // 修复：以「中心浮点索引」为基准动态取项，拖动时中心连续变化，
        // 不再固定画 index±half（那样滑过两格就是空白）。
        float center = index - drag / itemH;
        int first = (int) Math.floor(center) - visible;
        int last  = (int) Math.ceil(center) + visible;
        for (int i = first; i <= last; i++) {
            if (i < 0 || i >= items.length) continue;
            float off = i - center;
            float y = base + off * itemH;
            if (y < -itemH || y > getHeight() + itemH) continue;
            float dist = Math.abs(off);
            Paint pt;
            if (dist < 0.5f) {
                pSel.setAlpha((int) (255 * Math.max(0.45f, 1f - dist)));
                pt = pSel;
            } else {
                pItem.setTextSize(dist < 1.6f ? nearSize : farSize);
                int alpha = (int) (255 * Math.max(0.12f, 1f - dist / (half + 1f)));
                pItem.setAlpha(alpha);
                pt = pItem;
            }
            cv.drawText(items[i], cx, y - (pt.descent() + pt.ascent()) / 2, pt);
        }
    }

    // ---- 手势 ----

    private float downY;
    private float lastY;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (items.length == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                drag = 0;
                animating = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - lastY;
                lastY = e.getY();
                drag += dy;
                if (index == 0 && drag > 0) drag *= 0.35f;
                if (index == items.length - 1 && drag < 0) drag *= 0.35f;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int moved = (int) Math.abs(e.getY() - downY);
                if (moved < Ui.dp(getContext(), 6)) {
                    float y = e.getY();
                    int center = getHeight() / 2;
                    if (y > center + itemH / 2f) step(1);
                    else if (y < center - itemH / 2f) step(-1);
                    return true;
                }
                int steps = Math.round(-drag / itemH);
                drag = 0;
                settleTo(index + steps);
                return true;
            }
        }
        return false;
    }

    private void step(int dir) { settleTo(index + dir); }

    /** 带一点减速地滑到目标格（自己驱动，不依赖 Scroller，省掉一套插值器） */
    private void settleTo(int target) {
        target = Math.max(0, Math.min(items.length - 1, target));
        final int from = index;
        final int to = target;
        if (from == to) { invalidate(); return; }
        animating = true;
        final long t0 = System.currentTimeMillis();
        final long dur = 160L;
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            public void run() {
                float t = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                float eased = 1 - (1 - t) * (1 - t);
                drag = (from + (to - from) * eased - index) * -itemH;
                invalidate();
                if (t >= 1f) {
                    index = to;
                    drag = 0;
                    animating = false;
                    invalidate();
                    if (onPick != null) onPick.onPick(index);
                    return;
                }
                postDelayed(tick[0], 16);
            }
        };
        post(tick[0]);
    }
}
