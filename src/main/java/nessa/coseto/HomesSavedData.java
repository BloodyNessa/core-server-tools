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
 * Simple file-backed homes storage as a fallback.
 * Stores lines of: <uuid> <x> <y> <z>
 *
 * Note: This writes to the server's working directory (./bledmi_homes.dat).
 */
public class HomesSavedData {
	private static final Path FILE = Paths.get("core-server-tools", "bledmi_homes.dat");
	private static final HomesSavedData INSTANCE = new HomesSavedData();

	private final Map<UUID, BlockPos> homes = new HashMap<>();

	private HomesSavedData() {
		load();
	}

	public static HomesSavedData get(ServerLevel world) {
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
				if (parts.length < 4) continue;
				try {
					UUID uuid = UUID.fromString(parts[0]);
					int x = Integer.parseInt(parts[1]);
					int y = Integer.parseInt(parts[2]);
					int z = Integer.parseInt(parts[3]);
					homes.put(uuid, new BlockPos(x, y, z));
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
				for (Map.Entry<UUID, BlockPos> e : homes.entrySet()) {
					BlockPos p = e.getValue();
					w.write(e.getKey().toString() + " " + p.getX() + " " + p.getY() + " " + p.getZ());
					w.newLine();
				}
			}
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ex) {
			// ignore for now
		}
	}

	public synchronized void setHome(UUID uuid, BlockPos pos) {
		homes.put(uuid, pos);
		save();
	}

	public synchronized boolean removeHome(UUID uuid) {
		boolean removed = homes.remove(uuid) != null;
		if (removed) save();
		return removed;
	}

	public synchronized BlockPos getHome(UUID uuid) {
		return homes.get(uuid);
	}
}
