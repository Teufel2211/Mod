package core.economy;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class ShopScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int COLS = 9;
    private static final int SHOP_SIZE = ROWS * COLS;

    private static final int DISPLAY_SIZE = 45; // first 5 rows

    private static final int BTN_CATEGORIES = 45;
    private static final int BTN_SELL = 46;
    private static final int BTN_QTY_MINUS = 47;
    private static final int BTN_QTY_PLUS_1 = 48;
    private static final int BTN_QTY_PLUS_16 = 49;
    private static final int BTN_QTY_PLUS_64 = 50;
    private static final int BTN_PREV = 51;
    private static final int BTN_NEXT = 52;
    private static final int BTN_CLOSE = 53;

    private static final int CONFIRM_ITEM = 22;
    private static final int CONFIRM_YES = 30;
    private static final int CONFIRM_NO = 32;

    private enum Mode { CATEGORIES, ITEMS, CONFIRM, SELL }

    private final SimpleInventory guiInventory = new SimpleInventory(SHOP_SIZE);
    private final List<String> slotTokens = new ArrayList<>(SHOP_SIZE);
    private final PlayerInventory playerInventory;

    private Mode mode = Mode.CATEGORIES;
    private String selectedCategory = "General";
    private int page = 0;
    private int quantity = 1;

    private String pendingItemId;
    private int pendingQuantity;

    public ShopScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.playerInventory = playerInventory;

        for (int i = 0; i < SHOP_SIZE; i++) {
            slotTokens.add(null);
        }

        addShopSlots();
        addPlayerSlots(playerInventory);
        render();
    }

    private void addShopSlots() {
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = col + row * COLS;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlot(new GuiSlot(guiInventory, index, x, y));
            }
        }
    }

    private void addPlayerSlots(PlayerInventory playerInventory) {
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

    private void render() {
        clearDisplay();

        switch (mode) {
            case CATEGORIES -> renderCategories();
            case ITEMS -> renderItems();
            case CONFIRM -> renderConfirm();
            case SELL -> renderSell();
        }

        renderControls();
        sendContentUpdates();
    }

    private void clearDisplay() {
        for (int i = 0; i < SHOP_SIZE; i++) {
            if (i >= DISPLAY_SIZE && i < SHOP_SIZE) {
                // keep sell items in SELL mode, but clear controls always
                guiInventory.setStack(i, ItemStack.EMPTY);
                slotTokens.set(i, null);
                continue;
            }

            if (mode == Mode.SELL) {
                // In SELL mode, the display area is the drop zone; don't wipe it.
                continue;
            }

            guiInventory.setStack(i, ItemStack.EMPTY);
            slotTokens.set(i, null);
        }
    }

    private void renderCategories() {
        List<String> categories = ShopManager.getCategories();
        if (categories.isEmpty()) {
            categories = List.of("General");
        }

        int slot = 0;
        for (String category : categories) {
            if (slot >= DISPLAY_SIZE) break;
            ItemStack icon = categoryIcon(category);
            icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6" + category));
            guiInventory.setStack(slot, icon);
            slotTokens.set(slot, "cat:" + category);
            slot++;
        }
    }

    private void renderItems() {
        ServerPlayerEntity viewer = viewer();
        Map<String, ShopManager.ShopItem> items = ShopManager.getShopItems();
        List<ShopManager.ShopItem> filtered = items.values().stream()
            .filter(i -> i != null && i.itemId != null)
            .filter(i -> i.category != null && i.category.equalsIgnoreCase(selectedCategory))
            .sorted(Comparator.comparing(i -> i.itemId))
            .collect(Collectors.toList());

        int pageSize = DISPLAY_SIZE;
        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * pageSize;
        int end = Math.min(filtered.size(), start + pageSize);

        int slot = 0;
        for (int i = start; i < end; i++) {
            ShopManager.ShopItem entry = filtered.get(i);
            if (slot >= DISPLAY_SIZE) break;

            ItemStack display;
            boolean locked = viewer != null && !hasMinPermissionLevel(viewer, entry.minPermissionLevel);
            if (locked) {
                display = new ItemStack(Items.BARRIER);
                display.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§cLocked §7(OP " + entry.minPermissionLevel + "+)"));
            } else {
                Item item = Registries.ITEM.get(Identifier.of(entry.itemId));
                display = new ItemStack(item, 1);
                String stockText = entry.stock < 0 ? "∞" : Integer.toString(entry.stock);
                String priceText = EconomyManager.formatCurrencyCompact(BigDecimal.valueOf(entry.buyPrice), EconomyManager.Currency.COINS);
                display.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + display.getName().getString() + " §7[" + stockText + "] §a" + priceText));
            }

            guiInventory.setStack(slot, display);
            slotTokens.set(slot, "item:" + entry.itemId);
            slot++;
        }
    }

    private void renderConfirm() {
        if (pendingItemId == null || pendingQuantity <= 0) {
            mode = Mode.ITEMS;
            render();
            return;
        }

        ShopManager.ShopItem item = ShopManager.getShopItem(pendingItemId);
        if (item == null) {
            mode = Mode.ITEMS;
            render();
            return;
        }

        Item mcItem = Registries.ITEM.get(Identifier.of(pendingItemId));
        ItemStack center = new ItemStack(mcItem, 1);
        BigDecimal totalCost = BigDecimal.valueOf(item.buyPrice).multiply(BigDecimal.valueOf(pendingQuantity));
        center.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + center.getName().getString() + " x" + pendingQuantity + " §a" + EconomyManager.formatCurrency(totalCost)));
        guiInventory.setStack(CONFIRM_ITEM, center);
        slotTokens.set(CONFIRM_ITEM, "confirm:item");

        ItemStack yes = new ItemStack(Items.LIME_WOOL);
        yes.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§aConfirm Purchase"));
        guiInventory.setStack(CONFIRM_YES, yes);
        slotTokens.set(CONFIRM_YES, "confirm:yes");

        ItemStack no = new ItemStack(Items.RED_WOOL);
        no.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§cCancel"));
        guiInventory.setStack(CONFIRM_NO, no);
        slotTokens.set(CONFIRM_NO, "confirm:no");
    }

    private void renderSell() {
        // Sell mode uses the display area as a drop zone. Control row provides actions.
    }

    private void renderControls() {
        ItemStack categories = new ItemStack(Items.MAP);
        categories.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6Categories"));
        guiInventory.setStack(BTN_CATEGORIES, categories);
        slotTokens.set(BTN_CATEGORIES, "btn:categories");

        if (mode == Mode.SELL) {
            ItemStack sellNow = new ItemStack(Items.EMERALD);
            sellNow.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§aSell Now"));
            guiInventory.setStack(BTN_SELL, sellNow);
            slotTokens.set(BTN_SELL, "btn:sellnow");
        } else {
            ItemStack sell = new ItemStack(Items.HOPPER);
            sell.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§eSell"));
            guiInventory.setStack(BTN_SELL, sell);
            slotTokens.set(BTN_SELL, "btn:sell");
        }

        ItemStack qtyMinus = new ItemStack(Items.RED_DYE);
        qtyMinus.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c-1"));
        guiInventory.setStack(BTN_QTY_MINUS, qtyMinus);
        slotTokens.set(BTN_QTY_MINUS, "btn:qty:-1");

        ItemStack qty1 = new ItemStack(Items.GREEN_DYE);
        qty1.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§a+1 §7(Selected: " + quantity + ")"));
        guiInventory.setStack(BTN_QTY_PLUS_1, qty1);
        slotTokens.set(BTN_QTY_PLUS_1, "btn:qty:+1");

        ItemStack qty16 = new ItemStack(Items.LAPIS_LAZULI);
        qty16.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b+16"));
        guiInventory.setStack(BTN_QTY_PLUS_16, qty16);
        slotTokens.set(BTN_QTY_PLUS_16, "btn:qty:+16");

        ItemStack qty64 = new ItemStack(Items.DIAMOND);
        qty64.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b+64"));
        guiInventory.setStack(BTN_QTY_PLUS_64, qty64);
        slotTokens.set(BTN_QTY_PLUS_64, "btn:qty:+64");

        ItemStack prev = new ItemStack(Items.ARROW);
        prev.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7Prev Page"));
        guiInventory.setStack(BTN_PREV, prev);
        slotTokens.set(BTN_PREV, "btn:prev");

        ItemStack next = new ItemStack(Items.ARROW);
        next.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7Next Page"));
        guiInventory.setStack(BTN_NEXT, next);
        slotTokens.set(BTN_NEXT, "btn:next");

        ItemStack close = new ItemStack(Items.BARRIER);
        close.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§cClose"));
        guiInventory.setStack(BTN_CLOSE, close);
        slotTokens.set(BTN_CLOSE, "btn:close");
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < SHOP_SIZE) {
            String token = slotTokens.get(slotIndex);
            if (token != null && player instanceof ServerPlayerEntity serverPlayer) {
                handleToken(serverPlayer, token);
                return;
            }

            if (mode != Mode.SELL) {
                // Prevent interacting with display stacks in non-sell modes.
                if (slotIndex < DISPLAY_SIZE) return;
            }
        }

        super.onSlotClick(slotIndex, button, actionType, player);
    }

    private void handleToken(ServerPlayerEntity player, String token) {
        if (token.startsWith("btn:")) {
            switch (token) {
                case "btn:categories" -> {
                    returnSellItems(player);
                    mode = Mode.CATEGORIES;
                    render();
                }
                case "btn:sell" -> {
                    pendingItemId = null;
                    pendingQuantity = 0;
                    mode = Mode.SELL;
                    render();
                }
                case "btn:sellnow" -> {
                    if (mode != Mode.SELL) return;
                    double earned = 0.0;
                    int soldStacks = 0;
                    int unsellableStacks = 0;

                    for (int i = 0; i < DISPLAY_SIZE; i++) {
                        ItemStack stack = guiInventory.getStack(i);
                        if (stack.isEmpty()) continue;

                        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                        ShopManager.ShopItem shopItem = ShopManager.getShopItem(itemId);
                        if (shopItem == null || shopItem.sellPrice <= 0) {
                            unsellableStacks++;
                            continue;
                        }

                        ItemStack toSell = stack.copy();
                        guiInventory.setStack(i, ItemStack.EMPTY);
                        earned += ShopManager.sellItem(player.getUuid(), toSell);
                        soldStacks++;
                    }

                    if (soldStacks > 0) {
                        player.sendMessage(Text.literal("§aSold items for " + EconomyManager.formatCurrencyCompact(BigDecimal.valueOf(earned), EconomyManager.Currency.COINS) + "."), false);
                    } else {
                        player.sendMessage(Text.literal("§eNothing to sell."), false);
                    }
                    if (unsellableStacks > 0) {
                        player.sendMessage(Text.literal("§7" + unsellableStacks + " stack(s) not sellable (not in shop or sell price = 0)."), false);
                    }
                    render();
                }
                case "btn:qty:-1" -> {
                    quantity = Math.max(1, quantity - 1);
                    render();
                }
                case "btn:qty:+1" -> {
                    quantity = Math.min(64 * 36, quantity + 1);
                    render();
                }
                case "btn:qty:+16" -> {
                    quantity = Math.min(64 * 36, quantity + 16);
                    render();
                }
                case "btn:qty:+64" -> {
                    quantity = Math.min(64 * 36, quantity + 64);
                    render();
                }
                case "btn:prev" -> {
                    if (mode == Mode.ITEMS) {
                        page = Math.max(0, page - 1);
                        render();
                    }
                }
                case "btn:next" -> {
                    if (mode == Mode.ITEMS) {
                        page = page + 1;
                        render();
                    }
                }
                case "btn:close" -> player.closeHandledScreen();
                default -> {}
            }
            return;
        }

        if (token.startsWith("cat:")) {
            returnSellItems(player);
            selectedCategory = token.substring("cat:".length());
            page = 0;
            mode = Mode.ITEMS;
            render();
            return;
        }

        if (token.startsWith("item:")) {
            if (mode != Mode.ITEMS) return;
            pendingItemId = token.substring("item:".length());
            pendingQuantity = Math.max(1, quantity);
            mode = Mode.CONFIRM;
            render();
            return;
        }

        if (token.startsWith("confirm:")) {
            if (mode != Mode.CONFIRM) return;
            switch (token) {
                case "confirm:yes" -> {
                    int qty = pendingQuantity;
                    String itemId = pendingItemId;
                    pendingItemId = null;
                    pendingQuantity = 0;
                    mode = Mode.ITEMS;

                    if (itemId != null && qty > 0) {
                        ShopManager.BuyResult result = ShopManager.buyAndDeliver(player, itemId, qty);
                        switch (result) {
                            case SUCCESS -> player.sendMessage(Text.literal("§aBought " + qty + "x " + itemId + "."), false);
                            case NOT_FOUND -> player.sendMessage(Text.literal("§cItem not found in shop."), false);
                            case OUT_OF_STOCK -> player.sendMessage(Text.literal("§cOut of stock."), false);
                            case INSUFFICIENT_FUNDS -> player.sendMessage(Text.literal("§cInsufficient funds."), false);
                            case ERROR -> player.sendMessage(Text.literal("§cPurchase failed."), false);
                        }
                    }
                    render();
                }
                case "confirm:no" -> {
                    pendingItemId = null;
                    pendingQuantity = 0;
                    mode = Mode.ITEMS;
                    render();
                }
                default -> {}
            }
        }
    }

    private void returnSellItems(ServerPlayerEntity player) {
        if (mode != Mode.SELL) return;
        for (int i = 0; i < DISPLAY_SIZE; i++) {
            ItemStack stack = guiInventory.getStack(i);
            if (stack.isEmpty()) continue;
            guiInventory.setStack(i, ItemStack.EMPTY);
            boolean inserted = player.getInventory().insertStack(stack);
            if (!inserted && !stack.isEmpty()) {
                player.dropItem(stack, false);
            }
        }
    }

    @Override
    public void onClosed(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity sp) {
            returnSellItems(sp);
        }
        super.onClosed(player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return ItemStack.EMPTY;

        if (mode != Mode.SELL) {
            // No shift transfers in buy modes.
            return ItemStack.EMPTY;
        }

        Slot slot = this.slots.get(slotIndex);
        if (slot == null || !slot.hasStack()) return ItemStack.EMPTY;

        // Shift from player inventory into sell zone.
        if (slotIndex >= SHOP_SIZE) {
            ItemStack stack = slot.getStack();
            ItemStack original = stack.copy();

            boolean moved = this.insertItem(stack, 0, DISPLAY_SIZE, false);
            if (!moved) return ItemStack.EMPTY;

            slot.markDirty();
            if (stack.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            }
            return original;
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    private ServerPlayerEntity viewer() {
        if (playerInventory.player instanceof ServerPlayerEntity sp) return sp;
        return null;
    }

    private static boolean hasMinPermissionLevel(ServerPlayerEntity player, int minLevel) {
        PermissionLevel permLevel = switch (minLevel) {
            case 0 -> PermissionLevel.ALL;
            case 1 -> PermissionLevel.MODERATORS;
            case 2 -> PermissionLevel.ADMINS;
            default -> PermissionLevel.OWNERS;
        };
        return player.getCommandSource().getPermissions().hasPermission(new Permission.Level(permLevel));
    }

    private static ItemStack categoryIcon(String category) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "materials" -> new ItemStack(Items.IRON_INGOT);
            case "blocks" -> new ItemStack(Items.STONE);
            case "tools" -> new ItemStack(Items.IRON_PICKAXE);
            case "food" -> new ItemStack(Items.BREAD);
            case "redstone" -> new ItemStack(Items.REDSTONE);
            default -> new ItemStack(Items.CHEST);
        };
    }

    private final class GuiSlot extends Slot {
        private GuiSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            if (this.getIndex() >= DISPLAY_SIZE) return false; // controls row never accepts
            return mode == Mode.SELL; // sell drop zone
        }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) {
            if (this.getIndex() >= DISPLAY_SIZE) return false;
            return mode == Mode.SELL;
        }
    }
}
