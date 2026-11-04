package dev.buildermod.client;

import org.junit.Assert;
import org.junit.Test;

public final class TheLowWikiReaderTest {
    @Test public void buildsOnlyRelativeWikiUrls() {
        Assert.assertEquals("https://wikiwiki.jp/thelow-dev/", TheLowWikiReader.urlFor(""));
        Assert.assertTrue(TheLowWikiReader.urlFor("command/sequencecommand").contains("command%2Fsequencecommand"));
        try { TheLowWikiReader.urlFor("https://example.com/"); Assert.fail("expected invalid page"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void removesMarkupFromWikiHtml() {
        String text = TheLowWikiReader.htmlToText("<h1>Title</h1><script>bad()</script><p>One &amp; Two</p>");
        Assert.assertTrue(text.contains("Title")); Assert.assertTrue(text.contains("One & Two")); Assert.assertFalse(text.contains("bad"));
    }
}
