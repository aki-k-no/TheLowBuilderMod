package dev.buildermod.client;

import java.io.File;
import java.io.IOException;

/** Locates a supported standalone Codex CLI without copying app-package binaries. */
final class CodexExecutableResolver {
    private CodexExecutableResolver() {
    }

    static String resolve() throws IOException {
        String explicit = clean(System.getenv("BUILDERMOD_CODEX_PATH"));
        if (!explicit.isEmpty()) {
            return explicit;
        }

        String fromPath = findOnPath(System.getenv("PATH"));
        if (fromPath != null && !isWindowsAppPath(fromPath)) {
            return fromPath;
        }

        String standalone = findOfficialStandalone(
            System.getenv("LOCALAPPDATA"),
            System.getenv("USERPROFILE"),
            System.getenv("CODEX_HOME")
        );
        if (standalone != null) {
            return standalone;
        }

        throw new IOException(
            "Standalone Codex CLI was not found. Install it from https://chatgpt.com/codex/install.ps1"
        );
    }

    static String findOnPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            String directory = clean(entry);
            if (directory.isEmpty()) {
                continue;
            }
            File candidate = new File(directory, isWindows() ? "codex.exe" : "codex");
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    static String findOfficialStandalone(String localAppData, String userProfile, String codexHome) {
        String local = clean(localAppData);
        if (!local.isEmpty()) {
            File visibleCommand = new File(local, "Programs\\OpenAI\\Codex\\bin\\codex.exe");
            if (visibleCommand.isFile()) {
                return visibleCommand.getAbsolutePath();
            }
        }

        String home = clean(codexHome);
        if (home.isEmpty()) {
            String user = clean(userProfile);
            if (!user.isEmpty()) {
                home = new File(user, ".codex").getAbsolutePath();
            }
        }
        if (!home.isEmpty()) {
            File current = new File(home, "packages\\standalone\\current");
            File packageLayout = new File(current, "bin\\codex.exe");
            if (packageLayout.isFile()) {
                return packageLayout.getAbsolutePath();
            }
            File legacyLayout = new File(current, "codex.exe");
            if (legacyLayout.isFile()) {
                return legacyLayout.getAbsolutePath();
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            return cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static boolean isWindowsAppPath(String path) {
        return path.replace('/', '\\').toLowerCase().contains("\\windowsapps\\");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
