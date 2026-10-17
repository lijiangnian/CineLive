package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.leanback.widget.BaseGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.activity.SourceCatalogActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.PushActivity;
import com.fongmi.android.tv.ui.activity.SourceStatusActivity;

/** Main destinations stay focusable even while a source is loading or empty. */
public class TvNavigationView extends LinearLayoutCompat {
    private int lastFocusedId = R.id.navVod;
    private View chrome;
    private View wall;
    private ViewTreeObserver focusObserver;
    private WeakReference<RecyclerView> contentRoot = new WeakReference<>(null);
    private List<Integer> contentPositions = new ArrayList<>();
    private final Runnable collapse = () -> {
        if (chrome != null && isWallFocus(wall.findFocus())) chrome.setVisibility(GONE);
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        removeCallbacks(collapse);
        if (isWallFocus(newFocus)) {
            rememberContent(newFocus);
            post(collapse);
        }
    };

    public TvNavigationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        LayoutInflater.from(context).inflate(R.layout.view_tv_navigation, this, true);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setOnFocusChangeListener((view, focused) -> {
                if (focused) lastFocusedId = view.getId();
            });
        }
    }

    public void bind(Activity activity, Runnable openVod, View content, View above) {
        findViewById(R.id.navVod).setOnClickListener(view -> openVod.run());
        findViewById(R.id.navLive).setOnClickListener(view -> {
            if (LiveConfig.hasUrl()) LiveActivity.start(activity);
            else SourceCatalogActivity.start(activity, 1);
        });
        findViewById(R.id.navSettings).setOnClickListener(view -> SettingActivity.start(activity));
        findViewById(R.id.navSearch).setOnClickListener(view -> SearchActivity.start(activity));
        findViewById(R.id.navKeep).setOnClickListener(view -> KeepActivity.start(activity));
        findViewById(R.id.navPush).setOnClickListener(view -> PushActivity.start(activity));
        findViewById(R.id.navSources).setOnClickListener(view -> SourceStatusActivity.start(activity));
        for (int i = 0; i < getChildCount(); i++) {
            final int index = i;
            View button = getChildAt(i);
            button.setNextFocusDownId(content.getId());
            button.setNextFocusUpId(above == null ? button.getId() : above.getId());
            button.setOnKeyListener((view, keyCode, event) -> {
                // Do not let a source's grid or an off-screen neighbour capture horizontal keys.
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int next = Math.max(0, Math.min(getChildCount() - 1,
                                index + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                        getChildAt(next).requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && content.isShown()) focusContent(content);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && above == null) return true;
                return false;
            });
        }
    }

    public boolean focusLast() {
        showChrome();
        return findViewById(lastFocusedId).requestFocus();
    }

    /** Collapse only after a real content item takes focus; loading/empty screens retain controls. */
    public void setCollapsibleHeader(View header, View content) {
        chrome = header;
        wall = content;
        if (isAttachedToWindow()) observeFocus();
    }

    public void showChrome() {
        removeCallbacks(collapse);
        if (chrome != null) chrome.setVisibility(VISIBLE);
    }

    public boolean isChromeVisible() {
        return chrome == null || chrome.getVisibility() == VISIBLE;
    }

    private void rememberContent(View focus) {
        List<Integer> positions = new ArrayList<>();
        RecyclerView root = null;
        View child = focus;
        while (child.getParent() instanceof View parent) {
            if (parent instanceof RecyclerView grid) {
                int position = grid.getChildAdapterPosition(child);
                if (position < 0) return;
                positions.add(0, position);
                root = grid;
            }
            if (parent == wall) break;
            child = parent;
        }
        contentRoot = new WeakReference<>(root);
        contentPositions = positions;
    }

    /** Restore both the poster row and column after the header changes the viewport height. */
    public boolean focusContent(View content) {
        if (!content.isShown()) return false;
        if (content == contentRoot.get() && !contentPositions.isEmpty()) {
            View candidate = content;
            List<Integer> positions = new ArrayList<>(contentPositions);
            for (int position : positions) {
                RecyclerView grid = findGrid(candidate);
                if (grid == null || grid.getAdapter() == null || position >= grid.getAdapter().getItemCount()) break;
                if (grid instanceof BaseGridView leanback) leanback.setSelectedPosition(position);
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
                if (holder == null) break;
                candidate = holder.itemView;
            }
            if (candidate != content && candidate.requestFocus()) return true;
        }
        return content.requestFocus();
    }

    private RecyclerView findGrid(View view) {
        if (view instanceof RecyclerView grid) return grid;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                RecyclerView grid = findGrid(group.getChildAt(i));
                if (grid != null) return grid;
            }
        }
        return null;
    }

    private boolean isWallFocus(View focus) {
        if (wall == null || focus == null || focus == wall || focus instanceof androidx.recyclerview.widget.RecyclerView) return false;
        for (ViewParent parent = focus.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == wall) return true;
        }
        return false;
    }

    private void observeFocus() {
        if (focusObserver != null && focusObserver.isAlive()) focusObserver.removeOnGlobalFocusChangeListener(focusListener);
        focusObserver = getRootView().getViewTreeObserver();
        focusObserver.addOnGlobalFocusChangeListener(focusListener);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observeFocus();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(collapse);
        if (focusObserver != null && focusObserver.isAlive()) focusObserver.removeOnGlobalFocusChangeListener(focusListener);
        focusObserver = null;
        super.onDetachedFromWindow();
    }
}
