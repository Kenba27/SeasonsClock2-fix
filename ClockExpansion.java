package vn.seasonsclock;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class ClockExpansion extends PlaceholderExpansion {

    private final SeasonsClock plugin;

    public ClockExpansion(SeasonsClock plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "seasonclock";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SeasonsClock";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        switch (params.toLowerCase()) {
            case "timeleft":
                return plugin.formatTimeLeft();
            case "daysleft":
                return String.valueOf(plugin.daysLeftInSeason());
            case "progress":
                return String.valueOf(plugin.seasonProgressPercent());
            case "day":
                return String.valueOf(plugin.calendar()[0]);
            case "month":
                return String.valueOf(plugin.calendar()[1]);
            case "year":
                return String.valueOf(plugin.calendar()[2]);
            case "date":
                return plugin.formatDate();
            case "fulldate":
                return plugin.formatFullDate();
            case "monthname":
                return plugin.monthName();
            default:
                return null;
        }
    }
}
