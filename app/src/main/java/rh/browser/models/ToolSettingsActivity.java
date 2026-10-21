package rh.browser.models;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class ToolSettingsActivity extends Activity {
    private LinearLayout list;
    private TextView count;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tool Browser");
        // These are regular content screens: do not inherit Browser's immersive flags.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);
        buildUi();
        renderTools();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 8));
        setContentView(root);

        LinearLayout top = UiKit.horizontal(this);
        TextView back = UiKit.iconButton(this, 0xf060, "Kembali");
        top.addView(back, UiKit.lp(this, 44, 44));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = UiKit.dp(this, 12);
        top.addView(titleCol, titleLp);
        titleCol.addView(UiKit.label(this, "Tool Browser", 21, UiKit.TEXT, true));
        titleCol.addView(UiKit.label(this, "Atur akses tool yang terlihat oleh AI", 12, UiKit.MUTED, false));
        root.addView(top, UiKit.lp(this, -1, 54));

        LinearLayout summary = UiKit.card(this, 14);
        summary.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sumLp = UiKit.lp(this, -1, -2);
        sumLp.topMargin = UiKit.dp(this, 12);
        root.addView(summary, sumLp);
        LinearLayout sumRow = UiKit.horizontal(this);
        TextView icon = UiKit.icon(this, 0xf1de, 23, UiKit.BLUE);
        sumRow.addView(icon, UiKit.lp(this, 34, 34));
        LinearLayout sumText = new LinearLayout(this);
        sumText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, -2, 1);
        stLp.leftMargin = UiKit.dp(this, 8);
        sumRow.addView(sumText, stLp);
        sumText.addView(UiKit.label(this, "Kontrol akses AI", 15, UiKit.TEXT, true));
        count = UiKit.label(this, "Memuat tool...", 12, UiKit.MUTED, false);
        sumText.addView(count);
        summary.addView(sumRow);

        LinearLayout buttons = UiKit.horizontal(this);
        LinearLayout.LayoutParams buttonLp = UiKit.lp(this, -1, 42);
        buttonLp.topMargin = UiKit.dp(this, 12);
        summary.addView(buttons, buttonLp);
        LinearLayout enable = UiKit.action(this, 0xf205, "Semua aktif", true);
        buttons.addView(enable, new LinearLayout.LayoutParams(0, -1, 1));
        enable.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ToolSettings.enableAll(ToolSettingsActivity.this); renderTools(); Toast.makeText(ToolSettingsActivity.this, "Semua tool diaktifkan", Toast.LENGTH_SHORT).show(); } });
        LinearLayout disable = UiKit.action(this, 0xf204, "Semua mati", false);
        LinearLayout.LayoutParams disableLp = new LinearLayout.LayoutParams(0, -1, 1);
        disableLp.leftMargin = UiKit.dp(this, 8);
        buttons.addView(disable, disableLp);
        disable.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ToolSettings.disableAll(ToolSettingsActivity.this); renderTools(); Toast.makeText(ToolSettingsActivity.this, "Semua tool dinonaktifkan", Toast.LENGTH_SHORT).show(); } });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = UiKit.dp(this, 12);
        root.addView(scroll, scrollLp);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
    }

    private void renderTools() {
        if (list == null) return;
        list.removeAllViews();
        List<ToolDef> tools = ToolRegistry.all();
        int enabled = 0;
        for (int i = 0; i < tools.size(); i++) if (ToolSettings.isEnabled(this, tools.get(i))) enabled++;
        count.setText(enabled + " dari " + tools.size() + " tool aktif · perubahan langsung berlaku untuk daftar MCP");

        for (int i = 0; i < tools.size(); i++) {
            final ToolDef tool = tools.get(i);
            LinearLayout row = UiKit.horizontal(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12));
            row.setBackground(UiKit.shape(UiKit.CARD, 17, UiKit.BORDER, 1, this));
            LinearLayout.LayoutParams rowLp = UiKit.lp(this, -1, -2);
            rowLp.bottomMargin = UiKit.dp(this, 8);
            list.addView(row, rowLp);

            TextView ico = UiKit.icon(this, iconFor(tool.name), 17, UiKit.BLUE);
            ico.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 13, 0, 0, this));
            row.addView(ico, UiKit.lp(this, 42, 42));

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(0, -2, 1);
            detailLp.leftMargin = UiKit.dp(this, 10);
            detailLp.rightMargin = UiKit.dp(this, 6);
            row.addView(details, detailLp);
            details.addView(UiKit.label(this, tool.name, 13, UiKit.TEXT, true));
            TextView desc = UiKit.label(this, tool.description, 11, UiKit.MUTED, false);
            desc.setMaxLines(3);
            details.addView(desc);
            TextView access = UiKit.label(this, tool.accessMode == ToolDef.ACCESS_READ ? "READ · Baca data" : "WRITE · Aksi halaman", 10, tool.accessMode == ToolDef.ACCESS_READ ? UiKit.GREEN : 0xFFFFC36B, true);
            LinearLayout.LayoutParams accessLp = UiKit.lp(this, -2, -2);
            accessLp.topMargin = UiKit.dp(this, 5);
            details.addView(access, accessLp);

            final Switch toggle = new Switch(this);
            toggle.setChecked(ToolSettings.isEnabled(this, tool));
            toggle.setText("");
            toggle.setThumbTintList(new ColorStateList(new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} }, new int[] { UiKit.BLUE, 0xFF71839A }));
            toggle.setTrackTintList(new ColorStateList(new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} }, new int[] { 0xFF326CAF, 0xFF334358 }));
            row.addView(toggle, UiKit.lp(this, 52, 44));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    ToolSettings.setTool(ToolSettingsActivity.this, tool, isChecked);
                    updateCount();
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { toggle.setChecked(!toggle.isChecked()); }
            });
        }
    }

    private void updateCount() {
        int active = 0;
        List<ToolDef> tools = ToolRegistry.all();
        for (int i = 0; i < tools.size(); i++) if (ToolSettings.isEnabled(this, tools.get(i))) active++;
        count.setText(active + " dari " + tools.size() + " tool aktif · perubahan langsung berlaku untuk daftar MCP");
    }

    private int iconFor(String name) {
        if (name.indexOf("open") >= 0) return 0xf0ac;
        if (name.indexOf("back") >= 0) return 0xf060;
        if (name.indexOf("inspect") >= 0) return 0xf06e;
        if (name.indexOf("click") >= 0) return 0xf25a;
        if (name.indexOf("input") >= 0) return 0xf11c;
        if (name.indexOf("scroll") >= 0) return 0xf338;
        if (name.indexOf("source") >= 0) return 0xf121;
        if (name.indexOf("screenshot") >= 0) return 0xf030;
        if (name.indexOf("cookies") >= 0) return 0xf563;
        if (name.indexOf("localstorage") >= 0 || name.indexOf("sessionstorage") >= 0) return 0xf1c0;
        if (name.indexOf("user_agent") >= 0 || name.indexOf("mode") >= 0) return 0xf0ac;
        if (name.indexOf("viewport") >= 0) return 0xf065;
        if (name.indexOf("item") >= 0) return 0xf1c9;
        if (name.indexOf("console") >= 0) return 0xf120;
        return 0xf013;
    }
}
