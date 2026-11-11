package dev.buildermod.client;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public final class GoogleSheetServiceTest {
    @Test public void acceptsOnlyGoogleSpreadsheetUrls() {
        Assert.assertEquals("abc_123-XYZ", GoogleSheetService.spreadsheetId(
            "https://docs.google.com/spreadsheets/d/abc_123-XYZ/edit#gid=0"));
        try {
            GoogleSheetService.spreadsheetId("https://example.com/spreadsheets/d/abc/edit");
            Assert.fail("Unexpected host was accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            GoogleSheetService.spreadsheetId("https://docs.google.com.evil.example/spreadsheets/d/abc/edit");
            Assert.fail("Lookalike host was accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void requiresAnAliasWhenSeveralFilesAreConfigured() {
        Map<String, String> sheets = new LinkedHashMap<String, String>();
        sheets.put("commands", "https://docs.google.com/spreadsheets/d/one/edit");
        sheets.put("items", "https://docs.google.com/spreadsheets/d/two/edit");
        try {
            GoogleSheetService.selectSheet(new JsonObject(), sheets);
            Assert.fail("Ambiguous sheet was accepted");
        } catch (IllegalArgumentException expected) { }
        JsonObject request = new JsonObject(); request.addProperty("sheet", "items");
        Assert.assertEquals("items", GoogleSheetService.selectSheet(request, sheets));
        request.addProperty("sheet", "missing");
        try {
            GoogleSheetService.selectSheet(request, sheets);
            Assert.fail("Unknown sheet was accepted");
        } catch (IllegalArgumentException expected) { }
        sheets.remove("commands");
        Assert.assertEquals("items", GoogleSheetService.selectSheet(new JsonObject(), sheets));
    }

}
