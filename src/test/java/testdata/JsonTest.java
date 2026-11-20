package testdata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesASingleStepTestsCase() {
        var text = """
            [ {"name": "a9 2c 5f",
               "initial": {"pc": 512, "s": 253, "a": 0, "x": 0, "y": 0, "p": 36, "ram": [[512, 169], [513, 44]]},
               "cycles": [[512, 169, "read"], [513, 44, "read"]]} ]
            """;

        assertEquals(List.of(Map.of(
                "name", "a9 2c 5f",
                "initial", Map.of("pc", 512, "s", 253, "a", 0, "x", 0, "y", 0, "p", 36,
                        "ram", List.of(List.of(512, 169), List.of(513, 44))),
                "cycles", List.of(List.of(512, 169, "read"), List.of(513, 44, "read")))),
                Json.parse(text));
    }

    @Test
    void parsesEmptyContainers() {
        assertEquals(List.of(Map.of(), List.of()), Json.parse("[{},[]]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "[", "[1,]", "[1 2]", "{\"a\" 1}", "{\"a\":1,}", "{1:2}", "[1] x", "[-1]", "[1.5]", "[1e3]",
        "[true]", "[false]", "[null]", "[\"a\\\"b\"]", "[\"unterminated]", "[\"tab\there\"]", "[01]",
        "[99999999999]",
    })
    void rejectsAnythingOutsideTheSubset(String text) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
    }
}
