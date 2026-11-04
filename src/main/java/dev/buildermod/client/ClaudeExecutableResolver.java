package dev.buildermod.client;

import java.io.File;
import java.io.IOException;

/** Finds Claude Code installed on PATH or supplied by the user. */
final class ClaudeExecutableResolver {
    private ClaudeExecutableResolver() { }

    static String resolve() throws IOException {
        String explicit = System.getenv("BUILDERMOD_CLAUDE_PATH");
        if (explicit != null && !explicit.trim().isEmpty()) return preferNativeExecutable(new File(explicit.trim())).getAbsolutePath();
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(File.pathSeparator)) {
            File candidate = new File(entry.trim(), isWindows() ? "claude.exe" : "claude");
            if (candidate.isFile()) return candidate.getAbsolutePath();
            File script = new File(entry.trim(), isWindows() ? "claude.cmd" : "claude");
            if (script.isFile()) return preferNativeExecutable(script).getAbsolutePath();
        }
        throw new IOException("Claude Code CLI was not found. Install @anthropic-ai/claude-code, sign in with 'claude', or set BUILDERMOD_CLAUDE_PATH.");
    }

    static File preferNativeExecutable(File launcher) {
        if (isWindows() && launcher.getName().equalsIgnoreCase("claude.cmd")) {
            File nativeExecutable = new File(launcher.getParentFile(), "node_modules\\@anthropic-ai\\claude-code\\bin\\claude.exe");
            if (nativeExecutable.isFile()) return nativeExecutable;
        }
        return launcher;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
}
