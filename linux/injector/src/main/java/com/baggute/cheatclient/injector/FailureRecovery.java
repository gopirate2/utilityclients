package com.baggute.cheatclient.injector;

import java.util.List;
import java.util.Locale;

/** Concrete next steps shared by the GUI and CLI; no automatic repeated attachment. */
public final class FailureRecovery {
    public record Advice(String title, List<String> steps) {
        public String text() { return title + "\n" + String.join("\n", steps); }
    }
    private FailureRecovery() {}
    public static Advice forMessage(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("integrity") || text.contains("checksum") || text.contains("sha-256") || text.contains("zip") || text.contains("wrong client"))
            return new Advice("Replace the selected client", List.of("Download the matching UtilityClient release again.", "Select the client JAR, not a source/archive or unrelated mod.", "Verify it before installing or injecting."));
        if (text.contains("already-loaded") || text.contains("already loaded") || text.contains("classloader") || text.contains("hook compatibility")
                || text.contains("required game hooks") || text.contains("linkage") || text.contains("noclassdeffound") || text.contains("nosuchmethod"))
            return new Advice("Restart with a matching build", List.of("Fully close Minecraft and the injector.", "Repair the installed client/injector pair, then launch the supported Minecraft/Fabric version.", "Inject once after restart; existing loaded classes cannot be replaced by selecting another JAR."));
        if (text.contains("no such process") || text.contains("process not found") || text.contains("process exited"))
            return new Advice("Select the current game process", List.of("Open or wait for Minecraft to finish starting.", "Refresh the process list and select its new PID."));
        if (text.contains("disableattachmechanism") || text.contains("attach is not supported"))
            return new Advice("Enable JVM attachment", List.of("Remove -XX:+DisableAttachMechanism from the game's JVM arguments.", "Restart Minecraft and refresh the injector's process list."));
        if (text.contains("access denied") || text.contains("access is denied") || text.contains("permission denied") || text.contains("operation not permitted"))
            return new Advice("Match the process permissions", List.of("Run Minecraft and the injector as the same user and privilege level.", "Close an elevated copy and relaunch it normally, then refresh the process list."));
        if (text.contains("jdk.attach") || text.contains("attach api") || text.contains("unsupportedclassversion"))
            return new Advice("Repair the Java runtime", List.of("Use the bootstrapper's Health page to check the private runtime.", "Run Repair to restore Java 25 with jdk.attach and java.desktop."));
        if (text.contains("timed out") || text.contains("timeout"))
            return new Advice("Check whether the target completed", List.of("Read the target output and Minecraft log before retrying.", "Refresh the process list. If the target is unresponsive or has an older loaded client, restart Minecraft.", "A returned attach call without READY does not confirm initialization."));
        if (text.contains("locked") || text.contains("being used") || text.contains("rollback") || text.contains("backup"))
            return new Advice("Release the locked installation", List.of("Close Minecraft and the injector before repairing or restoring files.", "Keep retained backups if rollback was incomplete, then retry Repair."));
        if (text.contains("http") || text.contains("fetch") || text.contains("download") || text.contains("connect"))
            return new Advice("Retry the release download", List.of("Check the connection and try again after a service/rate-limit error clears.", "Use a previously verified local release or the embedded offline installer while downloads are unavailable."));
        if (text.contains("missing") || text.contains("no such") || text.contains("not found"))
            return new Advice("Check the selected paths", List.of("Select existing client and host files, or use bootstrapper Repair for missing installed files.", "For Baritone, install the client as a Fabric mod before game startup and restart Minecraft."));
        return new Advice("Inspect the failure details", List.of("Keep the technical error shown in target output.", "Check the installation with Health and confirm the supported Minecraft/Fabric version.", "Repair or restart only after checking whether the target has already initialized."));
    }
}
