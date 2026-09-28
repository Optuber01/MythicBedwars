package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.domain.item.service.SandboxItems;
import dev.ua.ikeepcalm.bedwars.domain.reward.RewardRedeemer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The survival server's side of a player coming home.
 *
 * <p>Contains no MBedwars types, and is only registered in the SMP role.
 */
public class SmpEventListener implements Listener {

    private final MythicBedwars plugin;
    private final RewardRedeemer redeemer;
    private final ReturnGreeter greeter;

    public SmpEventListener(MythicBedwars plugin, RewardRedeemer redeemer, ReturnGreeter greeter) {
        this.plugin = plugin;
        this.redeemer = redeemer;
        this.greeter = greeter;
    }

    /**
     * Greets them, then pays out whatever is waiting.
     *
     * <p>Deliberately keyed off login rather than off the return message: a player who was
     * disconnected, transferred while the bus was down, or simply never came back still gets paid
     * the next time they appear.
     *
     * <p>The greeting and the payout are independent on purpose. Losing an outcome should cost a nice
     * message and nothing else — the rewards are guarded separately, by their own queue.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        reclaimMatchItems(event.getPlayer());

        greeter.onJoin(event.getPlayer());
        redeemer.redeemOnJoin(event.getPlayer());
    }

    /**
     * Second line of defence against match-issued crafting materials reaching real progression.
     *
     * <p>The Bedwars server already strips them as a player leaves the arena, and the two backends
     * normally keep separate inventories, so this should never find anything. It exists because the
     * two assumptions it rests on — that the strip ran, and that no inventory-sync plugin carries
     * the Bedwars inventory home — are both things an operator can change without knowing that free
     * Sequence 4 characteristics were the consequence.
     */
    private void reclaimMatchItems(org.bukkit.entity.Player player) {
        java.util.List<org.bukkit.inventory.ItemStack> removed = new java.util.ArrayList<>();
        int stripped = SandboxItems.strip(player.getInventory(), removed::add);
        if (stripped > 0) {
            plugin.getLogger().warning("Removed " + stripped + " match-issued item stack(s) from "
                                       + player.getName() + " on arrival. They should not have crossed "
                                       + "the proxy — check for inventory syncing between backends.");
            auditStripped(player, removed);
        }
    }

    /**
     * One row per join that removed anything, listing every stack. Main thread; never throws.
     */
    private void auditStripped(org.bukkit.entity.Player player, java.util.List<org.bukkit.inventory.ItemStack> removed) {
        try {
            java.util.StringJoiner materials = new java.util.StringJoiner(",");
            int total = 0;
            for (org.bukkit.inventory.ItemStack stack : removed) {
                materials.add(stack.getType().name() + "x" + stack.getAmount());
                total += stack.getAmount();
            }
            BedwarsAuditEmitter.AuditRow row = BedwarsAuditEmitter.AuditRow.of("item.sandbox_stripped",
                            AuditOutcome.COMMITTED)
                    .risk(AuditRisk.HIGH)
                    .subject(player.getUniqueId())
                    .put("actor", "system")
                    .put("player_name", player.getName())
                    .put("stack_count", removed.size())
                    .put("item_count", total)
                    .put("materials_list", materials.toString());
            BedwarsAuditEmitter.putLocation(row.metadata(), player.getLocation());
            if (removed.size() == 1) {
                BedwarsAuditEmitter.putItem(row.metadata(), removed.getFirst());
            }
            plugin.getAudit().emit(row);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }
}
