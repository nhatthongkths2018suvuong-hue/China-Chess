package com.cotuong.game;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int[] TIME_MIN = {0, 5, 10, 15, 30};
    private static final String[] TIME_LABEL = {
            "Không giới hạn thời gian", "5 phút mỗi bên", "10 phút mỗi bên",
            "15 phút mỗi bên", "30 phút mỗi bên"};
    private Spinner timeSpinner;
    private CheckBox firstBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(243, 230, 200));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("CỜ TƯỚNG");
        title.setTextSize(40);
        title.setTextColor(Color.rgb(150, 20, 20));
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(0, 24));

        root.addView(modeButton("Chơi dễ", 0), lp(dp(320), 10));
        root.addView(modeButton("Chơi vừa", 1), lp(dp(320), 10));
        root.addView(modeButton("Chơi khó", 2), lp(dp(320), 10));
        root.addView(modeButton("Hai người chơi", 3), lp(dp(320), 10));

        firstBox = new CheckBox(this);
        firstBox.setText("Đi trước (cầm quân Đỏ)");
        firstBox.setTextSize(18);
        firstBox.setTextColor(Color.rgb(60, 40, 20));
        firstBox.setChecked(true);
        root.addView(firstBox, lp(0, 10));

        TextView lbl = new TextView(this);
        lbl.setText("Giới hạn thời gian (ai hết giờ trước sẽ thua):");
        lbl.setTextColor(Color.rgb(60, 40, 20));
        lbl.setGravity(Gravity.CENTER);
        root.addView(lbl, lp(0, 4));

        timeSpinner = new Spinner(this);
        timeSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, TIME_LABEL));
        root.addView(timeSpinner, lp(dp(320), 10));

        java.io.File dir = getExternalFilesDir(null); // tạo thư mục ảnh nếu chưa có
        TextView path = new TextView(this);
        path.setText("Thư mục ảnh tùy chỉnh (chép board.png, r_k.png, b_k.png... vào đây):\n"
                + (dir != null ? dir.getAbsolutePath() : "(không có bộ nhớ ngoài)"));
        path.setTextSize(12);
        path.setTextColor(Color.rgb(110, 90, 60));
        path.setGravity(Gravity.CENTER);
        root.addView(path, lp(0, 0));

        setContentView(root);
    }

    private Button modeButton(String text, final int mode) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(20);
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, GameActivity.class);
                i.putExtra("mode", mode); // 0 dễ, 1 vừa, 2 khó, 3 hai người
                i.putExtra("minutes", TIME_MIN[timeSpinner.getSelectedItemPosition()]);
                i.putExtra("playerFirst", firstBox.isChecked()); // chỉ có tác dụng khi chơi với máy
                startActivity(i);
            }
        });
        return bt;
    }

    private LinearLayout.LayoutParams lp(int w, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                w == 0 ? LinearLayout.LayoutParams.WRAP_CONTENT : w, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
