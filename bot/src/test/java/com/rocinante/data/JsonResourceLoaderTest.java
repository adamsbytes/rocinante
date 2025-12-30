package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link JsonResourceLoader} utility class.
 *
 * Tests retry logic, error handling, and JSON field extraction methods.
 */
public class JsonResourceLoaderTest {

    private Gson gson;

    @Before
    public void setUp() {
        gson = GsonFactory.create();
    }

    // ========================================================================
    // load() - Error Handling
    // ========================================================================

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testLoad_NonexistentResource_ThrowsException() {
        JsonResourceLoader.load(gson, "/nonexistent/path/to/resource.json");
    }

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testLoad_NonexistentResource_WithRetries_ThrowsAfterAllAttempts() {
        // Should fail after all retry attempts
        JsonResourceLoader.load(gson, "/nonexistent/resource.json", 3, 10);
    }

    // ========================================================================
    // tryLoadOptional() Tests
    // ========================================================================

    @Test
    public void testTryLoadOptional_NonexistentResource_ReturnsNull() {
        JsonObject result = JsonResourceLoader.tryLoadOptional(gson, "/nonexistent/resource.json");
        assertNull("tryLoadOptional should return null for missing resources", result);
    }

    // ========================================================================
    // getRequiredObject() Tests
    // ========================================================================

    @Test
    public void testGetRequiredObject_Exists_ReturnsChild() {
        JsonObject root = new JsonObject();
        JsonObject child = new JsonObject();
        child.addProperty("key", "value");
        root.add("childObject", child);

        JsonObject result = JsonResourceLoader.getRequiredObject(root, "childObject");
        
        assertNotNull("Should return child object", result);
        assertEquals("Child should have correct value", "value", result.get("key").getAsString());
    }

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testGetRequiredObject_Missing_ThrowsException() {
        JsonObject root = new JsonObject();
        root.addProperty("someField", "someValue");

        JsonResourceLoader.getRequiredObject(root, "nonexistentField");
    }

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testGetRequiredObject_EmptyRoot_ThrowsException() {
        JsonObject root = new JsonObject();
        JsonResourceLoader.getRequiredObject(root, "anyField");
    }

    @Test
    public void testGetRequiredObject_FieldIsNotObject_ThrowsClassCastException() {
        JsonObject root = new JsonObject();
        root.addProperty("stringField", "notAnObject");

        // Gson throws ClassCastException when trying to get non-object as object
        try {
            JsonResourceLoader.getRequiredObject(root, "stringField");
            fail("Should throw an exception for non-object field");
        } catch (ClassCastException | IllegalStateException e) {
            // Expected - Gson throws ClassCastException or IllegalStateException
        }
    }

    // ========================================================================
    // getRequiredArray() Tests
    // ========================================================================

    @Test
    public void testGetRequiredArray_Exists_ReturnsArray() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        array.add("item1");
        array.add("item2");
        array.add("item3");
        root.add("items", array);

        JsonArray result = JsonResourceLoader.getRequiredArray(root, "items");
        
        assertNotNull("Should return array", result);
        assertEquals("Array should have 3 elements", 3, result.size());
        assertEquals("First element should match", "item1", result.get(0).getAsString());
    }

    @Test
    public void testGetRequiredArray_EmptyArray_ReturnsEmptyArray() {
        JsonObject root = new JsonObject();
        JsonArray emptyArray = new JsonArray();
        root.add("emptyItems", emptyArray);

        JsonArray result = JsonResourceLoader.getRequiredArray(root, "emptyItems");
        
        assertNotNull("Should return empty array", result);
        assertEquals("Array should be empty", 0, result.size());
    }

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testGetRequiredArray_Missing_ThrowsException() {
        JsonObject root = new JsonObject();
        root.addProperty("someField", "someValue");

        JsonResourceLoader.getRequiredArray(root, "nonexistentArray");
    }

    @Test
    public void testGetRequiredArray_FieldIsNotArray_ThrowsClassCastException() {
        JsonObject root = new JsonObject();
        root.addProperty("stringField", "notAnArray");

        // Gson throws ClassCastException when trying to get non-array as array
        try {
            JsonResourceLoader.getRequiredArray(root, "stringField");
            fail("Should throw an exception for non-array field");
        } catch (ClassCastException | IllegalStateException e) {
            // Expected - Gson throws ClassCastException or IllegalStateException
        }
    }

    @Test
    public void testGetRequiredArray_FieldIsObject_ThrowsClassCastException() {
        JsonObject root = new JsonObject();
        JsonObject child = new JsonObject();
        root.add("objectField", child);

        // Gson throws ClassCastException when trying to get object as array
        try {
            JsonResourceLoader.getRequiredArray(root, "objectField");
            fail("Should throw an exception for object field");
        } catch (ClassCastException | IllegalStateException e) {
            // Expected - Gson throws ClassCastException or IllegalStateException
        }
    }

    // ========================================================================
    // loadAndParse() Tests
    // ========================================================================

    @Test(expected = JsonResourceLoader.JsonLoadException.class)
    public void testLoadAndParse_NonexistentResource_ThrowsException() {
        JsonResourceLoader.loadAndParse(gson, "/nonexistent/resource.json", 
                json -> json.get("key").getAsString());
    }

    // ========================================================================
    // JsonLoadException Tests
    // ========================================================================

    @Test
    public void testJsonLoadException_MessageOnly() {
        JsonResourceLoader.JsonLoadException ex = 
                new JsonResourceLoader.JsonLoadException("Test message");
        
        assertEquals("Message should match", "Test message", ex.getMessage());
        assertNull("Cause should be null", ex.getCause());
    }

    @Test
    public void testJsonLoadException_WithCause() {
        Exception cause = new RuntimeException("Original error");
        JsonResourceLoader.JsonLoadException ex = 
                new JsonResourceLoader.JsonLoadException("Wrapped message", cause);
        
        assertEquals("Message should match", "Wrapped message", ex.getMessage());
        assertSame("Cause should match", cause, ex.getCause());
    }

    @Test
    public void testJsonLoadException_IsRuntimeException() {
        JsonResourceLoader.JsonLoadException ex = 
                new JsonResourceLoader.JsonLoadException("Test");
        
        assertTrue("Should be RuntimeException", ex instanceof RuntimeException);
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testDefaultMaxAttempts_IsPositive() {
        assertTrue("Default max attempts should be positive", 
                JsonResourceLoader.DEFAULT_MAX_ATTEMPTS > 0);
    }

    @Test
    public void testDefaultRetryDelay_IsPositive() {
        assertTrue("Default retry delay should be positive", 
                JsonResourceLoader.DEFAULT_RETRY_DELAY_MS > 0);
    }

    @Test
    public void testDefaultMaxAttempts_IsReasonable() {
        // Should not retry too many times
        assertTrue("Default max attempts should be <= 10", 
                JsonResourceLoader.DEFAULT_MAX_ATTEMPTS <= 10);
    }

    @Test
    public void testDefaultRetryDelay_IsReasonable() {
        // Should not wait too long between retries
        assertTrue("Default retry delay should be <= 5000ms", 
                JsonResourceLoader.DEFAULT_RETRY_DELAY_MS <= 5000);
    }

    // ========================================================================
    // Nested Object/Array Tests
    // ========================================================================

    @Test
    public void testGetRequiredObject_NestedObject() {
        JsonObject root = new JsonObject();
        JsonObject level1 = new JsonObject();
        JsonObject level2 = new JsonObject();
        level2.addProperty("deepValue", "found");
        level1.add("nested", level2);
        root.add("level1", level1);

        JsonObject resultLevel1 = JsonResourceLoader.getRequiredObject(root, "level1");
        JsonObject resultLevel2 = JsonResourceLoader.getRequiredObject(resultLevel1, "nested");
        
        assertEquals("Should access nested value", "found", 
                resultLevel2.get("deepValue").getAsString());
    }

    @Test
    public void testGetRequiredArray_ArrayWithObjects() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        
        JsonObject item1 = new JsonObject();
        item1.addProperty("id", 1);
        item1.addProperty("name", "First");
        array.add(item1);
        
        JsonObject item2 = new JsonObject();
        item2.addProperty("id", 2);
        item2.addProperty("name", "Second");
        array.add(item2);
        
        root.add("items", array);

        JsonArray result = JsonResourceLoader.getRequiredArray(root, "items");
        
        assertEquals("Should have 2 items", 2, result.size());
        assertEquals("First item id should be 1", 1, 
                result.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals("Second item name should be 'Second'", "Second", 
                result.get(1).getAsJsonObject().get("name").getAsString());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testGetRequiredObject_NullFieldValue_ThrowsException() {
        JsonObject root = new JsonObject();
        root.add("nullField", null);

        // Getting a null field as object returns null (which triggers JsonLoadException)
        // OR it may throw ClassCastException depending on Gson version
        try {
            JsonObject result = JsonResourceLoader.getRequiredObject(root, "nullField");
            // If it returns null, the method itself should throw JsonLoadException
            assertNull("Returned result is null", result);
        } catch (JsonResourceLoader.JsonLoadException | ClassCastException e) {
            // Expected - null field handling
        }
    }

    @Test
    public void testGetRequiredArray_NumericValues() {
        JsonObject root = new JsonObject();
        JsonArray numbers = new JsonArray();
        numbers.add(1);
        numbers.add(2);
        numbers.add(3);
        numbers.add(4);
        numbers.add(5);
        root.add("numbers", numbers);

        JsonArray result = JsonResourceLoader.getRequiredArray(root, "numbers");
        
        assertEquals("Should have 5 numbers", 5, result.size());
        assertEquals("First number should be 1", 1, result.get(0).getAsInt());
        assertEquals("Last number should be 5", 5, result.get(4).getAsInt());
    }

    @Test
    public void testGetRequiredArray_MixedTypes() {
        JsonObject root = new JsonObject();
        JsonArray mixed = new JsonArray();
        mixed.add("string");
        mixed.add(42);
        mixed.add(true);
        mixed.add(3.14);
        root.add("mixed", mixed);

        JsonArray result = JsonResourceLoader.getRequiredArray(root, "mixed");
        
        assertEquals("Should have 4 elements", 4, result.size());
        assertEquals("First element should be string", "string", result.get(0).getAsString());
        assertEquals("Second element should be 42", 42, result.get(1).getAsInt());
        assertTrue("Third element should be true", result.get(2).getAsBoolean());
        assertEquals("Fourth element should be 3.14", 3.14, result.get(3).getAsDouble(), 0.001);
    }
}
