package nessa.coseto;

import net.minecraft.server.level.ServerLevel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Simple file-backed trust lists.
 * Stores lines of: <owner-uuid> <trusted-uuid>
 */
public class TrustsSavedData {
    private static final Path FILE = Paths.get("core-server-tools", "bledmi_trusts.dat");
    private static final TrustsSavedData INSTANCE = new TrustsSavedData();

    private final Map<UUID, Set<UUID>> trusts = new HashMap<>();

    private TrustsSavedData() {
        load();
    }

    public static TrustsSavedData get(ServerLevel world) {
        // world param retained for API compatibility; storage is server-global in this simple implementation
        return INSTANCE;
    }

    private synchronized void load() {
        if (!Files.exists(FILE)) return;
        try (BufferedReader r = Files.newBufferedReader(FILE)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length < 2) continue;
                try {
                    UUID owner = UUID.fromString(parts[0]);
                    UUID trusted = UUID.fromString(parts[1]);
                    trusts.computeIfAbsent(owner, k -> new HashSet<>()).add(trusted);
                } catch (Exception ex) {
                    // skip malformed
                }
            }
        } catch (IOException ex) {
            // ignore for now
        }
    }

    private synchronized void save() {
        try {
            Path parent = FILE.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Path tmp = FILE.resolveSibling(FILE.getFileName().toString() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<UUID, Set<UUID>> e : trusts.entrySet()) {
                    for (UUID t : e.getValue()) {
                        w.write(e.getKey().toString() + " " + t.toString());
                        w.newLine();
                    }
                }
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // ignore for now
        }
    }

    public synchronized boolean trust(UUID owner, UUID trusted) {
        if (owner.equals(trusted)) return false;
        Set<UUID> set = trusts.computeIfAbsent(owner, k -> new HashSet<>());
        boolean added = set.add(trusted);
        if (added) save();
        return added;
    }

    public synchronized boolean untrust(UUID owner, UUID trusted) {
        Set<UUID> set = trusts.get(owner);
        if (set == null) return false;
        boolean removed = set.remove(trusted);
        if (removed) save();
        return removed;
    }

    public synchronized boolean isTrusted(UUID owner, UUID maybeTrusted) {
        Set<UUID> set = trusts.get(owner);
        return set != null && set.contains(maybeTrusted);
    }

    public synchronized java.util.List<String> getTrusted(UUID owner) {
        Set<UUID> set = trusts.get(owner);
        if (set == null) return java.util.Collections.emptyList();
        java.util.List<String> l = new java.util.ArrayList<>();
        for (UUID u : set) l.add(u.toString());
        return l;
    }
}
