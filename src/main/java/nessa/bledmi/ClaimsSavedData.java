package nessa.bledmi;

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
 * Simple file-backed claims storage.
 * Stores lines of: <chunkX> <chunkZ> <owner-uuid>
 *
 * Note: This is server-global and not world-scoped. Use ClaimsSavedData.get(world) to access.
 */
public class ClaimsSavedData {
	private static final Path FILE = Paths.get("bledmi_claims.dat");
	private static final ClaimsSavedData INSTANCE = new ClaimsSavedData();

	private final Map<String, UUID> claims = new HashMap<>();

	private ClaimsSavedData() {
		load();
	}

	public static ClaimsSavedData get(ServerLevel world) {
		// world parameter retained for API compatibility; storage is server-global in this implementation
		return INSTANCE;
	}

	private static String key(int cx, int cz) {
		return cx + "," + cz;
	}

	private synchronized void load() {
		if (!Files.exists(FILE)) return;
		try (BufferedReader r = Files.newBufferedReader(FILE)) {
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				String[] parts = line.split(" ");
				if (parts.length < 3) continue;
				try {
					int cx = Integer.parseInt(parts[0]);
					int cz = Integer.parseInt(parts[1]);
					UUID owner = UUID.fromString(parts[2]);
					claims.put(key(cx, cz), owner);
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
			Path tmp = FILE.resolveSibling(FILE.getFileName().toString() + ".tmp");
			try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				for (Map.Entry<String, UUID> e : claims.entrySet()) {
					String[] parts = e.getKey().split(",");
					w.write(parts[0] + " " + parts[1] + " " + e.getValue().toString());
					w.newLine();
				}
			}
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ex) {
			// ignore for now
		}
	}

	/**
	 * Claim the chunk at (cx,cz) for owner. Returns true if the claim was set, false if already claimed.
	 */
	public synchronized boolean claimChunk(UUID owner, int cx, int cz) {
		String k = key(cx, cz);
		UUID existing = claims.get(k);
		if (existing == null) {
			claims.put(k, owner);
			save();
			return true;
		}
		// already claimed (by someone else or the same owner)
		return false;
	}

	/**
	 * Unclaim the chunk at (cx,cz) if owned by owner. Returns true if removed.
	 */
	public synchronized boolean unclaimChunk(UUID owner, int cx, int cz) {
		String k = key(cx, cz);
		UUID existing = claims.get(k);
		if (existing == null) return false;
		if (existing.equals(owner)) {
			claims.remove(k);
			save();
			return true;
		}
		return false;
	}

	public synchronized UUID getOwner(int cx, int cz) {
		return claims.get(key(cx, cz));
	}
}
