package com.bellota.mascota;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bellota.mascota.core.BellotaState;
import com.bellota.mascota.core.PetExpression;
import com.bellota.mascota.local.OfflineReplies;
import com.bellota.mascota.ui.GifFaceView;

public class MainActivity extends Activity {
    private final BellotaState state = new BellotaState();
    private final OfflineReplies offlineReplies = new OfflineReplies();
    private GifFaceView faceView;
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildHomeScreen();
        showExpression(PetExpression.HAPPY);
    }

    private void buildHomeScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("BELLOTA");
        title.setTextColor(0xFF00BFA6);
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.12f);
        root.addView(title, matchWrap());

        faceView = new GifFaceView(this);
        LinearLayout.LayoutParams faceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        faceParams.topMargin = dp(16);
        faceParams.bottomMargin = dp(12);
        root.addView(faceView, faceParams);

        message = new TextView(this);
        message.setText("¡Hola! Soy Bellota.");
        message.setTextColor(0xFFFFFFFF);
        message.setTextSize(19);
        message.setGravity(Gravity.CENTER);
        root.addView(message, matchWrap());

        TextView status = new TextView(this);
        status.setText("Modo local preparado");
        status.setTextColor(0xFF9FB3C8);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        root.addView(status, statusParams);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        for (final PetExpression expression : PetExpression.values()) {
            Button button = new Button(this);
            button.setText(expression.label);
            button.setAllCaps(false);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    showExpression(expression);
                    message.setText("Estoy " + expression.label.toLowerCase() + ".");
                }
            });
            choices.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        }
        scroll.addView(choices);
        LinearLayout.LayoutParams scrollParams = matchWrap();
        scrollParams.topMargin = dp(14);
        root.addView(scroll, scrollParams);

        Button greet = new Button(this);
        greet.setText("Saludar");
        greet.setAllCaps(false);
        greet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                message.setText(offlineReplies.reply("hola"));
                showExpression(PetExpression.HAPPY);
            }
        });
        root.addView(greet, matchWrap());

        setContentView(root);
    }

    private void showExpression(PetExpression expression) {
        state.setExpression(expression);
        faceView.loadAsset(this, expression.asset + ".gif");
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
