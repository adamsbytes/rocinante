package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Utility class for loading JSON data from classpath resources.
 * 
 * Provides:
 * - UTF-8 encoding
 * - Retry logic with configurable attempts and delays
 * - Consistent error handling
 * - Type-safe parsing
 *
 * Usage:
 * <pre>
 * JsonObject data = JsonResourceLoader.load(gson, "/data/my_data.json");
 * MyData result = JsonResourceLoader.loadAndParse(gson, "/data/my_data.json", 
 *     json -> parseMyData(json));
 * </pre>
 */
@Slf4j
public final class JsonResourceLoader {

    /** Default number of load attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    
    /** Default delay between retry attempts in milliseconds. */
    public static final long DEFAULT_RETRY_DELAY_MS = 1000;

    private JsonResourceLoader() {
        // Utility class - prevent instantiation
    }

    /**
     * Load a JSON resource file with retry logic.
     *
     * @param gson         the Gson instance for parsing
     * @param resourcePath the classpath resource path (e.g., "/data/my_file.json")
     * @return the parsed JsonObject
     * @throws JsonLoadException if loading fails after all retry attempts
     */
    public static JsonObject load(Gson gson, String resourcePath) {
        return load(gson, resourcePath, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
    }

    /**
     * Load a JSON resource file with configurable retry logic.
     *
     * @param gson         the Gson instance for parsing
     * @param resourcePath the classpath resource path (e.g., "/data/my_file.json")
     * @param maxAttempts  maximum number of load attempts
     * @param retryDelayMs delay between retries in milliseconds
     * @return the parsed JsonObject
     * @throws JsonLoadException if loading fails after all retry attempts
     */
    public static JsonObject load(Gson gson, String resourcePath, int maxAttempts, long retryDelayMs) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return tryLoad(gson, resourcePath);
            } catch (Exception e) {
                lastException = e;
                log.warn("JSON load attempt {}/{} failed for {}: {}", 
                        attempt, maxAttempts, resourcePath, e.getMessage());
                
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new JsonLoadException(
                                "Interrupted while loading " + resourcePath, ie);
                    }
                }
            }
        }
        
        throw new JsonLoadException(
                "Failed to load " + resourcePath + " after " + maxAttempts + " attempts", 
                lastException);
    }

    /**
     * Load a JSON resource and parse it using a custom parser function.
     *
     * @param gson         the Gson instance for parsing
     * @param resourcePath the classpath resource path
     * @param parser       function to parse the JsonObject into the desired type
     * @param <T>          the result type
     * @return the parsed result
     * @throws JsonLoadException if loading or parsing fails
     */
    public static <T> T loadAndParse(Gson gson, String resourcePath, Function<JsonObject, T> parser) {
        return loadAndParse(gson, resourcePath, parser, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
    }

    /**
     * Load a JSON resource and parse it using a custom parser function with retry logic.
     *
     * @param gson         the Gson instance for parsing
     * @param resourcePath the classpath resource path
     * @param parser       function to parse the JsonObject into the desired type
     * @param maxAttempts  maximum number of load attempts
     * @param retryDelayMs delay between retries in milliseconds
     * @param <T>          the result type
     * @return the parsed result
     * @throws JsonLoadException if loading or parsing fails
     */
    public static <T> T loadAndParse(
            Gson gson, 
            String resourcePath, 
            Function<JsonObject, T> parser,
            int maxAttempts,
            long retryDelayMs) {
        
        JsonObject json = load(gson, resourcePath, maxAttempts, retryDelayMs);
        try {
            return parser.apply(json);
        } catch (Exception e) {
            throw new JsonLoadException(
                    "Failed to parse JSON from " + resourcePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Try to load a JSON file from the given resource path.
     * Returns null if the resource doesn't exist.
     *
     * @param gson         the Gson instance
     * @param resourcePath the resource path
     * @return JsonObject or null if resource doesn't exist
     */
    @Nullable
    public static JsonObject tryLoadOptional(Gson gson, String resourcePath) {
        try {
            return tryLoad(gson, resourcePath);
        } catch (JsonLoadException e) {
            log.debug("Optional JSON resource not found or failed to load: {}", resourcePath);
            return null;
        }
    }

    /**
     * Get a required child object from a JsonObject.
     *
     * @param root      the parent JsonObject
     * @param fieldName the field name
     * @return the child JsonObject
     * @throws JsonLoadException if the field doesn't exist or isn't an object
     */
    public static JsonObject getRequiredObject(JsonObject root, String fieldName) {
        JsonObject child = root.getAsJsonObject(fieldName);
        if (child == null) {
            throw new JsonLoadException("Required field '" + fieldName + "' not found in JSON");
        }
        return child;
    }

    /**
     * Internal method to perform the actual load.
     */
    private static JsonObject tryLoad(Gson gson, String resourcePath) {
        try (InputStream is = JsonResourceLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new JsonLoadException("Resource not found: " + resourcePath);
            }
            
            JsonObject result = gson.fromJson(
                    new InputStreamReader(is, StandardCharsets.UTF_8),
                    JsonObject.class
            );
            
            if (result == null) {
                throw new JsonLoadException("Parsed JSON is null for: " + resourcePath);
            }
            
            return result;
        } catch (IOException e) {
            throw new JsonLoadException("I/O error reading " + resourcePath, e);
        }
    }

    /**
     * Exception thrown when JSON loading fails.
     */
    public static class JsonLoadException extends RuntimeException {
        public JsonLoadException(String message) {
            super(message);
        }
        
        public JsonLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

