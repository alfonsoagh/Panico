package com.example.panico;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class PanicLocationService extends Service implements LocationListener {

    public static final String ACTION_START = "com.example.panico.ACTION_START";
    public static final String ACTION_STOP  = "com.example.panico.ACTION_STOP";
    private static final String EXTRA_SHARE_ID = "extra_share_id";
    private static final String CHANNEL_ID = "panic_tracking_channel";
    private static final int NOTIF_ID = 911001;

    // Broadcast para sincronizar UI en MainActivity
    public static final String ACTION_TRACKING_STATE = MainActivity.ACTION_TRACKING_STATE;

    private LocationManager locationManager;
    private DatabaseReference dbRoot;
    private String shareId;

    public static Intent startIntent(Context ctx, String shareId) {
        Intent i = new Intent(ctx, PanicLocationService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_SHARE_ID, shareId);
        return i;
    }

    public static Intent stopIntent(Context ctx) {
        Intent i = new Intent(ctx, PanicLocationService.class);
        i.setAction(ACTION_STOP);
        return i;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        dbRoot = FirebaseDatabase.getInstance().getReference();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            shareId = intent.getStringExtra(EXTRA_SHARE_ID);

            // Asegura que el share vuelva a ser público al iniciar
            setShareVisibility(true);

            // Notificación con botón "Detener"
            startForeground(NOTIF_ID, buildNotificationWithStopAction());

            // Pedir updates SOLO cuando el service está activo
            try {
                if (locationManager != null) {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10f, this);
                    }
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 10f, this);
                    }
                }
            } catch (SecurityException ignored) {}

            // Avisar a la UI
            sendBroadcast(new Intent(ACTION_TRACKING_STATE).putExtra("active", true));

            // No queremos que el sistema lo reviva si muere
            return START_NOT_STICKY;

        } else if (ACTION_STOP.equals(action)) {
            stopTracking(/*clearFirebase=*/true);
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotificationWithStopAction() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        // Acción DETENER
        Intent stopI = new Intent(this, PanicLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher) // asegúrate de tener un ícono válido
                .setContentTitle("Envío de ubicación activo")
                .setContentText("Tu posición se comparte en tiempo real.")
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(
                        R.drawable.apppanico, "Detener", stopPi // usa un drawable existente
                ))
                .build();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Seguimiento emergencia", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        // SOLO el Service sube a Firebase
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
        // 1) Quitar updates
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) {}

        // 2) Limpieza "suave" en Firebase si se pide
        if (clearFirebase && dbRoot != null && shareId != null) {
            // Borrar ubicación visible
            dbRoot.child("shares").child(shareId).child("location").removeValue();

            // Ocultar el share en la web y marcar stop
            setShareVisibility(false);
        }

        // 3) Avisar a la UI
        sendBroadcast(new Intent(ACTION_TRACKING_STATE).putExtra("active", false));

        // 4) Detener foreground y Service
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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si el usuario "cierra" desde recientes, detenemos y limpiamos Firebase
        stopTracking(/*clearFirebase=*/true);
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}


