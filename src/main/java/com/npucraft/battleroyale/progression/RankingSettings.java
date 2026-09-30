package com.npucraft.battleroyale.progression;
import java.time.ZoneId;
import java.util.List;
public record RankingSettings(int initial, int minimum, List<Band> bands, int killPoints, int assistPoints,
                              ZoneId zone, int cacheSeconds) {
    public record Band(double maximum, int delta) {}
    public RankingSettings {
        bands = List.copyOf(bands);
        if (minimum < 0 || initial < minimum || killPoints < 0 || assistPoints < 0 || zone == null || cacheSeconds <= 0)
            throw new IllegalArgumentException("Invalid ranking settings");
        double previous = 0;
        for (var band : bands) {
            if (!Double.isFinite(band.maximum()) || band.maximum() <= previous || band.maximum() > 1)
                throw new IllegalArgumentException("Rating bands must strictly increase through 1.0");
            previous = band.maximum();
        }
        if (previous != 1) throw new IllegalArgumentException("Final rating band must be 1.0");
    }
    public static RankingSettings defaults() {
        return new RankingSettings(1000, 0, List.of(new Band(.05,40), new Band(.15,30), new Band(.30,20),
                new Band(.50,5), new Band(.75,-5), new Band(1,-15)), 10, 3, ZoneId.of("UTC"), 30);
    }
}
