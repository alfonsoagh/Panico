package com.example.panico;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

public class ContactsActivity extends AppCompatActivity implements ContactosAdapter.Listener {

    private EmergencyContactsManager manager;
    private ContactosAdapter adapter;

    private TextInputLayout tilName, tilPhone;
    private TextInputEditText etName, etPhone;
    private TextView tvCount;
    private View emptyState;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        tilName = findViewById(R.id.tilName);
        tilPhone = findViewById(R.id.tilPhone);
        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        tvCount = findViewById(R.id.tvCount);
        emptyState = findViewById(R.id.emptyState);

        // Fuerza máximo de 10 caracteres en el EditText
        etPhone.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(10) });

        // Sanea en vivo: solo dígitos y tope de 10 incluso si pegan texto
        etPhone.addTextChangedListener(new TextWatcher() {
            boolean selfChange = false;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (selfChange) return;
                String digits = s.toString().replaceAll("\\D+", "");
                if (digits.length() > 10) digits = digits.substring(0, 10);
                if (!digits.equals(s.toString())) {
                    selfChange = true;
                    etPhone.setText(digits);
                    etPhone.setSelection(digits.length());
                    selfChange = false;
                }
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> onAdd());
        findViewById(R.id.btnClearAll).setOnClickListener(v -> { manager.clearAll(); refresh(); });

        RecyclerView rv = findViewById(R.id.rvContactos);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactosAdapter(this);
        rv.setAdapter(adapter);

        manager = new EmergencyContactsManager(this);
        refresh();
    }

    private void onAdd() {
        String nombre = safe(etName.getText());
        String raw = safe(etPhone.getText()).replaceAll("\\D+", "");

        tilPhone.setError(null);

        if (raw.length() != 10) {
            tilPhone.setError("Ingresa exactamente 10 dígitos");
            return;
        }

        String formatted = formatNumeroMX(raw);
        String e164 = numeroE164MX(formatted);

        EmergencyContact c = new EmergencyContact(
                TextUtils.isEmpty(nombre) ? "Contacto" : nombre,
                formatted, e164, adapter.getItemCount() == 0 /*primer contacto => principal*/);

        boolean ok = manager.add(c);
        if (!ok) {
            Toast.makeText(this, "Máximo 5 contactos", Toast.LENGTH_SHORT).show();
            return;
        }

        etName.setText(null);
        etPhone.setText(null);
        refresh();
    }

    private void refresh() {
        List<EmergencyContact> list = manager.getAll();
        adapter.submit(list);
        tvCount.setText(list.size() + "/5");
        emptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // Adapter callbacks
    @Override public void onMakePrimary(int position) { manager.setPrimary(position); refresh(); }
    @Override public void onDelete(int position) { manager.removeAt(position); refresh(); }

    // Utils
    private static String safe(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
    private String formatNumeroMX(String digits) {
        if (digits.startsWith("52")) digits = digits.substring(2);
        if (digits.length() <= 3) return "+52 " + digits;
        else if (digits.length() <= 6) return "+52 " + digits.substring(0, 3) + " " + digits.substring(3);
        else {
            String p1 = digits.substring(0, 3);
            String p2 = digits.substring(3, Math.min(6, digits.length()));
            String p3 = digits.length() > 6 ? digits.substring(6, Math.min(10, digits.length())) : "";
            return p3.isEmpty() ? "+52 " + p1 + " " + p2 : "+52 " + p1 + " " + p2 + " " + p3;
        }
    }
    private String numeroE164MX(String text) {
        String digits = text.replaceAll("\\D+","");
        if (!digits.startsWith("52")) digits = "52" + digits;
        return "+" + digits;
    }
}
