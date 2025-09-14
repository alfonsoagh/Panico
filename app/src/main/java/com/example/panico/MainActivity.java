package com.example.panico;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements LocationListener {

    // ====== Permisos ======
    private static final int REQ_BASE_PERMS = 100;
    private static final int REQ_BG_LOCATION = 101;

    // ====== Config ======
    private static final long TIEMPO_PRESION_EMERGENCIA = 5000; // 5 s
    private static final String NUMERO_911 = "11111"; // usa 911 en prod
    private static final String PREFS_NAME = "PanicoPrefs";
    private static final String KEY_SHARE_ID = "share_id";
    private static final String KEY_TRACKING = "tracking_active";

    public static final String ACTION_TRACKING_STATE = "PANICO_TRACKING_STATE";

    // UI
    private Button btnEmergencia;
    private TextView tvEstado, tvUbicacion, tvContador;
    private ProgressBar progressBar;
    private View animatedCircle;

    // Ubicación
    private LocationManager locationManager;
    private Location ubicacionActual;

    // Presión prolongada
    private Handler handlerPresion, handlerContador;
    private Runnable runnableEmergencia, runnableContador;
    private boolean presionIniciada = false;
    private int contadorSegundos = 5;

    // Animaciones
    private ObjectAnimator animatorCircle;
    private ValueAnimator progressAnimator;

    // Vibrador y prefs
    private Vibrator vibrator;
    private SharedPreferences preferences;

    // Firebase
    private DatabaseReference dbRoot;
    private String uid, shareId;
    private ValueEventListener activeListener;

    // Estado (fuente de verdad: Firebase)
    private boolean trackingActivo = false;

    // Base62
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // Contactos
    private EmergencyContactsManager contactsManager;

    // Receiver local del service
    private final BroadcastReceiver trackingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || !ACTION_TRACKING_STATE.equals(i.getAction())) return;
            boolean active = i.getBooleanExtra("active", false);
            trackingActivo = active;
            preferences.edit().putBoolean(KEY_TRACKING, active).apply();
            refrescarUiEstado();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inicializarFirebaseYAuth();
        inicializarComponentes();
        solicitarPermisos();   // pide todo al inicio
        configurarBotonEmergencia();
        inicializarGPS();
        inicializarAnimaciones();
    }

    // ====================== FIREBASE ======================
    private void inicializarFirebaseYAuth() {
        try { FirebaseApp.initializeApp(this); } catch (Exception ignored) {}
        dbRoot = FirebaseDatabase.getInstance().getReference();

        FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    uid = (user != null) ? user.getUid() : null;
                    shareId = obtenerOGenerarShareIdLocal();
                    inicializarMetaEnFirebase();
                    iniciarObservadorEstadoFirebase(); // ← re-sincroniza contra Firebase
                })
                .addOnFailureListener(e -> mostrarToast("Auth anónima falló: " + e.getMessage()));
    }

    private void iniciarObservadorEstadoFirebase() {
        if (dbRoot == null || shareId == null) return;
        DatabaseReference activeRef = dbRoot.child("shares").child(shareId).child("active");

        if (activeListener != null) activeRef.removeEventListener(activeListener);

        activeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean active = snap.getValue(Boolean.class);
                boolean isActive = active != null && active;

                trackingActivo = isActive;
                preferences.edit().putBoolean(KEY_TRACKING, isActive).apply();
                refrescarUiEstado();

                // Si Firebase dice que está activo y el Service no corre, lo arrancamos.
                try {
                    if (isActive) {
                        startService(PanicLocationService.startIntent(MainActivity.this, shareId));
                    } else {
                        startService(PanicLocationService.stopIntent(MainActivity.this));
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        activeRef.addValueEventListener(activeListener);
    }

    private String obtenerOGenerarShareIdLocal() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String id = sp.getString(KEY_SHARE_ID, null);
        if (id != null) return id;
        id = randomId(8);
        sp.edit().putString(KEY_SHARE_ID, id).apply();
        return id;
    }

    private String randomId(int len) {
        StringBuilder sb = new StringBuilder(len);
        Random r = new Random();
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private void inicializarMetaEnFirebase() {
        if (dbRoot == null || uid == null || shareId == null) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("owner", uid);
        meta.put("publicRead", true);
        meta.put("createdAt", System.currentTimeMillis());
        dbRoot.child("shares").child(shareId).updateChildren(meta);
        dbRoot.child("users").child(uid).child("activeShareId").setValue(shareId);
    }
    // ================== FIN FIREBASE ======================

    private void inicializarComponentes() {
        btnEmergencia = findViewById(R.id.btnEmergencia);
        Button btnOpenContacts = findViewById(R.id.btnOpenContacts);
        btnOpenContacts.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ContactsActivity.class)));

        tvEstado = findViewById(R.id.tvEstado);
        tvUbicacion = findViewById(R.id.tvUbicacion);
        tvContador = findViewById(R.id.tvContador);
        progressBar = findViewById(R.id.progressBar);
        animatedCircle = findViewById(R.id.animatedCircle);

        handlerPresion = new Handler(Looper.getMainLooper());
        handlerContador = new Handler(Looper.getMainLooper());

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        contactsManager = new EmergencyContactsManager(this);

        trackingActivo = preferences.getBoolean(KEY_TRACKING, false);
        refrescarUiEstado();
    }

    private void inicializarAnimaciones() {
        animatorCircle = ObjectAnimator.ofFloat(animatedCircle, "rotation", 0f, 360f);
        animatorCircle.setDuration(3000);
        animatorCircle.setRepeatCount(ValueAnimator.INFINITE);
        animatorCircle.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorCircle.start();
    }

    // ======= Permisos (inicio) =======
    private void solicitarPermisos() {
        ArrayList<String> base = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            base.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            base.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            base.add(Manifest.permission.SEND_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            base.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            base.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!base.isEmpty()) {
            ActivityCompat.requestPermissions(this, base.toArray(new String[0]), REQ_BASE_PERMS);
        } else {
            pedirBackgroundSiHaceFalta();
            mostrarToast(getString(R.string.permissions_granted));
        }
    }

    private void pedirBackgroundSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        REQ_BG_LOCATION);
            }
        }
    }
    // =================================

    private void configurarBotonEmergencia() {
        btnEmergencia.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: iniciarPresionEmergencia(); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: cancelarPresionEmergencia(); return true;
            }
            return false;
        });
    }

    private void iniciarPresionEmergencia() {
        if (presionIniciada) return;
        presionIniciada = true;
        contadorSegundos = 5;

        vibrarDispositivo(100);

        tvContador.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        tvEstado.setText(trackingActivo
                ? "Mantén para DETENER en " + contadorSegundos + "…"
                : getString(R.string.status_pressing));

        iniciarAnimacionProgreso();

        runnableEmergencia = () -> {
            if (!presionIniciada) return;
            if (trackingActivo) detenerEmergencia(); else activarEmergencia();
        };

        runnableContador = new Runnable() {
            @Override public void run() {
                if (presionIniciada && contadorSegundos > 0) {
                    tvContador.setText(String.valueOf(contadorSegundos));
                    tvEstado.setText(trackingActivo
                            ? "Mantén para DETENER en " + contadorSegundos + "…"
                            : getString(R.string.status_countdown, contadorSegundos));
                    contadorSegundos--;
                    vibrarDispositivo(50);
                    handlerContador.postDelayed(this, 1000);
                }
            }
        };

        handlerPresion.postDelayed(runnableEmergencia, TIEMPO_PRESION_EMERGENCIA);
        handlerContador.post(runnableContador);
    }

    private void iniciarAnimacionProgreso() {
        progressAnimator = ValueAnimator.ofInt(0, 100);
        progressAnimator.setDuration(TIEMPO_PRESION_EMERGENCIA);
        progressAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        progressAnimator.addUpdateListener(a -> {
            if (presionIniciada) progressBar.setProgress((int) a.getAnimatedValue());
        });
        progressAnimator.start();
    }

    private void cancelarPresionEmergencia() {
        presionIniciada = false;
        if (runnableEmergencia != null) handlerPresion.removeCallbacks(runnableEmergencia);
        if (runnableContador != null) handlerContador.removeCallbacks(runnableContador);
        if (progressAnimator != null && progressAnimator.isRunning()) progressAnimator.cancel();

        tvContador.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        progressBar.setProgress(0);

        tvEstado.setText(trackingActivo ? "Envío activo" : getString(R.string.status_ready));
    }

    private boolean tienePermisosNecesarios() {
        boolean base =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean notif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            return base && notif;
        }
        return base;
    }

    private void activarEmergencia() {
        if (!tienePermisosNecesarios()) {
            mostrarToast("⚠️ Otorga ubicación/SMS/llamadas y notificaciones para activar.");
            cancelarPresionEmergencia();
            solicitarPermisos();
            return;
        }

        vibrarDispositivo(300);
        tvEstado.setText(getString(R.string.status_activated));
        tvContador.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        obtenerUbicacionActual();
        enviarSmsAContactos();
        realizarLlamada911();

        if (shareId != null) {
            Map<String,Object> meta = new HashMap<>();
            meta.put("active", true);
            meta.put("publicRead", true);
            meta.put("resumedAt", System.currentTimeMillis());
            dbRoot.child("shares").child(shareId).updateChildren(meta);

            startService(PanicLocationService.startIntent(this, shareId));
            mostrarToast("🚨 Emergencia ACTIVADA: ubicación en vivo.");
        } else {
            mostrarToast("⚠️ Generando ID de seguimiento, intenta de nuevo…");
        }

        presionIniciada = false;
    }

    private void detenerEmergencia() {
        vibrarDispositivo(200);
        try { startService(PanicLocationService.stopIntent(this)); } catch (Exception ignored) {}

        if (shareId != null) {
            Map<String,Object> meta = new HashMap<>();
            meta.put("active", false);
            meta.put("publicRead", false);
            meta.put("stoppedAt", System.currentTimeMillis());
            dbRoot.child("shares").child(shareId).updateChildren(meta);
        }

        presionIniciada = false;
        tvContador.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
    }

    private void refrescarUiEstado() {
        if (trackingActivo) {
            btnEmergencia.setText("Detener envío (mantén 5s)");
            btnEmergencia.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.red_primary));
            tvEstado.setText("Envío activo");
        } else {
            btnEmergencia.setText(getString(R.string.emergency_button_text));
            btnEmergencia.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_primary));
            tvEstado.setText(getString(R.string.status_ready));
        }
    }

    private void inicializarGPS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10, this);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 10, this);
                }

                Location lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                ubicacionActual = (lastKnownGPS != null) ? lastKnownGPS : lastKnownNetwork;
                actualizarTextoUbicacion();
            } catch (SecurityException e) {
                tvUbicacion.setText(getString(R.string.error_location));
            }
        }
    }

    private void obtenerUbicacionActual() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                Location l = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null) l = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (l != null) { ubicacionActual = l; actualizarTextoUbicacion(); }
            } catch (SecurityException e) {
                tvUbicacion.setText(getString(R.string.error_location));
            }
        }
    }

    private void actualizarTextoUbicacion() {
        if (ubicacionActual != null) {
            String txt = getString(R.string.location_found, ubicacionActual.getLatitude(), ubicacionActual.getLongitude());
            tvUbicacion.setText(txt);
        } else {
            tvUbicacion.setText(getString(R.string.location_obtaining));
        }
    }

    private void enviarSmsAContactos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            mostrarToast("⚠️ Falta permiso para SMS");
            return;
        }

        List<EmergencyContact> contactos = contactsManager.getAll();
        if (contactos.isEmpty()) {
            mostrarToast("⚠️ Configura al menos un contacto");
            return;
        }

        String timestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("es","MX")).format(new Date());
        String base;
        if (ubicacionActual != null) {
            base = "🚨 ALERTA DE EMERGENCIA 🚨\n\n" +
                    "¡Necesito ayuda urgente!\n\n" +
                    "📍 Mi ubicación:\n" +
                    "Lat: " + ubicacionActual.getLatitude() + "\n" +
                    "Lng: " + ubicacionActual.getLongitude() + "\n\n" +
                    "🗺️ https://maps.google.com/?q=" + ubicacionActual.getLatitude() + "," + ubicacionActual.getLongitude() + "\n\n" +
                    "🌐 Seguimiento:\nhttps://panico-web-mexico.netlify.app/?id=" + shareId + "\n\n" +
                    "⏰ " + timestamp + "\n\n" +
                    "App Pánico - México";
        } else {
            base = "🚨 ALERTA DE EMERGENCIA 🚨\n\n" +
                    "¡Necesito ayuda urgente!\n\n" +
                    "❌ Sin GPS en este momento\n" +
                    "🌐 Seguimiento:\nhttps://panico-web-mexico.netlify.app/?id=" + shareId + "\n\n" +
                    "⏰ " + timestamp + "\n\n" +
                    "App Pánico - México";
        }

        SmsManager sms = SmsManager.getDefault();
        int enviados = 0;
        for (EmergencyContact c : contactos) {
            String cuerpo = base;
//                    (c.name != null && !c.name.trim().isEmpty())
//                    ? ("👤 " + c.name + "\n\n" + base) : base;
            try {
                if (cuerpo.length() > 160) {
                    sms.sendMultipartTextMessage(c.phoneE164, null, sms.divideMessage(cuerpo), null, null);
                } else {
                    sms.sendTextMessage(c.phoneE164, null, cuerpo, null, null);
                }
                enviados++;
            } catch (Exception ignored) {}
        }
        if (enviados > 0) mostrarToast("✅ SMS enviado a " + enviados + " contacto(s)");
        else mostrarToast("⚠️ No se pudo enviar SMS");
    }

    private void realizarLlamada911() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(android.net.Uri.parse("tel:" + NUMERO_911));
                startActivity(intent);
                mostrarToast(getString(R.string.calling_emergency) + " " + NUMERO_911);
            } catch (Exception e) {
                mostrarToast(getString(R.string.error_call) + ": " + e.getMessage());
            }
        }
    }

    private void vibrarDispositivo(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else vibrator.vibrate(ms);
    }

    private void mostrarToast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    // ===== LocationListener =====
    @Override public void onLocationChanged(@NonNull Location location) { ubicacionActual = location; actualizarTextoUbicacion(); }
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status == LocationProvider.OUT_OF_SERVICE) tvUbicacion.setText(getString(R.string.error_location));
    }

    // ===== registerReceiver con flag en 33+ =====
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_TRACKING_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(trackingReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(trackingReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationManager != null) locationManager.removeUpdates(this);
        if (animatorCircle != null) animatorCircle.cancel();
        if (progressAnimator != null) progressAnimator.cancel();
        if (dbRoot != null && shareId != null && activeListener != null) {
            dbRoot.child("shares").child(shareId).child("active").removeEventListener(activeListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BASE_PERMS) {
            boolean todos = true;
            for (int r : grantResults) { if (r != PackageManager.PERMISSION_GRANTED) { todos = false; break; } }
            if (todos) { inicializarGPS(); mostrarToast(getString(R.string.permissions_granted)); pedirBackgroundSiHaceFalta(); }
            else { mostrarToast(getString(R.string.permissions_needed)); }
        } else if (requestCode == REQ_BG_LOCATION) {
            // opcional: feedback adicional
        }
    }
}
