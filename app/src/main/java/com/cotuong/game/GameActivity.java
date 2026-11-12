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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cotuong.engine.Engine;
import com.cotuong.engine.Position;

public class GameActivity extends Activity implements BoardView.Listener {

    // Cấu hình độ khó: {độ sâu tối đa, thời gian suy nghĩ (ms), độ nhiễu ngẫu nhiên}
    private static final int[][] LEVELS = {
            {2, 0, 60},       // dễ: nghĩ nông, hay đi sai
            {4, 3000, 0},     // vừa
            {10, 7000, 0}};   // khó: đào sâu tối đa 7 giây/nước

    private int mode;            // 0..2 = chơi với máy, 3 = hai người
    private boolean vsAi;
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
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        blackLabel = label(24);
        status = label(18);
        redLabel = label(24);
        panel.addView(blackLabel, lp());
        panel.addView(status, lp());
        panel.addView(redLabel, lp());

        undoBtn = button("Đi lại", new View.OnClickListener() {
            @Override public void onClick(View v) { undo(); }
        });
        panel.addView(undoBtn, lp());
        panel.addView(button("Ván mới", new View.OnClickListener() {
            @Override public void onClick(View v) { newGame(); }
        }), lp());
        panel.addView(button("Menu", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), lp());
        root.addView(panel);
        setContentView(root);
        applyOrientation();
        newGame();
    }

    private void applyOrientation() {
        boolean land = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        board.setLayoutParams(land ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
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
        pos = new Position();
        board.setPosition(pos);
        over = false;
        thinking = false;
        redMs = blackMs = initialMs;
        lastTick = SystemClock.elapsedRealtime();
        setStatus("Lượt Đỏ");
        updateClocks();
        board.setInputEnabled(true);
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
        setStatus((chk ? "CHIẾU TƯỚNG! " : "") + (pos.redToMove ? "Lượt Đỏ" : "Lượt Đen"));
        updateClocks();
        if (vsAi && !pos.redToMove) startAi();
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

    private void undo() {
        if (over || thinking || pos.sp == 0) return;
        int times = vsAi ? 2 : 1;
        for (int i = 0; i < times && pos.sp > 0; i++) pos.undoMove();
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
        new AlertDialog.Builder(this)
                .setTitle("Kết thúc ván cờ")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Ván mới", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { newGame(); }
                })
                .setNegativeButton("Menu", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .show();
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
        redLabel.setText("ĐỎ" + (timed ? "   " + fmt(redMs) : ""));
        blackLabel.setText((vsAi ? "MÁY (ĐEN)" : "ĐEN") + (timed ? "   " + fmt(blackMs) : ""));
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
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
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
