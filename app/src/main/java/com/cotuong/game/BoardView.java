package com.cotuong.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.cotuong.engine.Position;

/**
 * Vẽ bàn cờ + quân cờ. Tự động dùng ảnh PNG của bạn nếu có trong res/drawable-nodpi,
 * nếu thiếu ảnh nào thì vẽ tạm bằng Canvas để game vẫn chạy được.
 */
public class BoardView extends View {

    public interface Listener {
        void onUserMove(int from, int to);
    }

    // ======= CHỈNH CHO KHỚP ẢNH board.png CỦA BẠN =======
    // Tọa độ PIXEL (trong chính file board.png) của đường kẻ ngoài cùng:
    //   GRID_LEFT/RIGHT: x của đường dọc ngoài cùng bên trái/phải (cột 0 và cột 8)
    //   GRID_TOP/BOTTOM: y của đường ngang trên cùng/dưới cùng (hàng 0 và hàng 9)
    // Để -1 nghĩa là tự tính (giả định lề bằng đúng nửa ô cờ).
    static final float GRID_LEFT = -1, GRID_TOP = -1, GRID_RIGHT = -1, GRID_BOTTOM = -1;
    // Quân cờ chiếm bao nhiêu phần của một ô (1.0 = vừa khít ô).
    static final float PIECE_SCALE = 0.92f;
    // =====================================================

    private static final String[] LETTER = {"", "k", "a", "b", "n", "r", "c", "p"};
    private static final String CH_RED = " 帥仕相傌俥炮兵";   // dự phòng khi thiếu ảnh
    private static final String CH_BLACK = " 將士象馬車砲卒";

    private Position pos;
    private Listener listener;
    private boolean inputEnabled = false;
    private int selected = -1, lastFrom = -1, lastTo = -1;
    private final int[] targets = new int[24];
    private int targetCount = 0;

    private Bitmap boardBmp;
    private final Bitmap[] pieceBmp = new Bitmap[15]; // chỉ số = quân + 7
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    // hình học bàn cờ (tính lại mỗi lần vẽ)
    private float left, top, scale, gx0, gy0, stepX, stepY;

    public BoardView(Context ctx) {
        super(ctx);
        boardBmp = load(ctx, "board");
        for (int t = 1; t <= 7; t++) {
            pieceBmp[7 + t] = load(ctx, "r_" + LETTER[t]);
            pieceBmp[7 - t] = load(ctx, "b_" + LETTER[t]);
        }
    }

    private static Bitmap load(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        if (id == 0) return null;
        try {
            return BitmapFactory.decodeResource(ctx.getResources(), id);
        } catch (Throwable e) {
            return null;
        }
    }

    public void setPosition(Position p) { pos = p; clearSelection(); lastFrom = lastTo = -1; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setInputEnabled(boolean e) { inputEnabled = e; if (!e) clearSelection(); invalidate(); }
    public void setLastMove(int from, int to) { lastFrom = from; lastTo = to; invalidate(); }
    public void clearSelection() { selected = -1; targetCount = 0; }

    private void computeGeometry() {
        float iw = boardBmp != null ? boardBmp.getWidth() : 900f;
        float ih = boardBmp != null ? boardBmp.getHeight() : 1000f;
        scale = Math.min(getWidth() / iw, getHeight() / ih);
        left = (getWidth() - iw * scale) / 2f;
        top = (getHeight() - ih * scale) / 2f;
        float x0 = GRID_LEFT >= 0 ? GRID_LEFT : iw / 18f;
        float x1 = GRID_RIGHT >= 0 ? GRID_RIGHT : iw - iw / 18f;
        float y0 = GRID_TOP >= 0 ? GRID_TOP : ih / 20f;
        float y1 = GRID_BOTTOM >= 0 ? GRID_BOTTOM : ih - ih / 20f;
        gx0 = x0; gy0 = y0;
        stepX = (x1 - x0) / 8f;
        stepY = (y1 - y0) / 9f;
    }

    private float px(int c) { return left + (gx0 + c * stepX) * scale; }
    private float py(int r) { return top + (gy0 + r * stepY) * scale; }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pos == null) return;
        computeGeometry();
        drawBoard(canvas);
        float cell = Math.min(stepX, stepY) * scale;

        if (lastFrom >= 0) { // đánh dấu nước vừa đi
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, cell * 0.05f));
            paint.setColor(Color.rgb(40, 160, 40));
            for (int s : new int[]{lastFrom, lastTo}) {
                float x = px(s % 9), y = py(s / 9), h = cell * 0.5f;
                canvas.drawRect(x - h, y - h, x + h, y + h, paint);
            }
        }
        for (int s = 0; s < 90; s++) {
            int p = pos.sq[s];
            if (p != 0) drawPiece(canvas, p, px(s % 9), py(s / 9), cell);
        }
        if (selected >= 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, cell * 0.07f));
            paint.setColor(Color.rgb(255, 200, 0));
            canvas.drawCircle(px(selected % 9), py(selected / 9), cell * 0.48f, paint);
            for (int i = 0; i < targetCount; i++) {
                int t = targets[i];
                float x = px(t % 9), y = py(t / 9);
                if (pos.sq[t] != 0) {
                    paint.setColor(Color.rgb(220, 30, 30));
                    canvas.drawCircle(x, y, cell * 0.48f, paint);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.argb(190, 30, 110, 230));
                    canvas.drawCircle(x, y, cell * 0.13f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
        }
    }

    private void drawBoard(Canvas canvas) {
        if (boardBmp != null) {
            float iw = boardBmp.getWidth(), ih = boardBmp.getHeight();
            dst.set(left, top, left + iw * scale, top + ih * scale);
            canvas.drawBitmap(boardBmp, null, dst, paint);
            return;
        }
        // Dự phòng: tự vẽ bàn cờ khi chưa có board.png
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(236, 201, 140));
        canvas.drawRect(left, top, left + 900 * scale, top + 1000 * scale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.rgb(60, 35, 10));
        paint.setStrokeWidth(Math.max(1.5f, scale * 3));
        for (int r = 0; r < 10; r++) canvas.drawLine(px(0), py(r), px(8), py(r), paint);
        for (int c = 0; c < 9; c++) {
            if (c == 0 || c == 8) canvas.drawLine(px(c), py(0), px(c), py(9), paint);
            else {
                canvas.drawLine(px(c), py(0), px(c), py(4), paint);
                canvas.drawLine(px(c), py(5), px(c), py(9), paint);
            }
        }
        canvas.drawLine(px(3), py(0), px(5), py(2), paint);
        canvas.drawLine(px(5), py(0), px(3), py(2), paint);
        canvas.drawLine(px(3), py(7), px(5), py(9), paint);
        canvas.drawLine(px(5), py(7), px(3), py(9), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(stepY * scale * 0.55f);
        canvas.drawText("楚河        漢界", px(4), py(4) + stepY * scale * 0.75f, paint);
    }

    private void drawPiece(Canvas canvas, int p, float x, float y, float cell) {
        float size = cell * PIECE_SCALE;
        Bitmap b = pieceBmp[p + 7];
        if (b != null) {
            src.set(0, 0, b.getWidth(), b.getHeight());
            dst.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
            canvas.drawBitmap(b, src, dst, paint);
            return;
        }
        int type = p > 0 ? p : -p;
        int color = p > 0 ? Color.rgb(200, 20, 20) : Color.rgb(20, 20, 20);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(250, 235, 200));
        canvas.drawCircle(x, y, size / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, size * 0.05f));
        paint.setColor(color);
        canvas.drawCircle(x, y, size / 2, paint);
        canvas.drawCircle(x, y, size * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * 0.55f);
        String ch = String.valueOf((p > 0 ? CH_RED : CH_BLACK).charAt(type));
        canvas.drawText(ch, x, y + size * 0.19f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || !inputEnabled || pos == null) return true;
        computeGeometry();
        int c = Math.round((e.getX() - left) / scale / stepX - gx0 / stepX);
        int r = Math.round((e.getY() - top) / scale / stepY - gy0 / stepY);
        if (r < 0 || r > 9 || c < 0 || c > 8) return true;
        int idx = r * 9 + c;
        int p = pos.sq[idx];
        boolean own = p != 0 && (p > 0) == pos.redToMove;
        boolean isTarget = false;
        for (int i = 0; i < targetCount; i++) if (targets[i] == idx) isTarget = true;

        if (selected >= 0 && isTarget) {
            int from = selected;
            clearSelection();
            invalidate();
            if (listener != null) listener.onUserMove(from, idx);
        } else if (own) {
            selected = idx;
            targetCount = pos.legalDestinations(idx, targets);
            invalidate();
        } else {
            clearSelection();
            invalidate();
        }
        return true;
    }
}
