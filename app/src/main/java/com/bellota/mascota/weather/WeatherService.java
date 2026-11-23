package com.bellota.mascota.weather;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/** Consulta Open-Meteo con ubicación reciente o una única actualización bajo demanda. */
public final class WeatherService {
    public interface Callback { void onResult(String response); }
    private interface LocationCallback { void onLocation(Location location); }
    private static final String PREFS = "bellota_weather";
    private static final String KEY_CACHE = "last_weather";
    private static final String KEY_CACHE_TIME = "last_weather_time";
    private static final long MAX_LOCATION_AGE_MS = 2L * 60L * 60L * 1000L;
    private static final long MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final long LOCATION_TIMEOUT_MS = 10000L;
    private static final long MAX_FIX_AGE_MS = 2L * 60L * 1000L;
    private WeatherService() {}

    public static void fetch(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Location known = lastLocation(app);
        if (known != null) {
            fetchForLocation(app, known, callback);
            return;
        }
        // Only request one fix when the user asks for weather. No background or continuous GPS.
        requestSingleFreshLocation(app, location -> {
            if (location != null) {
                fetchForLocation(app, location, callback);
            } else {
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String cached = readFreshCache(prefs);
                deliver(callback, cached == null
                        ? "No pude obtener una ubicación reciente del teléfono. Activá la ubicación y volvé a preguntarme por el clima."
                        : cached + " No pude actualizar la ubicación; este dato guardado todavía tiene menos de seis horas.");
            }
        });
    }

    private static void fetchForLocation(Context app, Location location, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            String answer;
            try {
                String address = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto",
                        location.getLatitude(), location.getLongitude());
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(9000);
                connection.setReadTimeout(9000);
                connection.setRequestMethod("GET");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                int code = current.optInt("weather_code", -1);
                double temp = current.optDouble("temperature_2m");
                double feels = current.optDouble("apparent_temperature");
                int humidity = current.optInt("relative_humidity_2m");
                double wind = current.optDouble("wind_speed_10m");
                answer = String.format(new Locale("es", "AR"),
                        "Ahora hay %.0f grados y está %s. La sensación térmica es de %.0f grados, la humedad es del %d por ciento y el viento va a %.0f kilómetros por hora.",
                        temp, describe(code), feels, humidity, wind);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_CACHE, answer)
                        .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                        .apply();
            } catch (Exception error) {
                String cached = readFreshCache(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
                answer = cached == null
                        ? "No pude consultar el clima en este momento. Revisá tu conexión a internet y preguntame de nuevo."
                        : cached + " No pude actualizarlo; te comparto el último dato guardado de las últimas seis horas.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            deliver(callback, answer);
        }).start();
    }

    private static void requestSingleFreshLocation(Context context, LocationCallback callback) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null || !hasLocationPermission(context)) {
            callback.onLocation(null);
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] completed = { false };
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;
                long age = System.currentTimeMillis() - location.getTime();
                if (age < -60000L || age > MAX_FIX_AGE_MS) return;
                finish(location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            private void finish(Location location) {
                if (completed[0]) return;
                completed[0] = true;
                try { manager.removeUpdates(this); } catch (RuntimeException ignored) { }
                callback.onLocation(location);
            }
        };
        handler.postDelayed(() -> {
            if (completed[0]) return;
            completed[0] = true;
            try { manager.removeUpdates(listener); } catch (RuntimeException ignored) { }
            callback.onLocation(null);
        }, LOCATION_TIMEOUT_MS);
        try {
            List<String> providers = manager.getProviders(true);
            boolean requested = false;
            for (String provider : providers) {
                try {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                    requested = true;
                } catch (RuntimeException ignored) {
                    // A provider may require precise permission or may disappear; try the next one.
                }
            }
            if (!requested && !completed[0]) {
                handler.removeCallbacksAndMessages(null);
                completed[0] = true;
                callback.onLocation(null);
            }
        } catch (RuntimeException error) {
            if (!completed[0]) {
                completed[0] = true;
                handler.removeCallbacksAndMessages(null);
                callback.onLocation(null);
            }
        }
    }

    private static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static String readFreshCache(SharedPreferences prefs) {
        String cached = prefs.getString(KEY_CACHE, null);
        long savedAt = prefs.getLong(KEY_CACHE_TIME, 0L);
        long age = System.currentTimeMillis() - savedAt;
        return cached != null && savedAt > 0L && age >= 0L && age <= MAX_CACHE_AGE_MS
                ? cached : null;
    }

    @SuppressWarnings("MissingPermission")
    private static Location lastLocation(Context context) {
        if (!hasLocationPermission(context)) return null;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        long now = System.currentTimeMillis();
        try {
            for (String provider : manager.getProviders(true)) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    long age = now - candidate.getTime();
                    if (age < -5L * 60L * 1000L || age > MAX_LOCATION_AGE_MS) continue;
                    if (best == null || candidate.getTime() > best.getTime()) best = candidate;
                } catch (RuntimeException ignored) {
                    // Continue with other providers, including NETWORK when GPS is not authorized.
                }
            }
        } catch (RuntimeException ignored) {
            return best;
        }
        return best;
    }

    private static String describe(int code) {
        if (code == 0) return "despejado";
        if (code <= 3 && code >= 1) return "parcialmente nublado";
        if (code == 45 || code == 48) return "con niebla";
        if (code >= 51 && code <= 57) return "con llovizna";
        if (code >= 61 && code <= 67) return "lluvioso";
        if (code >= 71 && code <= 77) return "con nieve";
        if (code >= 80 && code <= 82) return "con chaparrones";
        if (code >= 85 && code <= 86) return "con nevadas";
        if (code >= 95) return "con tormentas";
        return "con condiciones variables";
    }

    private static void deliver(Callback callback, String value) {
        new Handler(Looper.getMainLooper()).post(() -> { if (callback != null) callback.onResult(value); });
    }
}
