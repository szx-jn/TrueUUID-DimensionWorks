package cn.alini.trueuuid.mixin.server;

import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.server.PlayerDataMigration;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMigrationMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void trueuuid$migrateVerifiedOfflineData(Connection connection, ServerPlayer player, CallbackInfo ci) {
        // MSL 的 authlib-injector 服务端模式走原版 online-mode 登录，不经过 TrueUUID 自定义握手。
        // placeNewPlayer 在玩家数据加载前执行，此时 GameProfile 已由 Yggdrasil hasJoined 验证。
        if (!this.server.usesAuthentication()) return;

        var profile = player.getGameProfile();
        String name = profile.getName();
        if (!PlayerDataMigration.needsOfflineUpgrade(this.server, name, profile.getId())) return;

        try {
            PlayerDataMigration.migrateOfflineToVerified(this.server, name, profile.getId());
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] online-mode verified player data inherited: player="
                        + name + ", uuid=" + profile.getId());
            }
        } catch (java.io.IOException ex) {
            System.err.println("[TrueUUID] 玩家数据自动继承失败，拒绝登录以保护存档: player="
                    + name + ", uuid=" + profile.getId() + ", error=" + ex);
            connection.disconnect(Component.literal(
                    "玩家数据自动继承失败，已取消进入以保护存档。请联系管理员检查 trueuuid-backups。"
            ));
            ci.cancel();
        }
    }
}
