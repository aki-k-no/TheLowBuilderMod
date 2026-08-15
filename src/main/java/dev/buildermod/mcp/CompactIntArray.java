package dev.buildermod.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;

/** Selects raw or run-length JSON encoding based on the actual serialized size. */
public final class CompactIntArray {
    private CompactIntArray() {
    }

    public static JsonObject encode(List<Integer> values) {
        JsonArray raw = new JsonArray();
        JsonArray runs = new JsonArray();
        int previous = 0;
        int count = 0;
        for (Integer boxed : values) {
            int value = boxed.intValue();
            raw.add(new JsonPrimitive(value));
            if (count > 0 && value == previous) {
                count++;
            } else {
                appendRun(runs, previous, count);
                previous = value;
                count = 1;
            }
        }
        appendRun(runs, previous, count);

        boolean useRuns = runs.toString().length() < raw.toString().length();
        JsonObject encoded = new JsonObject();
        encoded.addProperty("encoding", useRuns ? "rle" : "raw");
        encoded.addProperty("count", values.size());
        encoded.add("values", useRuns ? runs : raw);
        return encoded;
    }

    private static void appendRun(JsonArray runs, int value, int count) {
        if (count == 0) {
            return;
        }
        JsonArray run = new JsonArray();
        run.add(new JsonPrimitive(value));
        run.add(new JsonPrimitive(count));
        runs.add(run);
    }
}
