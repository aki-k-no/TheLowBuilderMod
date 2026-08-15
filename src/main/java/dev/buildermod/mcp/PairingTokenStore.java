package dev.buildermod.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class PairingTokenStore {
    private static final int TOKEN_BYTES = 32;

    private final Path tokenFile;
    private final String token;

    PairingTokenStore(File tokenFile) throws IOException {
        this.tokenFile = tokenFile.toPath();
        this.token = loadOrCreate();
    }

    String getToken() {
        return token;
    }

    boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String loadOrCreate() throws IOException {
        if (Files.isRegularFile(tokenFile)) {
            String existing = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
            if (existing.length() >= 32) {
                return existing;
            }
            throw new IOException("BuilderMod pairing token file is invalid: " + tokenFile);
        }

        Path parent = tokenFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        byte[] random = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        Path temporary = Files.createTempFile(parent, "buildermod-pairing-", ".tmp");
        try {
            Files.write(temporary, generated.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, tokenFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, tokenFile);
            }
            restrictToOwner(tokenFile.toFile());
        } finally {
            Files.deleteIfExists(temporary);
        }
        return generated;
    }

    private void restrictToOwner(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}
