package dev.ua.ikeepcalm.bedwars.domain.item.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Marks the Beyonder items a match hands out, so none of them survives the match.
 *
 * <p>Characteristics and ingredients are real Circle of Imagination progression items: they feed
 * artifact crafting, potion brewing and advancement on the survival server. A Bedwars round mints
 * them freely, out of iron and emeralds, into a sandbox Beyonder that is thrown away at the end —
 * and the round is played on a different backend that players are transferred home from. Without a
 * mark, a Paragon could buy a stack of characteristics for gold, log out through the proxy and
 * arrive on the SMP with a week of crafting in their inventory.
 *
 * <p>The mark is a persistent tag rather than a name or lore check: a player can rename an item,
 * and lore is stripped by any number of plugins, but PDC survives exactly the operations an
 * ItemStack survives.
 *
 * <p>This is deliberately about <i>where the item came from</i>, not what it is. An item a player
 * legitimately brought <i>into</i> the match from their own inventory carries no tag and is left
 * strictly alone.
 */
public class SandboxItems {

    private static final String TAG = "sandbox_item";

    private SandboxItems() {
    }

    private static NamespacedKey key() {
        return new NamespacedKey(MythicBedwars.getInstance(), TAG);
    }

    /**
     * Tags an item as match-issued.
     *
     * @return the same stack, for chaining. Null and empty stacks pass through untouched so callers
     * can tag whatever the COI API handed them without checking first.
     */
    public static ItemStack tag(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * @return whether this item was issued by a match and must not leave it
     */
    public static boolean isTagged(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
    }

    /**
     * Removes every match-issued item from a player's inventory, including armour and the off-hand.
     *
     * @return how many stacks were removed
     */
    public static int strip(Player player) {
        return strip(player.getInventory());
    }

    /**
     * Removes every match-issued item from an inventory.
     *
     * @return how many stacks were removed
     */
    public static int strip(Inventory inventory) {
        return strip(inventory, null);
    }

    /**
     * Removes every match-issued item from an inventory, handing each removed stack to
     * {@code removed} (when given) so the caller can record exactly what went.
     *
     * @return how many stacks were removed
     */
    public static int strip(Inventory inventory, java.util.function.Consumer<ItemStack> removed) {
        int count = 0;

        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTagged(contents[slot])) {
                inventory.setItem(slot, null);
                count++;
                if (removed != null) {
                    removed.accept(contents[slot]);
                }
            }
        }

        return count;
    }

    /**
     * @return whether this dropped entity is a match-issued item, so the drop can be removed rather
     * than left lying in a world that outlives the round
     */
    public static boolean isTagged(Item entity) {
        return entity != null && isTagged(entity.getItemStack());
    }
}
