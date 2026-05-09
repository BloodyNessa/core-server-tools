package nessa.coseto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Properties;

public class ModConfig {
    private static final Path CONFIG_PATH;
    private static final int DEFAULT_MAX_CLAIMS = 12;
    private static volatile int maxClaims = DEFAULT_MAX_CLAIMS;

    static {
        Path cfg;
        try {
            cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("core-server-tools.properties");
        } catch (Throwable t) {
            // Fall back to a ./config/ directory if FabricLoader is not available
            cfg = Paths.get("config", "core-server-tools.properties");
        }
        CONFIG_PATH = cfg;
        load();
        // Log resolved path (FabricLoader may not be available during static analysis)
        try {
            java.nio.file.Path p = CONFIG_PATH;
            System.out.println("[CoreServerTools] Resolved config path: " + p.toAbsolutePath().toString());
            System.out.println("[CoreServerTools] Config file exists: " + java.nio.file.Files.exists(p));
        } catch (Throwable t) {
            // ignore
        }
    }

    public static synchronized void load() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Properties p = new Properties();
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                    p.load(in);
                }
            } else {
                // write default config
                p.setProperty("maxClaims", String.valueOf(DEFAULT_MAX_CLAIMS));
                try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                    p.store(out, "Core Server Tools configuration");
                }
            }
            String mc = p.getProperty("maxClaims");
            if (mc != null) {
                try {
                    maxClaims = Integer.parseInt(mc.trim());
                } catch (NumberFormatException e) {
                    maxClaims = DEFAULT_MAX_CLAIMS;
                }
            } else {
                maxClaims = DEFAULT_MAX_CLAIMS;
            }
        } catch (IOException e) {
            // if anything goes wrong, keep defaults
            maxClaims = DEFAULT_MAX_CLAIMS;
        }
    }

    public static int getMaxClaims() {
        return maxClaims;
    }

    public static void reload() {
        load();
    }

    public static java.nio.file.Path getConfigPath() {
        return CONFIG_PATH;
    }
}
