package rh.browser.models;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WorkflowLogActivity extends Activity {
    private String workflowId = "";
    private boolean showErrors;
    private boolean showInteractive;
    private String since = "all";
    private LinearLayout entries;
    private TextView infoTab, errorTab, interactiveTab, title;
    private String lastInteractiveSignature = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> inputDrafts = new HashMap<String, String>();
    private final Runnable interactiveRefresh = new Runnable() {
        public void run() {
            if (showInteractive && !isFinishing()) {
                loadInteractive();
                handler.postDelayed(this, 1500L);
            }
        }
    };

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        workflowId = getIntent().getStringExtra("workflow_id");
        if (workflowId == null) workflowId = "";
        UiKit.applyImmersive(this);
        buildUi();
        loadLogs();
    }
    public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) UiKit.applyImmersive(this); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 6), UiKit.dp(this, 9), UiKit.dp(this, 5)); root.setBackgroundColor(0xFF000000); setContentView(root);
        LinearLayout header = UiKit.horizontal(this);
        TextView back = UiKit.iconButton(this, 0xf060, "Kembali"); header.addView(back, UiKit.lp(this, 40, 40)); back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1); nlp.leftMargin = UiKit.dp(this, 9); header.addView(names, nlp);
        title = UiKit.label(this, "Workflow Logs", 17, 0xFFEAEAEA, true); names.addView(title);
        names.addView(UiKit.label(this, workflowId, 11, 0xFF8B96A7, false));
        TextView refresh = UiKit.iconButton(this, 0xf2f1, "Refresh log"); header.addView(refresh, UiKit.lp(this, 40, 40)); refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loadLogs(); } });
        root.addView(header, UiKit.lp(this, -1, 42));

        LinearLayout toolbar = UiKit.horizontal(this); LinearLayout.LayoutParams tlp = UiKit.lp(this, -1, 39); tlp.topMargin = UiKit.dp(this, 7); root.addView(toolbar, tlp);
        infoTab = makeTab("INFO", !showErrors && !showInteractive); toolbar.addView(infoTab, new LinearLayout.LayoutParams(0, -1, 1)); infoTab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showErrors = false; showInteractive = false; lastInteractiveSignature = ""; stopInteractiveRefresh(); updateTabs(); loadLogs(); } });
        errorTab = makeTab("ERROR", showErrors && !showInteractive); LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, -1, 1); elp.leftMargin = UiKit.dp(this, 4); toolbar.addView(errorTab, elp); errorTab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showErrors = true; showInteractive = false; lastInteractiveSignature = ""; stopInteractiveRefresh(); updateTabs(); loadLogs(); } });
        interactiveTab = makeTab("INTERACTIVE", showInteractive); LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -1, 1.25f); ilp.leftMargin = UiKit.dp(this, 4); toolbar.addView(interactiveTab, ilp); interactiveTab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showInteractive = true; lastInteractiveSignature = ""; updateTabs(); loadInteractive(); startInteractiveRefresh(); } });
        Spinner range = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[] { "Semua waktu", "5 menit", "30 menit", "1 jam" }); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); range.setAdapter(adapter); LinearLayout.LayoutParams rlp = UiKit.lp(this, 105, 38); rlp.leftMargin = UiKit.dp(this, 4); toolbar.addView(range, rlp);
        range.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { since = position == 1 ? "last_5m" : position == 2 ? "last_30m" : position == 3 ? "last_1h" : "all"; if (showInteractive) loadInteractive(); else loadLogs(); }
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xFF000000); LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1); slp.topMargin = UiKit.dp(this, 7); root.addView(scroll, slp);
        entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL); entries.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 16)); entries.setBackgroundColor(0xFF000000); scroll.addView(entries, new ScrollView.LayoutParams(-1, -2));
        UiKit.addBottomNavigation(this, root, "workflow");
    }

    private TextView makeTab(String text, boolean active) {
        TextView view = UiKit.label(this, text, 12, active ? 0xFFFFFFFF : 0xFF9CA3AF, true); view.setGravity(Gravity.CENTER); view.setBackground(UiKit.shape(active ? 0xFF182433 : 0xFF080B10, 7, active ? 0xFF596B81 : 0xFF20252D, 1, this)); return view;
    }
    private void updateTabs() {
        setTabState(infoTab, !showErrors && !showInteractive);
        setTabState(errorTab, showErrors && !showInteractive);
        setTabState(interactiveTab, showInteractive);
    }
    private void setTabState(TextView tab, boolean active) {
        if (tab == null) return;
        tab.setTextColor(active ? 0xFFFFFFFF : 0xFF9CA3AF);
        tab.setBackground(UiKit.shape(active ? 0xFF182433 : 0xFF080B10, 7, active ? 0xFF596B81 : 0xFF20252D, 1, this));
    }

    private void loadLogs() {
        if (showInteractive) { loadInteractive(); return; }
        if (entries == null) return;
        entries.removeAllViews();
        try {
            JSONObject response = WorkflowManager.get(this).getLogs(workflowId, showErrors ? "error" : "info", since, 0L, 500, 0, "");
            JSONArray logs = response.optJSONArray("logs");
            if (logs == null || logs.length() == 0) {
                TextView empty = logText(showErrors ? "[no error logs in selected range]" : "[no info logs in selected range]", 0xFF788494);
                entries.addView(empty, UiKit.lp(this, -1, -2)); return;
            }
            for (int i = logs.length() - 1; i >= 0; i--) {
                JSONObject log = logs.optJSONObject(i); if (log == null) continue;
                String level = log.optString("level", "info").toUpperCase(java.util.Locale.US);
                StringBuilder text = new StringBuilder();
                text.append('[').append(log.optString("timestampText", "time-unknown")).append("] ").append(level);
                String event = log.optString("event", ""); if (event.length() > 0) text.append(" · ").append(event);
                String source = log.optString("sourceFile", ""); int line = log.optInt("lineNumber", 0);
                if (source.length() > 0) { text.append("\n  at ").append(source); if (line > 0) text.append(':').append(line); }
                String exec = log.optString("executionId", ""); if (exec.length() > 0) text.append("\n  execution: ").append(exec);
                text.append("\n").append(log.optString("message", ""));
                String details = log.optString("details", "");
                if (details.length() > 0 && !"null".equals(details)) {
                    if (details.length() > 6000) details = details.substring(0, 6000) + "\n[details truncated]";
                    text.append("\n").append(details);
                }
                text.append("\n\n");
                int color = "ERROR".equals(level) ? 0xFFFF6B6B : "WARN".equals(level) ? 0xFFFFD166 : 0xFFB8C5D6;
                TextView entry = logText(text.toString(), color); LinearLayout.LayoutParams lp = UiKit.lp(this, -1, -2); lp.bottomMargin = UiKit.dp(this, 2); entries.addView(entry, lp);
            }
        } catch (Exception e) {
            TextView error = logText("[log-reader error] " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 0xFFFF6B6B); entries.addView(error, UiKit.lp(this, -1, -2));
        }
    }

    private void loadInteractive() {
        if (entries == null) return;
        try {
            final WorkflowManager manager = WorkflowManager.get(this);
            JSONObject pendingResponse = manager.getPendingInputs(workflowId, 100);
            JSONArray pending = pendingResponse.optJSONArray("pending");
            JSONObject historyResponse = manager.getLogs(workflowId, "all", since, 0L, 100, 0, "");
            JSONArray history = historyResponse.optJSONArray("logs");
            StringBuilder signatureBuilder = new StringBuilder();
            if (pending != null) for (int i = 0; i < pending.length(); i++) { JSONObject p = pending.optJSONObject(i); if (p != null) signatureBuilder.append(p.optString("prompt_id", "")).append(':').append(p.optString("status", "")).append(';'); }
            if (history != null) for (int i = 0; i < history.length(); i++) { JSONObject row = history.optJSONObject(i); if (row != null && row.optString("event", "").startsWith("input.")) { signatureBuilder.append("last=").append(row.optLong("id", 0)); break; } }
            String signature = signatureBuilder.toString() + "|" + since;
            if (signature.equals(lastInteractiveSignature)) return;
            lastInteractiveSignature = signature;
            // Preserve unfinished text before rebuilding only when the queue/history changed.
            for (int i = 0; i < entries.getChildCount(); i++) {
                View child = entries.getChildAt(i);
                if (child.getTag() instanceof String && child instanceof EditText) inputDrafts.put((String)child.getTag(), ((EditText)child).getText().toString());
            }
            entries.removeAllViews();
            if (pending == null || pending.length() == 0) {
                entries.addView(logText("[" + nowText() + "]\n[no pending input] Workflow tidak sedang menunggu input.", 0xFF788494), UiKit.lp(this, -1, -2));
            } else {
                TextView section = logText("PENDING INPUT · " + pending.length() + "\n", 0xFF7EE787);
                entries.addView(section, UiKit.lp(this, -1, -2));
                for (int i = 0; i < pending.length(); i++) {
                    final JSONObject item = pending.optJSONObject(i);
                    if (item == null) continue;
                    final String promptId = item.optString("prompt_id", "");
                    final String executionId = item.optString("execution_id", "");
                    final String itemWorkflowId = item.optString("workflow_id", workflowId);
                    LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 7), UiKit.dp(this, 8), UiKit.dp(this, 8));
                    card.setBackground(UiKit.shape(0xFF090F16, 8, 0xFF29483A, 1, this));
                    LinearLayout.LayoutParams cp = UiKit.lp(this, -1, -2); cp.bottomMargin = UiKit.dp(this, 8); entries.addView(card, cp);
                    card.addView(logText("[" + item.optString("created_at_text", "time-unknown") + "] INPUT REQUIRED", 0xFF7EE787), UiKit.lp(this, -1, -2));
                    card.addView(logText("workflow: " + itemWorkflowId + "\nexecution: " + executionId + "\nprompt: " + promptId, 0xFF8B96A7), UiKit.lp(this, -1, -2));
                    TextView prompt = logText(item.optString("prompt", "Input diperlukan"), 0xFFE6EDF3); prompt.setTextSize(13); prompt.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 6)); card.addView(prompt, UiKit.lp(this, -1, -2));
                    final String inputType = item.optString("input_type", "text");
                    final boolean required = item.optBoolean("required", true);
                    final EditText field;
                    if ("choice".equals(inputType) && item.optJSONArray("choices") != null && item.optJSONArray("choices").length() > 0) {
                        final Spinner choice = new Spinner(this);
                        JSONArray choices = item.optJSONArray("choices");
                        final ArrayList<String> values = new ArrayList<String>();
                        for (int c = 0; c < choices.length(); c++) values.add(String.valueOf(choices.opt(c)));
                        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); choice.setAdapter(adapter);
                        card.addView(choice, UiKit.lp(this, -1, 42));
                        // Wrap Spinner state into a tiny EditText value holder so the same submit path is used.
                        field = new EditText(this); field.setVisibility(View.GONE); card.addView(field, UiKit.lp(this, 1, 1));
                        field.setTag(promptId);
                        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            public void onNothingSelected(AdapterView<?> parent) { field.setText(""); }
                            public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) { if (position >= 0 && position < values.size()) field.setText(values.get(position)); }
                        });
                    } else {
                        field = new EditText(this);
                        field.setTag(promptId);
                        field.setTextSize(12); field.setTextColor(0xFFE6EDF3); field.setHintTextColor(0xFF788494);
                        field.setHint(required ? "Input wajib diisi" : "Input opsional"); field.setSingleLine(false);
                        field.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 6), UiKit.dp(this, 9), UiKit.dp(this, 6));
                        field.setBackground(UiKit.shape(0xFF03070D, 7, 0xFF30363D, 1, this));
                        if ("number".equals(inputType)) field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                        else if ("password".equals(inputType)) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        else field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        String draft = inputDrafts.get(promptId); if (draft != null) field.setText(draft); else field.setText(item.optString("default_value", ""));
                        field.addTextChangedListener(new android.text.TextWatcher() {
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                            public void onTextChanged(CharSequence s, int start, int before, int count) { inputDrafts.put(promptId, s == null ? "" : s.toString()); }
                            public void afterTextChanged(android.text.Editable e) { }
                        });
                        card.addView(field, UiKit.lp(this, -1, "text".equals(inputType) || "password".equals(inputType) ? 44 : 42));
                    }
                    TextView send = UiKit.label(this, "Kirim input", 12, 0xFFFFFFFF, true); send.setGravity(Gravity.CENTER); send.setBackground(UiKit.gradient(0xFF20764B, 0xFF14532D, 8, this));
                    LinearLayout.LayoutParams sendLp = UiKit.lp(this, -1, 39); sendLp.topMargin = UiKit.dp(this, 6); card.addView(send, sendLp);
                    send.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            try {
                                String value = field.getText().toString();
                                if (required && value.trim().length() == 0) { Toast.makeText(WorkflowLogActivity.this, "Input wajib diisi", Toast.LENGTH_SHORT).show(); return; }
                                manager.submitInput(itemWorkflowId, executionId, promptId, value, false);
                                inputDrafts.remove(promptId);
                                Toast.makeText(WorkflowLogActivity.this, "Input dikirim; workflow melanjutkan eksekusi", Toast.LENGTH_SHORT).show();
                                loadInteractive();
                            } catch (Exception e) { Toast.makeText(WorkflowLogActivity.this, "Gagal mengirim input: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show(); }
                        }
                    });
                }
            }
            if (history != null) {
                boolean added = false;
                for (int i = history.length() - 1; i >= 0; i--) {
                    JSONObject row = history.optJSONObject(i); if (row == null) continue;
                    String event = row.optString("event", "");
                    if (!event.startsWith("input.")) continue;
                    if (!added) { entries.addView(logText("\nINPUT HISTORY\n", 0xFF9CA3AF), UiKit.lp(this, -1, -2)); added = true; }
                    String text = "[" + row.optString("timestampText", "time-unknown") + "] " + event + "\n" + row.optString("message", "");
                    String details = row.optString("details", ""); if (details.length() > 0 && !"null".equals(details)) text += "\n" + details;
                    entries.addView(logText(text + "\n\n", 0xFFB8C5D6), UiKit.lp(this, -1, -2));
                }
            }
        } catch (Exception e) {
            entries.addView(logText("[interactive-reader error] " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 0xFFFF6B6B), UiKit.lp(this, -1, -2));
        }
    }

    private String nowText() { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()); }
    private void startInteractiveRefresh() { handler.removeCallbacks(interactiveRefresh); handler.postDelayed(interactiveRefresh, 1500L); }
    private void stopInteractiveRefresh() { handler.removeCallbacks(interactiveRefresh); }

    private TextView logText(String value, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(color); t.setTextSize(11); t.setTypeface(android.graphics.Typeface.MONOSPACE); t.setTextIsSelectable(true); t.setGravity(Gravity.TOP | Gravity.LEFT); t.setIncludeFontPadding(true); t.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 4)); return t;
    }
    protected void onResume() { super.onResume(); loadLogs(); if (showInteractive) startInteractiveRefresh(); }
    protected void onPause() { stopInteractiveRefresh(); super.onPause(); }
    protected void onDestroy() { stopInteractiveRefresh(); super.onDestroy(); }
}
