package core.economy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class EconomyMenuGui {
    private EconomyMenuGui() {}

    public static void open(ServerPlayerEntity player) {
        if (player == null) return;
        player.openHandledScreen(new Factory());
    }

    private static final class Factory implements NamedScreenHandlerFactory {
        @Override
        public Text getDisplayName() {
            return Text.literal("Economy");
        }

        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
            return new EconomyMenuScreenHandler(syncId, playerInventory);
        }
    }
}

