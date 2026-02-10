package core.menu;

import core.anticheat.AntiCheatGui;
import core.claims.ClaimsGui;
import core.clans.ClansGui;
import core.economy.EconomyMenuGui;
import core.map.MapMenuGui;
import core.util.Safe;
import core.bounty.BountyGui;
import core.wanted.WantedGui;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class CoreMenuScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int BTN_ECONOMY = 20;
    private static final int BTN_CLAIMS = 21;
    private static final int BTN_CLANS = 22;
    private static final int BTN_BOUNTY = 23;
    private static final int BTN_WANTED = 24;
    private static final int BTN_MAP = 29;
    private static final int BTN_ANTICHEAT = 30;
    private static final int BTN_CLOSE = 49;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;

    public CoreMenuScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.setStack(BTN_ECONOMY, named(Items.EMERALD, "§aEconomy"));
        inv.setStack(BTN_CLAIMS, named(Items.GRASS_BLOCK, "§6Claims"));
        inv.setStack(BTN_CLANS, named(Items.WHITE_BANNER, "§bClans"));
        inv.setStack(BTN_BOUNTY, named(Items.PLAYER_HEAD, "§cBounty"));
        inv.setStack(BTN_WANTED, named(Items.REDSTONE, "§4Wanted"));
        inv.setStack(BTN_MAP, named(Items.MAP, "§eMap"));
        inv.setStack(BTN_ANTICHEAT, named(Items.IRON_SWORD, "§dAnti-Cheat"));
        inv.setStack(BTN_CLOSE, named(Items.BARRIER, "Close"));
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private void addSlots(PlayerInventory playerInventory) {
        // GUI inventory (locked)
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = col + row * COLS;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlot(new LockedSlot(inv, index, x, y));
            }
        }

        // Player inventory
        int playerInvY = 18 + ROWS * 18 + 13;
        int hotbarY = playerInvY + 58;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int x = 8 + col * 18;
                int y = playerInvY + row * 18;
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, x, y));
            }
        }

        for (int col = 0; col < 9; col++) {
            int x = 8 + col * 18;
            this.addSlot(new Slot(playerInventory, col, x, hotbarY));
        }
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        Safe.run("CoreMenuScreenHandler.click", () -> {
            if (slotIndex == BTN_CLOSE) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (slotIndex == BTN_ECONOMY) {
                EconomyMenuGui.open(serverPlayer);
                return;
            }
            if (slotIndex == BTN_CLAIMS) {
                ClaimsGui.open(serverPlayer);
                return;
            }
            if (slotIndex == BTN_CLANS) {
                ClansGui.open(serverPlayer);
                return;
            }
            if (slotIndex == BTN_BOUNTY) {
                BountyGui.open(serverPlayer, 0);
                return;
            }
            if (slotIndex == BTN_WANTED) {
                WantedGui.open(serverPlayer, 0);
                return;
            }
            if (slotIndex == BTN_MAP) {
                MapMenuGui.open(serverPlayer);
                return;
            }
            if (slotIndex == BTN_ANTICHEAT) {
                AntiCheatGui.open(serverPlayer, 0);
            }
        });
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    private static final class LockedSlot extends Slot {
        private LockedSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) {
            return false;
        }
    }
}

