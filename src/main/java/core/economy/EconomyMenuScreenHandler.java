package core.economy;

import core.util.Safe;
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
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

public final class EconomyMenuScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int BTN_BALANCE = 20;
    private static final int BTN_PAY = 21;
    private static final int BTN_SHOP = 23;
    private static final int BTN_AUCTION_LIST = 29;
    private static final int BTN_AUCTION_SELL = 30;
    private static final int BTN_AUCTION_CLAIM = 31;
    private static final int BTN_CLOSE = 49;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;

    public EconomyMenuScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.setStack(BTN_BALANCE, named(Items.GOLD_INGOT, "§eBalance"));
        inv.setStack(BTN_PAY, named(Items.PAPER, "§aPay"));
        inv.setStack(BTN_SHOP, named(Items.EMERALD, "§aShop"));
        inv.setStack(BTN_AUCTION_LIST, named(Items.CHEST, "§6Auctions: List"));
        inv.setStack(BTN_AUCTION_SELL, named(Items.ANVIL, "§6Auctions: Sell"));
        inv.setStack(BTN_AUCTION_CLAIM, named(Items.HOPPER, "§6Auctions: Claim"));
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

        Safe.run("EconomyMenuScreenHandler.click", () -> {
            if (slotIndex == BTN_CLOSE) {
                serverPlayer.closeHandledScreen();
                return;
            }

            if (slotIndex == BTN_SHOP) {
                ShopGui.open(serverPlayer);
                return;
            }

            if (slotIndex == BTN_BALANCE) {
                serverPlayer.sendMessage(runCmd("Balance", "/balance"), false);
                return;
            }

            if (slotIndex == BTN_PAY) {
                serverPlayer.sendMessage(Text.literal("§eUse: §f/pay <player> <amount>"), false);
                return;
            }

            if (slotIndex == BTN_AUCTION_LIST) {
                serverPlayer.sendMessage(runCmd("Auctions list", "/auction list"), false);
                return;
            }

            if (slotIndex == BTN_AUCTION_CLAIM) {
                serverPlayer.sendMessage(runCmd("Auctions claim", "/auction claim"), false);
                return;
            }

            if (slotIndex == BTN_AUCTION_SELL) {
                serverPlayer.sendMessage(Text.literal("§eUse: §f/auction sell <startingBid> [buyoutPrice]"), false);
            }
        });
    }

    private static Text runCmd(String label, String command) {
        return Text.literal("§e" + label + ": §f" + command)
            .setStyle(Text.empty().getStyle()
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to run"))));
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
