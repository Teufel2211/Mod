package core.map;

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

public final class MapMenuScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int BTN_WORLDMAP = 20;
    private static final int BTN_WAYPOINT_LIST = 22;
    private static final int BTN_WAYPOINT_ADD = 23;
    private static final int BTN_CLOSE = 49;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;

    public MapMenuScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.setStack(BTN_WORLDMAP, named(Items.MAP, "§eWorld Map"));
        inv.setStack(BTN_WAYPOINT_LIST, named(Items.PAPER, "§bWaypoints: List"));
        inv.setStack(BTN_WAYPOINT_ADD, named(Items.COMPASS, "§aWaypoints: Add"));
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

        Safe.run("MapMenuScreenHandler.click", () -> {
            if (slotIndex == BTN_CLOSE) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (slotIndex == BTN_WORLDMAP) {
                WorldMapGui.open(serverPlayer, 0);
                return;
            }
            if (slotIndex == BTN_WAYPOINT_LIST) {
                serverPlayer.sendMessage(runCmd("Waypoints list", "/map waypoint list"), false);
                return;
            }
            if (slotIndex == BTN_WAYPOINT_ADD) {
                serverPlayer.sendMessage(Text.literal("§eUse: §f/map waypoint add <name> <x> <y> <z> <type> <visibility>"), false);
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
