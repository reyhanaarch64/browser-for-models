package rh.browser.models;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;

/** Lightweight native editor: metadata is entered through fields; only workflow source is typed. */
public class WorkflowEditorActivity extends Activity {
    private static final int REQUEST_ASSET = 7510;
    private EditText nameField, idField, descriptionField, entryField, endpointField, currentPathField, codeField;
    private Spinner formatSpinner, endpointModeSpinner;
    private EditText endpointPortField;
    private Switch aiWriteInputSwitch;
    private TextView stateLabel;
    private String workflowId = "";
    private boolean newWorkflow;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.applyImmersive(this);
        workflowId = getIntent().getStringExtra("workflow_id");
        newWorkflow = workflowId == null || workflowId.trim().length() == 0;
        if (newWorkflow) workflowId = "";
        buildUi();
        if (!newWorkflow) loadMetadataAndEntry();
        else {
            setFormat("flow");
            entryField.setText("script/main.flow");
            currentPathField.setText("script/main.flow");
            codeField.setText(defaultFlow());
        }
        attachFormatChangeListener();
    }

    public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) UiKit.applyImmersive(this); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 5)); root.setBackgroundColor(UiKit.BG); setContentView(root);
        LinearLayout header = UiKit.horizontal(this);
        TextView back = UiKit.iconButton(this, 0xf060, "Kembali"); header.addView(back, UiKit.lp(this, 40, 40)); back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1); titleLp.leftMargin = UiKit.dp(this, 10); header.addView(titles, titleLp);
        titles.addView(UiKit.label(this, "Workflow Editor", 18, UiKit.TEXT, true));
        stateLabel = UiKit.label(this, newWorkflow ? "Workflow baru" : workflowId, 11, UiKit.MUTED, false); titles.addView(stateLabel);
        TextView save = UiKit.iconButton(this, 0xf0c7, "Simpan metadata dan file"); header.addView(save, UiKit.lp(this, 42, 40)); save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveWorkflow(); } });
        root.addView(header, UiKit.lp(this, -1, 42));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1); scrollLp.topMargin = UiKit.dp(this, 6); root.addView(scroll, scrollLp);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout metadata = UiKit.card(this, 12); body.addView(metadata, UiKit.lp(this, -1, -2));
        metadata.addView(UiKit.label(this, "Metadata workflow", 14, UiKit.TEXT, true));
        nameField = addField(metadata, "Nama", "Nama workflow", false);
        idField = addField(metadata, "ID", "huruf, angka, _ atau -", false);
        descriptionField = addField(metadata, "Deskripsi", "Deskripsi singkat", true);
        TextView formatLabel = UiKit.label(this, "Format script", 11, UiKit.MUTED, true); LinearLayout.LayoutParams flp = UiKit.lp(this, -1, -2); flp.topMargin = UiKit.dp(this, 7); metadata.addView(formatLabel, flp);
        formatSpinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[] { ".flow — Declarative", ".bshflow — BeanShell" }); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); formatSpinner.setAdapter(adapter); metadata.addView(formatSpinner, UiKit.lp(this, -1, 42));
        entryField = addField(metadata, "Entry point", "script/main.flow", false);
        endpointField = addField(metadata, "Endpoint path", "/workflow/id", false);
        TextView endpointModeLabel = UiKit.label(this, "Server endpoint (opsional)", 11, UiKit.MUTED, true); LinearLayout.LayoutParams emlp = UiKit.lp(this, -1, -2); emlp.topMargin = UiKit.dp(this, 7); metadata.addView(endpointModeLabel, emlp);
        endpointModeSpinner = new Spinner(this);
        ArrayAdapter<String> endpointAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[] { "Tanpa endpoint HTTP", "Server bersama · port 8084", "Port eksklusif untuk workflow ini" });
        endpointAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); endpointModeSpinner.setAdapter(endpointAdapter); metadata.addView(endpointModeSpinner, UiKit.lp(this, -1, 42));
        endpointPortField = addField(metadata, "Port eksklusif (0 = otomatis; maksimal 5 port aktif)", "8085 atau 0", false);
        aiWriteInputSwitch = new Switch(this); aiWriteInputSwitch.setText("Izinkan AI mengirim input interaktif"); aiWriteInputSwitch.setTextColor(UiKit.TEXT); aiWriteInputSwitch.setTextSize(12); LinearLayout.LayoutParams ailp = UiKit.lp(this, -1, 42); ailp.topMargin = UiKit.dp(this, 7); metadata.addView(aiWriteInputSwitch, ailp);
        metadata.addView(UiKit.label(this, "Jika nonaktif, AI tetap dapat melihat prompt tertunda, tetapi tidak bisa menjawab input workflow ini. Izin disimpan per workflow.", 10, UiKit.MUTED, false), UiKit.lp(this, -1, -2));
        endpointModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long rowId) { updateEndpointFields(); }
        });

        LinearLayout files = UiKit.card(this, 12); LinearLayout.LayoutParams filesLp = UiKit.lp(this, -1, -2); filesLp.topMargin = UiKit.dp(this, 9); body.addView(files, filesLp);
        files.addView(UiKit.label(this, "File workflow dan assets", 14, UiKit.TEXT, true));
        currentPathField = addField(files, "File yang sedang diedit", "script/main.flow", false);
        LinearLayout fileActions = UiKit.horizontal(this); files.addView(fileActions, UiKit.lp(this, -1, 38));
        addCompact(fileActions, "Buka file", new View.OnClickListener() { public void onClick(View v) { loadCurrentPath(); } });
        addCompact(fileActions, "Daftar file", new View.OnClickListener() { public void onClick(View v) { showFiles(); } });
        addCompact(fileActions, "File baru", new View.OnClickListener() { public void onClick(View v) { promptNewFile(); } });
        addCompact(fileActions, "Folder", new View.OnClickListener() { public void onClick(View v) { promptNewFolder(); } });
        LinearLayout fileActions2 = UiKit.horizontal(this); LinearLayout.LayoutParams fa2 = UiKit.lp(this, -1, 38); fa2.topMargin = UiKit.dp(this, 4); files.addView(fileActions2, fa2);
        addCompact(fileActions2, "Impor aset", new View.OnClickListener() { public void onClick(View v) { importAsset(); } });
        addCompact(fileActions2, "Jadikan entry", new View.OnClickListener() { public void onClick(View v) { entryField.setText(currentPathField.getText().toString().trim()); } });
        addCompact(fileActions2, "Hapus file", new View.OnClickListener() { public void onClick(View v) { confirmDeleteCurrentFile(); } });
        files.addView(UiKit.label(this, "Gunakan path relatif script/ atau assets/. Folder parent otomatis dibuat saat file disimpan.", 10, UiKit.MUTED, false));

        LinearLayout editor = UiKit.card(this, 10); LinearLayout.LayoutParams editorLp = UiKit.lp(this, -1, -2); editorLp.topMargin = UiKit.dp(this, 9); body.addView(editor, editorLp);
        LinearLayout editorHeading = UiKit.horizontal(this); editor.addView(editorHeading, UiKit.lp(this, -1, -2));
        editorHeading.addView(UiKit.label(this, "Source code", 14, UiKit.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView load = UiKit.label(this, "Muat ulang", 10, UiKit.BLUE, true); load.setGravity(Gravity.CENTER); editorHeading.addView(load, UiKit.lp(this, 75, 30)); load.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loadCurrentPath(); } });
        codeField = new EditText(this); codeField.setGravity(Gravity.TOP | Gravity.LEFT); codeField.setTextColor(0xFFE3EBF7); codeField.setHintTextColor(UiKit.MUTED); codeField.setHint("Tulis kode workflow di sini..."); codeField.setTextSize(12); codeField.setTypeface(android.graphics.Typeface.MONOSPACE); codeField.setSingleLine(false); codeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT); codeField.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI); codeField.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 9), UiKit.dp(this, 9), UiKit.dp(this, 9)); codeField.setMinLines(15); codeField.setBackground(UiKit.shape(0xFF03070D, 9, UiKit.BORDER, 1, this)); LinearLayout.LayoutParams codeLp = UiKit.lp(this, -1, -2); codeLp.topMargin = UiKit.dp(this, 6); editor.addView(codeField, codeLp);
        TextView bottom = UiKit.label(this, "Simpan menulis metadata melalui form UI dan source ke file yang dipilih. Aset biner dapat diimpor langsung.", 10, UiKit.MUTED, false); LinearLayout.LayoutParams bottomLp = UiKit.lp(this, -1, -2); bottomLp.topMargin = UiKit.dp(this, 6); editor.addView(bottom, bottomLp);
        LinearLayout actions = UiKit.horizontal(this); LinearLayout.LayoutParams actLp = UiKit.lp(this, -1, 42); actLp.topMargin = UiKit.dp(this, 7); body.addView(actions, actLp);
        TextView saveBottom = UiKit.label(this, "Simpan workflow", 13, UiKit.TEXT, true); saveBottom.setGravity(Gravity.CENTER); saveBottom.setBackground(UiKit.gradient(UiKit.BLUE, UiKit.BLUE_DARK, 12, this)); actions.addView(saveBottom, new LinearLayout.LayoutParams(0, -1, 1)); saveBottom.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveWorkflow(); } });
        TextView validate = UiKit.label(this, "Validasi", 12, UiKit.TEXT, true); validate.setGravity(Gravity.CENTER); validate.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 12, UiKit.BORDER, 1, this)); LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(0, -1, 1); valLp.leftMargin = UiKit.dp(this, 6); actions.addView(validate, valLp); validate.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveWorkflowAndValidate(); } });
        TextView test = UiKit.label(this, "Uji workflow", 12, UiKit.TEXT, true); test.setGravity(Gravity.CENTER); test.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 12, UiKit.BORDER, 1, this)); LinearLayout.LayoutParams testLp = UiKit.lp(this, -1, 40); testLp.topMargin = UiKit.dp(this, 5); body.addView(test, testLp); test.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { promptRunTest(); } });
    }

    private EditText addField(LinearLayout parent, String label, String hint, boolean multi) {
        TextView l = UiKit.label(this, label, 11, UiKit.MUTED, true); LinearLayout.LayoutParams llp = UiKit.lp(this, -1, -2); llp.topMargin = UiKit.dp(this, 7); parent.addView(l, llp);
        EditText e = new EditText(this); e.setTextColor(UiKit.TEXT); e.setHintTextColor(UiKit.MUTED); e.setTextSize(12); e.setSingleLine(!multi); e.setHint(hint); e.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 3), UiKit.dp(this, 10), UiKit.dp(this, 3)); e.setBackground(UiKit.shape(UiKit.SURFACE, 9, UiKit.BORDER, 1, this)); if (multi) { e.setMinLines(2); e.setGravity(Gravity.TOP | Gravity.LEFT); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); } parent.addView(e, UiKit.lp(this, -1, multi ? 68 : 39)); return e;
    }

    private void addCompact(LinearLayout row, String text, View.OnClickListener click) {
        TextView b = UiKit.label(this, text, 10, UiKit.TEXT, true); b.setGravity(Gravity.CENTER); b.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 8, UiKit.BORDER, 1, this)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.leftMargin = UiKit.dp(this, 2); lp.rightMargin = UiKit.dp(this, 2); row.addView(b, lp); b.setOnClickListener(click);
    }

    private void loadMetadataAndEntry() {
        try {
            JSONObject meta = WorkflowManager.get(this).getMetadata(workflowId);
            if (meta == null) throw new IllegalArgumentException("Workflow tidak ditemukan");
            nameField.setText(meta.optString("name", workflowId)); idField.setText(workflowId); idField.setEnabled(false); descriptionField.setText(meta.optString("description", ""));
            setFormat(meta.optString("format", "flow")); entryField.setText(meta.optString("entry", "script/main.flow"));
            JSONObject endpoint = meta.optJSONObject("endpoint"); endpointField.setText(endpoint == null ? "/workflow/" + workflowId : endpoint.optString("path", "/workflow/" + workflowId));
            setEndpointMode(endpoint == null ? "none" : endpoint.optString("mode", endpoint.optBoolean("enabled", false) ? "shared" : "none"));
            endpointPortField.setText(endpoint == null ? "0" : String.valueOf(endpoint.optInt("port", 0)));
            JSONObject permissions = meta.optJSONObject("permissions"); aiWriteInputSwitch.setChecked(permissions != null && permissions.optBoolean("aiWriteInput", false));
            updateEndpointFields();
            currentPathField.setText(meta.optString("entry", "script/main.flow")); loadCurrentPath();
        } catch (Exception e) { toast("Gagal membuka workflow: " + safe(e)); finish(); }
    }

    private void attachFormatChangeListener() {
        final String[] previous = new String[] { format() };
        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long rowId) {
                String next = position == 1 ? "bshflow" : "flow";
                String old = previous[0]; previous[0] = next;
                if (old == null || old.equals(next)) return;
                String oldExt = "." + old; String newExt = "." + next;
                String entry = entryField.getText().toString().trim();
                if (entry.endsWith(oldExt)) entryField.setText(entry.substring(0, entry.length() - oldExt.length()) + newExt);
                String current = currentPathField.getText().toString().trim();
                if (current.endsWith(oldExt)) currentPathField.setText(current.substring(0, current.length() - oldExt.length()) + newExt);
                if (newWorkflow) {
                    String code = codeField.getText().toString();
                    if ("bshflow".equals(next) && (code.length() == 0 || code.equals(defaultFlow()))) codeField.setText(defaultBeanShell());
                    else if ("flow".equals(next) && (code.length() == 0 || code.equals(defaultBeanShell()))) codeField.setText(defaultFlow());
                }
            }
        });
    }

    private void setFormat(String format) {
        int index = "bshflow".equalsIgnoreCase(format) || ".bshflow".equalsIgnoreCase(format) ? 1 : 0;
        if (formatSpinner != null) formatSpinner.setSelection(index);
    }
    private String format() { return formatSpinner != null && formatSpinner.getSelectedItemPosition() == 1 ? "bshflow" : "flow"; }
    private void setEndpointMode(String mode) {
        if (endpointModeSpinner == null) return;
        int index = "shared".equalsIgnoreCase(mode) ? 1 : "exclusive".equalsIgnoreCase(mode) ? 2 : 0;
        endpointModeSpinner.setSelection(index);
    }
    private String endpointMode() {
        return endpointModeSpinner != null && endpointModeSpinner.getSelectedItemPosition() == 1 ? "shared" : endpointModeSpinner != null && endpointModeSpinner.getSelectedItemPosition() == 2 ? "exclusive" : "none";
    }
    private void updateEndpointFields() {
        if (endpointField == null || endpointModeSpinner == null || endpointPortField == null) return;
        String mode = endpointMode();
        endpointField.setEnabled(!"none".equals(mode));
        endpointPortField.setEnabled("exclusive".equals(mode));
        if ("shared".equals(mode)) endpointPortField.setText("8084");
        else if ("none".equals(mode)) endpointPortField.setText("0");
        else if (endpointPortField.getText().toString().trim().length() == 0 || "8084".equals(endpointPortField.getText().toString().trim())) endpointPortField.setText("0");
    }


    private void loadCurrentPath() {
        String path = currentPathField.getText().toString().trim(); if (path.length() == 0) { toast("Isi path file terlebih dahulu"); return; }
        if (newWorkflow) { toast("Simpan workflow terlebih dahulu sebelum membuka file lain"); if (path.equals(entryField.getText().toString().trim())) return; }
        try {
            JSONObject file = WorkflowManager.get(this).readFile(workflowId, path);
            codeField.setText(file.optString("content", "")); stateLabel.setText(workflowId + " · " + path);
        } catch (Exception e) { toast("File belum ada atau tidak dapat dibaca: " + safe(e)); }
    }

    private boolean saveWorkflow() {
        try {
            String id = idField.getText().toString().trim(); String name = nameField.getText().toString().trim(); String description = descriptionField.getText().toString().trim();
            String entry = entryField.getText().toString().trim(); String path = currentPathField.getText().toString().trim(); String endpoint = endpointField.getText().toString().trim();
            if (name.length() == 0) throw new IllegalArgumentException("Nama workflow wajib diisi");
            if (entry.length() == 0 || !entry.startsWith("script/")) throw new IllegalArgumentException("Entry point harus berada di script/");
            if (path.length() == 0) throw new IllegalArgumentException("Path file sedang diedit wajib diisi");
            WorkflowManager manager = WorkflowManager.get(this);
            String mode = endpointMode();
            JSONObject endpointConfig = new JSONObject(); endpointConfig.put("mode", mode); endpointConfig.put("enabled", !"none".equals(mode)); endpointConfig.put("method", "POST"); endpointConfig.put("path", endpoint);
            if ("shared".equals(mode)) { endpointConfig.put("port", 8084); endpointConfig.put("autoPort", false); }
            else if ("exclusive".equals(mode)) {
                String portText = endpointPortField.getText().toString().trim(); int port = portText.length() == 0 ? 0 : Integer.parseInt(portText);
                if (port != 0 && (port < 1024 || port > 65535 || port == 8084 || port == 9010)) throw new IllegalArgumentException("Port eksklusif harus 1024-65535, bukan 8084 atau 9010; gunakan 0 untuk otomatis");
                endpointConfig.put("port", port); endpointConfig.put("autoPort", port == 0);
            } else { endpointConfig.put("port", 0); endpointConfig.put("autoPort", true); }
            JSONObject permissions = new JSONObject(); permissions.put("aiWriteInput", aiWriteInputSwitch.isChecked());
            JSONObject changes = new JSONObject(); changes.put("name", name); changes.put("description", description); changes.put("format", format()); changes.put("entry", entry); changes.put("endpoint", endpointConfig); changes.put("permissions", permissions);
            if (newWorkflow) {
                JSONObject meta = manager.createMetadata(id, name, description, format(), entry, endpoint);
                manager.createWorkflow(meta); workflowId = id; newWorkflow = false; idField.setText(id); idField.setEnabled(false);
            } else if (!id.equals(workflowId)) throw new IllegalArgumentException("ID workflow tidak dapat diganti");
            manager.updateMetadata(workflowId, changes);
            File entryFile = manager.resolveWorkflowFile(workflowId, entry, true);
            if (!entryFile.exists()) manager.writeFile(workflowId, entry, "flow".equals(format()) ? defaultFlow() : defaultBeanShell());
            manager.writeFile(workflowId, path, codeField.getText().toString());
            stateLabel.setText(workflowId + " · tersimpan");
            toast("Workflow tersimpan");
            return true;
        } catch (Exception e) { toast("Gagal menyimpan: " + safe(e)); return false; }
    }

    private void saveWorkflowAndValidate() {
        if (!saveWorkflow()) return;
        if (workflowId.length() == 0 || newWorkflow) return;
        try { JSONObject r = WorkflowManager.get(this).validateWorkflowAndLog(workflowId); new AlertDialog.Builder(this).setTitle(r.optBoolean("ok") ? "Workflow valid" : "Validasi gagal").setMessage(r.optString("message", "")).setPositiveButton("OK", null).show(); }
        catch (Exception e) { toast("Validasi gagal: " + safe(e)); }
    }

    private void promptRunTest() {
        if (!saveWorkflow()) return;
        if (newWorkflow || workflowId.length() == 0) return;
        final String id = workflowId;
        final EditText request = new EditText(this);
        request.setGravity(Gravity.TOP | Gravity.LEFT);
        request.setMinLines(4);
        request.setText("{} ");
        request.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("Input uji — JSON object").setMessage("Workflow akan dijalankan langsung untuk pengujian; endpoint tidak perlu diaktifkan.")
                .setView(request).setNegativeButton("Batal", null).setPositiveButton("Jalankan", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final String inputText = request.getText().toString().trim();
                        Thread worker = new Thread(new Runnable() {
                            public void run() {
                                String title = "Hasil uji"; String message;
                                try {
                                    JSONObject input = inputText.length() == 0 ? new JSONObject() : new JSONObject(inputText);
                                    JSONObject result = WorkflowManager.get(WorkflowEditorActivity.this).run(id, input);
                                    message = result.toString(2);
                                } catch (Exception e) { title = "Uji workflow gagal"; message = safe(e); }
                                final String finalTitle = title; final String finalMessage = message;
                                runOnUiThread(new Runnable() { public void run() {
                                    if (isFinishing()) return;
                                    new AlertDialog.Builder(WorkflowEditorActivity.this).setTitle(finalTitle).setMessage(finalMessage).setPositiveButton("OK", null).show();
                                }});
                            }
                        }, "rh-workflow-test"); worker.setDaemon(true); worker.start();
                    }
                }).show();
    }

    private void promptNewFile() {
        final EditText input = new EditText(this); input.setSingleLine(true); input.setHint("script/helper.flow atau assets/data.txt");
        new AlertDialog.Builder(this).setTitle("Buat file").setMessage("Gunakan path relatif di dalam script/ atau assets/.").setView(input)
            .setNegativeButton("Batal", null).setPositiveButton("Buat", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) {
                String path = input.getText().toString().trim(); if (path.length() == 0) return;
                try {
                    if (newWorkflow && !saveWorkflow()) return;
                    if (newWorkflow) return;
                    WorkflowManager manager = WorkflowManager.get(WorkflowEditorActivity.this); manager.writeFile(workflowId, path, ""); currentPathField.setText(path); codeField.setText(""); stateLabel.setText(workflowId + " · " + path);
                } catch (Exception e) { toast("Gagal membuat file: " + safe(e)); }
            }}).show();
    }

    private void promptNewFolder() {
        final EditText input = new EditText(this); input.setSingleLine(true); input.setHint("assets/images");
        new AlertDialog.Builder(this).setTitle("Buat folder").setView(input).setNegativeButton("Batal", null).setPositiveButton("Buat", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) {
            String path = input.getText().toString().trim(); if (path.length() == 0) return;
            try { if (newWorkflow && !saveWorkflow()) return; if (newWorkflow) return; WorkflowManager.get(WorkflowEditorActivity.this).createFolder(workflowId, path); toast("Folder dibuat: " + path); }
            catch (Exception e) { toast("Gagal membuat folder: " + safe(e)); }
        }}).show();
    }

    private void showFiles() {
        try {
            if (newWorkflow) { toast("Simpan workflow terlebih dahulu"); return; }
            JSONArray files = WorkflowManager.get(this).listFiles(workflowId); final ArrayList<String> paths = new ArrayList<String>();
            for (int i = 0; i < files.length(); i++) paths.add(files.getJSONObject(i).optString("path"));
            if (paths.size() == 0) { toast("Belum ada file"); return; }
            new AlertDialog.Builder(this).setTitle("File workflow").setItems(paths.toArray(new String[paths.size()]), new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int which) { currentPathField.setText(paths.get(which)); loadCurrentPath(); } }).show();
        } catch (Exception e) { toast("Gagal membaca daftar file: " + safe(e)); }
    }

    private void confirmDeleteCurrentFile() {
        final String path = currentPathField.getText().toString().trim(); if (path.length() == 0) return;
        new AlertDialog.Builder(this).setTitle("Hapus file?").setMessage(path).setNegativeButton("Batal", null).setPositiveButton("Hapus", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) {
            try { WorkflowManager.get(WorkflowEditorActivity.this).deleteFile(workflowId, path); codeField.setText(""); toast("File dihapus"); }
            catch (Exception e) { toast("Gagal menghapus: " + safe(e)); }
        }}).show();
    }

    private void importAsset() {
        try { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(Intent.createChooser(i, "Pilih file asset"), REQUEST_ASSET); }
        catch (Exception e) { toast("Pemilih file tidak tersedia: " + safe(e)); }
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (requestCode != REQUEST_ASSET || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (newWorkflow) { toast("Simpan workflow terlebih dahulu"); return; }
        Uri uri = data.getData(); String name = getDisplayName(uri); final String target = "assets/" + name;
        try { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new IllegalStateException("file tidak dapat dibuka"); try { WorkflowManager.get(this).writeAssetBytes(workflowId, target, in, 20L * 1024L * 1024L); } finally { in.close(); } toast("Aset diimpor: " + target); currentPathField.setText(target); codeField.setText(""); }
        catch (Exception e) { toast("Impor aset gagal: " + safe(e)); }
    }
    private String getDisplayName(Uri uri) {
        Cursor c = null; try { c = getContentResolver().query(uri, null, null, null, null); if (c != null && c.moveToFirst()) { int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) return cleanFileName(c.getString(idx)); } } catch (Exception ignored) { } finally { if (c != null) c.close(); }
        String last = uri.getLastPathSegment(); return cleanFileName(last == null ? "asset.bin" : last);
    }
    private String cleanFileName(String name) { String s = name == null ? "asset.bin" : name.replace('/', '_').replace('\\', '_'); if (s.length() == 0 || ".".equals(s) || "..".equals(s)) return "asset.bin"; return s; }
    private String defaultFlow() { return "{\n  \"steps\": [\n    { \"action\": \"navigate\", \"url\": \"https://example.com\" },\n    { \"action\": \"extract\", \"selector\": \"h1\", \"as\": \"title\" },\n    { \"action\": \"respond\", \"value\": \"${vars.title}\" }\n  ]\n}\n"; }
    private String defaultBeanShell() { return "// Browser workflow built-ins:\n// browser.navigate(url); browser.input(selector, value); browser.click(selector);\n// browser.waitFor(selector, timeoutMs); browser.extract(selector); browser.getInput(\"message\");\n// printLog(\"log message\"); browser.setResult(value);\n\n// browser.navigate(\"https://example.com\");\n// printLog(\"halaman dibuka\");\n// browser.setResult(browser.extract(\"h1\"));\n"; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String safe(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    protected void onResume() { super.onResume(); if (newWorkflow && codeField != null && codeField.length() == 0) codeField.setText("flow".equals(format()) ? defaultFlow() : defaultBeanShell()); }
}
