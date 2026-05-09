package nessa.bledmi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import nessa.bledmi.TrustsSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class BleedingEdgeMinecraft implements ModInitializer {
public static final String MOD_ID = "core-server-tools";

public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Players who currently have /seeclaims enabled
	private static final java.util.Set<java.util.UUID> SEEING_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// map of player -> last seen chunk (packed long: (cx << 32) | (cz & 0xffffffffL))
	private static final java.util.Map<java.util.UUID, Long> SEEING_LAST_CHUNK = new java.util.concurrent.ConcurrentHashMap<>();
	// simple tick counter for throttling (runs on server thread)
	private static int tickCounter = 0;

private static boolean isAllowed(ServerPlayer player, ServerLevel world, BlockPos pos) {
UUID owner = ClaimsSavedData.get(world).getOwner(pos.getX() >> 4, pos.getZ() >> 4);
if (owner == null) return true;
UUID playerId = player.getUUID();
if (owner.equals(playerId)) return true;
// trusted players may act in owner's claims
if (TrustsSavedData.get(world).isTrusted(owner, playerId)) return true;
return false;
}

private static String resolvePlayerName(net.minecraft.server.MinecraftServer server, UUID uuid) {
		// check persistent name cache first
		String cached = NameCacheSavedData.get().getName(uuid);
		if (cached != null) return cached;

	if (server == null || uuid == null) return uuid == null ? "unknown" : uuid.toString();
	try {
		Object profileCache = null;
		try {
			profileCache = server.getClass().getMethod("getProfileCache").invoke(server);
		} catch (NoSuchMethodException e) {
			// ignore
		}
		if (profileCache != null) {
			// try several possible method names to retrieve a profile
			String[] names = new String[] { "get", "getById", "getByUuid", "getProfileById", "getProfile" };
			for (String mname : names) {
				try {
					java.lang.reflect.Method m = profileCache.getClass().getMethod(mname, java.util.UUID.class);
					Object prof = m.invoke(profileCache, uuid);
					if (prof == null) continue;
					// handle Optional-like return values
					if (prof.getClass().getName().endsWith("Optional")) {
						java.lang.reflect.Method isPresent = prof.getClass().getMethod("isPresent");
						if ((Boolean) isPresent.invoke(prof)) {
							java.lang.reflect.Method get = prof.getClass().getMethod("get");
							Object inner = get.invoke(prof);
							java.lang.reflect.Method getName = inner.getClass().getMethod("getName");
							Object name = getName.invoke(inner);
							if (name != null) return name.toString();
						}
					} else {
						try {
							java.lang.reflect.Method getName = prof.getClass().getMethod("getName");
							Object name = getName.invoke(prof);
							if (name != null) return name.toString();
						} catch (NoSuchMethodException ns) {
							// fallthrough
						}
					}
				} catch (NoSuchMethodException ns) {
					// try next
				} catch (Exception ex) {
					// ignore other reflection errors
				}
			}
		}
	} catch (Exception e) {
		// ignore and fallback
	}
	return uuid.toString();
}

@Override
public void onInitialize() {
LOGGER.info("Hello Fabric world! Registering commands...");


// Update name cache on player join
try {
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
try {
ServerPlayer player = handler.player;
if (player != null) {
ServerLevel lvl = (ServerLevel) player.level();
NameCacheSavedData.get(lvl).setName(player.getUUID(), player.getName().getString());
}
} catch (Exception ex) {
// ignore
}
});
} catch (Throwable t) {
// ignore if event API not present at runtime
}


// Claim protection handlers
UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
if (world.isClientSide()) return InteractionResult.PASS;
if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
ServerLevel serverWorld = (ServerLevel) world;
BlockPos pos = hitResult.getBlockPos();
if (player instanceof ServerPlayer) {
ServerPlayer sp = (ServerPlayer) player;
if (!isAllowed(sp, serverWorld, pos)) {
sp.sendSystemMessage(Component.literal("This chunk is claimed."));
return InteractionResult.FAIL;
}
}
return InteractionResult.PASS;
});

AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
if (world.isClientSide()) return InteractionResult.PASS;
if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
if (player instanceof ServerPlayer) {
ServerPlayer sp = (ServerPlayer) player;
ServerLevel serverWorld = (ServerLevel) world;
if (!isAllowed(sp, serverWorld, pos)) {
sp.sendSystemMessage(Component.literal("This chunk is claimed."));
return InteractionResult.FAIL;
}
}
return InteractionResult.PASS;
});

AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
if (world.isClientSide()) return InteractionResult.PASS;
if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
ServerLevel serverWorld = (ServerLevel) world;
BlockPos pos = entity.blockPosition();
if (player instanceof ServerPlayer) {
ServerPlayer sp = (ServerPlayer) player;
if (!isAllowed(sp, serverWorld, pos)) {
sp.sendSystemMessage(Component.literal("You cannot attack entities in a claimed chunk."));
return InteractionResult.FAIL;
}
}
return InteractionResult.PASS;
});

UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
if (world.isClientSide()) return InteractionResult.PASS;
if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
ServerLevel serverWorld = (ServerLevel) world;
BlockPos pos = entity.blockPosition();
if (player instanceof ServerPlayer) {
ServerPlayer sp = (ServerPlayer) player;
if (!isAllowed(sp, serverWorld, pos)) {
sp.sendSystemMessage(Component.literal("You cannot interact with entities in a claimed chunk."));
return InteractionResult.FAIL;
}
}
return InteractionResult.PASS;
});

// Command registration

// Register server tick handler for /seeclaims particles
ServerTickEvents.END_SERVER_TICK.register(server -> {
	tickCounter = (tickCounter + 1) % 10; // run every 10 ticks
	if (tickCounter != 0) return;
	for (java.util.UUID uuid : SEEING_PLAYERS) {
		ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
		if (sp == null) continue;
		if (!(sp.level() instanceof ServerLevel)) continue;
		ServerLevel level = (ServerLevel) sp.level();
		int pcx = sp.blockPosition().getX() >> 4;
		int pcz = sp.blockPosition().getZ() >> 4;
		long currentChunkKey = (((long)pcx) << 32) | (pcz & 0xffffffffL);
		Long prevChunkKey = SEEING_LAST_CHUNK.get(uuid);
		if (prevChunkKey == null) {
			// initialize to current to avoid immediate announcement
			SEEING_LAST_CHUNK.put(uuid, currentChunkKey);
		} else if (prevChunkKey.longValue() != currentChunkKey) {
			int prevCx = (int)(prevChunkKey.longValue() >> 32);
			int prevCz = (int)(prevChunkKey.longValue() & 0xffffffffL);
UUID prevOwner = ClaimsSavedData.get(level).getOwner(prevCx, prevCz);
UUID currOwner = ClaimsSavedData.get(level).getOwner(pcx, pcz);
if (currOwner == null) {
// entered wilderness from a claimed chunk
if (prevOwner != null) {
sp.sendSystemMessage(Component.literal("Wilderness"));
}
} else {
if (prevOwner == null || !currOwner.equals(prevOwner)) {
Component ownerComp;
ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(currOwner);
if (ownerPlayer != null) ownerComp = ownerPlayer.getName(); else ownerComp = Component.literal(resolvePlayerName(server, currOwner));
int nameColor;
if (currOwner.equals(sp.getUUID())) {
nameColor = 0x99FF99; // light green
} else if (TrustsSavedData.get(level).isTrusted(currOwner, sp.getUUID())) {
nameColor = 0x99CCFF; // light blue
} else {
nameColor = 0xFF6666; // red
}
ownerComp = ((net.minecraft.network.chat.MutableComponent)ownerComp).withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(nameColor)));
sp.sendSystemMessage(Component.literal("Claim owned by: ").append(ownerComp));
}
}
SEEING_LAST_CHUNK.put(uuid, currentChunkKey);
		}
		int radiusChunks = 10; // show claims within 10 chunks
		int stride = 4; // spacing along chunk edge
		int shown = 0;
		java.util.Map<String, UUID> all = ClaimsSavedData.get(level).getAllClaims();
		for (java.util.Map.Entry<String, UUID> e : all.entrySet()) {
			if (shown > 400) break; // avoid excessive particle spam per tick
			String key = e.getKey();
			String[] parts = key.split(",");
			int cx = Integer.parseInt(parts[0]);
			int cz = Integer.parseInt(parts[1]);
			int dx = Math.abs(cx - pcx);
			int dz = Math.abs(cz - pcz);
			if (Math.max(dx, dz) > radiusChunks) continue;
			int x0 = cx << 4;
			int z0 = cz << 4;
			int x1 = x0 + 15;
			int z1 = z0 + 15;
			double y = sp.getY() + 0.5;
				UUID owner = e.getValue();
				// choose color: own (light green), trusted (light blue), other (red)
				int color;
				if (owner != null && owner.equals(sp.getUUID())) {
					color = 0x99FF99; // light green
				} else if (owner != null && TrustsSavedData.get(level).isTrusted(owner, sp.getUUID())) {
					color = 0x99CCFF; // light blue
				} else {
					color = 0xFF6666; // red
				}
			for (int x = x0; x <= x1; x += stride) {
				level.sendParticles(sp, new net.minecraft.core.particles.DustParticleOptions(color, 1.0f), true, false, x + 0.5, y, z0 + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
				level.sendParticles(sp, new net.minecraft.core.particles.DustParticleOptions(color, 1.0f), true, false, x + 0.5, y, z1 + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
				shown += 2;
			}
			for (int z = z0; z <= z1; z += stride) {
				level.sendParticles(sp, new net.minecraft.core.particles.DustParticleOptions(color, 1.0f), true, false, x0 + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
				level.sendParticles(sp, new net.minecraft.core.particles.DustParticleOptions(color, 1.0f), true, false, x1 + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
				shown += 2;
			}
		}
	}
});

CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
// /sethome
dispatcher.register(Commands.literal("sethome").executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
BlockPos pos = player.blockPosition();
ServerLevel world = source.getLevel();
HomesSavedData.get(world).setHome(player.getUUID(), pos);
source.sendSuccess(() -> Component.literal("Home set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /sethome."));
}
return 1;
}));

// /delhome
dispatcher.register(Commands.literal("delhome").executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
ServerLevel world = source.getLevel();
boolean removed = HomesSavedData.get(world).removeHome(player.getUUID());
if (removed) {
source.sendSuccess(() -> Component.literal("Home removed."), false);
} else {
source.sendFailure(Component.literal("No home set."));
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /delhome."));
}
return 1;
}));

// /claim
dispatcher.register(
Commands.literal("claim")
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
BlockPos pos = player.blockPosition();
int chunkX = pos.getX() >> 4;
int chunkZ = pos.getZ() >> 4;
ServerLevel world = source.getLevel();
// Enforce 12-claim limit for non-operators
boolean isOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
if (!isOp) {
    int currentClaims = ClaimsSavedData.get(world).getClaims(player.getUUID()).size();
    int maxClaims = ModConfig.getMaxClaims();
    if (currentClaims >= maxClaims) {
        source.sendFailure(Component.literal("You have reached the maximum of " + maxClaims + " claims."));
        return 0;
    }
}
boolean success = ClaimsSavedData.get(world).claimChunk(player.getUUID(), chunkX, chunkZ);
if (success) {
source.sendSuccess(() -> Component.literal("Chunk claimed (" + chunkX + ", " + chunkZ + ")."), false);
NameCacheSavedData.get(world).setName(player.getUUID(), player.getName().getString());
return 1;
} else {
UUID owner = ClaimsSavedData.get(world).getOwner(chunkX, chunkZ);
String ownerName = "someone";
if (owner != null) {
    if (source.getServer() != null) {
        ServerPlayer ownerPlayer = source.getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) ownerName = ownerPlayer.getName().getString();
        else ownerName = owner.toString();
    } else {
        ownerName = owner.toString();
    }
}
source.sendFailure(Component.literal("Chunk already claimed by " + ownerName));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /claim."));
return 0;
}
})
.then(Commands.argument("player", EntityArgument.player())
.requires(src -> { Entity ent = src.getEntity(); if (!(ent instanceof ServerPlayer)) return false; ServerPlayer sp = (ServerPlayer) ent; return src.getServer() != null && src.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(sp.getGameProfile())); })
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer operator = source.getPlayerOrException();
ServerPlayer target = EntityArgument.getPlayer(context, "player");
BlockPos pos = operator.blockPosition();
int chunkX = pos.getX() >> 4;
int chunkZ = pos.getZ() >> 4;
ServerLevel world = source.getLevel();
// Enforce 12-claim limit for non-operators (applies to the target)
boolean isTargetOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(target.getGameProfile()));
if (!isTargetOp) {
    int targetClaims = ClaimsSavedData.get(world).getClaims(target.getUUID()).size();
    int maxClaims = ModConfig.getMaxClaims();
    if (targetClaims >= maxClaims) {
        source.sendFailure(Component.literal(target.getName().getString() + " has reached the maximum of " + maxClaims + " claims."));
        return 0;
    }
}
boolean success = ClaimsSavedData.get(world).claimChunk(target.getUUID(), chunkX, chunkZ);
if (success) {
source.sendSuccess(() -> Component.literal("Chunk claimed for " + target.getName().getString() + " (" + chunkX + ", " + chunkZ + ")."), false);
NameCacheSavedData.get(world).setName(target.getUUID(), target.getName().getString());
return 1;
} else {
UUID owner = ClaimsSavedData.get(world).getOwner(chunkX, chunkZ);
String ownerName = "someone";
if (owner != null) {
    if (source.getServer() != null) {
        ServerPlayer ownerPlayer = source.getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) ownerName = ownerPlayer.getName().getString();
        else ownerName = owner.toString();
    } else {
        ownerName = owner.toString();
    }
}
source.sendFailure(Component.literal("Chunk already claimed by " + ownerName));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /claim NAME."));
return 0;
}
})
)
);

// /unclaim
dispatcher.register(
Commands.literal("unclaim")
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
BlockPos pos = player.blockPosition();
int chunkX = pos.getX() >> 4;
int chunkZ = pos.getZ() >> 4;
ServerLevel world = source.getLevel();
boolean removed = ClaimsSavedData.get(world).unclaimChunk(player.getUUID(), chunkX, chunkZ);
if (removed) {
source.sendSuccess(() -> Component.literal("Chunk unclaimed (" + chunkX + ", " + chunkZ + ")."), false);
return 1;
} else {
source.sendFailure(Component.literal("You do not own this chunk or it's not claimed."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /unclaim."));
return 0;
}
})
.then(Commands.argument("player", EntityArgument.player())
.requires(src -> { Entity ent = src.getEntity(); if (!(ent instanceof ServerPlayer)) return false; ServerPlayer sp = (ServerPlayer) ent; return src.getServer() != null && src.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(sp.getGameProfile())); })
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer operator = source.getPlayerOrException();
ServerPlayer target = EntityArgument.getPlayer(context, "player");
BlockPos pos = operator.blockPosition();
int chunkX = pos.getX() >> 4;
int chunkZ = pos.getZ() >> 4;
ServerLevel world = source.getLevel();
boolean removed = ClaimsSavedData.get(world).unclaimChunk(target.getUUID(), chunkX, chunkZ);
if (removed) {
source.sendSuccess(() -> Component.literal("Chunk unclaimed for " + target.getName().getString() + " (" + chunkX + ", " + chunkZ + ")."), false);
return 1;
} else {
source.sendFailure(Component.literal("Chunk not owned by " + target.getName().getString() + " or not claimed."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /unclaim NAME."));
return 0;
}
})
)
);

// /unclaimall
dispatcher.register(
Commands.literal("unclaimall")
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
ServerLevel world = source.getLevel();
int removed = ClaimsSavedData.get(world).unclaimAll(player.getUUID());
if (removed > 0) {
source.sendSuccess(() -> Component.literal("Unclaimed " + removed + " chunks."), false);
return 1;
} else {
source.sendFailure(Component.literal("You have no claims."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /unclaimall."));
return 0;
}
})
.then(Commands.argument("player", EntityArgument.player())
.requires(src -> { Entity ent = src.getEntity(); if (!(ent instanceof ServerPlayer)) return false; ServerPlayer sp = (ServerPlayer) ent; return src.getServer() != null && src.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(sp.getGameProfile())); })
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer target = EntityArgument.getPlayer(context, "player");
ServerLevel world = source.getLevel();
int removed = ClaimsSavedData.get(world).unclaimAll(target.getUUID());
if (removed > 0) {
source.sendSuccess(() -> Component.literal("Unclaimed " + removed + " chunks for " + target.getName().getString()), false);
return 1;
} else {
source.sendFailure(Component.literal("Player has no claims."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Usage: /unclaimall [player]"));
return 0;
}
})
)
);

// /claims
dispatcher.register(
Commands.literal("claims")
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
ServerLevel world = source.getLevel();
java.util.List<String> claims = ClaimsSavedData.get(world).getClaims(player.getUUID());
				int count = claims.size();
				boolean isOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
				if (isOp) {
					source.sendSuccess(() -> Component.literal("You have " + count + " claims (operator: unlimited)." + (count > 0 ? " Claims: " + String.join(", ", claims) : " No claims.")), false);
				} else {
					int maxClaims = ModConfig.getMaxClaims();
                    int remaining = Math.max(0, maxClaims - count);
					source.sendSuccess(() -> Component.literal("You have " + count + "/" + maxClaims + " claims - " + remaining + " remaining." + (count > 0 ? " Claims: " + String.join(", ", claims) : " No claims.")), false);
				}
				return 1;

} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /claims."));
return 0;
}
})
.then(Commands.argument("player", EntityArgument.player())
.requires(src -> { Entity ent = src.getEntity(); if (!(ent instanceof ServerPlayer)) return false; ServerPlayer sp = (ServerPlayer) ent; return src.getServer() != null && src.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(sp.getGameProfile())); })
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer target = EntityArgument.getPlayer(context, "player");
ServerLevel world = source.getLevel();
java.util.List<String> claims = ClaimsSavedData.get(world).getClaims(target.getUUID());
				int count = claims.size();
				boolean isTargetOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(target.getGameProfile()));
				if (isTargetOp) {
					source.sendSuccess(() -> Component.literal(target.getName().getString() + " has " + count + " claims (operator: unlimited)." + (count > 0 ? " Claims: " + String.join(", ", claims) : " No claims.")), false);
				} else {
					int maxClaims = ModConfig.getMaxClaims();
                    int remaining = Math.max(0, maxClaims - count);
					source.sendSuccess(() -> Component.literal(target.getName().getString() + " has " + count + "/" + maxClaims + " claims - " + remaining + " remaining." + (count > 0 ? " Claims: " + String.join(", ", claims) : " No claims.")), false);
				}
				return 1;

} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Usage: /claims [player]"));
return 0;
}
})
)
);

// /trust
dispatcher.register(
Commands.literal("trust")
.then(Commands.argument("player", EntityArgument.player())
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer owner = source.getPlayerOrException();
ServerPlayer target = EntityArgument.getPlayer(context, "player");
if (owner.getUUID().equals(target.getUUID())) {
source.sendFailure(Component.literal("You cannot trust yourself."));
return 0;
}
ServerLevel world = source.getLevel();
boolean added = TrustsSavedData.get(world).trust(owner.getUUID(), target.getUUID());
if (added) {
source.sendSuccess(() -> Component.literal("Trusted " + target.getName().getString() + "."), false);
return 1;
} else {
source.sendFailure(Component.literal(target.getName().getString() + " is already trusted."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /trust <player>."));
return 0;
}
})
)
);

// /untrust
dispatcher.register(
Commands.literal("untrust")
.then(Commands.argument("player", EntityArgument.player())
.executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer owner = source.getPlayerOrException();
ServerPlayer target = EntityArgument.getPlayer(context, "player");
ServerLevel world = source.getLevel();
boolean removed = TrustsSavedData.get(world).untrust(owner.getUUID(), target.getUUID());
if (removed) {
source.sendSuccess(() -> Component.literal("Untrusted " + target.getName().getString() + "."), false);
return 1;
} else {
source.sendFailure(Component.literal(target.getName().getString() + " was not trusted."));
return 0;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /untrust <player>."));
return 0;
}
})
)
);

// /seeclaims
dispatcher.register(Commands.literal("seeclaims").executes(context -> {
	CommandSourceStack source = context.getSource();
	try {
		ServerPlayer player = source.getPlayerOrException();
		boolean added = SEEING_PLAYERS.add(player.getUUID());
		if (added) {
			// initialize last seen chunk to current player chunk to avoid immediate message
			int pcxInit = player.blockPosition().getX() >> 4;
			int pczInit = player.blockPosition().getZ() >> 4;
			long initKey = (((long)pcxInit) << 32) | (pczInit & 0xffffffffL);
			SEEING_LAST_CHUNK.put(player.getUUID(), initKey);
			source.sendSuccess(() -> Component.literal("Showing claims. Use /unseeclaims to stop."), false);
			return 1;
		} else {
			source.sendFailure(Component.literal("You are already seeing claims. Use /unseeclaims to stop."));
			return 0;
		}
	} catch (CommandSyntaxException e) {
		source.sendFailure(Component.literal("Only players can use /seeclaims."));
		return 0;
	}
}));

// /unseeclaims
dispatcher.register(Commands.literal("unseeclaims").executes(context -> {
	CommandSourceStack source = context.getSource();
	try {
		ServerPlayer player = source.getPlayerOrException();
		boolean removed = SEEING_PLAYERS.remove(player.getUUID());
		if (removed) {
			SEEING_LAST_CHUNK.remove(player.getUUID());
			source.sendSuccess(() -> Component.literal("Stopped showing claims."), false);
			return 1;
		} else {
			source.sendFailure(Component.literal("You were not seeing claims."));
			return 0;
		}
	} catch (CommandSyntaxException e) {
		source.sendFailure(Component.literal("Only players can use /unseeclaims."));
		return 0;
	}
}));

// /home
dispatcher.register(Commands.literal("home").executes(context -> {
CommandSourceStack source = context.getSource();
try {
ServerPlayer player = source.getPlayerOrException();
ServerLevel world = source.getLevel();
BlockPos pos = HomesSavedData.get(world).getHome(player.getUUID());
if (pos == null) {
source.sendFailure(Component.literal("No home set. Use /sethome first."));
return 0;
} else {
player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
source.sendSuccess(() -> Component.literal("Teleported to home."), false);
return 1;
}
} catch (CommandSyntaxException e) {
source.sendFailure(Component.literal("Only players can use /home."));
return 0;
}
}));
});
}
}
