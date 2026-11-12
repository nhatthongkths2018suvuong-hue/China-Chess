package com.cotuong.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.cotuong.engine.Position;

import java.io.File;

/**
 * Vẽ bàn cờ + quân cờ.
 * Thứ tự tìm ảnh: (1) thư mục ảnh trên máy  ->  (2) ảnh đóng gói trong APK  ->  (3) tự vẽ tạm.
 * Thư mục ảnh trên máy: Android/data/com.cotuong.game/files/  (tên file: board.png, r_k.png, b_p.png ...)
 */
public class BoardView extends View {

    public interface Listener {
        void onUserMove(int from, int to);

        /** Gợi ý khi căn bàn cờ; null = quay về trạng thái bình thường. */
        void onHint(String message);
    }

    // Quân cờ chiếm bao nhiêu phần của một ô (1.0 = vừa khít ô).
    static final float PIECE_SCALE = 0.92f;
    // true: ép các ô cờ thành ô VUÔNG (ảnh bàn cờ sẽ bị co/giãn nhẹ theo một chiều nếu lưới của nó không vuông).
    // false: giữ nguyên tỷ lệ ảnh gốc.
    static final boolean FORCE_SQUARE_CELLS = true;
    // Chỉ dùng nếu muốn gán cứng lưới thay vì căn trong game (pixel trong board.png, -1 = bỏ qua).
    static final float GRID_LEFT = -1, GRID_TOP = -1, GRID_RIGHT = -1, GRID_BOTTOM = -1;

    private static final String[] LETTER = {"", "k", "a", "b", "n", "r", "c", "p"};
    private static final String CH_RED = " 帥仕相傌俥炮兵";   // dự phòng khi thiếu ảnh
    private static final String CH_BLACK = " 將士象馬車砲卒";

    private Position pos;
    private Listener listener;
    private boolean inputEnabled = false, flipped = false;
    private int selected = -1, lastFrom = -1, lastTo = -1;
    private final int[] targets = new int[24];
    private int targetCount = 0;

    private final File extDir;
    private Bitmap boardBmp;
    private String boardSig = "none";
    private final Bitmap[] pieceBmp = new Bitmap[15]; // chỉ số = quân + 7
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    // hình học (tính lại mỗi lần vẽ). gx0, gy0, stepX, stepY: tính theo pixel của ảnh; sx, sy: tỷ lệ phóng ảnh ra màn hình
    private float imgW, imgH, left, top, sx, sy, gx0, gy0, stepX, stepY, cell;

    // lưới do người dùng căn bằng 2 cú chạm (pixel trong ảnh bàn cờ), -1 = chưa căn
    private final SharedPreferences prefs;
    private float calX0 = -1, calY0 = -1, calX1 = -1, calY1 = -1;
    private boolean calibrating = false;
    private int calStep = 0;
    private float tapX, tapY;
    private long guideUntil = 0;

    public BoardView(Context ctx) {
        super(ctx);
        extDir = ctx.getExternalFilesDir(null); // gọi hàm này sẽ tạo luôn thư mục ảnh
        // bàn cờ
        File bf = findFile("board");
        if (bf != null) {
            boardBmp = decode(bf, 2048);
            if (boardBmp != null) boardSig = "ext:" + bf.length() + ":" + bf.lastModified();
        }
        if (boardBmp == null) {
            boardBmp = loadRes(ctx, "board");
            boardSig = boardBmp != null ? "res:" + boardBmp.getWidth() + "x" + boardBmp.getHeight() : "none";
        }
        // quân cờ
        for (int t = 1; t <= 7; t++) {
            pieceBmp[7 + t] = loadPiece(ctx, "r_" + LETTER[t]);
            pieceBmp[7 - t] = loadPiece(ctx, "b_" + LETTER[t]);
        }
        // giá trị căn: chỉ dùng nếu ảnh bàn cờ không đổi so với lúc căn
        prefs = ctx.getSharedPreferences("cotuong", Context.MODE_PRIVATE);
        float a = prefs.getFloat("gx0", -1), b = prefs.getFloat("gy0", -1);
        float c = prefs.getFloat("gx1", -1), d = prefs.getFloat("gy1", -1);
        if (boardSig.equals(prefs.getString("sig", "")) && a >= 0 && b >= 0 && c > a && d > b) {
            calX0 = a; calY0 = b; calX1 = c; calY1 = d;
        }
    }

    // ---------- Nạp ảnh ----------
    private File findFile(String name) {
        if (extDir == null) return null;
        String[] exts = {".png", ".PNG", ".jpg", ".jpeg"};
        for (String e : exts) {
            File f = new File(extDir, name + e);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static Bitmap decode(File f, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap loadRes(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        if (id == 0) return null;
        try {
            return BitmapFactory.decodeResource(ctx.getResources(), id);
        } catch (Throwable e) {
            return null;
        }
    }

    private Bitmap loadPiece(Context ctx, String name) {
        File f = findFile(name);
        if (f != null) {
            Bitmap b = decode(f, 400);
            if (b != null) return b;
        }
        return loadRes(ctx, name);
    }

    // ---------- Điều khiển từ Activity ----------
    public void setPosition(Position p) { pos = p; clearSelection(); lastFrom = lastTo = -1; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setInputEnabled(boolean e) { inputEnabled = e; if (!e) clearSelection(); invalidate(); }
    public void setLastMove(int from, int to) { lastFrom = from; lastTo = to; invalidate(); }
    public void setFlipped(boolean f) { flipped = f; invalidate(); }
    public void clearSelection() { selected = -1; targetCount = 0; }

    // ---------- Căn bàn cờ ----------
    public void startCalibration() {
        calibrating = true;
        calStep = 0;
        clearSelection();
        if (listener != null) listener.onHint("CĂN BÀN CỜ: chạm vào giao điểm GÓC TRÊN-TRÁI của lưới (hàng trên cùng, cột ngoài cùng bên trái)");
        invalidate();
    }

    public void resetCalibration() {
        calX0 = calY0 = calX1 = calY1 = -1;
        prefs.edit().remove("gx0").remove("gy0").remove("gx1").remove("gy1").remove("sig").apply();
        calibrating = false;
        showGuide();
        invalidate();
    }

    private void showGuide() {
        guideUntil = SystemClock.uptimeMillis() + 4000;
        postInvalidateDelayed(4100);
    }

    private void handleCalibrationTap(float x, float y) {
        computeGeometry();
        float ix = (x - left) / sx, iy = (y - top) / sy;
        if (calStep == 0) {
            tapX = ix;
            tapY = iy;
            calStep = 1;
            if (listener != null) listener.onHint("Tiếp theo: chạm vào giao điểm GÓC DƯỚI-PHẢI (hàng dưới cùng, cột ngoài cùng bên phải)");
            invalidate();
            return;
        }
        float x0 = Math.min(tapX, ix), x1 = Math.max(tapX, ix);
        float y0 = Math.min(tapY, iy), y1 = Math.max(tapY, iy);
        if (x1 - x0 < 50 || y1 - y0 < 50) {
            calStep = 0;
            if (listener != null) listener.onHint("Hai điểm quá gần nhau. Chạm lại từ GÓC TRÊN-TRÁI");
            invalidate();
            return;
        }
        calX0 = x0; calY0 = y0; calX1 = x1; calY1 = y1;
        prefs.edit().putFloat("gx0", x0).putFloat("gy0", y0).putFloat("gx1", x1).putFloat("gy1", y1)
                .putString("sig", boardSig).apply();
        calibrating = false;
        showGuide();
        if (listener != null) listener.onHint(null);
        invalidate();
    }

    // ---------- Hình học ----------
    private void computeGeometry() {
        imgW = boardBmp != null ? boardBmp.getWidth() : 900f;
        imgH = boardBmp != null ? boardBmp.getHeight() : 1000f;
        float x0 = calX0 >= 0 ? calX0 : (GRID_LEFT >= 0 ? GRID_LEFT : imgW / 18f);
        float x1 = calX1 >= 0 ? calX1 : (GRID_RIGHT >= 0 ? GRID_RIGHT : imgW - imgW / 18f);
        float y0 = calY0 >= 0 ? calY0 : (GRID_TOP >= 0 ? GRID_TOP : imgH / 20f);
        float y1 = calY1 >= 0 ? calY1 : (GRID_BOTTOM >= 0 ? GRID_BOTTOM : imgH - imgH / 20f);
        gx0 = x0;
        gy0 = y0;
        stepX = (x1 - x0) / 8f;
        stepY = (y1 - y0) / 9f;
        if (FORCE_SQUARE_CELLS) {
            // chọn cạnh ô s (pixel màn hình) lớn nhất sao cho cả ảnh vẫn nằm vừa trong view
            float s = Math.min(getWidth() * stepX / imgW, getHeight() * stepY / imgH);
            sx = s / stepX;
            sy = s / stepY;
        } else {
            sx = sy = Math.min(getWidth() / imgW, getHeight() / imgH);
        }
        left = (getWidth() - imgW * sx) / 2f;
        top = (getHeight() - imgH * sy) / 2f;
        cell = Math.min(stepX * sx, stepY * sy);
    }

    private float px(int c) { return left + (gx0 + (flipped ? 8 - c : c) * stepX) * sx; }
    private float py(int r) { return top + (gy0 + (flipped ? 9 - r : r) * stepY) * sy; }

    // ---------- Vẽ ----------
    @Override
    protected void onDraw(Canvas canvas) {
        if (pos == null) return;
        computeGeometry();
        drawBoard(canvas);

        for (int s = 0; s < 90; s++) {
            int p = pos.sq[s];
            if (p != 0) drawPiece(canvas, p, px(s % 9), py(s / 9));
        }
        if (lastFrom >= 0) { // đánh dấu nước vừa đi bằng chấm xanh lá (ở ô đi và ô đến), vẽ đè lên quân cờ
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(230, 30, 170, 30));
            for (int s : new int[]{lastFrom, lastTo}) canvas.drawCircle(px(s % 9), py(s / 9), cell * 0.13f, paint);
        }
        if (calibrating || SystemClock.uptimeMillis() < guideUntil) drawGuide(canvas);
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

    private void drawGuide(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(230, 255, 0, 255));
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) canvas.drawCircle(px(c), py(r), Math.max(3f, cell * 0.07f), paint);
        if (calibrating && calStep == 1) {
            float x = left + tapX * sx, y = top + tapY * sy, h = cell * 0.3f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, cell * 0.05f));
            paint.setColor(Color.rgb(0, 150, 255));
            canvas.drawLine(x - h, y, x + h, y, paint);
            canvas.drawLine(x, y - h, x, y + h, paint);
        }
    }

    private void drawBoard(Canvas canvas) {
        if (boardBmp != null) {
            dst.set(left, top, left + imgW * sx, top + imgH * sy);
            canvas.drawBitmap(boardBmp, null, dst, paint);
            return;
        }
        // Dự phòng: tự vẽ bàn cờ khi chưa có ảnh bàn cờ
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(236, 201, 140));
        canvas.drawRect(left, top, left + imgW * sx, top + imgH * sy, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.rgb(60, 35, 10));
        paint.setStrokeWidth(Math.max(1.5f, cell * 0.04f));
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
        paint.setTextSize(cell * 0.55f);
        canvas.drawText("楚河        漢界", px(4), (py(4) + py(5)) / 2f + cell * 0.2f, paint);
    }

    private void drawPiece(Canvas canvas, int p, float x, float y) {
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

    // ---------- Cảm ứng ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (calibrating) {
            if (e.getAction() == MotionEvent.ACTION_UP) handleCalibrationTap(e.getX(), e.getY());
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || !inputEnabled || pos == null) return true;
        computeGeometry();
        int c = Math.round(((e.getX() - left) / sx - gx0) / stepX);
        int r = Math.round(((e.getY() - top) / sy - gy0) / stepY);
        if (r < 0 || r > 9 || c < 0 || c > 8) return true;
        if (flipped) { r = 9 - r; c = 8 - c; }
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
