# Rh Browser for Models

<div align="center">
  <p><strong>Language / Bahasa</strong></p>
  <p>
    <a href="#bahasa-indonesia">Indonesia</a> ·
    <a href="#english-us">English (US)</a>
  </p>
</div>

## Bahasa Indonesia

<a id="bahasa-indonesia"></a>

browser android yang memberi akses browser ke model ai melalui protokol mcp (model context protocol) standar. aplikasi ini berjalan sebagai browser webview lokal yang bisa dikendalikan dari client ai lewat endpoint mcp di localhost maupun melalui tunnel publik, sehingga model bisa membuka halaman, membaca isi DOM, mengklik elemen, mengisi form, mengambil screenshot, dan mengakses resource web tanpa harus membuat browser desktop sendiri.

### ringkasan

repo ini berisi aplikasi android native yang menggabungkan:

- browser webview internal
- server mcp yang mengekspos tool browser ke client ai
- tunnel publik berbasis cloudflared untuk akses eksternal
- panel pengaturan untuk mengelola server, autentikasi, dan izin background
- mekanisme tool registry yang bisa diaktifkan/ditutup per kategori atau per tool

secara umum, project ini bukan sekadar browser biasa. ini adalah bridge antara model ai dan pengalaman browsing di perangkat android.

### tujuan proyek

proyek ini dibuat untuk menjembatani kebutuhan model ai agar bisa:

- membuka halaman web tertentu
- menavigasi antar halaman
- membaca informasi halaman
- mengklik elemen interaktif
- mengisi input dan form
- mengambil screenshot
- mengakses resource seperti html, gambar, atau file yang di-load halaman
- beroperasi dari background service dan tetap berjalan meski aplikasi tidak terlihat di foreground

ide utamanya adalah: model ai cukup memanggil tool mcp, lalu android app menjalankan operasi di browser webview nyata.

### arsitektur aplikasi

project ini berisi satu modul android app dengan struktur utama seperti ini:

```text
browser-for-models/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── fonts/
│           ├── java/rh/browser/models/
│           │   ├── BootReceiver.java
│           │   ├── BrowserDownloads.java
│           │   ├── BrowserManager.java
│           │   ├── BrowserService.java
│           │   ├── DownloadsActivity.java
│           │   ├── MainActivity.java
│           │   ├── McpServer.java
│           │   ├── ServiceRecoveryReceiver.java
│           │   ├── ServiceSettingsActivity.java
│           │   ├── ToolDef.java
│           │   ├── ToolRegistry.java
│           │   ├── ToolSettings.java
│           │   ├── ToolSettingsActivity.java
│           │   └── UiKit.java
│           ├── jniLibs/arm64-v8a/libcloudflared.so
│           └── res/
│               ├── drawable*
│               ├── layout/
│               └── values/
├── build.gradle
├── settings.gradle
├── .gitignore
├── LICENSE
└── README.md
```

### komponen penting

#### 1. MainActivity
`MainActivity.java` adalah layar utama app. ia menyiapkan ui browser sederhana dengan:

- address bar
- tombol navigasi basic (back, forward, reload)
- tombol buka url
- tombol copy url
- panel control browser
- embed webview yang diambil dari service

fungsi utama:

- menampilkan browser webview sebagai surface utama
- menghubungkan ke `BrowserService`
- menampilkan status browser
- menjalankan navigasi user dari app

#### 2. BrowserService
`BrowserService.java` adalah inti background service. ia bertanggung jawab untuk:

- menjalankan `BrowserManager`
- menjalankan `McpServer`
- menjalankan tunnel cloudflare bila diaktifkan
- menjaga service tetap hidup di foreground
- memantau health server
- menangani lifecycle app dan recovery background

beberapa fitur penting:

- server mcp aktif di port `9010`
- endpoint lokal: `http://127.0.0.1:9010/mcp`
- endpoint kesehatan: `/health`
- tunnel publik dibuat via `cloudflared` native binary
- notification foreground dan recovery receiver untuk menjaga service tetap aktif

#### 3. McpServer
`McpServer.java` adalah server kecil yang menerima request json-rpc/mcp dari client. ia menangani:

- `initialize`
- `ping`
- `tools/list`
- `resources/list`
- `prompts/list`
- `tools/call`

server ini mendukung request HTTP dan mengurai payload JSON, lalu memanggil tool yang ada di `ToolRegistry`. ia juga punya mekanisme otorisasi bearer token bila autentikasi diaktifkan.

#### 4. ToolRegistry
`ToolRegistry.java` mendefinisikan tool-tool yang bisa dipanggil oleh model ai. tool-tool ini adalah inti dari kemampuan browser automation. beberapa tool utama:

- `browser.open`
- `browser.back`
- `browser.inspect`
- `browser.click`
- `browser.input`
- `browser.scroll`
- `browser.source`
- `browser.screenshot`
- `browser.clear_cookies`
- `browser.clear_localstorage`
- `browser.clear_sessionstorage`
- `browser.set_user_agent`
- `browser.set_mode`
- `browser.set_viewport`
- `browser.item.list`
- `browser.get.item`
- `browser.debug.console`

setiap tool punya schema input, kategori, access mode, dan default enabled flag. ini memungkinkan pengaturan tool per kategori read/write dengan `ToolSettings.java`.

#### 5. BrowserManager
`BrowserManager.java` adalah komponen kerja browser paling penting. ia berisi implementasi operasi browser real di webview, seperti:

- membuka halaman dan menunggu render
- menghitung dan menemukan elemen DOM
- klik elemen berdasarkan selector atau ref
- mengisi form
- scroll halaman
- membaca HTML sumber halaman
- menangkap screenshot
- mengakses resource pada halaman aktif
- debug console log webview

meski file ini besar, fungsi utamanya adalah menjalankan aksi nyata pada webview. ini adalah tempat semua kemampuan browser diimplementasikan.

#### 6. ToolSettings dan model keamanan
`ToolSettings.java` menampung konfigurasi akses tool dan keamanan. ia mengelola:

- `auth_enabled`
- `mcp_token`
- `server_enabled`
- per-tool toggle
- category read/write status

kebijakan umum:

- tool bisa dinonaktifkan satu per satu
- category bisa dibatasi read/write
- token bearer opsional untuk autentikasi request mcp
- server dapat diaktifkan/dinonaktifkan melalui app settings

#### 7. ServiceSettingsActivity
`ServiceSettingsActivity.java` menyediakan cara user untuk:

- mengelola server mcp
- melihat status server
- melihat status tunnel
- menyalakan/matikan tunnel
- menyalin endpoint lokal dan token
- mengaktifkan autentikasi bearer token
- mengatur optimasi baterai dan autostart khusus perangkat vivo

ini sangat relevan karena app dirancang agar berjalan di background, bahkan di perangkat yang agresif dalam membatasi proses background.

### alur kerja umum

1. user membuka app
2. `MainActivity` menghubungkan ke `BrowserService`
3. `BrowserService` memulai `BrowserManager` dan `McpServer`
4. client ai menghubungi `http://127.0.0.1:9010/mcp` atau tunnel publik
5. client melakukan `tools/list` dan memilih tool yang dibutuhkan
6. tool dipanggil melalui JSON-RPC
7. `BrowserManager` mengoperasikan webview
8. hasil dikembalikan dalam format MCP content, bisa berupa text, image, atau blob/resource

### bagaimana tunnel publik bekerja

ketika server aktif, `BrowserService` juga bisa memulai `cloudflared` native library untuk membuka tunnel ke internet publik. ini dibuat dengan command seperti:

```text
libcloudflared.so tunnel --url http://127.0.0.1:9010
```

cloudflared akan menampilkan subdomain `trycloudflare`, lalu app menyimpannya sebagai public URL. jadi client bisa menjangkau browser dari luar perangkat android, asalkan otorisasi dan pengaturan keamanan diatur dengan benar.

### autentikasi dan keamanan

repo ini punya mekanisme keamanan dasar:

- bearer token opsional
- request hanya diterima bila token benar jika autentikasi aktif
- `Authorization: Bearer <token>` dapat dipakai pada client yang mendukungnya
- local endpoint tetap terbatas di perangkat itu sendiri
- tunnel publik perlu dikelola dengan hati-hati karena bisa mengekspos browser ke internet

catatan penting: repo ini bukan aplikasi secure-by-default untuk internet publik tanpa kontrol akses. pengaturan token dan firewall/tunnel kontrol tetap penting.

### teknologi yang dipakai

- bahasa: java
- platform: android native
- ui: android widgets + custom ui kit
- web rendering: android `WebView`
- protocol: MCP (model context protocol)
- network: HTTP JSON-RPC over localhost dan tunnel
- native binary: `libcloudflared.so`

### pengaturan build

project menggunakan Gradle Android lama dengan konfigurasi seperti ini:

```gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:1.+'
    }
}
```

and modul app:

```gradle
apply plugin: 'com.android.application'

android {
    compileSdkVersion 21
    buildToolsVersion "21.1.0"
    defaultConfig {
        applicationId "rh.browser.models"
        minSdkVersion 14
        targetSdkVersion 29
        versionCode 1
        versionName "1.0"
    }
}
```

catatan: project ini mengikuti pola android legacy, jadi build paling cocok di lingkungan android yang masih mendukung Gradle lama dan repository `jcenter`.

### persyaratan runtime

- Android 6+ (API 23 minimum berdasarkan manifest)
- internet permission
- foreground service permission
- notification permission pada Android 13+
- background service mungkin butuh pengaturan khusus pada Vivo dan perangkat vendor tertentu

### panduan build lokal

1. pastikan Android Studio terpasang
2. buka repo ini sebagai project Android
3. pastikan Gradle sync bisa menangani plugin lama
4. pilih module `app`
5. build debug atau release
6. install ke perangkat Android
7. buka aplikasi dan aktifkan server browser/MCP
8. gunakan endpoint lokal atau tunnel publik sesuai kebutuhan

### catatan penting untuk pengembangan

- repo ini sangat bergantung pada `WebView` dan lifecycle Android
- penggunaan service foreground sangat penting agar server tetap hidup
- cloudflared native library harus kompatibel dengan ABI perangkat
- pengaturan battery optimization dan autostart sering dibutuhkan di perangkat Vivo
- pengaturan auth token sangat disarankan bila tunnel dibuat publik

### kesimpulan

repo `reyhanaarch64/browser-for-models` adalah aplikasi Android yang mengubah browser handphone jadi alat interaksi model AI via MCP. ia menggabungkan browser WebView, server MCP, tool registry, dan tunnel publik dalam satu paket yang bisa dipakai untuk automasi browsing real-time.

fitur terkuat repo ini adalah kemampuannya untuk membiarkan model AI mengakses browser seperti manusia — buka halaman, klik, input, baca HTML, ambil screenshot, dan ekspose tool melalui protocol standard. ini cocok untuk eksperimen AI agent berbasis browser di perangkat Android.

### lisensi

repo ini memakai lisensi MIT. lihat file `LICENSE` untuk detail lengkap.

### rekomendasi lanjutan

beberapa hal yang bisa ditambahkan untuk proyek ini ke depannya:

- dokumentasi penggunaan API MCP per tool
- contoh client curl / Python yang mengakses endpoint
- dokumentasi payload response untuk tool screenshot dan resource
- sandboxing keamanan untuk tool write pada situs tertentu
- pemeriksaan website yang diizinkan agar browser tidak bisa mengakses sembarang domain
- CI pipeline untuk build Android
- uji integrasi untuk tool browser basic

---

## English (US)

<a id="english-us"></a>

Rh Browser for Models is an Android application that gives AI models access to a real mobile browser through the standard MCP (Model Context Protocol). The app runs a local WebView browser that can be controlled by an AI client through an MCP endpoint on localhost or via a public tunnel, allowing models to open pages, read the DOM, click elements, fill forms, capture screenshots, and access web resources without building a separate desktop browser.

### overview

This repository contains a native Android app that combines:

- an internal browser WebView
- an MCP server exposing browser tools to AI clients
- a public tunnel using Cloudflare tunnel (`cloudflared`)
- a settings panel for server, authentication, and background permissions
- a tool registry that can be enabled or disabled by category or by individual tool

In short, this is not just a browser. It is a bridge between AI models and the browsing experience on an Android device.

### project goal

This project is designed to let AI models do things like:

- open a web page
- navigate between pages
- read page information
- click interactive elements
- fill input fields and forms
- capture screenshots
- access resources such as HTML, images, and files loaded by a page
- keep working in the background while the app is not in the foreground

The core idea is simple: the AI model calls an MCP tool, and the Android app performs the real browser action in a WebView.

### architecture

The project is a single Android app module with the main structure below:

```text
browser-for-models/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── fonts/
│           ├── java/rh/browser/models/
│           │   ├── BootReceiver.java
│           │   ├── BrowserDownloads.java
│           │   ├── BrowserManager.java
│           │   ├── BrowserService.java
│           │   ├── DownloadsActivity.java
│           │   ├── MainActivity.java
│           │   ├── McpServer.java
│           │   ├── ServiceRecoveryReceiver.java
│           │   ├── ServiceSettingsActivity.java
│           │   ├── ToolDef.java
│           │   ├── ToolRegistry.java
│           │   ├── ToolSettings.java
│           │   ├── ToolSettingsActivity.java
│           │   └── UiKit.java
│           ├── jniLibs/arm64-v8a/libcloudflared.so
│           └── res/
│               ├── drawable*
│               ├── layout/
│               └── values/
├── build.gradle
├── settings.gradle
├── .gitignore
├── LICENSE
└── README.md
```

### key components

#### 1. MainActivity
`MainActivity.java` is the app's main screen. It sets up a lightweight browser UI with:

- an address bar
- basic browser controls (back, forward, refresh)
- URL open and copy actions
- a browser surface embedded inside the activity
- connection to the background service

It mainly handles the browser display and navigation from the user interface.

#### 2. BrowserService
`BrowserService.java` is the background service. It is responsible for:

- creating and maintaining `BrowserManager`
- starting `McpServer`
- launching Cloudflare tunnel when enabled
- keeping the service alive in foreground mode
- monitoring server health
- handling lifecycle and recovery tasks

Important features:

- MCP server runs on port `9010`
- local endpoint: `http://127.0.0.1:9010/mcp`
- health endpoint: `/health`
- public tunnel is started by the native `cloudflared` binary
- foreground notifications and recovery receivers help keep the service alive

#### 3. McpServer
`McpServer.java` is a lightweight server that accepts HTTP JSON-RPC/MCP requests. It handles:

- `initialize`
- `ping`
- `tools/list`
- `resources/list`
- `prompts/list`
- `tools/call`

It parses JSON payloads from clients and dispatches actions to the registered tools. It also supports bearer-token authentication if enabled.

#### 4. ToolRegistry
`ToolRegistry.java` declares the browser tools that AI models can invoke. These tools are the heart of the browser automation layer. Key tools include:

- `browser.open`
- `browser.back`
- `browser.inspect`
- `browser.click`
- `browser.input`
- `browser.scroll`
- `browser.source`
- `browser.screenshot`
- `browser.clear_cookies`
- `browser.clear_localstorage`
- `browser.clear_sessionstorage`
- `browser.set_user_agent`
- `browser.set_mode`
- `browser.set_viewport`
- `browser.item.list`
- `browser.get.item`
- `browser.debug.console`

Each tool has an input schema, category, access mode, and default enabled flag. This allows the app to control access per tool or by category with `ToolSettings.java`.

#### 5. BrowserManager
`BrowserManager.java` is the most important browser engine. It implements real browser operations in a `WebView`, including:

- opening a page and waiting for render completion
- detecting page elements in the DOM
- clicking elements using selector or reference data
- filling form inputs
- scrolling the page
- reading page source and text
- capturing screenshots
- accessing page resources
- reading console logs

This is where the app performs the actual browser actions.

#### 6. ToolSettings and security model
`ToolSettings.java` manages access and security configuration, including:

- `auth_enabled`
- `mcp_token`
- `server_enabled`
- per-tool toggles
- category read/write states

General behavior:

- tools can be disabled individually
- categories can be restricted by read/write access
- bearer token authentication is optional
- the server can be turned on or off in the app settings

#### 7. ServiceSettingsActivity
`ServiceSettingsActivity.java` gives the user a way to:

- manage the MCP server
- view server status
- view tunnel status
- start or stop the public tunnel
- copy the local endpoint and token
- enable Bearer-token authentication
- apply battery optimization and Vivo-specific background settings

This is important because the app is designed to stay alive in the background, even on devices that aggressively restrict background activity.

### general flow

1. the user opens the app
2. `MainActivity` binds to `BrowserService`
3. `BrowserService` starts `BrowserManager` and `McpServer`
4. an AI client connects to `http://127.0.0.1:9010/mcp` or a public tunnel
5. the client calls `tools/list` and chooses a tool
6. the tool is invoked through JSON-RPC
7. `BrowserManager` interacts with the WebView
8. the result is returned in MCP content format, which may be text, image, or binary/resource data

### how the public tunnel works

When the server is active, `BrowserService` can launch the native `cloudflared` binary and expose the local MCP server through a public URL. The command is conceptually:

```text
libcloudflared.so tunnel --url http://127.0.0.1:9010
```

`cloudflared` prints a `trycloudflare` subdomain, and the app stores it as the public URL. This allows a client outside the device to reach the browser, but only if security settings and authorization are managed correctly.

### authentication and security

The repository includes a basic security model:

- optional bearer token authentication
- requests are only accepted when the token matches if auth is enabled
- `Authorization: Bearer <token>` can be used by compatible clients
- the local endpoint remains limited to the device itself
- any public tunnel should be treated carefully because it may expose the browser to the internet

Important: this is not a secure-by-default public internet app without access control. Token configuration and tunnel/firewall control are still necessary.

### technologies used

- language: Java
- platform: native Android
- UI: Android widgets + custom UI kit
- rendering: Android `WebView`
- protocol: MCP (Model Context Protocol)
- networking: HTTP JSON-RPC over localhost and tunnel
- native binary: `libcloudflared.so`

### build settings

The project uses legacy Android Gradle configuration, for example:

```gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:1.+'
    }
}
```

And the app module includes:

```gradle
apply plugin: 'com.android.application'

android {
    compileSdkVersion 21
    buildToolsVersion "21.1.0"
    defaultConfig {
        applicationId "rh.browser.models"
        minSdkVersion 14
        targetSdkVersion 29
        versionCode 1
        versionName "1.0"
    }
}
```

Note: this project follows a legacy Android pattern, so it is best suited to older Android build environments and the `jcenter` repository setup.

### runtime requirements

- Android 6+ (API 23 minimum based on the manifest)
- internet permission
- foreground service permission
- notification permission on Android 13+
- background services may require special management on Vivo and some OEM devices

### local build guide

1. install Android Studio
2. open this repository as an Android project
3. make sure Gradle sync works with the older plugin version
4. select the `app` module
5. build debug or release
6. install it on an Android device
7. open the app and enable the browser/MCP server
8. use the local endpoint or public tunnel as needed

### development notes

- the project depends heavily on `WebView` and Android lifecycle behavior
- foreground services are essential for keeping the server alive
- the `cloudflared` native library must match the device ABI
- battery optimization and autostart settings are often required on Vivo devices
- bearer token authentication is strongly recommended if the tunnel is made public

### summary

`reyhanaarch64/browser-for-models` is an Android app that turns a phone browser into an AI-accessible tool via MCP. It combines a WebView browser, MCP server, tool registry, and public tunnel in one package for real-time browsing automation.

Its strongest feature is that it allows an AI model to interact with a browser much like a human user: open pages, click, type, read page HTML, capture screenshots, and expose tools through a standard protocol. This makes it well suited for browser-based AI agent experiments on Android devices.

### license

This repository uses the MIT license. See `LICENSE` for full terms.

---

This README has been updated to provide both Indonesian and English sections with an in-file language switch at the top.
