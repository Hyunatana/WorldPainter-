package org.pepsoft.worldpainter.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonProtocolTest {
    @Test public void preservesIntegerTypeAndSignedLongBoundaries() throws Exception {
        Map<?, ?> value = (Map<?, ?>) JsonProtocol.read("{\"zero\":0,\"min\":-9223372036854775808,\"max\":9223372036854775807}");
        assertEquals(0L, value.get("zero"));
        assertEquals(Long.MIN_VALUE, value.get("min"));
        assertEquals(Long.MAX_VALUE, value.get("max"));
        assertTrue(value.get("zero") instanceof Long);
        assertEquals(Map.of("commands", List.of("mountain"), "version", 1L),
                JsonProtocol.read(JsonProtocol.write(Map.of("commands", List.of("mountain"), "version", 1))));
    }

    @Test public void rejectsDuplicateKeysMalformedCommasAndTrailingTokens() {
        for (String json : List.of("{\"version\":1,\"version\":1}", "{\"a\":1,}", "[,1]", "[1,,2]",
                "[1,]", "{,\"a\":1}", "{}{}", "{} true", "// comment\n{}", "{'a':1}")) {
            assertThrows(json, JsonProcessingException.class, () -> JsonProtocol.read(json));
        }
    }

    @Test public void enforcesDepthStringAndNumberLimits() throws Exception {
        JsonProtocol.read("[".repeat(16) + "0" + "]".repeat(16));
        assertThrows(JsonProcessingException.class, () -> JsonProtocol.read("[".repeat(17) + "0" + "]".repeat(17)));
        assertEquals("x".repeat(65536), JsonProtocol.read("\"" + "x".repeat(65536) + "\""));
        assertThrows(JsonProcessingException.class, () -> JsonProtocol.read("\"" + "x".repeat(65537) + "\""));
        assertThrows(JsonProcessingException.class, () -> JsonProtocol.read("123456789012345678901"));
        assertThrows(JsonProcessingException.class, () -> JsonProtocol.read("9223372036854775808"));
    }
}
