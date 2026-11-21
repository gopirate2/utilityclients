package com.baggute.cheatclient.injector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Drains before a child fills its pipe, with bounded storage and one deadline. */
final class ProcessOutput {
    private ProcessOutput() {}

    static String read(Process process, long timeoutMillis) throws IOException, InterruptedException {
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream(); var bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int keep = Math.min(count, Math.max(0, 1024 * 1024 - bytes.size()));
                    if (keep > 0) bytes.write(buffer, 0, keep);
                }
                output.complete(bytes.toByteArray());
            } catch (Throwable failure) { output.completeExceptionally(failure); }
        }, "utilityclient-process-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("process timed out");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("output timed out");
            byte[] bytes = output.get(remaining, TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) throw new IOException("process exited " + process.exitValue());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("process output failed", failure);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            output.cancel(false);
        }
    }
}
