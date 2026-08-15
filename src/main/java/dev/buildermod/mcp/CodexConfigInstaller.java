package dev.buildermod.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class CodexConfigInstaller {
    private static final String TABLE = "[mcp_servers.buildermod]";
    private static final String TABLE_PREFIX = "[mcp_servers.buildermod.";

    private final Path codexHome;

    CodexConfigInstaller() {
        this(resolveCodexHome());
    }

    CodexConfigInstaller(Path codexHome) {
        this.codexHome = codexHome;
    }

    InstallResult install(String endpoint, String bearerToken) throws IOException {
        Files.createDirectories(codexHome);
        Path config = codexHome.resolve("config.toml");
        String original = Files.isRegularFile(config)
            ? new String(Files.readAllBytes(config), StandardCharsets.UTF_8)
            : "";
        String updated = replaceBuilderModBlock(original, endpoint, bearerToken);
        if (updated.equals(original)) {
            return new InstallResult(config, false);
        }

        if (Files.isRegularFile(config)) {
            Files.copy(
                config,
                codexHome.resolve("config.toml.buildermod.bak"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            );
        }
        writeAtomically(config, updated);
        restrictToOwner(config.toFile());
        return new InstallResult(config, true);
    }

    boolean isInstalled(String endpoint) {
        Path config = codexHome.resolve("config.toml");
        if (!Files.isRegularFile(config)) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
            int table = text.indexOf(TABLE);
            return table >= 0 && text.indexOf("url = \"" + escape(endpoint) + "\"", table) >= 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private String replaceBuilderModBlock(String original, String endpoint, String bearerToken) {
        String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<String>(Arrays.asList(normalized.split("\n", -1)));
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (TABLE.equals(lines.get(i).trim())) {
                start = i;
                end = i + 1;
                while (end < lines.size()) {
                    String line = lines.get(end).trim();
                    if (isTableHeader(line) && !isBuilderModTable(line)) {
                        break;
                    }
                    end++;
                }
                break;
            }
        }

        List<String> block = Arrays.asList(
            TABLE,
            "url = \"" + escape(endpoint) + "\"",
            "http_headers = { Authorization = \"Bearer " + escape(bearerToken) + "\" }",
            "required = false",
            "default_tools_approval_mode = \"writes\"",
            ""
        );
        if (start >= 0) {
            lines.subList(start, end).clear();
            lines.addAll(start, block);
        } else {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            lines.addAll(block);
        }
        return join(lines);
    }

    private boolean isTableHeader(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private boolean isBuilderModTable(String line) {
        String trimmed = line.trim();
        return TABLE.equals(trimmed) || trimmed.startsWith(TABLE_PREFIX);
    }

    private String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result.append(System.lineSeparator());
            }
            result.append(lines.get(i));
        }
        return result.toString();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeAtomically(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(codexHome, "config-", ".tmp");
        try {
            Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void restrictToOwner(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static Path resolveCodexHome() {
        String override = System.getenv("CODEX_HOME");
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim()).toPath();
        }
        return new File(System.getProperty("user.home"), ".codex").toPath();
    }

    static final class InstallResult {
        private final Path configFile;
        private final boolean changed;

        private InstallResult(Path configFile, boolean changed) {
            this.configFile = configFile;
            this.changed = changed;
        }

        Path getConfigFile() {
            return configFile;
        }

        boolean isChanged() {
            return changed;
        }
    }
}
