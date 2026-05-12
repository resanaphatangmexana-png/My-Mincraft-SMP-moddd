package com.mss3;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shop UI — redstone-only inventory using vanilla chest GUI.
 * Buy by clicking item, sell bonemeal via /shopsell.
 */
public class ShopHandler {

    /** Price per bonemeal piece (for /shopsell). */
    public static final long BONEMEAL_PRICE = 10;

    /** Redstone shop items. */
    private static final LinkedHashMap<Item, Long> REDSTONE_SHOP = new LinkedHashMap<>();
    static {
        // Wires
        REDSTONE_SHOP.put(Items.REDSTONE,            5L);
        REDSTONE_SHOP.put(Items.REDSTONE_TORCH,      10L);
        REDSTONE_SHOP.put(Items.REDSTONE_BLOCK,      50L);
        REDSTONE_SHOP.put(Items.REDSTONE_LAMP,       30L);

        // Logic
        REDSTONE_SHOP.put(Items.REPEATER,            25L);
        REDSTONE_SHOP.put(Items.COMPARATOR,          40L);
        REDSTONE_SHOP.put(Items.OBSERVER,            80L);

        // Pistons
        REDSTONE_SHOP.put(Items.PISTON,              100L);
        REDSTONE_SHOP.put(Items.STICKY_PISTON,       150L);
        REDSTONE_SHOP.put(Items.SLIME_BLOCK,         60L);
        REDSTONE_SHOP.put(Items.HONEY_BLOCK,         70L);

        // Containers
        REDSTONE_SHOP.put(Items.HOPPER,              200L);
        REDSTONE_SHOP.put(Items.DISPENSER,           120L);
        REDSTONE_SHOP.put(Items.DROPPER,             100L);
        REDSTONE_SHOP.put(Items.CHEST,               50L);
        REDSTONE_SHOP.put(Items.TRAPPED_CHEST,       60L);
        REDSTONE_SHOP.put(Items.BARREL,              40L);
        REDSTONE_SHOP.put(Items.CRAFTER,             500L);

        // Inputs
        REDSTONE_SHOP.put(Items.LEVER,               15L);
        REDSTONE_SHOP.put(Items.STONE_BUTTON,        10L);
        REDSTONE_SHOP.put(Items.OAK_BUTTON,          10L);
        REDSTONE_SHOP.put(Items.STONE_PRESSURE_PLATE,15L);
        REDSTONE_SHOP.put(Items.OAK_PRESSURE_PLATE,  15L);
        REDSTONE_SHOP.put(Items.LIGHT_WEIGHTED_PRESSURE_PLATE, 30L);
        REDSTONE_SHOP.put(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 30L);
        REDSTONE_SHOP.put(Items.TRIPWIRE_HOOK,       20L);
        REDSTONE_SHOP.put(Items.STRING,              5L);

        // Sensors
        REDSTONE_SHOP.put(Items.DAYLIGHT_DETECTOR,   100L);
        REDSTONE_SHOP.put(Items.TARGET,              150L);
        REDSTONE_SHOP.put(Items.SCULK_SENSOR,        300L);
        REDSTONE_SHOP.put(Items.CALIBRATED_SCULK_SENSOR, 500L);
        REDSTONE_SHOP.put(Items.LIGHTNING_ROD,       200L);

        // Rails
        REDSTONE_SHOP.put(Items.RAIL,                10L);
        REDSTONE_SHOP.put(Items.POWERED_RAIL,        50L);
        REDSTONE_SHOP.put(Items.DETECTOR_RAIL,       40L);
        REDSTONE_SHOP.put(Items.ACTIVATOR_RAIL,      50L);
        REDSTONE_SHOP.put(Items.MINECART,            80L);

        // Other useful
        REDSTONE_SHOP.put(Items.NOTE_BLOCK,          30L);
        REDSTONE_SHOP.put(Items.TNT,                 80L);
        REDSTONE_SHOP.put(Items.COPPER_BULB,         100L);
        REDSTONE_SHOP.put(Items.BONE_MEAL,           20L);  // for buying bonemeal
    }

    // ============================================================
    public static void openMainMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);

        // Border
        ItemStack glass = new ItemStack(Items.RED_STAINED_GLASS_PANE);
        glass.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        for (int i = 0; i < 9; i++) inv.setStack(i, glass.copy());
        for (int i = 45; i < 54; i++) inv.setStack(i, glass.copy());
        for (int row = 1; row < 5; row++) {
            inv.setStack(row * 9, glass.copy());
            inv.setStack(row * 9 + 8, glass.copy());
        }

        // Fill items
        int slot = 10;
        for (Map.Entry<Item, Long> entry : REDSTONE_SHOP.entrySet()) {
            ItemStack display = new ItemStack(entry.getKey());
            String name = itemName(entry.getKey());
            display.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal("§f" + name + " §a[$" + Mss3Mod.formatMoney(entry.getValue()) + "]")
                    .styled(s -> s.withItalic(false)));
            inv.setStack(slot, display);
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 54) break;
        }

        // Money indicator at bottom
        long money = Mss3State.get(player.getServer()).getOrCreatePlayer(player.getUuid()).money;
        ItemStack moneyStack = new ItemStack(Items.GOLD_NUGGET);
        moneyStack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
            Text.literal("§eเงินคุณ: §f$" + Mss3Mod.formatMoney(money))
                .styled(s -> s.withItalic(false)));
        inv.setStack(49, moneyStack);

        player.openHandledScreen(new ShopFactory(inv));
    }

    /** /shopsell — sell bonemeal in player's hand */
    public static int sellBonemeal(ServerPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || held.getItem() != Items.BONE_MEAL) {
            player.sendMessage(Text.literal("§cถือ Bone Meal ไว้ในมือก่อน!"), false);
            return 0;
        }
        int count = held.getCount();
        long total = BONEMEAL_PRICE * count;
        PlayerData data = Mss3State.get(player.getServer()).getOrCreatePlayer(player.getUuid());
        data.money += total;
        Mss3State.get(player.getServer()).markDirty();
        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        player.sendMessage(Text.literal("§aขาย §e" + count + " Bone Meal §aได้ §e$" + Mss3Mod.formatMoney(total)), false);
        player.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 1.0f, 1.2f);
        return 1;
    }

    /** Internal: handle clicking item in shop */
    public static void buyItem(ServerPlayerEntity player, Item item, int amount) {
        Long price = REDSTONE_SHOP.get(item);
        if (price == null) return;
        long total = price * amount;
        PlayerData data = Mss3State.get(player.getServer()).getOrCreatePlayer(player.getUuid());
        if (data.money < total) {
            player.sendMessage(Text.literal("§cเงินไม่พอ! ต้องการ $" + Mss3Mod.formatMoney(total)), false);
            player.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return;
        }
        data.money -= total;
        Mss3State.get(player.getServer()).markDirty();
        ItemStack stack = new ItemStack(item, amount);
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
        player.sendMessage(Text.literal("§aซื้อ §e" + amount + "x " + itemName(item) +
            " §aหมด §e$" + Mss3Mod.formatMoney(total)), true);
        player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.5f);
    }

    private static String itemName(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : id.getPath().toCharArray()) {
            if (c == '_') { sb.append(' '); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    // ============================================================
    public static class ShopFactory implements NamedScreenHandlerFactory {
        private final SimpleInventory inv;
        public ShopFactory(SimpleInventory inv) { this.inv = inv; }

        @Override
        public Text getDisplayName() {
            return Text.literal("§4§lMincraft Ss3 §6§lShop").styled(s -> s.withItalic(false));
        }

        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory playerInv, PlayerEntity player) {
            return new ShopScreenHandler(syncId, playerInv, inv);
        }
    }

    public static class ShopScreenHandler extends GenericContainerScreenHandler {
        private final Inventory backing;

        public ShopScreenHandler(int syncId, PlayerInventory playerInv, Inventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.backing = inv;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp)) return;
            if (slotIndex < 0 || slotIndex >= backing.size()) {
                // Player's inventory area - ignore (no shift-take from shop)
                return;
            }
            ItemStack clicked = backing.getStack(slotIndex);
            if (clicked.isEmpty()) return;
            // Don't allow clicking glass borders or money indicator
            if (clicked.getItem() == Items.RED_STAINED_GLASS_PANE) return;
            if (clicked.getItem() == Items.GOLD_NUGGET && slotIndex == 49) return;

            int amount = (action == SlotActionType.QUICK_MOVE) ? 64 : 1;
            buyItem(sp, clicked.getItem(), amount);
        }
    }
}
