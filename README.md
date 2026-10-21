# rh browser for models

browser android yang memberi akses browser ke model ai melalui protokol mcp (model context protocol) standar. aplikasi ini berjalan sebagai browser webview lokal yang bisa dikendalikan dari client ai lewat endpoint mcp di localhost maupun melalui tunnel publik, sehingga model bisa membuka halaman, membaca isi DOM, mengklik elemen, mengisi form, mengambil screenshot, dan mengakses resource web tanpa harus membuat browser desktop sendiri.

## ringkasan

repo ini berisi aplikasi android native yang menggabungkan:

- browser webview internal
- server mcp yang mengekspos tool browser ke client ai
- tunnel publik berbasis cloudflared untuk akses eksternal
- panel pengaturan untuk mengelola server, autentikasi, dan izin background
- mekanisme tool registry yang bisa diaktifkan/ditutup per kategori atau per tool

secara umum, project ini bukan sekadar browser biasa. ini adalah bridge antara model ai dan pengalaman browsing di perangkat android.

## tujuan proyek

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

## arsitektur aplikasi

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
└── README.md
```

## komponen penting

### 1. mainactivity
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

### 2. browserservice
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
- endpoint health: `/health`
- tunnel publik dibuat via `cloudflared` native binary
- notification foreground dan recovery receiver untuk menjaga service tetap aktif

### 3. mcpserver
`McpServer.java` adalah server kecil yang menerima request json-rpc/mcp dari client. ia menangani:

- `initialize`
- `ping`
- `tools/list`
- `resources/list`
- `prompts/list`
- `tools/call`

server ini mendukung h2h request http, mengurai payload json, dan memanggil tool yang ada di `ToolRegistry`. ia juga punya mekanisme otorisasi bearer token bila autentikasi diaktifkan.

### 4. toolregistry
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

### 5. browsermanager
`BrowserManager.java` adalah komponen kerja browser paling penting. ia berisi implementasi operasi browser real di webview, seperti:

- membuka halaman dan menunggu render
- menghitung/menemukan element DOM
- click element berdasarkan selector atau ref
- mengisi form
- scroll halaman
- membaca html/sumber halaman
- menangkap screenshot
- mengakses resource pada halaman aktif
- debug console log webview

meski file ini besar, fungsi utamanya adalah menjalankan aksi nyata pada webview. ini adalah tempat semua kemampuan browser di-implementasikan.

### 6. toolsettings dan security model
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

### 7. service settings dan ui
`ServiceSettingsActivity.java` menyediakan cara user untuk:

- mengelola server mcp
- melihat status server
- melihat status tunnel
- menyalakan/matikan tunnel
- menyalin endpoint lokal dan token
- mengaktifkan autentikasi bearer token
- mengatur optimasi baterai dan autostart khusus perangkat vivo

ini sangat relevan karena app dirancang agar berjalan di background, bahkan di perangkat yang agresif dalam membatasi proses background.

## alur kerja general

1. user membuka app
2. `MainActivity` menghubungkan ke `BrowserService`
3. `BrowserService` memulai `BrowserManager` dan `McpServer`
4. client ai menghubungi `http://127.0.0.1:9010/mcp` atau tunnel publik
5. client melakukan `tools/list` dan memilih tool yang dibutuhkan
6. tool dipanggil melalui json-rpc
7. `BrowserManager` mengoperasikan webview
8. hasil dikembalikan dalam format mcp content, bisa berupa text, image, atau blob/resource

## bagaimana tunnel publik bekerja

ketika server aktif, `BrowserService` juga bisa memulai `cloudflared` native library untuk membuka tunnel ke public internet. ini dibuat dengan command seperti:

```text
libcloudflared.so tunnel --url http://127.0.0.1:9010
```

cloudflared akan menampilkan subdomain trycloudflare, lalu app menyimpannya sebagai public url. jadi client bisa menjangkau browser dari luar perangkat android, asalkan otorisasi dan pengaturan keamanan diatur dengan benar.

## autentikasi dan keamanan

repo ini punya mekanisme keamanan dasar:

- bearer token opsional
- request hanya diterima bila token benar jika autentikasi aktif
- `Authorization: Bearer <token>` dapat dipakai pada client yang mendukungnya
- local endpoint tetap terbatas di perangkat itu sendiri
- tunnel publik perlu dikelola dengan hati-hati karena bisa mengekspos browser ke internet

catatan penting: repo ini bukan aplikasi secure-by-default untuk internet public tanpa kontrol akses. pengaturan token dan firewall/tunnel kontrol tetap penting.

## teknologi yang dipakai

- bahasa: java
- platform: android native
- ui: android widgets + custom ui kit
- web rendering: android `WebView`
- protocol: mcp (model context protocol)
- network: http json-rpc over localhost dan tunnel
- native binary: `libcloudflared.so`

## pengaturan build

project menggunakan gradle android lama dengan konfigurasi seperti ini:

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

dan modul app:

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

catatan: project ini mengikuti pola android legacy, jadi build paling cocok di lingkungan android yang masih support gradle lama dan repository `jcenter`.

## persyaratan runtime

- android 6+ (api 23 minimum berdasarkan manifest)
- internet permission
- foreground service permission
- notification permission pada android 13+
- background service mungkin butuh pengaturan khusus pada vivo dan perangkat vendor tertentu

## panduan build lokal

1. pastikan android studio terpasang
2. buka repo ini sebagai project android
3. pastikan gradle sync bisa menangani plugin lama
4. pilih module `app`
5. build debug atau release
6. install ke perangkat android
7. buka aplikasi dan aktifkan server browser/mcp
8. gunakan endpoint lokal atau tunnel publik sesuai kebutuhan

## catatan penting untuk pengembangan

- repo ini sangat bergantung pada `WebView` dan lifecycle android
- penggunaan service foreground sangat penting agar server tetap hidup
- cloudflared native library harus kompatibel dengan abi perangkat
- pengaturan battery optimization dan autostart sering dibutuhkan di perangkat vivo
- pengaturan auth token sangat disarankan bila tunnel dibuat publik

## kesimpulan

repo `reyhanaarch64/browser-for-models` adalah aplikasi android yang mengubah browser handphone jadi alat interaksi model ai via mcp. ia menggabungkan browser webview, server mcp, tool registry, dan tunnel publik dalam satu paket yang bisa dipakai untuk automasi browsing real-time.

fitur terkuat repo ini adalah kemampuannya untuk membiarkan model ai mengakses browser seperti manusia — buka halaman, klik, input, baca html, ambil screenshot, dan ekspos tool melalui protocol standard. ini cocok untuk eksperimen ai agent berbasis browser di perangkat android.

## lisensi dan status

repo ini tidak mencantumkan lisensi eksplisit pada tree project yang terlihat. jika project ini mau dipakai untuk publik, disarankan untuk menambahkan file `LICENSE` secara jelas sebelum distribusi luas.

## rekomendasi lanjutan

beberapa hal yang bisa ditambahkan untuk proyek ini ke depannya:

- dokumentasi penggunaan api mcp per tool
- contoh client curl / python yang mengakses endpoint
- dokumentasi payload response untuk tool screenshot dan resource
- sandboxing keamanan untuk tool write pada situs tertentu
- pemeriksaan website yang diizinkan agar browser tidak bisa mengakses sembarang domain
- file `LICENSE`
- CI pipeline untuk build android
- uji integrasi untuk tool browser basic

---

ini README dibuat berdasarkan analisis struktur project, konfigurasi android, serta implementasi server dan tool browser yang ada di repo.
