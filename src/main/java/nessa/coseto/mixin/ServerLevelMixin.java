package nessa.coseto.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.random.WeightedList;
import net.minecraft.core.Holder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;

import java.util.UUID;

import nessa.coseto.ClaimsSavedData;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V", at = @At("HEAD"), cancellable = true)
    private void onExplode(Entity entity, DamageSource damageSource, ExplosionDamageCalculator calculator, double x, double y, double z, float radius, boolean createFire, Level.ExplosionInteraction interaction, ParticleOptions particleOptions1, ParticleOptions particleOptions2, WeightedList weightedList, Holder soundHolder, CallbackInfo ci) {
        ServerLevel self = (ServerLevel)(Object)this;

        // Determine explosion owner UUID (if we can)
        UUID ownerUuid = null;
        ServerPlayer ownerPlayer = null;
        if (entity instanceof ServerPlayer) {
            ownerPlayer = (ServerPlayer) entity;
            ownerUuid = ownerPlayer.getUUID();
        } else if (entity instanceof PrimedTnt) {
            net.minecraft.world.entity.LivingEntity owner = ((PrimedTnt) entity).getOwner();
            if (owner instanceof ServerPlayer) {
                ownerPlayer = (ServerPlayer) owner;
                ownerUuid = ownerPlayer.getUUID();
            } else if (owner != null) {
                ownerUuid = owner.getUUID();
            }
        }

        ClaimsSavedData claims = ClaimsSavedData.get(self);

        int chunkMinX = (int)Math.floor((x - radius) / 16.0d);
        int chunkMaxX = (int)Math.floor((x + radius) / 16.0d);
        int chunkMinZ = (int)Math.floor((z - radius) / 16.0d);
        int chunkMaxZ = (int)Math.floor((z + radius) / 16.0d);

        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                UUID claimOwner = claims.getOwner(cx, cz);
                if (claimOwner != null) {
                    boolean allowed = false;
                    if (ownerUuid != null && claimOwner.equals(ownerUuid)) {
                        allowed = true;
                    }
                    if (!allowed && ownerPlayer != null) {
                        try {
                            if (self.getServer() != null && self.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(ownerPlayer.getGameProfile()))) {
                                allowed = true;
                            }
                        } catch (Exception ex) {
                            // ignore
                        }
                    }
                    if (!allowed) {
                        // Cancel the entire explosion if it would affect any claimed chunk not allowed.
                        ci.cancel();
                        return;
                    }
                }
            }
        }
    }
}
