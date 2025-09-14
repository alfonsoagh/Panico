package com.example.panico;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class EmergencyContactsManager {
    private static final String PREFS = "PanicoPrefs";
    private static final String KEY = "emergency_contacts";
    private static final int MAX = 5;

    private final SharedPreferences sp;

    public EmergencyContactsManager(Context ctx) { sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public List<EmergencyContact> getAll() {
        String json = sp.getString(KEY, "[]");
        List<EmergencyContact> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i=0;i<arr.length();i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new EmergencyContact(
                        o.optString("name",""),
                        o.optString("phoneFormatted",""),
                        o.optString("phoneE164",""),
                        o.optBoolean("isPrimary", false)
                ));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private void save(List<EmergencyContact> list) {
        JSONArray arr = new JSONArray();
        for (EmergencyContact c : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", c.name);
                o.put("phoneFormatted", c.phoneFormatted);
                o.put("phoneE164", c.phoneE164);
                o.put("isPrimary", c.isPrimary);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    public boolean add(EmergencyContact c) {
        List<EmergencyContact> list = getAll();
        if (list.size() >= MAX) return false;
        if (c.isPrimary) for (EmergencyContact e : list) e.isPrimary = false;
        else {
            boolean any = false; for (EmergencyContact e : list) if (e.isPrimary) { any = true; break; }
            if (!any) c.isPrimary = true;
        }
        list.add(c);
        save(list);
        return true;
    }

    public void removeAt(int idx) {
        List<EmergencyContact> list = getAll();
        if (idx < 0 || idx >= list.size()) return;
        boolean wasPrimary = list.get(idx).isPrimary;
        list.remove(idx);
        if (wasPrimary && !list.isEmpty()) list.get(0).isPrimary = true;
        save(list);
    }

    public void setPrimary(int idx) {
        List<EmergencyContact> list = getAll();
        if (idx < 0 || idx >= list.size()) return;
        for (int i=0;i<list.size();i++) list.get(i).isPrimary = (i==idx);
        save(list);
    }

    public void clearAll() {
        sp.edit().putString(KEY, "[]").apply();
    }
}
