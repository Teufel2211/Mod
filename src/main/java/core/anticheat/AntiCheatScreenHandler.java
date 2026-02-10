package core.anticheat;

import core.config.ConfigManager;
import core.util.Safe;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
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
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

public final class AntiCheatScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int LIST_ROWS = 5;
    private static final int LIST_SIZE = LIST_ROWS * COLS;

    private static final int BTN_PREV = 45;
    private static final int BTN_NEXT = 53;
    private static final int BTN_HELP = 49;

    private static final int BTN_RELOAD = 46;
    private static final int BTN_CHECK = 47;
    private static final int BTN_RESET = 48;
    private static final int BTN_IGNORE_ADD = 50;
    private static final int BTN_IGNORE_LIST = 51;
    private static final int BTN_CLOSE = 52;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;
    private final ServerPlayerEntity viewer;
    private final int page;

    public AntiCheatScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity viewer, int page) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        this.viewer = viewer;
        this.page = Math.max(0, page);
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.clear();

        boolean enabled = ConfigManager.getConfig() != null
            && ConfigManager.getConfig().antiCheat != null
            && ConfigManager.getConfig().antiCheat.enableAntiCheat;

        List<String> ignored = ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat != null
            ? ConfigManager.getConfig().antiCheat.ignoredPlayers
            : java.util.List.of();

        int totalPages = Math.max(1, (int) Math.ceil(ignored.size() / (double) LIST_SIZE));
        int safePage = Math.min(page, totalPages - 1);

        for (int i = 0; i < LIST_SIZE; i++) {
            int index = safePage * LIST_SIZE + i;
            if (index >= ignored.size()) break;
            inv.setStack(i, ignoredEntry(ignored.get(index)));
        }

        inv.setStack(BTN_PREV, navButton(Items.ARROW, "§ePrev Page", safePage > 0));
        inv.setStack(BTN_NEXT, navButton(Items.ARROW, "§eNext Page", safePage < totalPages - 1));

        inv.setStack(BTN_HELP, named(Items.BOOK, enabled ? "§aAnti-cheat enabled" : "§cAnti-cheat disabled"));
        inv.setStack(BTN_RELOAD, named(Items.REPEATER, "§eReload Config"));
        inv.setStack(BTN_CHECK, named(Items.SPYGLASS, "§bCheck Player"));
        inv.setStack(BTN_RESET, named(Items.LAVA_BUCKET, "§6Reset Player"));
        inv.setStack(BTN_IGNORE_ADD, named(Items.NAME_TAG, "§dIgnore Add/Remove"));
        inv.setStack(BTN_IGNORE_LIST, named(Items.PAPER, "§7Ignored: " + ignored.size()));
        inv.setStack(BTN_CLOSE, named(Items.BARRIER, "Close"));
    }

    private static ItemStack ignoredEntry(String uuidString) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        String shortId = uuidString != null && uuidString.length() >= 8 ? uuidString.substring(0, 8) : String.valueOf(uuidString);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7Ignored: §f" + shortId));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Full: §8" + uuidString),
            Text.literal("§7Use §e/ac ignore remove <player>§7 to unignore")
        )));
        return stack;
    }

    private static ItemStack navButton(Item item, String name, boolean enabled) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name + (enabled ? "" : " §8(disabled)")));
        return stack;
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private static Text suggest(String label, String command, String hover) {
        return Text.literal(label)
            .setStyle(Text.empty().getStyle()
                .withColor(Formatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
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

        Safe.run("AntiCheatScreenHandler.click", () -> {
            if (slotIndex == BTN_CLOSE) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (slotIndex == BTN_PREV) {
                if (page > 0) AntiCheatGui.open(serverPlayer, page - 1);
                return;
            }
            if (slotIndex == BTN_NEXT) {
                List<String> ignored = ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat != null
                    ? ConfigManager.getConfig().antiCheat.ignoredPlayers
                    : java.util.List.of();
                int totalPages = Math.max(1, (int) Math.ceil(ignored.size() / (double) LIST_SIZE));
                if (page < totalPages - 1) AntiCheatGui.open(serverPlayer, page + 1);
                return;
            }
            if (slotIndex == BTN_HELP) {
                var self = AntiCheatManager.getViolations(serverPlayer.getUuid());
                if (self != null) {
                    serverPlayer.sendMessage(Text.literal("§6Your violations: §f" + self.getViolationLevels()));
                } else {
                    serverPlayer.sendMessage(Text.literal("§6Your violations: §fNone"));
                }
                serverPlayer.sendMessage(suggest("§7- Check: ", "/ac check <player>", "Check a player's violations"));
                serverPlayer.sendMessage(suggest("§7- Reset: ", "/ac reset <player>", "Reset a player's violations"));
                serverPlayer.sendMessage(suggest("§7- Ignore: ", "/ac ignore add <player>", "Ignore a player"));
                return;
            }
            if (slotIndex == BTN_RELOAD) {
                ConfigManager.reloadConfig();
                serverPlayer.sendMessage(Text.literal("§aConfig reloaded."));
                return;
            }
            if (slotIndex == BTN_CHECK) {
                serverPlayer.sendMessage(Text.literal("§eCheck player:"));
                serverPlayer.sendMessage(suggest("§7Click to fill: ", "/ac check ", "Type player name"));
                return;
            }
            if (slotIndex == BTN_RESET) {
                serverPlayer.sendMessage(Text.literal("§eReset violations:"));
                serverPlayer.sendMessage(suggest("§7Click to fill: ", "/ac reset ", "Type player name"));
                return;
            }
            if (slotIndex == BTN_IGNORE_ADD) {
                serverPlayer.sendMessage(Text.literal("§eIgnore controls:"));
                serverPlayer.sendMessage(suggest("§7- Add: ", "/ac ignore add ", "Type player name"));
                serverPlayer.sendMessage(suggest("§7- Remove: ", "/ac ignore remove ", "Type player name"));
                serverPlayer.sendMessage(suggest("§7- List: ", "/ac ignore list", "List ignored players"));
                return;
            }
            if (slotIndex == BTN_IGNORE_LIST) {
                List<String> ignored = ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat != null
                    ? ConfigManager.getConfig().antiCheat.ignoredPlayers
                    : java.util.List.of();
                serverPlayer.sendMessage(Text.literal("§6Ignored players (" + ignored.size() + "):"));
                for (String uuid : ignored) {
                    serverPlayer.sendMessage(Text.literal("§7- " + uuid));
                }
                return;
            }
            if (slotIndex >= 0 && slotIndex < LIST_SIZE) {
                List<String> ignored = ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat != null
                    ? ConfigManager.getConfig().antiCheat.ignoredPlayers
                    : java.util.List.of();
                int idx = page * LIST_SIZE + slotIndex;
                if (idx >= ignored.size()) return;
                String uuidString = ignored.get(idx);
                String suggestedName = resolveName(serverPlayer, uuidString);
                serverPlayer.sendMessage(Text.literal("§eUnignore:"));
                serverPlayer.sendMessage(suggest("§7Click to fill: ", "/ac ignore remove " + suggestedName, "Remove from ignore list"));
            }
        });
    }

    private static String resolveName(ServerPlayerEntity viewer, String uuidString) {
        try {
            UUID id = UUID.fromString(uuidString);
            var online = viewer.getCommandSource().getServer().getPlayerManager().getPlayer(id);
            return online != null ? online.getName().getString() : "<player>";
        } catch (Exception ignored) {
            return "<player>";
        }
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
