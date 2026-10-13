package com.leonfbd.redbutton;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String STATE_COUNT = "press_count";

    private int pressCount = 0;
    private TextView countView;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            pressCount = savedInstanceState.getInt(STATE_COUNT, 0);
        }
        vibrator = getSystemService(Vibrator.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        countView = new TextView(this);
        countView.setTextColor(Color.WHITE);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(0, 0, 0, dp(32));
        root.addView(countView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button bigButton = new Button(this);
        bigButton.setText(R.string.press_button);
        bigButton.setTextColor(Color.WHITE);
        bigButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bigButton.setAllCaps(false);
        bigButton.setBackground(roundRed());
        bigButton.setOnClickListener(v -> onRedButtonPressed());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(220), dp(220));
        root.addView(bigButton, buttonParams);

        Button resetButton = new Button(this);
        resetButton.setText(R.string.reset);
        resetButton.setOnClickListener(v -> {
            pressCount = 0;
            updateCount();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(40);
        root.addView(resetButton, resetParams);

        updateCount();
        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_COUNT, pressCount);
    }

    private void onRedButtonPressed() {
        pressCount++;
        updateCount();
        vibrateShort();
    }

    private void updateCount() {
        countView.setText(getString(R.string.press_count, pressCount));
    }

    private void vibrateShort() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(40);
        }
    }

    private GradientDrawable roundRed() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#D50000"));
        return shape;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
