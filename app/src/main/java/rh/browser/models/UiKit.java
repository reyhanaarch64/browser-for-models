package rh.browser.models;

import android.content.Context;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int BG = 0xFF07111F;
    public static final int SURFACE = 0xFF0C1A2B;
    public static final int CARD = 0xFF10243A;
    public static final int CARD_LIGHT = 0xFF162D47;
    public static final int BORDER = 0xFF213D5D;
    public static final int TEXT = 0xFFF2F6FF;
    public static final int MUTED = 0xFF9EB0C8;
    public static final int BLUE = 0xFF2587FF;
    public static final int BLUE_DARK = 0xFF174DBB;
    public static final int GREEN = 0xFF2BD69A;
    public static final int RED = 0xFFFF667A;
    private static Typeface solidFace;
    private static Typeface regularFace;
    private static Typeface brandFace;

    private UiKit() { }

    public static int dp(Context c, float value) {
        return (int)(value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable shape(int color, float radiusDp, int strokeColor, float strokeDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    public static GradientDrawable gradient(int start, int end, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { start, end });
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static Typeface font(Context c, String name) {
        try {
            if ("solid".equals(name)) {
                if (solidFace == null) solidFace = Typeface.createFromAsset(c.getAssets(), "fonts/fa-solid-900.ttf");
                return solidFace;
            }
            if ("regular".equals(name)) {
                if (regularFace == null) regularFace = Typeface.createFromAsset(c.getAssets(), "fonts/fa-regular-400.ttf");
                return regularFace;
            }
            if (brandFace == null) brandFace = Typeface.createFromAsset(c.getAssets(), "fonts/fa-brands-400.ttf");
            return brandFace;
        } catch (Exception e) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    public static TextView icon(Context c, int codePoint, int sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(new String(Character.toChars(codePoint)));
        t.setTypeface(font(c, "solid"));
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView label(Context c, String text, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    public static LinearLayout action(Context c, int iconCode, String text, boolean primary) {
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        box.setBackground(primary ? gradient(BLUE, BLUE_DARK, 16, c) : shape(CARD_LIGHT, 16, BORDER, 1, c));
        TextView i = icon(c, iconCode, 15, TEXT);
        box.addView(i, new LinearLayout.LayoutParams(dp(c, 19), -2));
        TextView label = label(c, text, 13, TEXT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(c, 8);
        box.addView(label, lp);
        box.setContentDescription(text);
        box.setClickable(true);
        box.setFocusable(true);
        return box;
    }

    public static void setActionText(View view, String text) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout l = (LinearLayout)view;
        if (l.getChildCount() > 1 && l.getChildAt(1) instanceof TextView) ((TextView)l.getChildAt(1)).setText(text);
    }

    public static LinearLayout card(Context c, int paddingDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, paddingDp), dp(c, paddingDp), dp(c, paddingDp), dp(c, paddingDp));
        l.setBackground(shape(CARD, 19, BORDER, 1, c));
        return l;
    }

    public static TextView iconButton(Context c, int codePoint, String description) {
        TextView t = icon(c, codePoint, 17, TEXT);
        t.setContentDescription(description);
        t.setBackground(shape(CARD_LIGHT, 15, BORDER, 1, c));
        t.setPadding(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static void setMargins(View v, int left, int top, int right, int bottom, Context c) {
        ViewGroup.LayoutParams old = v.getLayoutParams();
        if (old instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) old;
            lp.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
            v.setLayoutParams(lp);
        }
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView pill(Context c, String text, int color) {
        TextView t = label(c, text, 11, color, true);
        t.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(0xFF142B40, 20, color, 1, c));
        return t;
    }

    public static LinearLayout.LayoutParams lp(Context c, int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(c, width), height < 0 ? height : dp(c, height));
    }
    public static void applyImmersive(Activity activity) {
        activity.getWindow().setStatusBarColor(BG);
        activity.getWindow().setNavigationBarColor(BG);
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    public static void addBottomNavigation(final Activity activity, LinearLayout root, String activeTab) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        bar.setBackground(shape(SURFACE, 19, BORDER, 1, activity));
        LinearLayout.LayoutParams barLp = lp(activity, -1, 56);
        barLp.topMargin = dp(activity, 5);
        root.addView(bar, barLp);
        addNavItem(activity, bar, 0xf0ac, "Browser", "browser".equals(activeTab), new View.OnClickListener() {
            public void onClick(View v) { openTab(activity, MainActivity.class); }
        });
        addNavItem(activity, bar, 0xf1de, "Tools", "tools".equals(activeTab), new View.OnClickListener() {
            public void onClick(View v) { openTab(activity, ToolSettingsActivity.class); }
        });
        addNavItem(activity, bar, 0xf019, "Downloads", "downloads".equals(activeTab), new View.OnClickListener() {
            public void onClick(View v) { openTab(activity, DownloadsActivity.class); }
        });
        addNavItem(activity, bar, 0xf0ae, "Workflow", "workflow".equals(activeTab), new View.OnClickListener() {
            public void onClick(View v) { openTab(activity, WorkflowActivity.class); }
        });
        addNavItem(activity, bar, 0xf013, "Service", "service".equals(activeTab), new View.OnClickListener() {
            public void onClick(View v) { openTab(activity, ServiceSettingsActivity.class); }
        });
    }

    public static void openTab(Activity current, Class target) {
        if (current == null || target == null || current.getClass() == target) return;
        android.content.Intent intent = new android.content.Intent(current, target);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
        current.startActivity(intent);
        current.overridePendingTransition(0, 0);
    }

    private static void addNavItem(Context context, LinearLayout parent, int iconCode, String label, boolean active, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        if (active) item.setBackground(shape(0xFF14385C, 14, 0, 0, context));
        TextView icon = icon(context, iconCode, 16, active ? BLUE : MUTED);
        item.addView(icon, lp(context, -1, 21));
        TextView text = label(context, label, 10, active ? TEXT : MUTED, active);
        text.setGravity(Gravity.CENTER);
        item.addView(text, lp(context, -1, 17));
        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, -1, 1);
        itemLp.leftMargin = dp(context, 2);
        itemLp.rightMargin = dp(context, 2);
        parent.addView(item, itemLp);
        item.setOnClickListener(listener);
    }

}
