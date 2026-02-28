package com.kitchenboard.weather;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Fetches weather data from the Open-Meteo free API (no API key required).
 * Geocoding also uses the free Open-Meteo geocoding API.
 */
public class WeatherApiClient {

    private static final String GEOCODING_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=en&format=json";

    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
            "&current_weather=true" +
            "&daily=temperature_2m_max,precipitation_sum,precipitation_hours,windspeed_10m_max" +
            "&hourly=windspeed_10m" +
            "&timezone=auto&forecast_days=14";

    public interface WeatherCallback {
        void onSuccess(WeatherData data);
        void onError(String message);
    }

    /**
     * Fetches weather directly from coordinates (skips geocoding step).
     * @param cityName display name for the location (e.g. from reverse geocoding)
     */
    public static void fetchWeatherByCoords(final double latitude, final double longitude,
                                             final String cityName, final WeatherCallback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String weatherResponse = httpGet(String.format(WEATHER_URL,
                            latitude, longitude));
                    JSONObject weatherJson = new JSONObject(weatherResponse);

                    JSONObject current = weatherJson.getJSONObject("current_weather");
                    double currentTemp = current.getDouble("temperature");
                    int weatherCode = current.getInt("weathercode");

                    JSONObject daily = weatherJson.getJSONObject("daily");
                    JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                    JSONArray precip = daily.getJSONArray("precipitation_sum");
                    double highTemp = maxTemps.getDouble(0);
                    double precipMm = precip.isNull(0) ? 0.0 : precip.getDouble(0);

                    WeatherData.WeekendDay[] weekend = parseWeekendDays(daily,
                            weatherJson.optJSONObject("hourly"));

                    final WeatherData data = new WeatherData(
                            currentTemp, highTemp, precipMm, weatherCode, cityName,
                            weekend[0], weekend[1]);
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onSuccess(data); }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            callback.onError("Failed to load weather: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    public static void fetchWeather(final String cityName, final WeatherCallback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Step 1: Geocode city name
                    String encodedCity = URLEncoder.encode(cityName, "UTF-8");
                    String geoResponse = httpGet(String.format(GEOCODING_URL, encodedCity));
                    JSONObject geoJson = new JSONObject(geoResponse);
                    JSONArray results = geoJson.optJSONArray("results");
                    if (results == null || results.length() == 0) {
                        final String err = "City not found: " + cityName;
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onError(err); }
                        });
                        return;
                    }
                    JSONObject place = results.getJSONObject(0);
                    final String resolvedCity = place.getString("name");
                    final double latitude = place.getDouble("latitude");
                    final double longitude = place.getDouble("longitude");

                    // Step 2: Fetch weather
                    String weatherResponse = httpGet(String.format(WEATHER_URL,
                            latitude, longitude));
                    JSONObject weatherJson = new JSONObject(weatherResponse);

                    JSONObject current = weatherJson.getJSONObject("current_weather");
                    double currentTemp = current.getDouble("temperature");
                    int weatherCode = current.getInt("weathercode");

                    JSONObject daily = weatherJson.getJSONObject("daily");
                    JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                    JSONArray precip = daily.getJSONArray("precipitation_sum");
                    double highTemp = maxTemps.getDouble(0);
                    double precipMm = precip.isNull(0) ? 0.0 : precip.getDouble(0);

                    WeatherData.WeekendDay[] weekend = parseWeekendDays(daily,
                            weatherJson.optJSONObject("hourly"));

                    final WeatherData data = new WeatherData(
                            currentTemp, highTemp, precipMm, weatherCode, resolvedCity,
                            weekend[0], weekend[1]);
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onSuccess(data); }
                    });

                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            callback.onError("Failed to load weather: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Parses forecast arrays to find the next Saturday and next Sunday.
     * Returns an array of length 2: [nextSaturday, nextSunday], either may be null.
     */
    private static WeatherData.WeekendDay[] parseWeekendDays(JSONObject daily,
                                                               JSONObject hourly) {
        WeatherData.WeekendDay[] result = new WeatherData.WeekendDay[2];
        try {
            JSONArray timeArr = daily.getJSONArray("time");
            JSONArray maxTempArr = daily.getJSONArray("temperature_2m_max");
            JSONArray precipHoursArr = daily.optJSONArray("precipitation_hours");
            JSONArray maxWindArr = daily.optJSONArray("windspeed_10m_max");
            JSONArray hourlyWindArr = hourly != null ? hourly.optJSONArray("windspeed_10m") : null;

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

            for (int i = 0; i < timeArr.length(); i++) {
                Date d = fmt.parse(timeArr.getString(i));
                if (d == null) continue;
                Calendar cal = Calendar.getInstance();
                cal.setTime(d);
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                boolean isSat = dow == Calendar.SATURDAY;
                boolean isSun = dow == Calendar.SUNDAY;

                if ((isSat && result[0] == null) || (isSun && result[1] == null)) {
                    double maxTemp = maxTempArr.isNull(i) ? 0.0 : maxTempArr.getDouble(i);
                    double precipH = (precipHoursArr == null || precipHoursArr.isNull(i))
                            ? 0.0 : precipHoursArr.getDouble(i);
                    double dryHours = Math.max(0.0, 24.0 - precipH);
                    double maxWind = (maxWindArr == null || maxWindArr.isNull(i))
                            ? 0.0 : maxWindArr.getDouble(i);

                    // Calculate mean wind from 24 hourly values for this day
                    double meanWind = 0.0;
                    if (hourlyWindArr != null) {
                        int count = 0;
                        int base = i * 24;
                        for (int h = base; h < base + 24 && h < hourlyWindArr.length(); h++) {
                            if (!hourlyWindArr.isNull(h)) {
                                meanWind += hourlyWindArr.getDouble(h);
                                count++;
                            }
                        }
                        if (count > 0) meanWind /= count;
                    }

                    String dayName = isSat ? "Saturday" : "Sunday";
                    WeatherData.WeekendDay wd = new WeatherData.WeekendDay(
                            dayName, maxTemp, dryHours, maxWind, meanWind);
                    if (isSat && result[0] == null) result[0] = wd;
                    if (isSun && result[1] == null) result[1] = wd;
                }
                if (result[0] != null && result[1] != null) break;
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + responseCode);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }
}
