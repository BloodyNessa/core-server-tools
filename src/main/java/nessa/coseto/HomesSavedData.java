package nessa.coseto;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

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
 * File-backed homes storage, now dimension-aware.
 * New format: "<uuid> <dim> <x> <y> <z>"
 * Legacy format: "<uuid> <x> <y> <z>" is loaded with dim="GLOBAL".
 */
public class HomesSavedData {
    private static final Path FILE = Paths.get("core-server-tools", "homes.dat");
    private static final String LEGACY_DIM = "GLOBAL";
    private static final HomesSavedData INSTANCE = new HomesSavedData();

    public static class HomeRecord {
        public final String dim;
        public final BlockPos pos;
        public HomeRecord(String dim, BlockPos pos) { this.dim = dim; this.pos = pos; }
    }

    private final Map<UUID, HomeRecord> homes = new HashMap<>();

    private HomesSavedData() { load(); }

    public static HomesSavedData.View get(ServerLevel world) { return new View(dimensionKey(world)); }

    public static HomeRecord getHomeRecord(UUID uuid) { return INSTANCE.homes.get(uuid); }

    public static class View {
        private final String dim;
        private View(String dim) { this.dim = dim; }
        public void setHome(UUID uuid, BlockPos pos) { INSTANCE.setHomeInternal(uuid, dim, pos); }
        public boolean removeHome(UUID uuid) { return INSTANCE.removeHomeInternal(uuid); }
        public BlockPos getHome(UUID uuid) {
            HomeRecord r = INSTANCE.homes.get(uuid);
            if (r == null) return null;
            return dim.equals(r.dim) ? r.pos : null;
        }
    }

    private synchronized void load() {
        // migrate old files (bledmi_*) into new core-server-tools folder with simplified names
        try {
            Path parent = FILE.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Path[] oldCandidates = new Path[] {
                Paths.get("bledmi_homes.dat"),
                Paths.get("core-server-tools", "bledmi_homes.dat")
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
                    if (parts.length == 4) {
                        // legacy: uuid x y z
                        UUID uuid = UUID.fromString(parts[0]);
                        int x = Integer.parseInt(parts[1]);
                        int y = Integer.parseInt(parts[2]);
                        int z = Integer.parseInt(parts[3]);
                        homes.put(uuid, new HomeRecord(LEGACY_DIM, new BlockPos(x, y, z)));
                    } else if (parts.length >= 5) {
                        UUID uuid = UUID.fromString(parts[0]);
                        String dim = parts[1];
                        int x = Integer.parseInt(parts[2]);
                        int y = Integer.parseInt(parts[3]);
                        int z = Integer.parseInt(parts[4]);
                        homes.put(uuid, new HomeRecord(dim, new BlockPos(x, y, z)));
                    }
                } catch (IllegalArgumentException ex) {
                    // skip malformed lines
                }
            }
        } catch (IOException e) {
            // ignore for now
        }
    }

    private synchronized void save() {
        try {
            Path parent = FILE.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Path tmp = FILE.resolveSibling(FILE.getFileName().toString() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<UUID, HomeRecord> e : homes.entrySet()) {
                    HomeRecord r = e.getValue();
                    BlockPos p = r.pos;
                    w.write(e.getKey().toString() + " " + r.dim + " " + p.getX() + " " + p.getY() + " " + p.getZ());
                    w.newLine();
                }
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // ignore for now
        }
    }

    private synchronized void setHomeInternal(UUID uuid, String dim, BlockPos pos) {
        homes.put(uuid, new HomeRecord(dim, pos));
        save();
    }

    private synchronized boolean removeHomeInternal(UUID uuid) {
        boolean removed = homes.remove(uuid) != null;
        if (removed) save();
        return removed;
    }

    // Reload data from disk and replace in-memory cache
    private synchronized void reloadInternal() {
        try { homes.clear(); } catch (Throwable _t) {}
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
