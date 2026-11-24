package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class NatCmpTest {

    @Test
    void comparesEmbeddedNumbersNumerically() {
        List<String> values = new ArrayList<>(List.of(
                "item10", "item2", "item1", "item02", "Item11", "item9"));
        values.sort(NatCmp.INSTANCE);
        assertEquals(List.of("item1", "item2", "item02", "item9", "item10", "Item11"), values);
        assertTrue(NatCmp.INSTANCE.compare("file2", "file10") < 0);
        assertTrue(NatCmp.INSTANCE.compare("x9y10", "x9y9") > 0);
    }

    @Test
    void handlesNullsAndLongNumbers() {
        List<String> values = new ArrayList<>(Arrays.asList(
                "v999999999999999999999999999999", "v1000000000000000000000000000000", null, ""));
        values.sort(NatCmp.INSTANCE);
        assertEquals(Arrays.asList(null, "", "v999999999999999999999999999999",
                "v1000000000000000000000000000000"), values);
    }
}
