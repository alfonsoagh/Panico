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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Firebase
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final long TIEMPO_PRESION_EMERGENCIA = 5000; // 5 segundos
    private static final String NUMERO_911 = "11111"; // usa "911" en producción
    private static final String PREFS_NAME = "PanicoPrefs";
    private static final String KEY_NUMERO_EMERGENCIA = "numero_emergencia";
    private static final String KEY_SHARE_ID = "share_id";
    private static final String KEY_TRACKING = "tracking_active";

    // Broadcast del servicio para sincronizar estado
    public static final String ACTION_TRACKING_STATE = "PANICO_TRACKING_STATE";

    // UI
    private Button btnEmergencia;
    private TextView tvEstado;
    private TextView tvUbicacion;
    private TextView tvContador;
    private EditText etNumeroEmergencia;
    private ImageButton btnGuardarNumero;
    private ProgressBar progressBar;
    private View animatedCircle;

    // Ubicación
    private LocationManager locationManager;
    private Location ubicacionActual;

    // Presión prolongada
    private Handler handlerPresion;
    private Handler handlerContador;
    private Runnable runnableEmergencia;
    private Runnable runnableContador;
    private boolean presionIniciada = false;
    private int contadorSegundos = 5;

    // Animaciones
    private ObjectAnimator animatorCircle;
    private ValueAnimator progressAnimator;

    // Vibrador y SharedPreferences
    private Vibrator vibrator;
    private SharedPreferences preferences;

    // Formateo teléfono
    private TextWatcher numeroWatcher;
    private boolean isFormatting = false;

    // Firebase
    private DatabaseReference dbRoot;
    private String uid;
    private String shareId;

    // Estado de tracking
    private boolean trackingActivo = false;

    // Base62 para shareId
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // Receiver para sincronizar estado desde el Service
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
        configurarEditTextNumero();
        cargarNumeroGuardado();
        solicitarPermisos();
        configurarBotonEmergencia();
        configurarBotonGuardar();
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
                })
                .addOnFailureListener(e -> mostrarToast("Auth anónima falló: " + e.getMessage()));
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
        java.util.Random r = new java.util.Random();
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

        trackingActivo = preferences.getBoolean(KEY_TRACKING, false);
        refrescarUiEstado();
    }

    private void configurarEditTextNumero() {
        if (numeroWatcher != null) etNumeroEmergencia.removeTextChangedListener(numeroWatcher);

        numeroWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isFormatting) return;
                String raw = s.toString();
                String digits = raw.replaceAll("\\D+", "");
                String formatted = formatNumeroMX(digits);
                if (!raw.equals(formatted)) {
                    isFormatting = true;
                    etNumeroEmergencia.setText(formatted);
                    etNumeroEmergencia.setSelection(formatted.length());
                    isFormatting = false;
                }
                btnGuardarNumero.setVisibility(digits.length() >= 10 ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etNumeroEmergencia.addTextChangedListener(numeroWatcher);
    }

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
        String digits = text.replaceAll("\\D+", "");
        if (!digits.startsWith("52")) digits = "52" + digits;
        return "+" + digits;
    }

    private void configurarBotonGuardar() {
        btnGuardarNumero.setOnClickListener(v -> guardarNumeroEmergencia());
    }

    private void guardarNumeroEmergencia() {
        String texto = etNumeroEmergencia.getText().toString();
        String digits = texto.replaceAll("\\D+", "");
        if (digits.length() < 10) {
            mostrarToast("⚠️ Ingresa un número de 10 dígitos");
            return;
        }
        preferences.edit().putString(KEY_NUMERO_EMERGENCIA, formatNumeroMX(digits)).apply();
        mostrarToast("✅ Número de emergencia guardado");
        btnGuardarNumero.setVisibility(View.GONE);
        btnGuardarNumero.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100)
                .withEndAction(() -> btnGuardarNumero.animate().scaleX(1f).scaleY(1f).setDuration(100));
    }

    private void cargarNumeroGuardado() {
        String numeroGuardado = preferences.getString(KEY_NUMERO_EMERGENCIA, "");
        if (!numeroGuardado.isEmpty()) etNumeroEmergencia.setText(numeroGuardado);
    }

    private void inicializarAnimaciones() {
        animatorCircle = ObjectAnimator.ofFloat(animatedCircle, "rotation", 0f, 360f);
        animatorCircle.setDuration(3000);
        animatorCircle.setRepeatCount(ValueAnimator.INFINITE);
        animatorCircle.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorCircle.start();
    }

    private void solicitarPermisos() {
        // Permisos base
        String[] permisosNecesarios = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE
        };

        boolean todos = true;
        for (String p : permisosNecesarios) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                todos = false;
                break;
            }
        }
        if (!todos) {
            ActivityCompat.requestPermissions(this, permisosNecesarios, REQUEST_PERMISSIONS);
        }

        // Android 13+: notificaciones
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_PERMISSIONS);
            }
        }

        if (todos) mostrarToast(getString(R.string.permissions_granted));
    }

    private void configurarBotonEmergencia() {
        btnEmergencia.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    iniciarPresionEmergencia();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelarPresionEmergencia();
                    return true;
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
            if (trackingActivo) {
                detenerEmergencia();
            } else {
                activarEmergencia();
            }
        };

        runnableContador = new Runnable() {
            @Override
            public void run() {
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
        progressAnimator.addUpdateListener(animation -> {
            if (presionIniciada) {
                int progress = (int) animation.getAnimatedValue();
                progressBar.setProgress(progress);
            }
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
            boolean notif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
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

        // Solo para UI local (NO sube a Firebase desde Activity)
        obtenerUbicacionActual();

        enviarSms();
        realizarLlamada911();

        // Iniciar servicio en primer plano: seguirá subiendo ubicación
        if (shareId != null) {
            startService(PanicLocationService.startIntent(this, shareId));
            trackingActivo = true;
            preferences.edit().putBoolean(KEY_TRACKING, true).apply();
            refrescarUiEstado();
            mostrarToast("🚨 Emergencia ACTIVADA: ubicación en vivo.");
        } else {
            mostrarToast("⚠️ Generando ID de seguimiento, intenta de nuevo…");
        }

        presionIniciada = false;
    }

    private void detenerEmergencia() {
        vibrarDispositivo(200);
        try {
            // Pide al servicio que se detenga y limpie Firebase
            startService(PanicLocationService.stopIntent(this));
            // No es estrictamente necesario llamar a stopService manualmente,
            // el propio Service se autode-tendrá.
        } catch (Exception ignored) {}

        trackingActivo = false;
        preferences.edit().putBoolean(KEY_TRACKING, false).apply();
        refrescarUiEstado();
        mostrarToast("🛑 Envío de ubicación detenido");

        presionIniciada = false;
        tvContador.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
    }

    private void refrescarUiEstado() {
        if (trackingActivo) {
            btnEmergencia.setText("Detener envío (mantén 5s)");
            btnEmergencia.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.red_primary));
            tvEstado.setText("Envío activo");
            btnEmergencia.setAlpha(1f);
        } else {
            btnEmergencia.setText(getString(R.string.emergency_button_text));
            btnEmergencia.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.green_primary));
            tvEstado.setText(getString(R.string.status_ready));
            btnEmergencia.setAlpha(1f);
        }
    }

    private void inicializarGPS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 2000, 10, this);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 2000, 10, this);
                }

                Location lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

                if (lastKnownGPS != null) {
                    ubicacionActual = lastKnownGPS;
                    actualizarTextoUbicacion();
                } else if (lastKnownNetwork != null) {
                    ubicacionActual = lastKnownNetwork;
                    actualizarTextoUbicacion();
                }
            } catch (SecurityException e) {
                tvUbicacion.setText(getString(R.string.error_location));
            }
        }
    }

    private void obtenerUbicacionActual() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (location == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (location != null) {
                    ubicacionActual = location;
                    actualizarTextoUbicacion();
                    // ⚠️ Importante: NO subimos a Firebase desde la Activity
                }
            } catch (SecurityException e) {
                tvUbicacion.setText(getString(R.string.error_location));
            }
        }
    }

    private void actualizarTextoUbicacion() {
        if (ubicacionActual != null) {
            String textoUbicacion = getString(
                    R.string.location_found,
                    ubicacionActual.getLatitude(),
                    ubicacionActual.getLongitude()
            );
            tvUbicacion.setText(textoUbicacion);
        } else {
            tvUbicacion.setText(getString(R.string.location_obtaining));
        }
    }

    private void enviarSms() {
        String texto = etNumeroEmergencia.getText().toString().trim();
        String digits = texto.replaceAll("\\D+", "");
        if (digits.length() < 10) {
            mostrarToast("⚠️ Configura un número de emergencia primero");
            return;
        }
        String numero = numeroE164MX(texto);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {

            String mensaje;
            String timestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                    new Locale("es", "MX")).format(new Date());

            if (ubicacionActual != null) {
                mensaje = "🚨 ALERTA DE EMERGENCIA 🚨\n\n" +
                        "¡Necesito ayuda urgente!\n\n" +
                        "📍 Mi ubicación exacta:\n" +
                        "Latitud: " + ubicacionActual.getLatitude() + "\n" +
                        "Longitud: " + ubicacionActual.getLongitude() + "\n\n" +
                        "🗺️ Ver en Google Maps:\n" +
                        "https://maps.google.com/?q=" + ubicacionActual.getLatitude() +
                        "," + ubicacionActual.getLongitude() + "\n\n" +
                        "🌐 Seguimiento en vivo:\n" +
                        "https://panico-web-mexico.netlify.app/?id=" + shareId + "\n\n" +
                        "⏰ Enviado: " + timestamp + "\n\n" +
                        "Enviado desde App Pánico - México";
            } else {
                mensaje = "🚨 ALERTA DE EMERGENCIA 🚨\n\n" +
                        "¡Necesito ayuda urgente!\n\n" +
                        "❌ No se pudo obtener ubicación GPS\n" +
                        "🌐 Seguimiento en vivo:\n" +
                        "https://panico-web-mexico.netlify.app/?id=" + shareId + "\n\n" +
                        "⏰ Enviado: " + timestamp + "\n\n" +
                        "Enviado desde App Pánico - México";
            }

            try {
                SmsManager smsManager = SmsManager.getDefault();
                if (mensaje.length() > 160) {
                    smsManager.sendMultipartTextMessage(
                            numero, null, smsManager.divideMessage(mensaje), null, null
                    );
                } else {
                    smsManager.sendTextMessage(numero, null, mensaje, null, null);
                }
                mostrarToast(getString(R.string.sms_sent));
            } catch (Exception e) {
                mostrarToast(getString(R.string.error_sms) + ": " + e.getMessage());
            }
        }
    }

    private void realizarLlamada911() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                String numeroLlamar = NUMERO_911; // o 911 en prod
                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(Uri.parse("tel:" + numeroLlamar));
                startActivity(intent);
                mostrarToast(getString(R.string.calling_emergency) + " " + numeroLlamar);
            } catch (Exception e) {
                mostrarToast(getString(R.string.error_call) + ": " + e.getMessage());
            }
        }
    }

    private void vibrarDispositivo(long duracionMs) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect = VibrationEffect.createOneShot(duracionMs, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(duracionMs);
        }
    }

    private void mostrarToast(String mensaje) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show();
    }

    // ===== LocationListener =====
    @Override
    public void onLocationChanged(@NonNull Location location) {
        // Solo actualizamos UI local; NO subimos a Firebase desde Activity
        ubicacionActual = location;
        actualizarTextoUbicacion();
    }

    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status == LocationProvider.OUT_OF_SERVICE) {
            tvUbicacion.setText(getString(R.string.error_location));
        }
    }
    // ===========================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean todosOtorgados = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { todosOtorgados = false; break; }
            }
            if (todosOtorgados) {
                inicializarGPS();
                mostrarToast(getString(R.string.permissions_granted));
            } else {
                mostrarToast(getString(R.string.permissions_needed));
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
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
        if (numeroWatcher != null) etNumeroEmergencia.removeTextChangedListener(numeroWatcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // No cancelamos la pulsación para permitir llamada/segundo plano.
    }
}
