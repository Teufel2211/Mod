package core.bounty;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class BountyGui {
    private BountyGui() {}

    public static void open(ServerPlayerEntity player, int page) {
        if (player == null) return;
        int safePage = Math.max(0, page);
        player.openHandledScreen(new Factory(player, safePage));
    }

    private static final class Factory implements NamedScreenHandlerFactory {
        private final ServerPlayerEntity viewer;
        private final int page;

        private Factory(ServerPlayerEntity viewer, int page) {
            this.viewer = viewer;
            this.page = page;
        }

        @Override
        public Text getDisplayName() {
            return Text.literal("Bounty Board");
        }

        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
            return new BountyScreenHandler(syncId, playerInventory, viewer, page);
        }
    }
}

