package com.fongmi.android.tv.ui.source;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Shared phone/remote-friendly source pages with explicit system-bar clearance. */
public final class SourcePage {
    public static int dp(Context c,int value) {return Math.round(value*c.getResources().getDisplayMetrics().density);}
    public static LinearLayout page(AppCompatActivity activity,String title) {
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(18,25,33));
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int p=dp(activity,20);content.setPadding(p,p,p,p);
        ViewCompat.setOnApplyWindowInsetsListener(scroll,(view,insets)->{
            var bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            // Insets belong to the viewport: child padding scrolls away under system bars.
            int bottom=android.os.Build.VERSION.SDK_INT<30 ? Math.max(bars.bottom,insets.getSystemWindowInsetBottom()) : bars.bottom;
            scroll.setPadding(bars.left,bars.top,bars.right,bottom);
            scroll.setClipToPadding(true);
            return WindowInsetsCompat.CONSUMED;
        });
        text(content,title,26);scroll.addView(content);activity.setContentView(scroll);
        androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(),scroll).setAppearanceLightStatusBars(false);
        androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(),scroll).setAppearanceLightNavigationBars(false);
        ViewCompat.requestApplyInsets(scroll);return content;
    }
    public static TextView text(LinearLayout parent,String text,int size) {
        TextView view=new TextView(parent.getContext());view.setText(text);view.setTextSize(size);
        view.setTextColor(Color.rgb(230,237,243));view.setPadding(0,dp(parent.getContext(),8),0,dp(parent.getContext(),12));
        parent.addView(view);return view;
    }
    public static Button action(LinearLayout parent,String label,Runnable click) {
        Context c=parent.getContext();Button view=new androidx.appcompat.widget.AppCompatButton(c);
        view.setAllCaps(false);view.setBackgroundTintList(null);view.setStateListAnimator(null);view.setText(label);view.setTextSize(17);
        view.setTextColor(Color.WHITE);view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(c,56));int p=dp(c,16);view.setPadding(p,p,p,p);
        StateListDrawable bg=new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},shape(c,0xff236c6d));
        bg.addState(new int[]{android.R.attr.state_focused},shape(c,0xff236c6d));
        bg.addState(new int[]{android.R.attr.state_selected},shape(c,0xff214c50));
        bg.addState(new int[]{},shape(c,0xff263440));view.setBackground(bg);
        view.setFocusable(true);view.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(c,10);
        parent.addView(view,params);return view;
    }
    private static GradientDrawable shape(Context c,int color) {
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(c,12));return bg;
    }
    private SourcePage() {}
}
