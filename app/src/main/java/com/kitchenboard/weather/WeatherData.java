package com.kitchenboard.weather;

public class WeatherData {

    /** Forecast data for a single weekend day (Saturday or Sunday). */
    public static class WeekendDay {
        public final String dayName;
        public final double maxTemp;
        /** Hours without precipitation (24 - precipitationHours). */
        public final double dryHours;
        public final double maxWind;
        public final double meanWind;

        public WeekendDay(String dayName, double maxTemp, double dryHours,
                          double maxWind, double meanWind) {
            this.dayName = dayName;
            this.maxTemp = maxTemp;
            this.dryHours = dryHours;
            this.maxWind = maxWind;
            this.meanWind = meanWind;
        }
    }

    private final double currentTemperature;
    private final double highTemperature;
    private final double precipitationMm;
    private final int weatherCode;
    private final String cityName;
    private final WeekendDay nextSaturday;
    private final WeekendDay nextSunday;

    public WeatherData(double currentTemperature, double highTemperature,
                       double precipitationMm, int weatherCode, String cityName,
                       WeekendDay nextSaturday, WeekendDay nextSunday) {
        this.currentTemperature = currentTemperature;
        this.highTemperature = highTemperature;
        this.precipitationMm = precipitationMm;
        this.weatherCode = weatherCode;
        this.cityName = cityName;
        this.nextSaturday = nextSaturday;
        this.nextSunday = nextSunday;
    }

    public double getCurrentTemperature() { return currentTemperature; }
    public double getHighTemperature() { return highTemperature; }
    public double getPrecipitationMm() { return precipitationMm; }
    public int getWeatherCode() { return weatherCode; }
    public String getCityName() { return cityName; }
    public WeekendDay getNextSaturday() { return nextSaturday; }
    public WeekendDay getNextSunday() { return nextSunday; }

    /** Returns a human-readable description for the WMO weather code. */
    public String getWeatherDescription() {
        if (weatherCode == 0) return "Clear sky";
        if (weatherCode == 1) return "Mainly clear";
        if (weatherCode == 2) return "Partly cloudy";
        if (weatherCode == 3) return "Overcast";
        if (weatherCode == 45 || weatherCode == 48) return "Foggy";
        if (weatherCode >= 51 && weatherCode <= 55) return "Drizzle";
        if (weatherCode >= 61 && weatherCode <= 65) return "Rain";
        if (weatherCode >= 71 && weatherCode <= 77) return "Snow";
        if (weatherCode >= 80 && weatherCode <= 82) return "Rain showers";
        if (weatherCode >= 85 && weatherCode <= 86) return "Snow showers";
        if (weatherCode == 95) return "Thunderstorm";
        if (weatherCode == 96 || weatherCode == 99) return "Thunderstorm w/ hail";
        return "Unknown";
    }

    /** Returns a simple emoji icon for the weather condition. */
    public String getWeatherIcon() {
        if (weatherCode == 0) return "\u2600";           // ☀
        if (weatherCode <= 3) return "\u26C5";           // ⛅
        if (weatherCode == 45 || weatherCode == 48) return "\uD83C\uDF2B"; // 🌫
        if (weatherCode >= 51 && weatherCode <= 55) return "\uD83C\uDF26"; // 🌦
        if (weatherCode >= 61 && weatherCode <= 65) return "\uD83C\uDF27"; // 🌧
        if (weatherCode >= 71 && weatherCode <= 77) return "\uD83C\uDF28"; // 🌨
        if (weatherCode >= 80 && weatherCode <= 82) return "\uD83C\uDF26"; // 🌦
        if (weatherCode >= 95) return "\u26C8";          // ⛈
        return "\uD83C\uDF24";                           // 🌤
    }
}
