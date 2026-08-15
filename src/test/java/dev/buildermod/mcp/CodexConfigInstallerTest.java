package dev.buildermod.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CodexConfigInstallerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsWithoutDiscardingExistingConfiguration() throws Exception {
        Path home = temporaryFolder.newFolder("codex").toPath();
        Path config = home.resolve("config.toml");
        Files.write(config, "model = \"example\"\n".getBytes(StandardCharsets.UTF_8));

        CodexConfigInstaller installer = new CodexConfigInstaller(home);
        CodexConfigInstaller.InstallResult result = installer.install(
            "http://127.0.0.1:25585/mcp",
            "test-secret-token"
        );

        String installed = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Assert.assertTrue(result.isChanged());
        Assert.assertTrue(installed.contains("model = \"example\""));
        Assert.assertTrue(installed.contains("[mcp_servers.buildermod]"));
        Assert.assertTrue(installed.contains("Authorization = \"Bearer test-secret-token\""));
        Assert.assertTrue(installed.contains("default_tools_approval_mode = \"writes\""));
        Assert.assertFalse(installed.contains("approval_mode = \"approve\""));
        Assert.assertTrue(Files.isRegularFile(home.resolve("config.toml.buildermod.bak")));
        Assert.assertTrue(installer.isInstalled("http://127.0.0.1:25585/mcp"));
    }

    @Test
    public void replacesOnlyTheBuilderModTable() throws Exception {
        Path home = temporaryFolder.newFolder("codex-update").toPath();
        Path config = home.resolve("config.toml");
        String original = "[mcp_servers.buildermod]\n"
            + "url = \"http://old/mcp\"\n"
            + "http_headers = { Authorization = \"Bearer old\" }\n"
            + "default_tools_approval_mode = \"writes\"\n\n"
            + "[mcp_servers.buildermod.tools.set_block]\n"
            + "approval_mode = \"prompt\"\n\n"
            + "[mcp_servers.other]\n"
            + "url = \"https://example.test/mcp\"\n";
        Files.write(config, original.getBytes(StandardCharsets.UTF_8));

        new CodexConfigInstaller(home).install("http://127.0.0.1:25585/mcp", "new-token");

        String updated = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Assert.assertFalse(updated.contains("http://old/mcp"));
        Assert.assertFalse(updated.contains("approval_mode = \"prompt\""));
        Assert.assertTrue(updated.contains("Bearer new-token"));
        Assert.assertEquals(0, countOccurrences(updated, "[mcp_servers.buildermod.tools.set_block]"));
        Assert.assertTrue(updated.contains("[mcp_servers.other]"));
        Assert.assertTrue(updated.contains("https://example.test/mcp"));
    }

    private int countOccurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
