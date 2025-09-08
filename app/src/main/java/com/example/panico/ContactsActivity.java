package com.example.panico;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class ContactsActivity extends AppCompatActivity {

    private EditText etName, etPhone;
    private Button btnAdd, btnClearAll;
    private ListView listView;
    private ArrayAdapter<Contact> adapter;
    private ArrayList<Contact> data;
    private ContactsStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        store = new ContactsStore(this);

        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        btnAdd = findViewById(R.id.btnAdd);
        btnClearAll = findViewById(R.id.btnClearAll);
        listView = findViewById(R.id.listView);

        data = store.getContacts();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, data);
        listView.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> onAdd());
        btnClearAll.setOnClickListener(v -> onClearAll());

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(this)
                    .setTitle("Eliminar contacto")
                    .setMessage("¿Deseas eliminar este contacto?")
                    .setPositiveButton("Eliminar", (d, w) -> {
                        store.removeAt(position);
                        data.clear();
                        data.addAll(store.getContacts());
                        adapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        });
    }

    private void onAdd() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (TextUtils.isEmpty(phone)) {
            Toast.makeText(this, "Ingresa el teléfono", Toast.LENGTH_SHORT).show();
            return;
        }

        // Normaliza un poco la vista (solo presentación, guardamos tal cual)
        String pretty = PhoneNumberUtils.formatNumber(phone, Locale.getDefault().getCountry());
        if (!TextUtils.isEmpty(pretty)) phone = pretty;

        store.addContact(new Contact(name, phone));
        data.clear();
        data.addAll(store.getContacts());
        adapter.notifyDataSetChanged();

        etName.setText("");
        etPhone.setText("");
        Toast.makeText(this, "Contacto agregado", Toast.LENGTH_SHORT).show();
    }

    private void onClearAll() {
        if (data.isEmpty()) {
            Toast.makeText(this, "No hay contactos", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eliminar todos")
                .setMessage("¿Seguro que deseas borrar todos los contactos?")
                .setPositiveButton("Sí, borrar todo", (d, w) -> {
                    store.clearAll();
                    data.clear();
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Contactos eliminados", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
