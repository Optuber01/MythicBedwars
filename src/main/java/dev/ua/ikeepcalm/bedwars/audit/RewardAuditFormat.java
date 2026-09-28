package dev.ua.ikeepcalm.bedwars.audit;

import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Plain-text snapshots of reward bundles for audit metadata. Role-neutral: no MBedwars types.
 */
public final class RewardAuditFormat {

    private RewardAuditFormat() {
    }

    /**
     * One grant as {@code KIND[/ITEM] tier amount=.. int=.. count=.. str=.. maxSeq=.. [epic]}, so
     * a remainder written into a FAILED row can be re-issued exactly by hand.
     */
    public static String grant(RewardGrant grant) {
        StringBuilder text = new StringBuilder(grant.kind().name());
        if (grant.item() != null) {
            text.append('/').append(grant.item().name());
        }
        text.append(' ').append(grant.tier() == null ? "?" : grant.tier().name())
                .append(" amount=").append(String.format(Locale.ROOT, "%.4f", grant.amount()))
                .append(" int=").append(grant.intArg())
                .append(" count=").append(grant.count());
        if (grant.strArg() != null) {
            text.append(" str=").append(grant.strArg());
        }
        if (grant.isExchangeToken()) {
            text.append(" maxSeq=").append(grant.maxSequence());
        }
        if (grant.epic()) {
            text.append(" epic");
        }
        return text.toString();
    }

    public static String grants(List<RewardGrant> grants) {
        StringJoiner joined = new StringJoiner("; ");
        for (RewardGrant grant : grants) {
            joined.add(grant(grant));
        }
        return joined.toString();
    }

    /** Adds the bundle's identity and contents to a row. Plain values only; safe off-thread. */
    public static BedwarsAuditEmitter.AuditRow describe(BedwarsAuditEmitter.AuditRow row, RewardBundle bundle) {
        return row.correlation(BedwarsAuditEmitter.bundleCorrelation(bundle.eventId(), bundle.playerId()))
                .business(bundle.eventId())
                .subject(bundle.playerId())
                .put("actor", "system")
                .put("event_id", bundle.eventId())
                .put("player_name", bundle.playerName())
                .put("arena", bundle.arena())
                .put("event_pathway", bundle.eventPathway())
                .put("won", bundle.won())
                .put("earned_at", bundle.earnedAtEpochMs())
                .put("grant_count", bundle.grants().size())
                .put("grants", grants(bundle.grants()));
    }
}
