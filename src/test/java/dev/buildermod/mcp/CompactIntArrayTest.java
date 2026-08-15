package dev.buildermod.mcp;

import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public final class CompactIntArrayTest {
    @Test
    public void usesRleForFlatTerrain() {
        JsonObject encoded = CompactIntArray.encode(Arrays.asList(64, 64, 64, 64, 64, 64));
        Assert.assertEquals("rle", encoded.get("encoding").getAsString());
        Assert.assertEquals(6, encoded.get("count").getAsInt());
    }

    @Test
    public void usesRawForHighlyVariableTerrain() {
        JsonObject encoded = CompactIntArray.encode(Arrays.asList(1, 2, 3, 4, 5, 6));
        Assert.assertEquals("raw", encoded.get("encoding").getAsString());
        Assert.assertEquals(6, encoded.getAsJsonArray("values").size());
    }
}
