package nessa.bledmi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BleedingEdgeMinecraft implements ModInitializer {
	public static final String MOD_ID = "bleeding-edge-minecraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Simple in-memory store for player homes (UUID -> BlockPos). Not persisted across restarts.
	public static final ConcurrentHashMap<UUID, BlockPos> HOMES = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world! Registering commands...");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("sethome").executes(context -> {
				CommandSourceStack source = context.getSource();
				try {
					ServerPlayer player = source.getPlayerOrException();
					BlockPos pos = player.blockPosition();
					HOMES.put(player.getUUID(), pos);
					source.sendSuccess(() -> Component.literal("Home set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
				} catch (CommandSyntaxException e) {
					source.sendFailure(Component.literal("Only players can use /sethome."));
				}
				return 1;
			}));

			dispatcher.register(Commands.literal("home").executes(context -> {
				CommandSourceStack source = context.getSource();
				try {
					ServerPlayer player = source.getPlayerOrException();
					BlockPos pos = HOMES.get(player.getUUID());
					if (pos == null) {
						source.sendFailure(Component.literal("No home set. Use /sethome first."));
						return 0;
					} else {
						ServerLevel world = source.getLevel();
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