package core.claims;

import core.map.WorldMapGui;
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

public final class ClaimsScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int BTN_CLAIM = 20;
    private static final int BTN_UNCLAIM = 21;
    private static final int BTN_INFO = 23;
    private static final int BTN_TRUST = 29;
    private static final int BTN_UNTRUST = 30;
    private static final int BTN_MAP = 31;
    private static final int BTN_CLOSE = 49;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;

    public ClaimsScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.setStack(BTN_CLAIM, named(Items.GOLDEN_SHOVEL, "§6Claim Chunk"));
        inv.setStack(BTN_UNCLAIM, named(Items.IRON_SHOVEL, "§eUnclaim Chunk"));
        inv.setStack(BTN_INFO, named(Items.BOOK, "§bClaim Info"));
        inv.setStack(BTN_TRUST, named(Items.LIME_DYE, "§aTrust Player"));
        inv.setStack(BTN_UNTRUST, named(Items.RED_DYE, "§cUntrust Player"));
        inv.setStack(BTN_MAP, named(Items.MAP, "§eWorld Map"));
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

        Safe.run("ClaimsScreenHandler.click", () -> {
            if (slotIndex == BTN_CLOSE) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (slotIndex == BTN_MAP) {
                WorldMapGui.open(serverPlayer, 0);
                return;
            }
            if (slotIndex == BTN_CLAIM) {
                serverPlayer.sendMessage(runCmd("Claim chunk", "/claim"), false);
                return;
            }
            if (slotIndex == BTN_UNCLAIM) {
                serverPlayer.sendMessage(runCmd("Unclaim chunk", "/unclaim"), false);
                return;
            }
            if (slotIndex == BTN_INFO) {
                serverPlayer.sendMessage(runCmd("Claim info", "/claim info"), false);
                return;
            }
            if (slotIndex == BTN_TRUST) {
                serverPlayer.sendMessage(Text.literal("§eUse: §f/trust <player>"), false);
                return;
            }
            if (slotIndex == BTN_UNTRUST) {
                serverPlayer.sendMessage(Text.literal("§eUse: §f/untrust <player>"), false);
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
