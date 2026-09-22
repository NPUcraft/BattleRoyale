package com.npucraft.lastsector.progression;
import com.npucraft.lastsector.api.rating.RatingCalculator;
public final class PlacementRatingCalculator implements RatingCalculator {
    private final RankingSettings settings;
    public PlacementRatingCalculator(RankingSettings settings) { this.settings = settings; }
    public int delta(int rating, int placement, int teams) {
        if (teams < 1 || placement < 1 || placement > teams || rating < 0)
            throw new IllegalArgumentException("Invalid placement rating input");
        double percentile = teams == 1 ? 0 : (double)(placement - 1) / (teams - 1);
        int base = settings.bands().stream().filter(b -> percentile <= b.maximum()).findFirst().orElseThrow().delta();
        long after = Math.min(Integer.MAX_VALUE, Math.max(settings.minimum(), (long) rating + base));
        return Math.toIntExact(after - rating);
    }
    public long killScore(int kills, int assists) {
        if (kills < 0 || assists < 0) throw new IllegalArgumentException("Negative combat statistics");
        return Math.addExact(Math.multiplyExact((long)kills, settings.killPoints()), Math.multiplyExact((long)assists, settings.assistPoints()));
    }
    @Override public Result calculate(Input input) {
        return new Result(delta(input.currentRating(), input.placement(), input.match().totalTeams()), killScore(input.kills(), input.assists()));
    }
}
