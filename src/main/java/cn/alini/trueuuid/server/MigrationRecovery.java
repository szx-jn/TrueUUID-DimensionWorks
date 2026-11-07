package cn.alini.trueuuid.server;

import cn.alini.trueuuid.Trueuuid;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/** Resolve a stopped transaction before FTB/vanilla read the world. */
@Mod.EventBusSubscriber(modid = Trueuuid.MODID)
public final class MigrationRecovery {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeWorldLoad(ServerAboutToStartEvent event) {
        try {
            MigrationTransaction.recover(PlayerDataMigration.root(event.getServer()));
        } catch (IOException failure) {
            LogUtils.getLogger().error("[TrueUUID] 迁移恢复失败，停止启动以保全玩家数据。", failure);
            throw new IllegalStateException("TrueUUID 迁移恢复失败，请保留原档及 trueuuid-backups。", failure);
        }
    }

    private MigrationRecovery() {}
}
