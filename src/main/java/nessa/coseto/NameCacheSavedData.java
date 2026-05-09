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
import java.util.Map;
import java.util.UUID;

/**
 * Simple file-backed name cache: uuid -> last-seen player name
 * Stored as lines: <uuid> <name>
 */
public class NameCacheSavedData {
    private static final Path FILE = Paths.get("core-server-tools", "namecache.dat");
    private static final NameCacheSavedData INSTANCE = new NameCacheSavedData();

    private final Map<UUID, String> names = new HashMap<>();

    private NameCacheSavedData() {
        load();
    }

    public static NameCacheSavedData get(ServerLevel world) {
        // world parameter retained for API compatibility; storage is server-global in this implementation
        return INSTANCE;
    }

    /** Convenience getter when a ServerLevel isn't available */
    public static NameCacheSavedData get() {
        return INSTANCE;
    }

    private synchronized void load() {
        // migrate old files (bledmi_*) into new core-server-tools folder with simplified names
        try {
            Path parent = FILE.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Path[] oldCandidates = new Path[] {
                Paths.get("bledmi_namecache.dat"),
                Paths.get("core-server-tools", "bledmi_namecache.dat")
            };
            for (Path old : oldCandidates) {
                if (Files.exists(old) && !Files.exists(FILE)) {
                    try {
                        Files.move(old, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException ex) {
                        try { Files.move(old, FILE, StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { /* ignore */ }
                    }
                    break;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        if (!Files.exists(FILE)) return;
        try (BufferedReader r = Files.newBufferedReader(FILE)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ", 2);
                if (parts.length < 2) continue;
                try {
                    UUID id = UUID.fromString(parts[0]);
                    String name = parts[1];
                    names.put(id, name);
                } catch (Exception ex) {
                    // skip malformed lines
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
                for (Map.Entry<UUID, String> e : names.entrySet()) {
                    w.write(e.getKey().toString() + " " + e.getValue());
                    w.newLine();
                }
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // ignore for now
        }
    }

    public synchronized void setName(UUID uuid, String name) {
        if (uuid == null || name == null) return;
        String prev = names.put(uuid, name);
        if (prev == null || !prev.equals(name)) save();
    }

    public synchronized String getName(UUID uuid) {
        return names.get(uuid);
    }
}
