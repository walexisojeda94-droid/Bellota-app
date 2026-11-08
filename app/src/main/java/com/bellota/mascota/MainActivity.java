package com.bellota.mascota;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView connectionStatus;
    private TextView petMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildHomeScreen();
    }

    private void buildHomeScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("BELLOTA");
        title.setTextColor(0xFF00BFA6);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.12f);
        root.addView(title, matchWrap());

        TextView face = new TextView(this);
        face.setText("•ᴗ•");
        face.setTextColor(0xFFFFFFFF);
        face.setTextSize(64);
        face.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams faceParams = matchWrap();
        faceParams.topMargin = dp(56);
        faceParams.bottomMargin = dp(24);
        root.addView(face, faceParams);

        petMessage = new TextView(this);
        petMessage.setText("Hola. Soy Bellota.");
        petMessage.setTextColor(0xFFFFFFFF);
        petMessage.setTextSize(20);
        petMessage.setGravity(Gravity.CENTER);
        root.addView(petMessage, matchWrap());

        connectionStatus = new TextView(this);
        connectionStatus.setText("Preparando conexión...");
        connectionStatus.setTextColor(0xFF9FB3C8);
        connectionStatus.setTextSize(14);
        connectionStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(20);
        root.addView(connectionStatus, statusParams);

        TextView note = new TextView(this);
        note.setText("Esta es la estructura inicial. La conexión con XiaoZhi y las respuestas sin internet se incorporarán en los siguientes módulos.");
        note.setTextColor(0xFF9FB3C8);
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(32);
        root.addView(note, noteParams);

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
