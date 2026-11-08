package com.bellota.mascota.weather;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Consulta el tiempo actual en Open-Meteo usando la última ubicación conocida del teléfono. */
public final class WeatherService {
    public interface Callback { void onResult(String response); }
    private static final String PREFS = "bellota_weather";
    private static final String KEY_CACHE = "last_weather";
    private WeatherService() {}

    public static void fetch(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Location location = lastLocation(app);
        if (location == null) {
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String cached = prefs.getString(KEY_CACHE, null);
            deliver(callback, cached == null
                    ? "No pude obtener la ubicación del teléfono. Activá la ubicación y volvé a preguntarme por el clima."
                    : cached + " Este dato es el último que pude guardar y podría estar desactualizado.");
            return;
        }
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
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CACHE, answer).apply();
            } catch (Exception error) {
                String cached = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, null);
                answer = cached == null
                        ? "No pude consultar el clima en este momento. Revisá tu conexión a internet y preguntame de nuevo."
                        : cached + " No pude actualizarlo, así que podría estar desactualizado.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            deliver(callback, answer);
        }).start();
    }

    @SuppressWarnings("MissingPermission")
    private static Location lastLocation(Context context) {
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
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
