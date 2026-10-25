package rh.browser.models;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;

public class WorkflowActivity extends Activity {
    private static final int REQUEST_IMPORT = 7410;
    private static final int REQUEST_EXPORT = 7411;
    private LinearLayout list;
    private String exportId;
    private int requestCounter;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Workflow");
        UiKit.applyImmersive(this);
        buildUi();
        reload();
    }

    public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) UiKit.applyImmersive(this); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 5)); root.setBackgroundColor(UiKit.BG); setContentView(root);
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(UiKit.label(this, "Workflow", 22, UiKit.TEXT, true));
        heading.addView(UiKit.label(this, "Editor script, endpoint HTTP, paket ZIP, dan log", 12, UiKit.MUTED, false));
        root.addView(heading, UiKit.lp(this, -1, -2));
        LinearLayout actions = UiKit.horizontal(this); LinearLayout.LayoutParams actionsLp = UiKit.lp(this, -1, 44); actionsLp.topMargin = UiKit.dp(this, 10); root.addView(actions, actionsLp);
        TextView create = UiKit.label(this, "+ Buat", 12, UiKit.TEXT, true); create.setGravity(Gravity.CENTER); create.setBackground(UiKit.gradient(UiKit.BLUE, UiKit.BLUE_DARK, 12, this)); actions.addView(create, new LinearLayout.LayoutParams(0, -1, 1));
        create.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(WorkflowActivity.this, WorkflowEditorActivity.class)); } });
        TextView install = UiKit.label(this, "Instal ZIP", 12, UiKit.TEXT, true); install.setGravity(Gravity.CENTER); install.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 12, UiKit.BORDER, 1, this)); LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(0, -1, 1); installLp.leftMargin = UiKit.dp(this, 6); actions.addView(install, installLp);
        install.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { chooseImport(); } });
        TextView refresh = UiKit.iconButton(this, 0xf2f1, "Muat ulang"); LinearLayout.LayoutParams refreshLp = UiKit.lp(this, 42, 42); refreshLp.leftMargin = UiKit.dp(this, 6); actions.addView(refresh, refreshLp); refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { reload(); } });
        LinearLayout endpointNote = UiKit.card(this, 10); LinearLayout.LayoutParams noteLp = UiKit.lp(this, -1, -2); noteLp.topMargin = UiKit.dp(this, 8); root.addView(endpointNote, noteLp);
        endpointNote.addView(UiKit.label(this, "Endpoint workflow lokal", 12, UiKit.TEXT, true));
        endpointNote.addView(UiKit.label(this, "Opsional: tanpa server, server bersama port 8084, atau satu port eksklusif per workflow (maksimal lima port eksklusif aktif).", 11, UiKit.MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1); scrollLp.topMargin = UiKit.dp(this, 9); root.addView(scroll, scrollLp);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        UiKit.addBottomNavigation(this, root, "workflow");
    }

    private void reload() {
        if (list == null) return;
        list.removeAllViews();
        try {
            JSONArray workflows = WorkflowManager.get(this).listWorkflows();
            if (workflows.length() == 0) {
                LinearLayout empty = UiKit.card(this, 22); empty.setGravity(Gravity.CENTER); empty.addView(UiKit.icon(this, 0xf0ae, 30, UiKit.BLUE), UiKit.lp(this, -1, 48));
                TextView title = UiKit.label(this, "Belum ada workflow", 16, UiKit.TEXT, true); title.setGravity(Gravity.CENTER); empty.addView(title, UiKit.lp(this, -1, 28));
                TextView sub = UiKit.label(this, "Buat workflow di editor atau instal paket ZIP.", 12, UiKit.MUTED, false); sub.setGravity(Gravity.CENTER); empty.addView(sub, UiKit.lp(this, -1, -2));
                list.addView(empty, UiKit.lp(this, -1, -2)); return;
            }
            for (int i = 0; i < workflows.length(); i++) addWorkflowCard(workflows.getJSONObject(i));
        } catch (Exception e) { showMessage("Gagal memuat workflow: " + safe(e)); }
    }

    private void addWorkflowCard(final JSONObject meta) throws Exception {
        final String id = meta.optString("id", "");
        LinearLayout card = UiKit.card(this, 13); LinearLayout.LayoutParams cardLp = UiKit.lp(this, -1, -2); cardLp.bottomMargin = UiKit.dp(this, 9); list.addView(card, cardLp);
        LinearLayout heading = UiKit.horizontal(this);
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); heading.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        titleBox.addView(UiKit.label(this, meta.optString("name", id), 16, UiKit.TEXT, true));
        titleBox.addView(UiKit.label(this, id + " · " + meta.optString("format", "flow") + " · " + meta.optString("entry", ""), 10, UiKit.MUTED, false));
        boolean valid = meta.optBoolean("valid", false); boolean enabled = meta.optBoolean("enabled", false);
        boolean runtimeActive = meta.optBoolean("runtimeActive", false); boolean endpointRuntimeActive = meta.optBoolean("endpointRuntimeActive", false); int runningCount = meta.optInt("runningExecutions", 0);
        String endpointMode = meta.optString("endpointMode", "none");
        String runtimeText = !valid ? "Tidak valid" : !enabled ? "Nonaktif" : "Workflow aktif";
        if (enabled && "none".equals(endpointMode)) runtimeText += " · tanpa endpoint";
        else if (enabled && endpointRuntimeActive) runtimeText += " · endpoint aktif";
        else if (enabled && !"none".equals(endpointMode)) runtimeText += " · endpoint belum tersedia";
        if (runningCount > 0) runtimeText += " · running " + runningCount;
        int pendingInputs = meta.optInt("pendingInputCount", 0); if (pendingInputs > 0) runtimeText += " · menunggu input " + pendingInputs;
        Switch active = new Switch(this); active.setText(runtimeText); active.setTextColor(valid ? UiKit.TEXT : UiKit.RED); active.setTextSize(10); active.setChecked(enabled); active.setEnabled(true); heading.addView(active, UiKit.lp(this, -2, 42));
        card.addView(heading, UiKit.lp(this, -1, -2));
        String endpointUrl = meta.optString("endpointUrl", "");
        if (!"none".equals(endpointMode)) {
            String label = endpointUrl.length() > 0 ? endpointUrl : "Endpoint " + endpointMode + " · port akan dialokasikan saat aktif";
            card.addView(UiKit.label(this, label, 11, UiKit.BLUE, false));
        } else {
            card.addView(UiKit.label(this, "Tanpa endpoint HTTP · dapat dijalankan manual atau melalui MCP", 11, UiKit.MUTED, false));
        }
        card.addView(UiKit.label(this, "AI write input: " + (meta.optBoolean("aiWriteInput", false) ? "diizinkan" : "nonaktif"), 10, meta.optBoolean("aiWriteInput", false) ? UiKit.GREEN : UiKit.MUTED, false));
        if (!valid) card.addView(UiKit.label(this, "Tidak valid: " + meta.optString("validationMessage", "periksa metadata dan entry point"), 11, UiKit.RED, false));
        final String endpointText = endpointUrl;
        active.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                try {
                    if (checked) WorkflowManager.get(WorkflowActivity.this).activate(id); else WorkflowManager.get(WorkflowActivity.this).deactivate(id);
                    Toast.makeText(WorkflowActivity.this, checked ? "Workflow diaktifkan" : "Workflow dinonaktifkan", Toast.LENGTH_SHORT).show(); reload();
                } catch (Exception e) { Toast.makeText(WorkflowActivity.this, "Gagal mengubah status: " + safe(e), Toast.LENGTH_LONG).show(); reload(); }
            }
        });
        LinearLayout buttons = UiKit.horizontal(this); LinearLayout.LayoutParams bLp = UiKit.lp(this, -1, 38); bLp.topMargin = UiKit.dp(this, 8); card.addView(buttons, bLp);
        addSmallButton(buttons, "Edit", new View.OnClickListener() { public void onClick(View v) { Intent intent = new Intent(WorkflowActivity.this, WorkflowEditorActivity.class); intent.putExtra("workflow_id", id); startActivity(intent); } });
        addSmallButton(buttons, "Log", new View.OnClickListener() { public void onClick(View v) { Intent intent = new Intent(WorkflowActivity.this, WorkflowLogActivity.class); intent.putExtra("workflow_id", id); startActivity(intent); } });
        if (!"none".equals(endpointMode) && endpointText.length() > 0) addSmallButton(buttons, "Salin URL", new View.OnClickListener() { public void onClick(View v) { android.content.ClipboardManager cm = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("workflow endpoint", endpointText)); Toast.makeText(WorkflowActivity.this, "URL endpoint disalin", Toast.LENGTH_SHORT).show(); } });
        addSmallButton(buttons, "ZIP", new View.OnClickListener() { public void onClick(View v) { chooseExport(id); } });
        addSmallButton(buttons, "Hapus", new View.OnClickListener() { public void onClick(View v) { confirmDelete(id, meta.optString("name", id)); } });
    }

    private void addSmallButton(LinearLayout row, String text, View.OnClickListener click) {
        TextView b = UiKit.label(this, text, 11, UiKit.TEXT, true); b.setGravity(Gravity.CENTER); b.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 9, UiKit.BORDER, 1, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.leftMargin = UiKit.dp(this, 3); lp.rightMargin = UiKit.dp(this, 3); row.addView(b, lp); b.setOnClickListener(click);
    }

    private void confirmDelete(final String id, String name) {
        new AlertDialog.Builder(this).setTitle("Hapus workflow?").setMessage("Workflow '" + name + "', semua file termasuk assets, dan seluruh log-nya akan dihapus.")
                .setNegativeButton("Batal", null).setPositiveButton("Hapus", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try { WorkflowManager.get(WorkflowActivity.this).deleteWorkflow(id); Toast.makeText(WorkflowActivity.this, "Workflow dihapus", Toast.LENGTH_SHORT).show(); reload(); }
                        catch (Exception e) { showMessage("Gagal menghapus: " + safe(e)); }
                    }
                }).show();
    }

    private void chooseImport() {
        try { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("application/zip"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(Intent.createChooser(i, "Pilih paket workflow ZIP"), REQUEST_IMPORT); }
        catch (Exception e) { showMessage("Pemilih file tidak tersedia: " + safe(e)); }
    }
    private void chooseExport(String id) {
        exportId = id;
        try { Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip"); i.putExtra(Intent.EXTRA_TITLE, id + ".zip"); startActivityForResult(i, REQUEST_EXPORT); }
        catch (Exception e) { showMessage("Ekspor tidak tersedia: " + safe(e)); }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData(); final int req = requestCode; final String id = exportId; final int gen = ++requestCounter;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                String message;
                try {
                    if (req == REQUEST_IMPORT) {
                        InputStream in = getContentResolver().openInputStream(uri);
                        try { JSONObject result = WorkflowManager.get(WorkflowActivity.this).importZip(in); message = "Workflow '" + result.optJSONObject("workflow").optString("name", "") + "' diimpor. Valid: " + result.optBoolean("valid"); }
                        finally { if (in != null) in.close(); }
                    } else if (req == REQUEST_EXPORT) {
                        OutputStream out = getContentResolver().openOutputStream(uri);
                        if (out == null) throw new IllegalStateException("output file tidak bisa dibuka");
                        WorkflowManager.get(WorkflowActivity.this).exportZip(id, out); try { out.close(); } catch (Exception ignored) { }
                        message = "Workflow diekspor ke ZIP";
                    } else return;
                } catch (Exception e) { message = "Operasi ZIP gagal: " + safe(e); }
                final String resultMessage = message;
                runOnUiThread(new Runnable() { public void run() { if (isFinishing() || gen != requestCounter) return; Toast.makeText(WorkflowActivity.this, resultMessage, Toast.LENGTH_LONG).show(); reload(); } });
            }
        }, "rh-workflow-zip"); worker.setDaemon(true); worker.start();
    }

    private void showMessage(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String safe(Exception e) { String s = e.getMessage(); return s == null ? e.getClass().getSimpleName() : s; }
    protected void onResume() { super.onResume(); reload(); }
}
