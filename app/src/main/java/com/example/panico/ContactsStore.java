package com.example.panico;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class ContactsStore {
    private static final String PREFS = "PanicoPrefs";
    private static final String KEY_CONTACTS = "emergency_contacts";

    private final SharedPreferences sp;

    public ContactsStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public ArrayList<Contact> getContacts() {
        ArrayList<Contact> list = new ArrayList<>();
        String json = sp.getString(KEY_CONTACTS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Contact(
                        o.optString("name", ""),
                        o.optString("phone", "")
                ));
            }
        } catch (JSONException ignored) {}
        return list;
    }

    public void saveContacts(ArrayList<Contact> list) {
        JSONArray arr = new JSONArray();
        for (Contact c : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", c.name == null ? "" : c.name);
                o.put("phone", c.phone == null ? "" : c.phone);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        sp.edit().putString(KEY_CONTACTS, arr.toString()).apply();
    }

    public void addContact(Contact c) {
        ArrayList<Contact> list = getContacts();
        list.add(c);
        saveContacts(list);
    }

    public void removeAt(int index) {
        ArrayList<Contact> list = getContacts();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            saveContacts(list);
        }
    }

    public void clearAll() {
        sp.edit().putString(KEY_CONTACTS, "[]").apply();
    }
}
