package core.bounty;

import core.economy.EconomyManager;
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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BountyScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SIZE = ROWS * COLS;

    private static final int LIST_ROWS = 5;
    private static final int LIST_SIZE = LIST_ROWS * COLS;

    private static final int BTN_PREV = 45;
    private static final int BTN_NEXT = 53;
    private static final int BTN_HELP = 49;

    private final Inventory inv = new SimpleInventory(SIZE);
    private final PlayerInventory playerInventory;
    private final ServerPlayerEntity viewer;
    private final int page;
    private final List<Map.Entry<UUID, Double>> sorted;

    public BountyScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity viewer, int page) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;
        this.viewer = viewer;
        this.page = Math.max(0, page);
        this.sorted = BountyManager.getAllBounties().entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
            .toList();
        populate();
        addSlots(playerInventory);
    }

    private void populate() {
        inv.clear();
        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) LIST_SIZE));
        int safePage = Math.min(page, totalPages - 1);

        for (int i = 0; i < LIST_SIZE; i++) {
            int index = safePage * LIST_SIZE + i;
            if (index >= sorted.size()) break;
            UUID target = sorted.get(index).getKey();
            double amount = sorted.get(index).getValue();
            inv.setStack(i, bountyEntry(target, amount));
        }

        inv.setStack(BTN_PREV, navButton(Items.ARROW, "§ePrev Page", safePage > 0));
        inv.setStack(BTN_NEXT, navButton(Items.ARROW, "§eNext Page", safePage < totalPages - 1));
        inv.setStack(BTN_HELP, named(Items.BOOK, "§bHow to Place a Bounty"));

        inv.setStack(46, named(Items.GOLD_NUGGET, "§a/bounty set <player> <amount>"));
        inv.setStack(47, named(Items.GOLD_INGOT, "§a/bounty list"));
        inv.setStack(48, named(Items.REDSTONE, "§7Click a player to get a command hint"));
        inv.setStack(50, named(Items.PAPER, "§7Top bounties are shown"));
        inv.setStack(51, named(Items.CLOCK, "§7Rewards pay out on kill"));
        inv.setStack(52, named(Items.BARRIER, "Close"));
    }

    private static ItemStack bountyEntry(UUID target, double amount) {
        String who = shortUuid(target);
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c" + who + " §7- §a" + EconomyManager.formatCurrency(amount)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Click to get a /bounty set hint"),
            Text.literal("§7You can also use /bounty list")
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

    private static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
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

        Safe.run("BountyScreenHandler.click", () -> {
            if (slotIndex == 52) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (slotIndex == BTN_PREV) {
                if (page > 0) BountyGui.open(serverPlayer, page - 1);
                return;
            }
            if (slotIndex == BTN_NEXT) {
                int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) LIST_SIZE));
                if (page < totalPages - 1) BountyGui.open(serverPlayer, page + 1);
                return;
            }
            if (slotIndex == BTN_HELP) {
                serverPlayer.sendMessage(Text.literal("§6Bounty help:"));
                serverPlayer.sendMessage(suggest("§7- Place: ", "/bounty set <player> <amount>", "Place a bounty"));
                serverPlayer.sendMessage(suggest("§7- List: ", "/bounty list", "List bounties"));
                return;
            }
            if (slotIndex >= 0 && slotIndex < LIST_SIZE) {
                int index = page * LIST_SIZE + slotIndex;
                if (index >= sorted.size()) return;
                UUID target = sorted.get(index).getKey();
                String targetName = resolveName(serverPlayer, target);
                serverPlayer.sendMessage(Text.literal("§ePlace bounty on §f" + targetName + "§e:"));
                serverPlayer.sendMessage(suggest("§7Click to fill: ", "/bounty set " + targetName + " 100", "Edit amount as needed"));
            }
        });
    }

    private static String resolveName(ServerPlayerEntity viewer, UUID target) {
        var online = viewer.getCommandSource().getServer().getPlayerManager().getPlayer(target);
        return online != null ? online.getName().getString() : shortUuid(target);
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
