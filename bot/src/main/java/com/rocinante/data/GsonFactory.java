package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Centralized factory for creating Gson instances with proper TypeAdapters.
 * 
 * <p>Handles Java time types (Instant, Duration, LocalDate, etc.) using ISO-8601
 * format strings instead of reflection. This avoids {@link java.lang.reflect.InaccessibleObjectException}
 * on Java 9+ where module system blocks reflective access to java.time internals.
 * 
 * <p>Usage:
 * <pre>
 * // For general use
 * Gson gson = GsonFactory.create();
 * 
 * // For pretty-printed output
 * Gson gson = GsonFactory.createPrettyPrinting();
 * 
 * // For custom configuration
 * Gson gson = GsonFactory.builder()
 *     .serializeNulls()
 *     .create();
 * </pre>
 */
public final class GsonFactory {

    private GsonFactory() {
        // Utility class - prevent instantiation
    }

    /**
     * Create a Gson instance with Java time TypeAdapters registered.
     *
     * @return configured Gson instance
     */
    public static Gson create() {
        return builder().create();
    }

    /**
     * Create a Gson instance with pretty printing enabled.
     *
     * @return configured Gson instance with pretty printing
     */
    public static Gson createPrettyPrinting() {
        return builder().setPrettyPrinting().create();
    }

    /**
     * Get a GsonBuilder pre-configured with Java time TypeAdapters.
     * Use this when you need additional customization.
     *
     * @return pre-configured GsonBuilder
     */
    public static GsonBuilder builder() {
        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, new InstantAdapter())
                .registerTypeAdapter(Duration.class, new DurationAdapter())
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .registerTypeAdapter(LocalTime.class, new LocalTimeAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .registerTypeAdapter(ZonedDateTime.class, new ZonedDateTimeAdapter());
    }

    // ========================================================================
    // TypeAdapters for java.time types (ISO-8601 format)
    // ========================================================================

    /**
     * TypeAdapter for {@link Instant} using ISO-8601 format.
     * Example: "2024-01-15T10:30:00Z"
     */
    private static class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }

    /**
     * TypeAdapter for {@link Duration} using ISO-8601 format.
     * Example: "PT1H30M" (1 hour 30 minutes)
     */
    private static class DurationAdapter extends TypeAdapter<Duration> {
        @Override
        public void write(JsonWriter out, Duration value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Duration read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Duration.parse(in.nextString());
        }
    }

    /**
     * TypeAdapter for {@link LocalDate} using ISO-8601 format.
     * Example: "2024-01-15"
     */
    private static class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDate.parse(in.nextString());
        }
    }

    /**
     * TypeAdapter for {@link LocalTime} using ISO-8601 format.
     * Example: "10:30:00"
     */
    private static class LocalTimeAdapter extends TypeAdapter<LocalTime> {
        @Override
        public void write(JsonWriter out, LocalTime value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public LocalTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalTime.parse(in.nextString());
        }
    }

    /**
     * TypeAdapter for {@link LocalDateTime} using ISO-8601 format.
     * Example: "2024-01-15T10:30:00"
     */
    private static class LocalDateTimeAdapter extends TypeAdapter<LocalDateTime> {
        @Override
        public void write(JsonWriter out, LocalDateTime value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public LocalDateTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDateTime.parse(in.nextString());
        }
    }

    /**
     * TypeAdapter for {@link ZonedDateTime} using ISO-8601 format.
     * Example: "2024-01-15T10:30:00+01:00[Europe/Paris]"
     */
    private static class ZonedDateTimeAdapter extends TypeAdapter<ZonedDateTime> {
        @Override
        public void write(JsonWriter out, ZonedDateTime value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public ZonedDateTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return ZonedDateTime.parse(in.nextString());
        }
    }
}

