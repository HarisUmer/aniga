package com.example.aniga;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.aniga.data.Progress;
import com.example.aniga.data.Session;
import com.example.aniga.ui.discover.DiscoverFragment;
import com.example.aniga.ui.foryou.ForYouFragment;
import com.example.aniga.ui.home.HomeFragment;
import com.example.aniga.ui.library.LibraryFragment;
import com.example.aniga.ui.people.PeopleFragment;
import com.example.aniga.ui.widget.InkNavView;

public class MainActivity extends AppCompatActivity {

    private static final String[] TAGS = {"home", "foryou", "discover", "library", "people"};
    private static final String STATE_TAB = "tab";

    private InkNavView nav;
    private int current = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        nav = findViewById(R.id.ink_nav);
        nav.setItems(
                new InkNavView.Item("棚", getString(R.string.nav_home)),
                new InkNavView.Item("推", getString(R.string.nav_foryou)),
                new InkNavView.Item("探", getString(R.string.nav_discover)),
                new InkNavView.Item("帳", getString(R.string.nav_library)),
                new InkNavView.Item("縁", getString(R.string.nav_people)));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Top inset is left to each tab so its header art can bleed under the status bar.
            v.setPadding(bars.left, 0, bars.right, 0);
            nav.setBottomInset(bars.bottom);
            View scrim = findViewById(R.id.status_scrim);
            scrim.getLayoutParams().height = bars.top;
            scrim.requestLayout();
            return insets;
        });
        Progress.load(this, Session.userId(this));
        nav.setOnSelect(this::show);
        int tab = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_TAB, 0);
        nav.select(tab, false);
        show(tab);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, current);
    }

    private void show(int index) {
        boolean forward = index > current;
        current = index;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Fragment shown = null;
        for (int i = 0; i < TAGS.length; i++) {
            Fragment f = fm.findFragmentByTag(TAGS[i]);
            if (i == index) {
                if (f == null) {
                    f = create(i);
                    tx.add(R.id.tab_container, f, TAGS[i]);
                } else {
                    tx.show(f);
                }
                shown = f;
            } else if (f != null) {
                tx.hide(f);
            }
        }
        tx.setReorderingAllowed(true).commitNow();
        View v = shown != null ? shown.getView() : null;
        if (v != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            // Slide a little in the direction of travel, like turning to the next tab of a binder.
            v.setAlpha(0f);
            v.setTranslationX(getResources().getDisplayMetrics().density * (forward ? 28 : -28));
            v.animate().alpha(1f).translationX(0f).setDuration(300)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
        if (shown instanceof LibraryFragment) ((LibraryFragment) shown).refresh();
    }

    private static Fragment create(int index) {
        switch (index) {
            case 1:
                return new ForYouFragment();
            case 2:
                return new DiscoverFragment();
            case 3:
                return new LibraryFragment();
            case 4:
                return new PeopleFragment();
            default:
                return new HomeFragment();
        }
    }
}
