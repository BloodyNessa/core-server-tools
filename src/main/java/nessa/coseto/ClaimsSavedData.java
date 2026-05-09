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
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/**
 * File-backed claims storage, now dimension-aware.
 * New file format: "<dim> <cx> <cz> <ownerUUID>"
 * Legacy format ("cx cz ownerUUID") is loaded into the special "GLOBAL" dimension key.
 */
public class ClaimsSavedData {
    private static final Path FILE = Paths.get("core-server-tools", "claims.dat");
    private static final String LEGACY_DIM = "GLOBAL";
    private static final ClaimsSavedData INSTANCE = new ClaimsSavedData();

    // dim -> ("cx,cz" -> owner UUID)
    private final Map<String, Map<String, UUID>> claimsByDim = new HashMap<>();

    private ClaimsSavedData() {
        load();
    }

    /**
     * View for a particular dimension (captures the dimension key).
     */
    public static class View {
        private final String dim;
        private View(String dim) { this.dim = dim; }

        public boolean claimChunk(UUID owner, int cx, int cz) { return INSTANCE.claimChunkForDim(dim, owner, cx, cz); }
        public boolean unclaimChunk(UUID owner, int cx, int cz) { return INSTANCE.unclaimChunkForDim(dim, owner, cx, cz); }
        public UUID getOwner(int cx, int cz) { return INSTANCE.getOwnerForDimOrGlobal(dim, cx, cz); }
        public List<String> getClaims(UUID owner) { return INSTANCE.getClaimsForDim(dim, owner); }
        public int unclaimAll(UUID owner) { return INSTANCE.unclaimAllForDim(dim, owner); }
        public Map<String, UUID> getAllClaims() { return INSTANCE.getAllClaimsForDim(dim); }
    }

    public static View get(ServerLevel world) {
        String dim = dimensionKey(world);
        return new View(dim);
    }

    private static String key(int cx, int cz) { return cx + "," + cz; }

    private synchronized void load() {
        // migrate old files (bledmi_*) into new core-server-tools folder with simplified names
        try {
            Path parent = FILE.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Path[] oldCandidates = new Path[] {
                Paths.get("bledmi_claims.dat"),
                Paths.get("core-server-tools", "bledmi_claims.dat")
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
                String[] parts = line.split(" ");
                try {
                    if (parts.length == 3) {
                        // legacy: cx cz owner
                        int cx = Integer.parseInt(parts[0]);
                        int cz = Integer.parseInt(parts[1]);
                        UUID owner = UUID.fromString(parts[2]);
                        claimsByDim.computeIfAbsent(LEGACY_DIM, k -> new HashMap<>()).put(key(cx, cz), owner);
                    } else if (parts.length >= 4) {
                        String dim = parts[0];
                        int cx = Integer.parseInt(parts[1]);
                        int cz = Integer.parseInt(parts[2]);
                        UUID owner = UUID.fromString(parts[3]);
                        claimsByDim.computeIfAbsent(dim, k -> new HashMap<>()).put(key(cx, cz), owner);
                    }
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
                for (Map.Entry<String, Map<String, UUID>> dimEntry : claimsByDim.entrySet()) {
                    String dim = dimEntry.getKey();
                    for (Map.Entry<String, UUID> e : dimEntry.getValue().entrySet()) {
                        String[] parts = e.getKey().split(",");
                        w.write(dim + " " + parts[0] + " " + parts[1] + " " + e.getValue().toString());
                        w.newLine();
                    }
                }
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // ignore for now
        }
    }

    private synchronized boolean claimChunkForDim(String dim, UUID owner, int cx, int cz) {
        Map<String, UUID> map = claimsByDim.computeIfAbsent(dim, k -> new HashMap<>());
        String k = key(cx, cz);
        if (!map.containsKey(k)) {
            map.put(k, owner);
            save();
            return true;
        }
        return false;
    }

    private synchronized boolean unclaimChunkForDim(String dim, UUID owner, int cx, int cz) {
        Map<String, UUID> map = claimsByDim.get(dim);
        if (map == null) return false;
        UUID existing = map.get(key(cx, cz));
        if (existing == null) return false;
        if (existing.equals(owner)) {
            map.remove(key(cx, cz));
            save();
            return true;
        }
        return false;
    }

    private synchronized UUID getOwnerForDimOrGlobal(String dim, int cx, int cz) {
        Map<String, UUID> map = claimsByDim.get(dim);
        UUID u = (map == null) ? null : map.get(key(cx, cz));
        if (u != null) return u;
        Map<String, UUID> legacy = claimsByDim.get(LEGACY_DIM);
        return (legacy == null) ? null : legacy.get(key(cx, cz));
    }

    private synchronized List<String> getClaimsForDim(String dim, UUID owner) {
        List<String> list = new ArrayList<>();
        Map<String, UUID> map = claimsByDim.get(dim);
        if (map != null) {
            for (Map.Entry<String, UUID> e : map.entrySet()) {
                if (e.getValue().equals(owner)) list.add(e.getKey());
            }
        }
        return list;
    }

    private synchronized int unclaimAllForDim(String dim, UUID owner) {
        int removed = 0;
        Map<String, UUID> map = claimsByDim.get(dim);
        if (map != null) {
            Iterator<Map.Entry<String, UUID>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, UUID> e = it.next();
                if (e.getValue().equals(owner)) { it.remove(); removed++; }
            }
            if (removed > 0) save();
        }
        return removed;
    }

    private synchronized Map<String, UUID> getAllClaimsForDim(String dim) {
        Map<String, UUID> map = claimsByDim.get(dim);
        if (map == null) return new HashMap<>();
        return new HashMap<>(map);
    }

    // Reload data from disk and replace in-memory cache
    private synchronized void reloadInternal() {
        try { claimsByDim.clear(); } catch (Throwable _t) {}
        load();
    }

    public static void reload() { INSTANCE.reloadInternal(); }

    private static String dimensionKey(ServerLevel world) {
        if (world == null) return LEGACY_DIM;
        try {
            java.lang.reflect.Method mDim = world.getClass().getMethod("dimension");
            Object dim = mDim.invoke(world);
            if (dim != null) {
                try {
                    java.lang.reflect.Method mLoc = dim.getClass().getMethod("location");
                    Object loc = mLoc.invoke(dim);
                    if (loc != null) return loc.toString();
                } catch (NoSuchMethodException ns) {
                    return dim.toString();
                }
            }
        } catch (NoSuchMethodException ns) {
            // ignore
        } catch (Exception e) {
            // ignore
        }
        try { return world.toString(); } catch (Throwable t) { return LEGACY_DIM; }
    }
}
