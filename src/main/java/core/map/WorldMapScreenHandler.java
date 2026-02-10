package core.map;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorldMapScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 44; // inclusive (4 rows)
    private static final int PAGE_SIZE = (CONTENT_END - CONTENT_START) + 1;

    private final Inventory inv;
    private final ServerPlayerEntity viewer;
    private final int page;
    private final List<String> slotWaypointNames;

    public WorldMapScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity viewer, int page) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.inv = new SimpleInventory(SIZE);
        this.viewer = viewer;
        this.page = Math.max(0, page);
        this.slotWaypointNames = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) slotWaypointNames.add(null);

        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        // Controls row
        inv.setStack(0, named(Items.MAP, "Waypoints"));
        inv.setStack(1, named(Items.COMPASS, "Teleport: click a waypoint"));
        inv.setStack(7, named(Items.ARROW, "Previous Page"));
        inv.setStack(8, named(Items.ARROW, "Next Page"));

        // Content
        Map<String, MapManager.Waypoint> visible = (viewer != null) ? MapManager.getVisibleWaypoints(viewer) : MapManager.getWaypoints();
        List<Map.Entry<String, MapManager.Waypoint>> sorted = new ArrayList<>(visible.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

        int start = page * PAGE_SIZE;
        int end = Math.min(sorted.size(), start + PAGE_SIZE);

        int slot = CONTENT_START;
        for (int i = start; i < end; i++) {
            var entry = sorted.get(i);
            String name = entry.getKey();
            MapManager.Waypoint wp = entry.getValue();
            if (wp == null || wp.pos == null || wp.dimension == null) continue;

            String title = name + " (" + wp.pos.getX() + ", " + wp.pos.getY() + ", " + wp.pos.getZ() + ")";
            ItemStack icon = named(Items.PAPER, title);
            icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Dimension: " + wp.dimension),
                Text.literal("Type: " + (wp.type == null ? "other" : wp.type)),
                Text.literal("Visibility: " + (wp.visibility == null ? "private" : wp.visibility))
            )));

            inv.setStack(slot, icon);
            slotWaypointNames.set(slot, name);
            slot++;
            if (slot > CONTENT_END) break;
        }

        // Footer
        inv.setStack(49, named(Items.BARRIER, "Close"));
        inv.setStack(53, named(Items.PAPER, "Page " + (page + 1)));
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private void addSlots(PlayerInventory playerInventory) {
        // GUI inventory
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
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        // Top controls and footer
        if (slotIndex == 7) {
            WorldMapGui.open(serverPlayer, page - 1);
            return;
        }
        if (slotIndex == 8) {
            WorldMapGui.open(serverPlayer, page + 1);
            return;
        }
        if (slotIndex == 49) {
            serverPlayer.closeHandledScreen();
            return;
        }

        // Waypoint content
        if (slotIndex >= CONTENT_START && slotIndex <= CONTENT_END) {
            String wpName = slotWaypointNames.get(slotIndex);
            if (wpName == null) return;

            MapManager.Waypoint wp = MapManager.getWaypoint(wpName);
            if (wp == null) return;
            if (!MapPermissions.canViewWaypoint(serverPlayer, wp)) return;

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(wp.dimension));
            ServerWorld currentWorld = (ServerWorld) serverPlayer.getEntityWorld();
            ServerWorld targetWorld = currentWorld.getServer().getWorld(worldKey);
            if (targetWorld == null) {
                serverPlayer.sendMessage(Text.literal("§cDimension '" + wp.dimension + "' is not loaded."), false);
                return;
            }

            serverPlayer.teleport(targetWorld, wp.pos.getX() + 0.5, wp.pos.getY(), wp.pos.getZ() + 0.5, Set.<PositionFlag>of(), 0F, 0F, false);
            serverPlayer.sendMessage(Text.literal("§aTeleported to waypoint '" + wpName + "'."), false);
            return;
        }

        super.onSlotClick(slotIndex, button, actionType, player);
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
