package com.example.aniga.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.aniga.MainActivity;
import com.example.aniga.R;
import com.example.aniga.ui.widget.HankoStampView;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        HankoStampView stamp = findViewById(R.id.stamp);
        View wordmark = findViewById(R.id.wordmark);
        View tagline = findViewById(R.id.tagline);
        float rise = Ui.dp(this, 18);
        wordmark.setTranslationY(rise);
        tagline.setTranslationY(rise);
        PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);

        stamp.postDelayed(() -> stamp.play(() -> {
            wordmark.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(emphasized).start();
            tagline.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(420)
                    .setInterpolator(emphasized).start();
        }), 180);

        stamp.postDelayed(this::continueAfterSplash, 1900);
    }

    private void continueAfterSplash() {
        Class<?> next;
        if (!com.example.aniga.data.Session.isSignedIn(this)) {
            next = SignInActivity.class;
        } else if (!com.example.aniga.data.Session.prefsDone(this)) {
            next = TasteActivity.class;
        } else {
            next = MainActivity.class;
        }
        go(next);
    }

    private void go(Class<?> next) {
        startActivity(new Intent(this, next));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }
}
