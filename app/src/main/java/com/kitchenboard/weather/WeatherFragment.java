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
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

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
    private static final long SUB_PAGE_ADVANCE_MS = 5_000;

    private EditText etCity;
    private ProgressBar progressBar;

    // Views inside view_weather_current.xml (page 0)
    private TextView tvIcon;
    private TextView tvCurrentTemp;
    private TextView tvDescription;
    private TextView tvHighTemp;
    private TextView tvRain;
    private TextView tvDate;
    private TextView tvStatus;

    // Views inside view_weather_weekend.xml (page 1)
    private TextView tvSatMaxTemp;
    private TextView tvSatDryHours;
    private TextView tvSatWind;
    private TextView tvSunMaxTemp;
    private TextView tvSunDryHours;
    private TextView tvSunWind;
    private TextView tvWeekendStatus;

    private ViewPager2 weatherViewPager;
    private WeatherPagerAdapter weatherPagerAdapter;
    private View[] weatherDots;

    private final Handler subPageHandler = new Handler(Looper.getMainLooper());
    private final Runnable subPageRunnable = new Runnable() {
        @Override
        public void run() {
            if (weatherViewPager == null || weatherPagerAdapter == null) return;
            int next = (weatherViewPager.getCurrentItem() + 1)
                    % weatherPagerAdapter.getItemCount();
            weatherViewPager.setCurrentItem(next, true);
            subPageHandler.postDelayed(this, SUB_PAGE_ADVANCE_MS);
        }
    };

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
        progressBar = view.findViewById(R.id.progress_weather);
        weatherViewPager = view.findViewById(R.id.weather_view_pager);
        LinearLayout dotContainer = view.findViewById(R.id.weather_dot_container);

        // Set up the inner pager adapter (pre-inflates both pages)
        LayoutInflater li = LayoutInflater.from(requireContext());
        weatherPagerAdapter = new WeatherPagerAdapter(li, weatherViewPager);
        weatherViewPager.setAdapter(weatherPagerAdapter);

        // Bind view references from the pre-inflated pages
        View currentPage = weatherPagerAdapter.getPage(0);
        tvIcon = currentPage.findViewById(R.id.tv_weather_icon);
        tvCurrentTemp = currentPage.findViewById(R.id.tv_current_temp);
        tvDescription = currentPage.findViewById(R.id.tv_weather_desc);
        tvHighTemp = currentPage.findViewById(R.id.tv_high_temp);
        tvRain = currentPage.findViewById(R.id.tv_rain);
        tvDate = currentPage.findViewById(R.id.tv_date);
        tvStatus = currentPage.findViewById(R.id.tv_status);

        View weekendPage = weatherPagerAdapter.getPage(1);
        tvSatMaxTemp = weekendPage.findViewById(R.id.tv_sat_max_temp);
        tvSatDryHours = weekendPage.findViewById(R.id.tv_sat_dry_hours);
        tvSatWind = weekendPage.findViewById(R.id.tv_sat_wind);
        tvSunMaxTemp = weekendPage.findViewById(R.id.tv_sun_max_temp);
        tvSunDryHours = weekendPage.findViewById(R.id.tv_sun_dry_hours);
        tvSunWind = weekendPage.findViewById(R.id.tv_sun_wind);
        tvWeekendStatus = weekendPage.findViewById(R.id.tv_weekend_status);

        // Dot indicators
        setupWeatherDots(dotContainer, weatherPagerAdapter.getItemCount());
        weatherViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateWeatherDots(position);
                subPageHandler.removeCallbacks(subPageRunnable);
                subPageHandler.postDelayed(subPageRunnable, SUB_PAGE_ADVANCE_MS);
            }
        });

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

    // ── Sub-page dot helpers ──────────────────────────────────────────────────

    private void setupWeatherDots(LinearLayout container, int count) {
        container.removeAllViews();
        weatherDots = new View[count];
        int sizePx = dpToPx(5);
        int marginPx = dpToPx(3);
        for (int i = 0; i < count; i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.dot_indicator));
            weatherDots[i] = dot;
            container.addView(dot);
        }
        updateWeatherDots(0);
    }

    private void updateWeatherDots(int activeIndex) {
        if (weatherDots == null) return;
        for (int i = 0; i < weatherDots.length; i++) {
            weatherDots[i].setAlpha(i == activeIndex ? 0.7f : 0.2f);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        subPageHandler.postDelayed(subPageRunnable, SUB_PAGE_ADVANCE_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        subPageHandler.removeCallbacks(subPageRunnable);
    }

    // ── Location ──────────────────────────────────────────────────────────────

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
        etCity.setText(data.getCityName());

        displayWeekendWeather(data);
    }

    private void displayWeekendWeather(WeatherData data) {
        WeatherData.WeekendDay sat = data.getNextSaturday();
        WeatherData.WeekendDay sun = data.getNextSunday();

        if (sat == null && sun == null) {
            tvWeekendStatus.setText(R.string.weekend_no_data);
            tvWeekendStatus.setVisibility(View.VISIBLE);
            return;
        }
        tvWeekendStatus.setVisibility(View.GONE);

        if (sat != null) {
            tvSatMaxTemp.setText(String.format(getString(R.string.weekend_max_temp), sat.maxTemp));
            tvSatDryHours.setText(String.format(getString(R.string.weekend_dry_hours), sat.dryHours));
            tvSatWind.setText(String.format(getString(R.string.weekend_wind), sat.maxWind, sat.meanWind));
        } else {
            tvSatMaxTemp.setText("--");
            tvSatDryHours.setText("--");
            tvSatWind.setText("--");
        }

        if (sun != null) {
            tvSunMaxTemp.setText(String.format(getString(R.string.weekend_max_temp), sun.maxTemp));
            tvSunDryHours.setText(String.format(getString(R.string.weekend_dry_hours), sun.dryHours));
            tvSunWind.setText(String.format(getString(R.string.weekend_wind), sun.maxWind, sun.meanWind));
        } else {
            tvSunMaxTemp.setText("--");
            tvSunDryHours.setText("--");
            tvSunWind.setText("--");
        }
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        weatherViewPager.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    // ── Prefs / keyboard helpers ──────────────────────────────────────────────

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

    // ── Inner ViewPager2 adapter ──────────────────────────────────────────────

    private static class WeatherPagerAdapter
            extends RecyclerView.Adapter<WeatherPagerAdapter.PageHolder> {

        private final View[] pages = new View[2];

        WeatherPagerAdapter(LayoutInflater inflater, ViewGroup parent) {
            // Pre-inflate both pages with the ViewPager2 as parent so layout params
            // are correctly resolved, but do not attach them yet (attachToParent=false).
            pages[0] = inflater.inflate(R.layout.view_weather_current, parent, false);
            pages[1] = inflater.inflate(R.layout.view_weather_weekend, parent, false);
        }

        View getPage(int position) {
            return pages[position];
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(pages[viewType]);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {}

        @Override
        public int getItemCount() {
            return pages.length;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        static class PageHolder extends RecyclerView.ViewHolder {
            PageHolder(View v) {
                super(v);
            }
        }
    }
}

