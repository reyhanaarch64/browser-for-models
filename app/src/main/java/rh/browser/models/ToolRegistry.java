package rh.browser.models;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolRegistry {
    private static final LinkedHashMap<String, ToolDef> TOOLS = new LinkedHashMap<String, ToolDef>();
    private ToolRegistry() {}
    private static JSONObject schema() {
        JSONObject o = new JSONObject();
        try { o.put("type", "object"); o.put("properties", new JSONObject()); } catch (Exception ignored) {}
        return o;
    }
    private static JSONObject schema(String[] names, String[] types, String[] descriptions, String[] required) {
        JSONObject o = schema();
        try {
            JSONObject props = o.getJSONObject("properties");
            for (int i=0; i<names.length; i++) {
                JSONObject p = new JSONObject(); p.put("type", types[i]);
                if (descriptions != null && descriptions[i] != null) p.put("description", descriptions[i]);
                props.put(names[i], p);
            }
            if (required != null && required.length > 0) { JSONArray a = new JSONArray(); for (String s: required) a.put(s); o.put("required", a); }
        } catch (Exception ignored) {}
        return o;
    }
    private static void add(String name, String desc, JSONObject schema, String category, int mode, boolean enabled) {
        TOOLS.put(name, new ToolDef(name, desc, schema, category, mode, enabled));
    }
    static {
        add("browser.open", "Membuka URL HTTP/HTTPS pada satu tab WebView internal yang sama. waitMs membatasi waktu tunggu halaman.", schema(new String[]{"url","waitMs"}, new String[]{"string","integer"}, new String[]{"URL situs HTTP/HTTPS","Waktu tunggu 500-30000 ms, default 18000"}, new String[]{"url"}), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.back", "Kembali satu langkah pada riwayat navigasi tab browser yang sama.", schema(), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.inspect", "Memeriksa metadata halaman dan elemen interaktif DOM; ref eN hanya berlaku untuk hasil inspeksi terbaru.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas elemen interaktif 1-200, default 80"}, null), "browser", ToolDef.ACCESS_READ, true);
        add("browser.click", "Memicu klik DOM pada elemen berdasarkan elementRef dari browser.inspect, selector CSS, atau teks tombol.", schema(new String[]{"elementRef","selector","text","settleMs"}, new String[]{"string","string","string","integer"}, new String[]{"Ref seperti e0 dari inspect","Selector CSS","Teks tombol/tautan","Waktu tunggu setelah klik 0-10000 ms"}, null), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.input", "Mengisi input, textarea, select, atau contenteditable pada halaman aktif; target berdasarkan elementRef, selector, id, name, atau placeholder.", schema(new String[]{"elementRef","selector","id","name","placeholder","value","clearFirst","settleMs"}, new String[]{"string","string","string","string","string","string","boolean","integer"}, new String[]{"Ref eN dari inspect","Selector CSS","ID elemen","Atribut name","Teks placeholder","Nilai yang diisikan","Ganti nilai (true) atau tambahkan ke nilai lama (false)","Waktu tunggu setelah input 0-5000 ms"}, new String[]{"value"}), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.scroll", "Menggulir halaman atau elemen tertentu ke atas, bawah, kiri, atau kanan.", schema(new String[]{"direction","pixels","selector","settleMs"}, new String[]{"string","integer","string","integer"}, new String[]{"up, down, left, atau right","Jarak gulir 1-10000 piksel, default 600","Selector CSS container scroll; kosong untuk halaman","Waktu tunggu setelah scroll 0-5000 ms"}, null), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.source", "Mengambil HTML DOM saat ini atau teks halaman, dengan batas panjang untuk melindungi respons.", schema(new String[]{"mode","maxChars"}, new String[]{"string","integer"}, new String[]{"html atau text, default html","Batas 100-100000 karakter"}, null), "browser", ToolDef.ACCESS_READ, true);
        add("browser.screenshot", "Mengambil screenshot viewport WebView saat ini dan melampirkan hasilnya sebagai gambar MCP.", schema(), "browser", ToolDef.ACCESS_READ, true);
        add("browser.clear_cookies", "Menghapus cookie yang terlihat untuk host situs aktif secara best-effort; tidak mengembalikan nilai cookie.", schema(), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.clear_localstorage", "Menghapus localStorage pada origin halaman aktif saja.", schema(), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.clear_sessionstorage", "Menghapus sessionStorage pada origin halaman aktif saja.", schema(), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.set_user_agent", "Mengatur User-Agent global WebView dan menyimpannya; halaman aktif dimuat ulang secara default.", schema(new String[]{"userAgent","reloadCurrent"}, new String[]{"string","boolean"}, new String[]{"User-Agent yang diinginkan; kosong atau default untuk bawaan WebView","Muat ulang halaman aktif agar request berikutnya memakai User-Agent baru, default true"}, new String[]{"userAgent"}), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.set_mode", "Memilih profil tampilan desktop atau mobile; menerapkan User-Agent profil dan viewport default.", schema(new String[]{"mode","reloadCurrent","waitMs"}, new String[]{"string","boolean","integer"}, new String[]{"desktop atau mobile","Muat ulang halaman aktif agar layout diterapkan, default true","Waktu tunggu reload 500-30000 ms, default 18000"}, new String[]{"mode"}), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.set_viewport", "Mengatur ukuran viewport CSS WebView tanpa mengubah profil mode atau User-Agent.", schema(new String[]{"width","height","reloadCurrent","waitMs"}, new String[]{"integer","integer","boolean","integer"}, new String[]{"Lebar 320-1280 CSS px","Tinggi 240-1000 CSS px","Muat ulang halaman aktif, default false","Waktu tunggu reload 500-30000 ms, default 18000"}, new String[]{"width","height"}), "browser", ToolDef.ACCESS_WRITE, true);
        add("browser.item.list", "Mendaftar resource HTTP(S) yang teramati atau dirujuk halaman aktif, dengan ID item untuk pengambilan.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Jumlah item terbaru 1-300, default 100"}, null), "browser", ToolDef.ACCESS_READ, true);
        add("browser.get.item", "Mengambil ulang resource GET berdasarkan itemId; resource di atas batas byte ditolak, gambar dilampirkan sebagai image, teks ditampilkan, dan binari dilampirkan sebagai embedded MCP resource.", schema(new String[]{"itemId","maxBytes"}, new String[]{"string","integer"}, new String[]{"ID seperti r12 dari browser.item.list","Batas 1-20000000 byte, default 20000000"}, new String[]{"itemId"}), "browser", ToolDef.ACCESS_READ, true);
        add("browser.debug.console", "Membaca entri console WebView seperti log, debug, warning, dan error yang berhasil ditangkap.", schema(new String[]{"level","limit","clearAfterRead"}, new String[]{"string","integer","boolean"}, new String[]{"all, error, warning/warn, log/info, debug, atau tip","Batas entri 1-500, default 100","Hapus seluruh entri setelah diambil"}, null), "browser", ToolDef.ACCESS_READ, true);
    }
    public static ToolDef get(String name) { return TOOLS.get(name); }
    public static List<ToolDef> all() { return new ArrayList<ToolDef>(TOOLS.values()); }
    public static JSONArray toJson(Context context) {
        JSONArray a = new JSONArray();
        List<ToolDef> list = all();
        Collections.sort(list, new Comparator<ToolDef>() { public int compare(ToolDef a, ToolDef b) { return a.name.compareTo(b.name); } });
        for (ToolDef t : list) if (context == null || ToolSettings.isEnabled(context, t)) a.put(t.toJson());
        return a;
    }
    public static JSONArray toJson() { return toJson(null); }
}
