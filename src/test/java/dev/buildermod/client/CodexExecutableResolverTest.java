package dev.buildermod.client;

import java.io.File;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CodexExecutableResolverTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void findsCodexExecutableOnPath() throws Exception {
        File first = temporaryFolder.newFolder("first");
        File second = temporaryFolder.newFolder("second");
        File executable = new File(second, System.getProperty("os.name").toLowerCase().contains("win")
            ? "codex.exe"
            : "codex");
        Assert.assertTrue(executable.createNewFile());

        String path = first.getAbsolutePath() + File.pathSeparator + second.getAbsolutePath();
        Assert.assertEquals(executable.getAbsolutePath(), CodexExecutableResolver.findOnPath(path));
    }

    @Test
    public void returnsNullWhenPathDoesNotContainCodex() throws Exception {
        File directory = temporaryFolder.newFolder("empty");
        Assert.assertNull(CodexExecutableResolver.findOnPath(directory.getAbsolutePath()));
    }

    @Test
    public void findsOfficialStandaloneVisibleCommand() throws Exception {
        File localAppData = temporaryFolder.newFolder("local-app-data");
        File executable = new File(localAppData, "Programs\\OpenAI\\Codex\\bin\\codex.exe");
        Assert.assertTrue(executable.getParentFile().mkdirs());
        Assert.assertTrue(executable.createNewFile());

        String found = CodexExecutableResolver.findOfficialStandalone(
            localAppData.getAbsolutePath(),
            null,
            null
        );

        Assert.assertEquals(executable.getAbsolutePath(), found);
    }

    @Test
    public void findsOfficialStandaloneCurrentPackage() throws Exception {
        File codexHome = temporaryFolder.newFolder("codex-home");
        File executable = new File(codexHome, "packages\\standalone\\current\\bin\\codex.exe");
        Assert.assertTrue(executable.getParentFile().mkdirs());
        Assert.assertTrue(executable.createNewFile());

        String found = CodexExecutableResolver.findOfficialStandalone(null, null, codexHome.getAbsolutePath());

        Assert.assertEquals(executable.getAbsolutePath(), found);
    }
}
