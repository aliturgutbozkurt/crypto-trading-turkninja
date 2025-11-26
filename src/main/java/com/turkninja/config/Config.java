package com.turkninja.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            System.err.println("Could not load application.properties");
        }
    }

    public static String get(String key) {
        String value = System.getenv(key);
        if (value == null) {
            value = properties.getProperty(key);
        }
        return value;
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    public static double getDouble(String key, double defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid format for key " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid format for key " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    public static final String BINANCE_API_KEY = "BINANCE_API_KEY";
    public static final String BINANCE_SECRET_KEY = "BINANCE_SECRET_KEY";
    public static final String MONGODB_URI = "MONGODB_URI";
    public static final String DB_NAME = "DB_NAME";
    public static final String DRY_RUN = "DRY_RUN";

    /**
     * Set a property value (useful for testing)
     */
    public static void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }
}
