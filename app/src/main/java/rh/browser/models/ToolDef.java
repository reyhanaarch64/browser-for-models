package rh.browser.models;

import org.json.JSONArray;
import org.json.JSONObject;

public class ToolDef {
    public final String name;
    public final String description;
    public final JSONObject inputSchema;
    public final String category;
    public final int accessMode;
    public final boolean defaultEnabled;

    public static final int ACCESS_NONE = 0;
    public static final int ACCESS_READ = 1;
    public static final int ACCESS_WRITE = 2;

    public ToolDef(String name, String description, JSONObject inputSchema,
                   String category, int accessMode, boolean defaultEnabled) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.category = category;
        this.accessMode = accessMode;
        this.defaultEnabled = defaultEnabled;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("description", description);
            o.put("inputSchema", inputSchema);
        } catch (Exception ignored) {
        }
        return o;
    }
}
