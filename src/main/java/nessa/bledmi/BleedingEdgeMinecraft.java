package nessa.bledmi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
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

import java.util.UUID;

public class BleedingEdgeMinecraft implements ModInitializer {
	public static final String MOD_ID = "bleeding-edge-minecraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean isAllowed(ServerPlayer player, ServerLevel world, BlockPos pos) {
		UUID owner = ClaimsSavedData.get(world).getOwner(pos.getX() >> 4, pos.getZ() >> 4);
		if (owner == null) return true;
		return owner.equals(player.getUUID());
	}

	// Homes are persisted via HomesSavedData (world data). Use HomesSavedData.get(serverLevel) to access.

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world! Registering commands...");

		// Register claim protection event handlers
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

		// TODO: Handle entity damage prevention via ServerLivingEntityEvents.ALLOW_DAMAGE once mappings/signatures are verified.
		// The previous implementation attempted to block damage from non-owners (including projectiles),
		// but method/accessor names vary between Minecraft mappings. Reintroduce when resolved.

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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

			dispatcher.register(Commands.literal("claim").executes(context -> {
				CommandSourceStack source = context.getSource();
				try {
					ServerPlayer player = source.getPlayerOrException();
					BlockPos pos = player.blockPosition();
					int chunkX = pos.getX() >> 4;
					int chunkZ = pos.getZ() >> 4;
					ServerLevel world = source.getLevel();
					boolean success = ClaimsSavedData.get(world).claimChunk(player.getUUID(), chunkX, chunkZ);
					if (success) {
						source.sendSuccess(() -> Component.literal("Chunk claimed (" + chunkX + ", " + chunkZ + ")."), false);
						return 1;
					} else {
						UUID owner = ClaimsSavedData.get(world).getOwner(chunkX, chunkZ);
						source.sendFailure(Component.literal("Chunk already claimed by " + (owner != null ? owner.toString() : "someone")));
						return 0;
					}
				} catch (CommandSyntaxException e) {
					source.sendFailure(Component.literal("Only players can use /claim."));
					return 0;
				}
			}));

			dispatcher.register(Commands.literal("unclaim").executes(context -> {
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
			}));

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