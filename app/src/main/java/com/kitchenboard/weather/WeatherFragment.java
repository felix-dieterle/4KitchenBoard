package com.kitchenboard.weather;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kitchenboard.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WeatherFragment extends Fragment {

    private static final String PREFS_NAME = "weather_prefs";
    private static final String KEY_CITY = "city_name";
    private static final String DEFAULT_CITY = "Berlin";
    private static final int LOCATION_PERMISSION_REQUEST = 100;

    private EditText etCity;
    private TextView tvIcon;
    private TextView tvCurrentTemp;
    private TextView tvDescription;
    private TextView tvHighTemp;
    private TextView tvRain;
    private TextView tvDate;
    private TextView tvStatus;
    private ProgressBar progressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_weather, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etCity = view.findViewById(R.id.et_city);
        tvIcon = view.findViewById(R.id.tv_weather_icon);
        tvCurrentTemp = view.findViewById(R.id.tv_current_temp);
        tvDescription = view.findViewById(R.id.tv_weather_desc);
        tvHighTemp = view.findViewById(R.id.tv_high_temp);
        tvRain = view.findViewById(R.id.tv_rain);
        tvDate = view.findViewById(R.id.tv_date);
        tvStatus = view.findViewById(R.id.tv_status);
        progressBar = view.findViewById(R.id.progress_weather);
        Button btnRefresh = view.findViewById(R.id.btn_refresh);
        Button btnLocate = view.findViewById(R.id.btn_locate);

        String savedCity = getSavedCity();
        etCity.setText(savedCity);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadWeather();
            }
        });

        btnLocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocationWeather();
            }
        });

        etCity.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadWeather();
                    return true;
                }
                return false;
            }
        });

        loadWeather();
    }

    private void requestLocationWeather() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
        } else {
            fetchLocationAndWeather();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLocationAndWeather();
            } else {
                tvStatus.setText(R.string.location_permission_denied);
                tvStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    private void fetchLocationAndWeather() {
        LocationManager lm = (LocationManager)
                requireActivity().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            tvStatus.setText(R.string.location_unavailable);
            tvStatus.setVisibility(View.VISIBLE);
            return;
        }

        Location location = null;
        try {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (location == null && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (location == null && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                location = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }
        } catch (SecurityException e) {
            tvStatus.setText(R.string.location_unavailable);
            tvStatus.setVisibility(View.VISIBLE);
            return;
        }

        if (location == null) {
            tvStatus.setText(R.string.location_unavailable);
            tvStatus.setVisibility(View.VISIBLE);
            return;
        }

        loadWeatherByLocation(location.getLatitude(), location.getLongitude());
    }

    private void loadWeatherByLocation(final double lat, final double lon) {
        showLoading(true);
        tvStatus.setVisibility(View.GONE);
        final Context appContext = requireContext().getApplicationContext();
        final String fallbackName = getString(R.string.current_location);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String cityName = fallbackName;
                try {
                    Geocoder geocoder = new Geocoder(appContext, Locale.getDefault());
                    @SuppressWarnings("deprecation")
                    List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        String locality = addresses.get(0).getLocality();
                        if (locality != null) cityName = locality;
                    }
                } catch (Exception ignored) {}

                final String resolvedCity = cityName;
                WeatherApiClient.fetchWeatherByCoords(lat, lon, resolvedCity,
                        new WeatherApiClient.WeatherCallback() {
                            @Override
                            public void onSuccess(WeatherData data) {
                                if (isAdded()) {
                                    showLoading(false);
                                    displayWeather(data);
                                    saveCity(data.getCityName());
                                    etCity.setText(data.getCityName());
                                }
                            }

                            @Override
                            public void onError(String message) {
                                if (isAdded()) {
                                    showLoading(false);
                                    tvStatus.setText(message);
                                    tvStatus.setVisibility(View.VISIBLE);
                                }
                            }
                        });
            }
        }).start();
    }

    private void loadWeather() {
        String city = etCity.getText().toString().trim();
        if (city.isEmpty()) {
            city = DEFAULT_CITY;
            etCity.setText(city);
        }
        saveCity(city);
        hideKeyboard();
        showLoading(true);
        tvStatus.setVisibility(View.GONE);

        final String cityToLoad = city;
        WeatherApiClient.fetchWeather(cityToLoad, new WeatherApiClient.WeatherCallback() {
            @Override
            public void onSuccess(WeatherData data) {
                showLoading(false);
                displayWeather(data);
            }

            @Override
            public void onError(String message) {
                showLoading(false);
                tvStatus.setText(message);
                tvStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    private void displayWeather(WeatherData data) {
        tvIcon.setText(data.getWeatherIcon());
        tvCurrentTemp.setText(String.format("%.0f°C", data.getCurrentTemperature()));
        tvDescription.setText(data.getWeatherDescription());
        tvHighTemp.setText(String.format("High: %.0f°C", data.getHighTemperature()));

        if (data.getPrecipitationMm() > 0) {
            tvRain.setText(String.format("Rain: %.1f mm", data.getPrecipitationMm()));
        } else {
            tvRain.setText("No rain expected");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.getDefault());
        tvDate.setText(sdf.format(new Date()));
        tvDate.setVisibility(View.VISIBLE);

        tvStatus.setVisibility(View.GONE);

        // Update city field with resolved name
        etCity.setText(data.getCityName());
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        tvIcon.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvCurrentTemp.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvDescription.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvHighTemp.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvRain.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvDate.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private String getSavedCity() {
        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CITY, DEFAULT_CITY);
    }

    private void saveCity(String city) {
        requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CITY, city).apply();
    }

    private void hideKeyboard() {
        View view = requireActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)
                    requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
}
