# Rh Browser for Models

<p align="center"><a href="#english">English</a> | <a href="#bahasa-indonesia">Bahasa Indonesia</a></p>

## English

An Android app that gives AI models access to a real mobile browser through the Model Context Protocol (MCP). It runs a local `WebView` browser that an AI client controls through an MCP endpoint on localhost, or through a public tunnel, so a model can open pages, read the DOM, click elements, fill forms, take screenshots and reach page resources without a desktop browser. It also includes a Workflow subsystem for saving automation scripts on the device and running them on demand.

**Current version:** `1.5.0-models-browser.beta` (version code `151`), package `rh.browser.models`. Requires Android 6.0 (API 23) or newer and targets API 30. The code follows a Java 7 style, and BeanShell is loaded through reflection.

### Features

- A `WebView` browser that stays alive in a foreground service, even when the app is not visible
- An MCP server (JSON-RPC over HTTP) with 48 tools: 27 browser tools and 21 workflow tools
- A tool registry that can be switched on or off per tool or per category, with read/write access modes
- Optional bearer-token authentication for the MCP endpoint
- A Cloudflare quick tunnel that the user starts manually
- A Workflow subsystem: declarative `.flow` scripts and BeanShell `.bshflow` scripts, a native editor, execution logs, interactive input and optional local HTTP endpoints
- A built-in download manager screen
- A recovery watchdog and an in-process health check for the listeners

### Architecture

The project is a single Android module. The Java sources are in `app/src/main/java/rh/browser/models/`.

```text
browser-for-models/
├── app/
│   ├── libs/bsh-2.0b4.jar                  (BeanShell)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/fonts/
│       ├── java/rh/browser/models/
│       ├── jniLibs/arm64-v8a/libcloudflared.so
│       └── res/
├── build.gradle
├── settings.gradle
├── .gitignore
├── LICENSE
└── README.md
```

#### Main source files

- `MainActivity`: the browser screen, with an address bar, back/forward/reload, open and copy URL actions, and the browser control panel. It shows the WebView that belongs to the service.
- `BrowserService`: the foreground service that hosts `BrowserManager`, `McpServer`, the workflow HTTP listeners and the optional tunnel. It also runs the health check and recovery logic.
- `BrowserManager`: performs the real WebView operations: opening pages, inspecting and searching the DOM, clicks and coordinate clicks, text and file input, option selection, key presses, scrolling, page source, screenshots, page resources, network observation and the console.
- `McpServer`: the HTTP JSON-RPC server. It handles `initialize`, `ping`, `tools/list`, `resources/list`, `prompts/list` and `tools/call`, and checks the bearer token when authentication is enabled.
- `ToolRegistry`, `ToolDef`, `ToolSettings`, `ToolSettingsActivity`: tool definitions with input schema, category, access mode and default state, plus the settings screen **Tool Browser** that stores `auth_enabled`, `mcp_token`, `server_enabled`, per-tool toggles and category read/write states.
- `ServiceSettingsActivity`: the **Service & Tunnel** screen for the server status, the tunnel, the local endpoint and token, authentication, battery optimization and the Vivo autostart settings.
- `BrowserDownloads`, `DownloadsActivity`: the download manager screen.
- `McpFileProvider`: a read-only content provider that serves files staged in the app cache (`mcp_input_files`).
- `ServiceRecoveryReceiver`, `BootReceiver`: a best-effort watchdog and the boot/package-replaced receiver.
- `UiKit`: the shared native UI helpers.
- `WorkflowManager`: native storage, metadata, the log database, ZIP import/export and the MCP management API.
- `WorkflowExecutor`: runs declarative `.flow` jobs and BeanShell `.bshflow` jobs, each in its own WebView.
- `WorkflowBrowser`: the independent WebView used by workflows.
- `WorkflowRuntimeApi`: the object surface that BeanShell workflow scripts can use.
- `WorkflowHttpServer`: the optional local HTTP endpoints of workflows.
- `WorkflowActivity`, `WorkflowEditorActivity`, `WorkflowLogActivity`, `WorkflowExecutionException`: the workflow list, the native editor, the log screen and the execution error type.

### How it works

1. The user opens the app, and `MainActivity` connects to `BrowserService`.
2. `BrowserService` starts `BrowserManager` and `McpServer` (port `9010`).
3. An AI client connects to `http://127.0.0.1:9010/mcp`, or to a public tunnel if the user started one.
4. The client calls `tools/list`, chooses a tool and calls it through JSON-RPC.
5. `BrowserManager` (or the workflow runtime) operates a real WebView.
6. The result returns as MCP content: text, an image or a blob/resource.

The health endpoint is `http://127.0.0.1:9010/health`.

### MCP tools

Every tool has an input schema, a category (`browser` or `workflow`), an access mode (read or write) and a default enabled state. Tools can be switched off one by one in **Tool Browser**.

**Browser tools (27)**

- Navigation and page profile: `browser.open`, `browser.back`, `browser.wait_for`, `browser.set_mode`, `browser.set_viewport`, `browser.set_user_agent`
- Inspection and reading: `browser.inspect`, `browser.search_element`, `browser.get_element`, `browser.source`, `browser.screenshot`, `browser.map_coordinates`, `browser.item.list`, `browser.get.item`, `browser.network`, `browser.debug.console`
- Interaction: `browser.click`, `browser.click_at`, `browser.scroll`, `browser.press_key`, `browser.input`, `browser.input_text`, `browser.input_files`, `browser.input_option`
- Site data: `browser.clear_cookies`, `browser.clear_localstorage`, `browser.clear_sessionstorage`

A few notes on these tools:

- `browser.inspect` returns interactive elements with `eN` references. A reference is only valid for the latest inspection result.
- `browser.wait_for` supports the conditions `element`, `text`, `url`, `load`, `hidden`, `network_idle` and `custom`.
- `browser.map_coordinates` returns a viewport screenshot with a labeled grid, so visual elements can be mapped to `browser.click_at`.
- `browser.input_files` puts files into an `input type=file` through the native WebView file chooser. The source can be a path, a URL, base64 or inline content.
- `browser.network` lists observed requests. Without arguments it returns all of them, and the optional filters are combined with AND.
- `browser.item.list` and `browser.get.item` list resources referenced by the page and fetch one again by item ID.

**Workflow tools (21)**

- Management: `workflow.list`, `workflow.create`, `workflow.update`, `workflow.delete`
- Files: `workflow.list_files`, `workflow.read_file`, `workflow.write_file`, `workflow.create_folder`, `workflow.delete_file`
- Lifecycle: `workflow.validate`, `workflow.activate`, `workflow.deactivate`
- Execution: `workflow.run`, `workflow.start`, `workflow.get_execution`
- Interactive input: `workflow.get_pending_input`, `workflow.submit_input`
- Logs: `workflow.get_logs`, `workflow.get_info_logs`, `workflow.get_error_logs`, `workflow.get_errors`

### Workflow subsystem

A workflow is a saved automation that runs in its own WebView. It can be created and edited in the app or through the MCP workflow tools.

#### Storage layout

Workflows are stored privately at `files/workflows/<id>/`. `metadata.json` is generated and updated by the editor or by the structured MCP metadata tools; authors normally edit only the script files.

```text
search-tes/
├── metadata.json
├── script/
│   ├── main.flow
│   └── helpers.bshflow
└── assets/
    ├── config.json
    ├── template.html
    └── images/
        └── logo.png
```

The app creates `script/` and `assets/`. The editor can create and open files and folders, edit text, delete files, import binary assets, choose an entry point, and export or import a whole workflow as a ZIP. The installer expects `workflow/metadata.json` and the related `workflow/script/` and `workflow/assets/` entries in a ZIP.

#### Metadata and lifecycle

Metadata fields include `id`, `name`, `description`, `format`, `entry`, `enabled`, `endpoint` and `permissions`. The editor handles metadata as form fields, not as a raw JSON editor. A new or imported workflow stays disabled until it is activated explicitly.

`enabled` is the desired activation state. An endpoint is a separate, optional transport: a workflow can be installed and run without opening any HTTP port.

#### Declarative `.flow`

A `.flow` file is UTF-8 JSON with a `steps` array:

```json
{
  "steps": [
    { "action": "navigate", "url": "https://example.com" },
    { "action": "extract", "selector": "h1", "as": "title" }
  ],
  "output": { "title": "${vars.title}" }
}
```

Supported actions:

- `navigate` / `open`
- `waitFor` / `wait_for` / `wait`
- `input` / `prompt` / `input_prompt` (interactive prompt) and `input` / `fill` (DOM input)
- `click`
- `extract`, `extractAll` / `extract_all`
- `set`, `delay` / `sleep`, `scroll`, `assert`
- `respond` / `return`
- `log` / `printLog`
- `readAsset` / `read_asset` and `writeAsset` / `write_asset` for text or base64 asset files (`readAsset` can use `parseJson: true` to parse a JSON asset into a value)

For a DOM input step, set `selector`. An interactive prompt looks like this:

```json
{
  "action": "input",
  "name": "query",
  "prompt": "Enter the search query",
  "inputType": "text",
  "required": true,
  "timeout": 600000
}
```

The interactive step checks `input.query` first. If the caller already supplied it, the value is reused without prompting, unless `alwaysPrompt` is `true`. If the required input is missing, execution pauses and asks the user or an authorized AI. The answer is added to the input object and also stored in the step variable (`as`, or the input name by default).

Placeholders support `${input.message}`, nested paths such as `${input.query.text}`, `${vars.name}`, and the moustache forms `{{input.query}}` and `{{vars.name}}`. `extract` stores into `vars.result` by default, or into the name given by `as`; `extractAll` stores an array. The root `output` object or value defines the final output, and output placeholders can return JSON values and arrays, not only strings.

`waitFor.condition` supports `element` (default), `text`, `url`, `hidden` / `element_hidden` and `custom`. A `custom` condition is a JavaScript expression evaluated inside the workflow page.

#### BeanShell `.bshflow`

`.bshflow` runs through `bsh.Interpreter` by reflection. Syntax validation uses `bsh.Parser` without executing the script. BeanShell comes from `app/libs/bsh-2.0b4.jar`, which is part of this repository.

The script receives the `browser` and `workflow` objects. These functions are defined by the runtime: `printLog(Object)`, `logInfo(Object)`, `logWarn(Object)`, `logError(Object)`, `input(String prompt)` and `input(String prompt, String inputKey)`. `input(prompt)` opens an interactive prompt. `input(prompt, key)` uses an input value that was already provided, and otherwise prompts and stores the answer under that key.

```java
String query = input("Enter the search query");
printLog("Query received: " + query);
browser.navigate("https://example.com/search?q=" + query);
String title = browser.extract("h1");
browser.setResult(title);
```

Available `browser` / `workflow` methods: `navigate(url[, timeoutMs])`, `waitFor(selector, timeoutMs)`, `waitForText(text, timeoutMs)`, `waitForUrl(urlPart, timeoutMs)`, `input(selector, value)`, `click(selector)`, `extract(selector[, attribute])`, `extractAll(selector[, attribute])`, `text(selector)`, `exists(selector)`, `getInput(name)`, `getInputValue(name)`, `getInputJson()`, `setResult(value)`, `getResult()`, `getUrl()`, `printLog(message)`, `logInfo(message)`, `logWarn(message)`, `logError(message)`, `readAsset(path)`, `writeAsset(path, content)`, `assetPath(path)`, `delay(ms)` and `scroll(direction, pixels, selector)`.

#### Optional HTTP endpoints

The endpoint mode is configured per workflow:

- `none` (default): no endpoint and no listening port. The workflow can still run manually from the app or through MCP.
- `shared`: registers a unique path on the shared HTTP listener at `127.0.0.1:8084`. Port `8084` is only bound while at least one active, valid workflow asks for shared mode.
- `exclusive`: opens one listener on a dedicated port for that workflow. A workflow can own at most one exclusive port, and at most five exclusive listeners are active at once. Port `0` lets the system choose a free ephemeral port; explicit ports from `1024` to `65535` are accepted, except `8084` and `9010`. If a remembered automatic port is now in use, the app asks for a new one.

Example for shared mode: `POST http://127.0.0.1:8084/workflow/search-tes`. An exclusive endpoint uses the port and path shown in the workflow list. The request must be a JSON object of up to 2 MiB (an empty body means `{}`), and it becomes the workflow `input`. The response contains `ok`, `workflowId`, `executionId`, `durationMs` and the workflow result.

Every endpoint is opt-in, and activating a workflow with mode `none` opens no port. The listeners bind to `127.0.0.1`, not to LAN interfaces. Serving endpoints requires `BrowserService` to be running with the server setting enabled. The MCP server stays on `9010`, and the Cloudflare tunnel still targets that MCP port, never the workflow ports.

Each execution uses a separate WebView instance, so its navigation and DOM state do not change the main model browser. The default WebView cookie and profile storage is still shared with the app's other WebViews.

#### Interactive input and AI permission

Both `.flow` and `.bshflow` can pause for input. The workflow log screen has an `INTERACTIVE` tab with a native field and a **Kirim input** button. Every prompt is tied to a unique `workflow_id`, `execution_id` and `prompt_id`, and prompts and submissions are timestamped to the millisecond. A prompt has a bounded timeout (default 10 minutes, maximum 30 minutes). The supported input types are `text`, `number`, `password`, `boolean` and `choice`.

An AI can inspect pending prompts and submit answers through MCP. Reading pending prompts is allowed even when write access is disabled. Each workflow has `permissions.aiWriteInput`, which defaults to `false` and is controlled only by the **Izinkan AI mengirim input interaktif** toggle in the local editor. MCP tools and imported ZIP metadata cannot grant it to themselves. While it is `false`, `workflow.submit_input` is rejected for that workflow, but human input in the app stays allowed. The runtime enforces this check; it does not rely on the UI. AI submissions must give matching workflow, execution and prompt IDs.

#### Running and managing workflows through MCP

`workflow.run` executes synchronously and needs neither activation nor an HTTP endpoint. For workflows that may pause for interactive input, use `workflow.start`: it returns an execution ID once the execution is registered, so the model can call `workflow.get_pending_input`, submit an answer (when the per-workflow permission is on) and poll `workflow.get_execution` until the status leaves `running`. The possible states are `waiting_input`, `running`, `completed` and `failed`; `waiting_input` means a prompt should be fetched. A finished execution includes its result or error.

JSON inputs go in the `input` argument. Binary assets move through the MCP file tools as base64. `metadata.json` is changed through structured metadata operations (`workflow.create` and `workflow.update`), not through the general file writer.

#### Logs

Logs are kept in the private SQLite database `workflow_log.db`, table `workflow_logs`, and are shared by the terminal-style screen and the MCP log tools. The log screen of each workflow separates `INFO`, `ERROR` and `INTERACTIVE`. An entry contains a timestamp (`yyyy-MM-dd HH:mm:ss.SSS`), the event, the message, the source file, the line or step when available, the execution ID and optional details.

The MCP log filters accept `since` with `last_5m`, `last_30m`, `last_1h` or `all`, plus an optional level, workflow, execution ID, an explicit lower timestamp in epoch milliseconds, `limit` and `offset`. The database keeps the newest 20,000 records.

### Service and tunnel

- The Cloudflare tunnel never starts by itself from `BrowserService`. It starts only when the user presses the tunnel action in **Service & Tunnel**.
- That action is a start/stop toggle, and it offers a retry if the last start failed. The tunnel intent lives in memory only, so a service or process restart does not reopen a public tunnel on its own.
- Saved tunnel URLs are cleared when the service process is recreated and when the tunnel stops. If the tunnel process exits, the app records the exit instead of keeping a stale public URL. Reconnection is only attempted after a manual start, and only while that service instance stays alive.
- The tunnel is started with `libcloudflared.so tunnel --url http://127.0.0.1:9010`. `cloudflared` prints a `trycloudflare` subdomain and the app stores it as the public URL.
- An in-process health check runs every 60 seconds. If the MCP listener stops while the service process is still alive, the service recreates it, and the workflow HTTP listener is checked and restarted the same way.
- The recovery alarm interval is 10 minutes, and a failed listener start is retried sooner.

**Reliability limits.** Android and Vivo can still terminate the app process, especially after a force-stop or because of vendor-specific restrictions. A foreground service with AlarmManager recovery is best-effort, not a guarantee. The in-process health check cannot run once the whole process is killed; the external recovery alarm covers that case, and Android may delay it in idle modes. Battery optimization and autostart settings are usually needed on Vivo devices.

### Authentication and security

- Bearer-token authentication is optional. When it is enabled, requests are only accepted with the correct token (`Authorization: Bearer <token>`).
- The local endpoint is limited to the device. A public tunnel can expose the browser to the internet, so enable the token before you start one. The app is not secure by default for the public internet without access control.
- Workflow HTTP listeners bind to `127.0.0.1` only, and the tunnel never targets them.
- Workflow AI write permission is per workflow and can only be granted from the local editor.
- The manifest allows cleartext HTTP traffic, so pages loaded over `http://` work.

### Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `READ_EXTERNAL_STORAGE` (up to API 32), `MANAGE_EXTERNAL_STORAGE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The service runs as a `dataSync` foreground service.

### Build and run

1. Clone this repository, or download it as a ZIP.
2. Open the project in AIDE (the project uses a Java 7 style).
3. Make sure `app/libs/bsh-2.0b4.jar` and `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` are present. Both are in the repository. The tunnel binary targets `arm64-v8a`; other ABIs need a matching `cloudflared` binary.
4. Build and install the app on an Android device.
5. Open the app, enable the browser/MCP server and, if you need it, the tunnel.
6. Connect your AI client to the local endpoint or to the public tunnel URL.

### License

This repository uses the MIT license. See `LICENSE` for the full terms.

---

## Bahasa Indonesia

Aplikasi Android yang memberi model AI akses ke browser seluler sungguhan lewat Model Context Protocol (MCP). Aplikasi ini menjalankan browser `WebView` lokal yang dikendalikan client AI lewat endpoint MCP di localhost atau lewat tunnel publik, sehingga model bisa membuka halaman, membaca DOM, mengklik elemen, mengisi form, mengambil screenshot, dan mengakses resource halaman tanpa browser desktop. Aplikasi ini juga punya subsistem Workflow untuk menyimpan skrip otomasi di perangkat dan menjalankannya sesuai kebutuhan.

**Versi saat ini:** `1.5.0-models-browser.beta` (version code `151`), package `rh.browser.models`. Membutuhkan Android 6.0 (API 23) atau lebih baru dan menargetkan API 30. Kodenya bergaya Java 7, dan BeanShell dimuat lewat reflection.

### Fitur

- Browser `WebView` yang tetap hidup di foreground service, bahkan saat aplikasi tidak terlihat
- Server MCP (JSON-RPC lewat HTTP) dengan 48 tool: 27 tool browser dan 21 tool workflow
- Tool registry yang bisa dinyalakan atau dimatikan per tool atau per kategori, dengan mode akses read/write
- Autentikasi bearer token opsional untuk endpoint MCP
- Cloudflare quick tunnel yang dinyalakan manual oleh pengguna
- Subsistem Workflow: skrip `.flow` deklaratif dan skrip BeanShell `.bshflow`, editor native, log eksekusi, input interaktif, dan endpoint HTTP lokal opsional
- Layar download manager bawaan
- Watchdog pemulihan dan health check dalam proses untuk listener

### Arsitektur

Project ini satu modul Android. Source Java ada di `app/src/main/java/rh/browser/models/`.

```text
browser-for-models/
├── app/
│   ├── libs/bsh-2.0b4.jar                  (BeanShell)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/fonts/
│       ├── java/rh/browser/models/
│       ├── jniLibs/arm64-v8a/libcloudflared.so
│       └── res/
├── build.gradle
├── settings.gradle
├── .gitignore
├── LICENSE
└── README.md
```

#### File source utama

- `MainActivity`: layar browser, berisi address bar, back/forward/reload, aksi buka dan salin URL, serta panel kontrol browser. Layar ini menampilkan WebView milik service.
- `BrowserService`: foreground service yang menjalankan `BrowserManager`, `McpServer`, listener HTTP workflow, dan tunnel opsional. Service ini juga menjalankan health check dan logika pemulihan.
- `BrowserManager`: melakukan operasi WebView yang nyata: membuka halaman, inspeksi dan pencarian DOM, klik dan klik koordinat, input teks dan file, pemilihan opsi, tombol keyboard, scroll, source halaman, screenshot, resource halaman, pengamatan network, dan console.
- `McpServer`: server HTTP JSON-RPC. Server ini menangani `initialize`, `ping`, `tools/list`, `resources/list`, `prompts/list`, dan `tools/call`, serta memeriksa bearer token jika autentikasi aktif.
- `ToolRegistry`, `ToolDef`, `ToolSettings`, `ToolSettingsActivity`: definisi tool beserta input schema, kategori, mode akses, dan status default, ditambah layar pengaturan **Tool Browser** yang menyimpan `auth_enabled`, `mcp_token`, `server_enabled`, toggle per tool, dan status read/write per kategori.
- `ServiceSettingsActivity`: layar **Service & Tunnel** untuk status server, tunnel, endpoint lokal dan token, autentikasi, optimasi baterai, serta pengaturan autostart Vivo.
- `BrowserDownloads`, `DownloadsActivity`: layar download manager.
- `McpFileProvider`: content provider read-only yang menyajikan file yang disiapkan di cache aplikasi (`mcp_input_files`).
- `ServiceRecoveryReceiver`, `BootReceiver`: watchdog best-effort dan receiver untuk boot/penggantian package.
- `UiKit`: helper UI native bersama.
- `WorkflowManager`: penyimpanan native, metadata, database log, import/export ZIP, dan API manajemen MCP.
- `WorkflowExecutor`: menjalankan job `.flow` deklaratif dan job `.bshflow` BeanShell, masing-masing di WebView sendiri.
- `WorkflowBrowser`: WebView independen yang dipakai workflow.
- `WorkflowRuntimeApi`: permukaan objek yang bisa dipakai skrip workflow BeanShell.
- `WorkflowHttpServer`: endpoint HTTP lokal opsional milik workflow.
- `WorkflowActivity`, `WorkflowEditorActivity`, `WorkflowLogActivity`, `WorkflowExecutionException`: daftar workflow, editor native, layar log, dan tipe error eksekusi.

### Cara kerja

1. Pengguna membuka aplikasi, lalu `MainActivity` terhubung ke `BrowserService`.
2. `BrowserService` menjalankan `BrowserManager` dan `McpServer` (port `9010`).
3. Client AI terhubung ke `http://127.0.0.1:9010/mcp`, atau ke tunnel publik jika pengguna sudah menyalakannya.
4. Client memanggil `tools/list`, memilih tool, lalu memanggilnya lewat JSON-RPC.
5. `BrowserManager` (atau runtime workflow) mengoperasikan WebView yang nyata.
6. Hasilnya kembali sebagai konten MCP: teks, gambar, atau blob/resource.

Endpoint kesehatan ada di `http://127.0.0.1:9010/health`.

### Tool MCP

Setiap tool punya input schema, kategori (`browser` atau `workflow`), mode akses (read atau write), dan status default aktif. Tool bisa dimatikan satu per satu di **Tool Browser**.

**Tool browser (27)**

- Navigasi dan profil halaman: `browser.open`, `browser.back`, `browser.wait_for`, `browser.set_mode`, `browser.set_viewport`, `browser.set_user_agent`
- Inspeksi dan pembacaan: `browser.inspect`, `browser.search_element`, `browser.get_element`, `browser.source`, `browser.screenshot`, `browser.map_coordinates`, `browser.item.list`, `browser.get.item`, `browser.network`, `browser.debug.console`
- Interaksi: `browser.click`, `browser.click_at`, `browser.scroll`, `browser.press_key`, `browser.input`, `browser.input_text`, `browser.input_files`, `browser.input_option`
- Data situs: `browser.clear_cookies`, `browser.clear_localstorage`, `browser.clear_sessionstorage`

Beberapa catatan tentang tool tersebut:

- `browser.inspect` mengembalikan elemen interaktif dengan referensi `eN`. Referensi hanya berlaku untuk hasil inspeksi terbaru.
- `browser.wait_for` mendukung kondisi `element`, `text`, `url`, `load`, `hidden`, `network_idle`, dan `custom`.
- `browser.map_coordinates` mengembalikan screenshot viewport dengan grid berlabel, sehingga elemen visual bisa dipetakan ke `browser.click_at`.
- `browser.input_files` memasukkan file ke `input type=file` lewat file chooser WebView native. Sumbernya bisa berupa path, URL, base64, atau konten langsung.
- `browser.network` menampilkan request yang teramati. Tanpa argumen, tool ini mengembalikan semuanya, dan filter opsional digabung dengan logika AND.
- `browser.item.list` dan `browser.get.item` mendaftar resource yang dirujuk halaman dan mengambil ulang satu resource berdasarkan ID item.

**Tool workflow (21)**

- Manajemen: `workflow.list`, `workflow.create`, `workflow.update`, `workflow.delete`
- File: `workflow.list_files`, `workflow.read_file`, `workflow.write_file`, `workflow.create_folder`, `workflow.delete_file`
- Siklus hidup: `workflow.validate`, `workflow.activate`, `workflow.deactivate`
- Eksekusi: `workflow.run`, `workflow.start`, `workflow.get_execution`
- Input interaktif: `workflow.get_pending_input`, `workflow.submit_input`
- Log: `workflow.get_logs`, `workflow.get_info_logs`, `workflow.get_error_logs`, `workflow.get_errors`

### Subsistem Workflow

Workflow adalah otomasi tersimpan yang berjalan di WebView-nya sendiri. Workflow bisa dibuat dan diedit di aplikasi atau lewat tool workflow MCP.

#### Struktur penyimpanan

Workflow disimpan secara privat di `files/workflows/<id>/`. `metadata.json` dibuat dan diperbarui oleh editor atau oleh tool metadata MCP yang terstruktur; penulis workflow biasanya hanya mengedit file skrip.

```text
search-tes/
├── metadata.json
├── script/
│   ├── main.flow
│   └── helpers.bshflow
└── assets/
    ├── config.json
    ├── template.html
    └── images/
        └── logo.png
```

Aplikasi membuat folder `script/` dan `assets/`. Editor bisa membuat dan membuka file serta folder, mengedit teks, menghapus file, mengimpor aset biner, memilih entry point, dan mengekspor atau mengimpor seluruh workflow sebagai ZIP. Installer mengharapkan `workflow/metadata.json` beserta entri `workflow/script/` dan `workflow/assets/` di dalam ZIP.

#### Metadata dan siklus hidup

Field metadata mencakup `id`, `name`, `description`, `format`, `entry`, `enabled`, `endpoint`, dan `permissions`. Editor memperlakukan metadata sebagai field form, bukan editor JSON mentah. Workflow baru atau hasil impor tetap nonaktif sampai diaktifkan secara eksplisit.

`enabled` adalah status aktivasi yang diinginkan. Endpoint adalah jalur transport terpisah yang opsional: workflow bisa dipasang dan dijalankan tanpa membuka port HTTP apa pun.

#### `.flow` deklaratif

File `.flow` adalah JSON UTF-8 dengan array `steps`:

```json
{
  "steps": [
    { "action": "navigate", "url": "https://example.com" },
    { "action": "extract", "selector": "h1", "as": "title" }
  ],
  "output": { "title": "${vars.title}" }
}
```

Aksi yang didukung:

- `navigate` / `open`
- `waitFor` / `wait_for` / `wait`
- `input` / `prompt` / `input_prompt` (prompt interaktif) dan `input` / `fill` (input DOM)
- `click`
- `extract`, `extractAll` / `extract_all`
- `set`, `delay` / `sleep`, `scroll`, `assert`
- `respond` / `return`
- `log` / `printLog`
- `readAsset` / `read_asset` dan `writeAsset` / `write_asset` untuk file aset teks atau base64 (`readAsset` bisa memakai `parseJson: true` untuk mengurai aset JSON menjadi nilai)

Untuk langkah input DOM, isi `selector`. Prompt interaktif berbentuk seperti ini:

```json
{
  "action": "input",
  "name": "query",
  "prompt": "Masukkan query pencarian",
  "inputType": "text",
  "required": true,
  "timeout": 600000
}
```

Langkah interaktif memeriksa `input.query` terlebih dahulu. Jika pemanggil sudah menyediakannya, nilainya dipakai tanpa prompt, kecuali `alwaysPrompt` bernilai `true`. Jika input yang wajib belum ada, eksekusi dijeda dan meminta nilainya ke pengguna atau AI yang diberi izin. Jawabannya ditambahkan ke objek input dan juga disimpan di variabel langkah (`as`, atau nama input secara default).

Placeholder mendukung `${input.message}`, path bersarang seperti `${input.query.text}`, `${vars.name}`, serta bentuk moustache `{{input.query}}` dan `{{vars.name}}`. `extract` menyimpan ke `vars.result` secara default, atau ke nama yang diberikan lewat `as`; `extractAll` menyimpan array. Objek atau nilai `output` di root menentukan output akhir, dan placeholder di output bisa mengembalikan nilai JSON dan array, bukan hanya string.

`waitFor.condition` mendukung `element` (default), `text`, `url`, `hidden` / `element_hidden`, dan `custom`. Kondisi `custom` adalah ekspresi JavaScript yang dievaluasi di dalam halaman workflow.

#### `.bshflow` BeanShell

`.bshflow` dijalankan lewat `bsh.Interpreter` secara reflektif. Validasi sintaks memakai `bsh.Parser` tanpa menjalankan skrip. BeanShell berasal dari `app/libs/bsh-2.0b4.jar`, yang menjadi bagian dari repository ini.

Skrip menerima objek `browser` dan `workflow`. Fungsi berikut didefinisikan oleh runtime: `printLog(Object)`, `logInfo(Object)`, `logWarn(Object)`, `logError(Object)`, `input(String prompt)`, dan `input(String prompt, String inputKey)`. `input(prompt)` membuka prompt interaktif. `input(prompt, key)` memakai nilai input yang sudah diberikan, dan jika belum ada, fungsi ini menampilkan prompt lalu menyimpan jawabannya di key tersebut.

```java
String query = input("Masukkan query pencarian");
printLog("Query diterima: " + query);
browser.navigate("https://example.com/search?q=" + query);
String title = browser.extract("h1");
browser.setResult(title);
```

Method `browser` / `workflow` yang tersedia: `navigate(url[, timeoutMs])`, `waitFor(selector, timeoutMs)`, `waitForText(text, timeoutMs)`, `waitForUrl(urlPart, timeoutMs)`, `input(selector, value)`, `click(selector)`, `extract(selector[, attribute])`, `extractAll(selector[, attribute])`, `text(selector)`, `exists(selector)`, `getInput(name)`, `getInputValue(name)`, `getInputJson()`, `setResult(value)`, `getResult()`, `getUrl()`, `printLog(message)`, `logInfo(message)`, `logWarn(message)`, `logError(message)`, `readAsset(path)`, `writeAsset(path, content)`, `assetPath(path)`, `delay(ms)`, dan `scroll(direction, pixels, selector)`.

#### Endpoint HTTP opsional

Mode endpoint diatur per workflow:

- `none` (default): tanpa endpoint dan tanpa port yang didengarkan. Workflow tetap bisa dijalankan manual dari aplikasi atau lewat MCP.
- `shared`: mendaftarkan path unik di listener HTTP bersama pada `127.0.0.1:8084`. Port `8084` hanya dibuka selama ada minimal satu workflow aktif dan valid yang meminta mode shared.
- `exclusive`: membuka satu listener di port khusus untuk workflow tersebut. Satu workflow maksimal memiliki satu port exclusive, dan paling banyak lima listener exclusive aktif sekaligus. Port `0` membuat sistem memilih port ephemeral yang bebas; port eksplisit `1024` sampai `65535` diterima, kecuali `8084` dan `9010`. Jika port otomatis yang diingat sedang dipakai, aplikasi meminta port baru.

Contoh mode shared: `POST http://127.0.0.1:8084/workflow/search-tes`. Endpoint exclusive memakai port dan path yang tampil di daftar workflow. Request harus berupa objek JSON maksimal 2 MiB (body kosong berarti `{}`), dan isinya menjadi `input` workflow. Respons berisi `ok`, `workflowId`, `executionId`, `durationMs`, dan hasil workflow.

Setiap endpoint bersifat opt-in, dan mengaktifkan workflow dengan mode `none` tidak membuka port apa pun. Listener hanya terikat ke `127.0.0.1`, bukan ke interface LAN. Endpoint hanya bisa dilayani saat `BrowserService` berjalan dengan pengaturan server aktif. Server MCP tetap di `9010`, dan tunnel Cloudflare tetap mengarah ke port MCP itu, tidak pernah ke port workflow.

Setiap eksekusi memakai instance WebView terpisah, sehingga navigasi dan state DOM-nya tidak mengubah browser utama milik model. Penyimpanan cookie dan profil WebView default tetap dipakai bersama dengan WebView lain di aplikasi.

#### Input interaktif dan izin AI

`.flow` maupun `.bshflow` bisa berhenti untuk meminta input. Layar log workflow punya tab `INTERACTIVE` dengan kolom input native dan tombol **Kirim input**. Setiap prompt terikat pada `workflow_id`, `execution_id`, dan `prompt_id` yang unik, dan prompt serta pengiriman jawaban dicatat dengan timestamp sampai milidetik. Prompt punya batas waktu (default 10 menit, maksimal 30 menit). Tipe input yang didukung adalah `text`, `number`, `password`, `boolean`, dan `choice`.

AI bisa melihat prompt yang tertunda dan mengirim jawaban lewat MCP. Membaca prompt yang tertunda tetap diizinkan walau akses write dimatikan. Setiap workflow punya `permissions.aiWriteInput`, yang defaultnya `false` dan hanya dikendalikan oleh toggle **Izinkan AI mengirim input interaktif** di editor lokal. Tool MCP dan metadata ZIP hasil impor tidak bisa memberikan izin itu kepada dirinya sendiri. Selama nilainya `false`, `workflow.submit_input` ditolak untuk workflow tersebut, tetapi input manusia di aplikasi tetap diizinkan. Pemeriksaan ini dilakukan oleh runtime, tidak mengandalkan UI. Pengiriman dari AI harus menyebut ID workflow, eksekusi, dan prompt yang cocok.

#### Menjalankan dan mengelola workflow lewat MCP

`workflow.run` dijalankan secara sinkron dan tidak membutuhkan aktivasi maupun endpoint HTTP. Untuk workflow yang mungkin meminta input interaktif, gunakan `workflow.start`: tool ini mengembalikan ID eksekusi setelah eksekusi terdaftar, sehingga model bisa memanggil `workflow.get_pending_input`, mengirim jawaban (jika izin per-workflow aktif), dan memantau `workflow.get_execution` sampai statusnya bukan `running` lagi. Status yang mungkin adalah `waiting_input`, `running`, `completed`, dan `failed`; `waiting_input` berarti ada prompt yang perlu diambil. Eksekusi yang selesai menyertakan hasil atau errornya.

Input JSON dikirim lewat argumen `input`. Aset biner dipindahkan lewat tool file MCP dalam bentuk base64. `metadata.json` diubah lewat operasi metadata terstruktur (`workflow.create` dan `workflow.update`), bukan lewat penulis file umum.

#### Log

Log disimpan di database SQLite privat `workflow_log.db`, tabel `workflow_logs`, dan dipakai bersama oleh layar bergaya terminal dan tool log MCP. Layar log tiap workflow memisahkan `INFO`, `ERROR`, dan `INTERACTIVE`. Satu entri berisi timestamp (`yyyy-MM-dd HH:mm:ss.SSS`), event, pesan, file sumber, baris atau langkah bila ada, ID eksekusi, dan detail opsional.

Filter log MCP menerima `since` dengan `last_5m`, `last_30m`, `last_1h`, atau `all`, ditambah level, workflow, ID eksekusi, batas bawah timestamp eksplisit dalam epoch milidetik, `limit`, dan `offset` secara opsional. Database menyimpan 20.000 record terbaru.

### Service dan tunnel

- Tunnel Cloudflare tidak pernah menyala sendiri dari `BrowserService`. Tunnel hanya dimulai ketika pengguna menekan aksi tunnel di **Service & Tunnel**.
- Aksi itu berupa toggle mulai/berhenti, dan menawarkan coba lagi jika percobaan terakhir gagal. Niat menyalakan tunnel hanya ada di memori, jadi restart service atau proses tidak membuka kembali tunnel publik secara otomatis.
- URL tunnel yang tersimpan dibersihkan saat proses service dibuat ulang dan saat tunnel dihentikan. Jika proses tunnel keluar, aplikasi mencatat keluarnya proses itu, bukan menyimpan URL publik yang sudah usang. Penyambungan ulang hanya dicoba setelah pengguna menyalakannya secara manual, dan hanya selama instance service itu masih hidup.
- Tunnel dijalankan dengan `libcloudflared.so tunnel --url http://127.0.0.1:9010`. `cloudflared` menampilkan subdomain `trycloudflare`, lalu aplikasi menyimpannya sebagai URL publik.
- Health check dalam proses berjalan setiap 60 detik. Jika listener MCP berhenti padahal proses service masih hidup, service membuatnya ulang, dan listener HTTP workflow diperiksa serta dijalankan ulang dengan cara yang sama.
- Interval alarm pemulihan adalah 10 menit, dan startup listener yang gagal dicoba ulang lebih cepat.

**Batasan keandalan.** Android dan Vivo tetap bisa menghentikan proses aplikasi, terutama setelah force-stop atau karena pembatasan khusus vendor. Foreground service dengan pemulihan AlarmManager bersifat best-effort, bukan jaminan. Health check dalam proses tidak bisa berjalan setelah seluruh proses dimatikan; alarm pemulihan eksternal menangani kasus itu, dan Android bisa menundanya pada mode idle. Pengaturan optimasi baterai dan autostart biasanya dibutuhkan di perangkat Vivo.

### Autentikasi dan keamanan

- Autentikasi bearer token bersifat opsional. Saat aktif, request hanya diterima dengan token yang benar (`Authorization: Bearer <token>`).
- Endpoint lokal terbatas di perangkat itu sendiri. Tunnel publik bisa mengekspos browser ke internet, jadi aktifkan token sebelum menyalakannya. Aplikasi ini tidak aman secara default untuk internet publik tanpa kontrol akses.
- Listener HTTP workflow hanya terikat ke `127.0.0.1`, dan tunnel tidak pernah mengarah ke sana.
- Izin AI write per workflow hanya bisa diberikan dari editor lokal.
- Manifest mengizinkan lalu lintas HTTP cleartext, sehingga halaman `http://` bisa dimuat.

### Permission

`INTERNET`, `ACCESS_NETWORK_STATE`, `READ_EXTERNAL_STORAGE` (sampai API 32), `MANAGE_EXTERNAL_STORAGE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED`, dan `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Service berjalan sebagai foreground service `dataSync`.

### Build dan menjalankan

1. Clone repository ini, atau unduh sebagai ZIP.
2. Buka project di AIDE (project memakai gaya Java 7).
3. Pastikan `app/libs/bsh-2.0b4.jar` dan `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` ada. Keduanya sudah ada di repository. Binary tunnel menargetkan `arm64-v8a`; ABI lain membutuhkan binary `cloudflared` yang sesuai.
4. Build lalu pasang aplikasi di perangkat Android.
5. Buka aplikasi, aktifkan server browser/MCP, dan jika perlu, tunnel.
6. Hubungkan client AI ke endpoint lokal atau ke URL tunnel publik.

### Lisensi

Repository ini memakai lisensi MIT. Lihat file `LICENSE` untuk ketentuan lengkapnya.
