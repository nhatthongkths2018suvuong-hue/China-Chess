package com.cotuong.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cotuong.engine.Engine;
import com.cotuong.engine.Position;

public class GameActivity extends Activity implements BoardView.Listener {

    // Cấu hình độ khó: {độ sâu tối đa, thời gian suy nghĩ tối đa (ms), độ nhiễu ngẫu nhiên}
    // Độ nhiễu tính theo thang điểm quân: 100 = một con tốt, 900 = một con xe.
    private static final int[][] LEVELS = {
            {2, 1500, 300},   // dễ: nhìn nông, hay đi sai
            {8, 3000, 0},     // vừa
            {20, 8000, 0}};   // khó: nghĩ tối đa 8 giây/nước

    private int mode;            // 0..2 = chơi với máy, 3 = hai người
    // Khoảng cách (dp) từ mép trên màn hình đến khung bàn cờ. Tăng số này nếu muốn bàn cờ thấp xuống nữa.
    private static final int BOARD_TOP_GAP_PORTRAIT_DP = 32, BOARD_TOP_GAP_LANDSCAPE_DP = 8;
    // Hòa nếu có chừng này nước đi liên tiếp (mỗi bên đi một nước tính là 2) mà không quân nào bị ăn.
    private static final int NO_CAPTURE_DRAW_PLIES = 50;
    // Chế độ hai người: xoay quân Đen ngược lại để người ngồi phía đối diện nhìn thuận chiều. Đặt false để tắt.
    private static final boolean ROTATE_BLACK_IN_TWO_PLAYER = true;
    // Chiều ngang (dp) của hộp thoại kết thúc ván khi cầm máy ngang. Giảm số này nếu vẫn thấy che bàn cờ.
    private static final int END_DIALOG_WIDTH_LANDSCAPE_DP = 210;
    private boolean vsAi;
    private boolean humanRed = true; // khi chơi với máy: người chơi cầm quân Đỏ (đi trước) hay Đen (đi sau)
    // Khi người chơi cầm quân Đen thì lật bàn cờ để quân của mình nằm phía dưới. Đặt false để tắt.
    private static final boolean FLIP_WHEN_BLACK = true;
    private boolean timed;
    private long redMs, blackMs, initialMs;

    private Position pos = new Position();
    private Engine engine = new Engine();
    private BoardView board;
    private TextView blackLabel, redLabel, status;
    private LinearLayout root, panel;
    private Button undoBtn;

    private boolean over = false, thinking = false, destroyed = false;
    private int gameGen = 0;
    private long lastTick;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mode = getIntent().getIntExtra("mode", 0);
        vsAi = mode < 3;
        humanRed = !vsAi || getIntent().getBooleanExtra("playerFirst", true);
        int minutes = getIntent().getIntExtra("minutes", 0);
        timed = minutes > 0;
        initialMs = minutes * 60000L;

        root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(243, 230, 200));
        board = new BoardView(this);
        board.setListener(this);
        root.addView(board, new LinearLayout.LayoutParams(0, 0, 1f));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));
        blackLabel = label(24);
        status = label(18);
        redLabel = label(24);
        panel.addView(blackLabel, lp());
        panel.addView(status, lp());
        panel.addView(redLabel, lp());

        // 4 nút chức năng xếp lưới 2x2
        undoBtn = button("Đi lại", new View.OnClickListener() {
            @Override public void onClick(View v) { undo(); }
        });
        LinearLayout row1 = new LinearLayout(this);
        row1.addView(undoBtn, cellLp());
        row1.addView(button("Ván mới", new View.OnClickListener() {
            @Override public void onClick(View v) { newGame(); }
        }), cellLp());
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("Căn bàn cờ", new View.OnClickListener() {
            @Override public void onClick(View v) { calibrateMenu(); }
        }), cellLp());
        row2.addView(button("Menu", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), cellLp());
        panel.addView(row1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(row2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(panel);
        setContentView(root);
        applyOrientation();
        newGame();
    }

    private void applyOrientation() {
        boolean land = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        // khung bàn cờ: chiếm hết chiều ngang (dọc), chừa một khoảng nhỏ phía trên cho khỏi sát mép
        LinearLayout.LayoutParams blp = land ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(land ? BOARD_TOP_GAP_LANDSCAPE_DP : BOARD_TOP_GAP_PORTRAIT_DP);
        board.setLayoutParams(blp);
        panel.setLayoutParams(land ? new LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation();
    }

    // ---------------- Ván cờ ----------------

    private void newGame() {
        gameGen++;
        engine = new Engine();
        // Mức dễ không biết luật hòa 50 nước; mức vừa và khó biết và biết tận dụng.
        engine.setNoCaptureLimit(mode >= 1 ? NO_CAPTURE_DRAW_PLIES : 0);
        pos = new Position();
        board.setPosition(pos);
        board.setRotateBlack(!vsAi && ROTATE_BLACK_IN_TWO_PLAYER);
        over = false;
        thinking = false;
        redMs = blackMs = initialMs;
        lastTick = SystemClock.elapsedRealtime();
        board.setFlipped(vsAi && !humanRed && FLIP_WHEN_BLACK);
        setStatus("Lượt Đỏ");
        updateClocks();
        if (vsAi && !humanRed) startAi(); // máy cầm quân Đỏ nên đi trước
        else board.setInputEnabled(true);
    }

    @Override
    public void onUserMove(int from, int to) {
        if (over || thinking) return;
        applyMove(from | (to << 8));
    }

    private void applyMove(int m) {
        pos.makeMove(m);
        board.setLastMove(m & 255, m >> 8);
        board.clearSelection();

        int[] buf = new int[200];
        int n = pos.genLegal(buf);
        boolean chk = pos.inCheck(pos.redToMove);
        if (n == 0) { // hết nước đi (bị chiếu bí hoặc bị bí nước) = thua
            endGame((pos.redToMove ? "Đen" : "Đỏ") + " thắng!" + (chk ? "\n(Chiếu bí)" : "\n(Đối phương hết nước đi)"));
            return;
        }
        if (pos.repetitions() >= 2) {
            endGame("Hòa!\n(Thế cờ lặp lại 3 lần)");
            return;
        }
        if (pos.quietPlies() >= NO_CAPTURE_DRAW_PLIES) {
            endGame("Hòa!\n(" + NO_CAPTURE_DRAW_PLIES + " nước liên tiếp không có quân nào bị ăn)");
            return;
        }
        setStatus((chk ? "CHIẾU TƯỚNG! " : "") + (pos.redToMove ? "Lượt Đỏ" : "Lượt Đen"));
        updateClocks();
        if (vsAi && pos.redToMove != humanRed) startAi();
        else board.setInputEnabled(true);
    }

    private void startAi() {
        thinking = true;
        board.setInputEnabled(false);
        setStatus("Máy đang suy nghĩ…");
        final int gen = gameGen;
        final Engine eng = engine;
        final Position copy = pos.copy();
        final int[] lv = LEVELS[mode];
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int m = eng.think(copy, lv[0], lv[1], lv[2]);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || gen != gameGen || over) return;
                        thinking = false;
                        if (m != 0) applyMove(m);
                    }
                });
            }
        }).start();
    }

    private void calibrateMenu() {
        if (thinking) return;
        new AlertDialog.Builder(this)
                .setTitle("Căn lưới bàn cờ cho khớp ảnh")
                .setItems(new CharSequence[]{"Căn bằng 2 cú chạm", "Về mặc định"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) board.startCalibration();
                                else board.resetCalibration();
                            }
                        })
                .show();
    }

    @Override
    public void onHint(String message) {
        if (message != null) setStatus(message);
        else if (!over) setStatus(thinking ? "Máy đang suy nghĩ…" : (pos.redToMove ? "Lượt Đỏ" : "Lượt Đen"));
    }

    private void undo() {
        if (over || thinking) return;
        int times = vsAi ? 2 : 1; // chơi với máy: đi lại cả nước của máy lẫn nước của mình
        if (pos.sp < times) return;
        for (int i = 0; i < times; i++) pos.undoMove();
        board.setPosition(pos);
        setStatus(pos.redToMove ? "Lượt Đỏ" : "Lượt Đen");
        board.setInputEnabled(true);
    }

    private void endGame(String msg) {
        over = true;
        thinking = false;
        board.setInputEnabled(false);
        setStatus(msg.replace("\n", " "));
        updateClocks();
        if (destroyed || isFinishing()) return;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Kết thúc ván cờ")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Ván mới", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { newGame(); }
                })
                .setNegativeButton("Menu", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .create();
        // Phải gọi show() TRƯỚC rồi mới chỉnh cửa sổ: nếu chỉnh trước, Android sẽ đặt lại kích thước,
        // vị trí và lớp làm tối nền về mặc định ngay khi hộp thoại hiện ra.
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // không làm tối bàn cờ
            boolean land = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0f;
            if (land) { // máy nằm ngang: thu nhỏ chiều ngang và đẩy sang bên phải, bàn cờ ở bên trái
                lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                lp.x = 0;
                lp.width = dp(END_DIALOG_WIDTH_LANDSCAPE_DP);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            } else {    // máy đứng: đẩy xuống sát đáy màn hình
                lp.gravity = Gravity.BOTTOM;
                lp.y = dp(8);
            }
            w.setAttributes(lp);
        }
    }

    // ---------------- Đồng hồ ----------------

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long dt = now - lastTick;
        lastTick = now;
        if (!over && timed) {
            if (pos.redToMove) redMs -= dt; else blackMs -= dt;
            if (redMs <= 0) { redMs = 0; updateClocks(); endGame("Đỏ hết giờ!\nĐen thắng."); return; }
            if (blackMs <= 0) { blackMs = 0; updateClocks(); endGame("Đen hết giờ!\nĐỏ thắng."); return; }
        }
        updateClocks();
    }

    private static String fmt(long ms) {
        long s = (ms + 999) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void updateClocks() {
        boolean redTurn = pos.redToMove && !over;
        boolean blackTurn = !pos.redToMove && !over;
        String redName = !vsAi ? "ĐỎ" : (humanRed ? "BẠN (ĐỎ)" : "MÁY (ĐỎ)");
        String blackName = !vsAi ? "ĐEN" : (humanRed ? "MÁY (ĐEN)" : "BẠN (ĐEN)");
        redLabel.setText(redName + (timed ? "   " + fmt(redMs) : ""));
        blackLabel.setText(blackName + (timed ? "   " + fmt(blackMs) : ""));
        redLabel.setBackgroundColor(redTurn ? Color.rgb(255, 215, 120) : Color.TRANSPARENT);
        blackLabel.setBackgroundColor(blackTurn ? Color.rgb(255, 215, 120) : Color.TRANSPARENT);
    }

    // ---------------- Vòng đời ----------------

    @Override protected void onResume() {
        super.onResume();
        lastTick = SystemClock.elapsedRealtime();
        handler.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        gameGen++;
    }

    // ---------------- Tiện ích giao diện ----------------

    private void setStatus(String s) { status.setText(s); }

    private TextView label(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(50, 30, 10));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(6);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
