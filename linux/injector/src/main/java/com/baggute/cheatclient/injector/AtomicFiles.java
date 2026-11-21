package com.baggute.cheatclient.injector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Commits complete files without truncating the last working copy. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void writeString(Path target, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path pending = Files.createTempFile(absolute.getParent(), ".uc-save-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            commit(pending, absolute);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** The staged file must be beside the destination, on the same filesystem. */
    public static void commit(Path pending, Path target) throws IOException {
        try {
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Path backup = null;
            try {
                if (Files.exists(target)) {
                    backup = Files.createTempFile(target.toAbsolutePath().getParent(), ".uc-last-good-", ".bak");
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                }
                try {
                    Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) {
                    if (backup != null) {
                        try {
                            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                            backup = null;
                        } catch (IOException restoreFailure) {
                            failure.addSuppressed(new IOException("Last good file retained at " + backup, restoreFailure));
                            // Keep the recovery copy when restoration itself fails.
                            backup = null;
                        }
                    }
                    throw failure;
                }
            } finally {
                if (backup != null) Files.deleteIfExists(backup);
            }
        }
    }
}
