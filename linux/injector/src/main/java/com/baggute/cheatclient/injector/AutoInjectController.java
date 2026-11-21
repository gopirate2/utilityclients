package com.baggute.cheatclient.injector;

import java.nio.file.Path;
import java.util.*;

/** Scheduling only: callers own integrity verification, attachment and confirmed READY handling. */
public final class AutoInjectController {
    public record Identity(String pid, long startedAtMillis) {}
    public record Candidate(JvmScanner.Target target, Identity identity) {
        public static Candidate observe(JvmScanner.Target target) {
            try {
                var process = ProcessHandle.of(Long.parseLong(target.pid()));
                if (process.isEmpty() || !process.get().isAlive()) return null;
                var started = process.get().info().startInstant();
                return started.isPresent() ? new Candidate(target, new Identity(target.pid(), started.get().toEpochMilli())) : null;
            } catch (RuntimeException unreadable) { return null; }
        }
        public boolean stillRunning() {
            Candidate current = observe(target);
            return current != null && identity.equals(current.identity());
        }
    }
    public record Decision(Candidate candidate, String message) { public boolean ready() { return candidate != null; } }
    private final Set<Identity> attempted = new HashSet<>();
    private boolean succeeded;

    public Decision poll(List<Candidate> observations, LaunchSettings settings, long now) {
        if (!settings.autoInject) return new Decision(null, "Auto Inject off");
        if (succeeded && !settings.reinjectOnRestart) return new Decision(null, "Auto Inject completed; restart watching to arm another session");
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : observations) {
            if (candidate == null || candidate.identity() == null || candidate.identity().startedAtMillis() <= 0
                    || !candidate.target().pid().equals(candidate.identity().pid()) || !candidate.target().isAutoInjectable()
                    || !matchesDirectory(candidate.target().gameDirectory(), settings.gameDirectory)) continue;
            matches.add(candidate);
        }
        if (matches.isEmpty()) return new Decision(null, "Auto Inject waiting for a matching Minecraft client");
        if (matches.size() > 1 && !settings.allowMultiple) return new Decision(null, "Auto Inject paused: multiple matching games; select a game folder in Launch Options");
        matches.sort(Comparator.comparingLong((Candidate c) -> c.identity().startedAtMillis()).reversed().thenComparing(c -> c.target().pid()));
        Candidate target = matches.getFirst();
        if (attempted.contains(target.identity())) return new Decision(null, "Auto Inject already attempted PID " + target.target().pid() + "; use Reset Attempts to retry");
        long age = now - target.identity().startedAtMillis(), wait = settings.warmupSeconds * 1000L;
        if (age < wait) return new Decision(null, "Auto Inject warming up PID " + target.target().pid() + " (" + Math.max(1, (wait - age + 999) / 1000) + "s)");
        return new Decision(target, "Auto Inject ready for PID " + target.target().pid());
    }
    static boolean matchesDirectory(String actual, String configured) {
        if (configured == null || configured.isBlank()) return true;
        if (actual == null || actual.isBlank()) return false;
        try {
            String first = Path.of(actual).toAbsolutePath().normalize().toString(), second = Path.of(configured).toAbsolutePath().normalize().toString();
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? first.equalsIgnoreCase(second) : first.equals(second);
        } catch (RuntimeException invalid) { return false; }
    }
    public void started(Candidate candidate) { attempted.add(candidate.identity()); }
    public void completed(Candidate candidate, boolean success) { attempted.add(candidate.identity()); if (success) succeeded = true; }
    public void reset() { attempted.clear(); succeeded = false; }
}
