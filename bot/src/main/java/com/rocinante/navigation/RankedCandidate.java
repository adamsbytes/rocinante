package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;

/**
 * A training object candidate ranked by efficiency.
 *
 * <p>Candidates are ranked by total path cost, which varies based on whether
 * banking is required:
 * <ul>
 *   <li>No banking: cost = path from player to object</li>
 *   <li>With banking: cost = roundtrip (object → bank → object)</li>
 * </ul>
 *
 * <p>Lower cost = higher efficiency. Index 0 in a ranked list is the optimal choice.
 *
 * @param position     the world position of the training object
 * @param objectId     the RuneLite object ID
 * @param cost         total path cost (tiles), lower is better
 * @param bankDistance distance to nearest bank (0 if banking not required)
 */
public record RankedCandidate(
        WorldPoint position,
        int objectId,
        int cost,
        int bankDistance
) {
    /**
     * Create a candidate for non-banking training.
     *
     * @param position the object position
     * @param objectId the object ID
     * @param cost     path cost from player to object
     * @return new candidate with bankDistance = 0
     */
    public static RankedCandidate withoutBanking(WorldPoint position, int objectId, int cost) {
        return new RankedCandidate(position, objectId, cost, 0);
    }

    /**
     * Create a candidate for banking training.
     *
     * @param position     the object position
     * @param objectId     the object ID
     * @param roundtripCost total roundtrip cost (object → bank → object)
     * @param bankDistance  one-way distance to bank
     * @return new candidate with banking metrics
     */
    public static RankedCandidate withBanking(WorldPoint position, int objectId, 
                                               int roundtripCost, int bankDistance) {
        return new RankedCandidate(position, objectId, roundtripCost, bankDistance);
    }

    /**
     * Check if this candidate requires banking.
     *
     * @return true if bankDistance > 0
     */
    public boolean requiresBanking() {
        return bankDistance > 0;
    }
}
