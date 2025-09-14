package com.example.panico;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.Priority;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.CancellationTokenSource;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * - Actualiza por TIEMPO (cada 2s), no por distancia
 * - PRIORITY_HIGH_ACCURACY
 * - Pide un fix inicial preciso (getCurrentLocation)
 * - Filtra lecturas con mala accuracy (>50m) o mock
 */
public class PanicLocationService extends Service {

    public static final String ACTION_START = "com.example.panico.ACTION_START";
    public static final String ACTION_STOP  = "com.example.panico.ACTION_STOP";
    private static final String EXTRA_SHARE_ID = "extra_share_id";
    private static final String CHANNEL_ID = "panic_tracking_channel";
    private static final int NOTIF_ID = 911001;

    // Broadcast para sincronizar UI en MainActivity
    public static final String ACTION_TRACKING_STATE = MainActivity.ACTION_TRACKING_STATE;

    // Intervalos (ms)
    private static final long INTERVAL_MS = 2000;     // cada 2s
    private static final long FASTEST_MS = 1000;      // como mínimo 1s
    private static final float MAX_ALLOWED_ACCURACY_M = 50f; // descarta lecturas peores a 50m

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private DatabaseReference dbRoot;
    private String shareId;

    public static Intent startIntent(android.content.Context ctx, String shareId) {
        Intent i = new Intent(ctx, PanicLocationService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_SHARE_ID, shareId);
        return i;
    }

    public static Intent stopIntent(android.content.Context ctx) {
        Intent i = new Intent(ctx, PanicLocationService.class);
        i.setAction(ACTION_STOP);
        return i;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        dbRoot = FirebaseDatabase.getInstance().getReference();
        fused = LocationServices.getFusedLocationProviderClient(this);
        createChannelIfNeeded();
        buildCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            shareId = intent.getStringExtra(EXTRA_SHARE_ID);

            // Asegura visibilidad pública del share al iniciar
            setShareVisibility(true);

            // Foreground mínimo (no se puede ocultar en Android 8+)
            startForeground(NOTIF_ID, buildMinNotification());

            // Verifica settings (GPS encendido, alta precisión)
            LocationRequest req = buildLocationRequest();
            LocationSettingsRequest settingsReq = new LocationSettingsRequest.Builder()
                    .addLocationRequest(req)
                    .setAlwaysShow(false)
                    .build();

            Task<LocationSettingsResponse> settingsTask =
                    LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq);

            settingsTask.addOnSuccessListener(r -> {
                // Arranca actualizaciones por tiempo
                startTimedUpdates(req);
                // Pide un fix inicial de alta precisión para acelerar el arranque
                requestOneAccurateFix();
                // Notifica a la UI
                sendBroadcast(new Intent(ACTION_TRACKING_STATE).putExtra("active", true));
            }).addOnFailureListener(e -> {
                // Aun si falla, intenta con lo que haya
                startTimedUpdates(req);
                requestOneAccurateFix();
                sendBroadcast(new Intent(ACTION_TRACKING_STATE).putExtra("active", true));
            });

            return START_NOT_STICKY;

        } else if (ACTION_STOP.equals(action)) {
            stopTracking(/*clearFirebase=*/true);
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private LocationRequest buildLocationRequest() {
        // Builder nuevo de Play Services (intervalo por tiempo, no por distancia)
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_MS)
                .setWaitForAccurateLocation(true)     // pide una lectura precisa cuando sea posible
                .setMaxUpdateDelayMillis(INTERVAL_MS) // sin batching
                .build();
    }

    private void startTimedUpdates(LocationRequest req) {
        try {
            fused.requestLocationUpdates(req, callback, getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void requestOneAccurateFix() {
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) handleLocation(loc);
                    });
        } catch (SecurityException ignored) {}
    }

    private void buildCallback() {
        callback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull com.google.android.gms.location.LocationResult result) {
                for (Location l : result.getLocations()) handleLocation(l);
            }
        };
    }

    private void handleLocation(Location loc) {
        if (loc == null) return;
        // Filtra mock y lecturas imprecisas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (loc.isMock()) return;
        } else if (loc.isFromMockProvider()) {
            return;
        }
        if (loc.hasAccuracy() && loc.getAccuracy() > MAX_ALLOWED_ACCURACY_M) {
            // Demasiado imprecisa, descarta
            return;
        }

        if (dbRoot == null || shareId == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("lat", loc.getLatitude());
        payload.put("lng", loc.getLongitude());
        payload.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : null);
        payload.put("speed", loc.hasSpeed() ? loc.getSpeed() : null);
        payload.put("bearing", loc.hasBearing() ? loc.getBearing() : null);
        payload.put("timestamp", System.currentTimeMillis());
        dbRoot.child("shares").child(shareId).child("location").updateChildren(payload);
    }

    private void stopTracking(boolean clearFirebase) {
        try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}

        if (clearFirebase && dbRoot != null && shareId != null) {
            dbRoot.child("shares").child(shareId).child("location").removeValue();
            setShareVisibility(false);
        }

        sendBroadcast(new Intent(ACTION_TRACKING_STATE).putExtra("active", false));
        stopForeground(true);
        stopSelf();
    }

    private void setShareVisibility(boolean isPublic) {
        if (dbRoot == null || shareId == null) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("publicRead", isPublic);
        meta.put(isPublic ? "resumedAt" : "stoppedAt", System.currentTimeMillis());
        dbRoot.child("shares").child(shareId).updateChildren(meta);
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Seguimiento emergencia", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Ubicación en vivo durante emergencias");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildMinNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Envío de ubicación activo")
                .setContentText("Compartiendo tu posición (alta precisión)")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si el usuario "cierra" desde recientes
        stopTracking(/*clearFirebase=*/true);
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }
}
