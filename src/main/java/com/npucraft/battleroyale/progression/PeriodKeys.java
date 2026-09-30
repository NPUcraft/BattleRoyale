package com.npucraft.battleroyale.progression;
import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
/** Keys are frozen into the result; later configuration changes never reclassify history. */
public record PeriodKeys(String day, String week, String month) {
    public static PeriodKeys at(Instant completedAt, ZoneId zone) {
        var date = completedAt.atZone(zone).toLocalDate();
        return new PeriodKeys(date.toString(), String.format(Locale.ROOT, "%04d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)), YearMonth.from(date).toString());
    }
    public Map<String,String> entries() { return Map.of("DAY",day,"WEEK",week,"MONTH",month); }
}
