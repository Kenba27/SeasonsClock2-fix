package vn.seasonsclock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Addon cho SeasonsPlus 1.4.1: mua + lich theo ngay game, thong bao doi mua,
 * hieu ung nong/lanh theo giai doan, hat la roi/tuyet roi.
 * Doc/dieu khien SeasonsPlus bang reflection (khong sua plugin goc).
 */
public class SeasonsClock extends JavaPlugin {

    private static final String[] ORDER = {"SPRING", "SUMMER", "AUTUMN", "WINTER"};

    private Object seasonManager;
    private Method getCurrentSeason;
    private Method setSeason;
    private Class<?> seasonClass;
    private Field durationField;
    private Object temperatureApi;
    private Method playerTemperature;

    private ClockExpansion expansion;
    private File dataFile;
    private volatile long epochDay;
    private boolean warned;
    private boolean thermalWarned;
    private String lastAnnounced;

    private final Map<UUID, String> heatStage = new HashMap<>();
    private final Map<UUID, String> coldStage = new HashMap<>();
    private final Random random = new Random();

    private Particle leafParticle;
    private Particle snowParticle;
    private final List<Color> leafColors = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");

        Plugin seasons = Bukkit.getPluginManager().getPlugin("Seasons");
        if (seasons == null) {
            getLogger().severe("Khong tim thay plugin Seasons (SeasonsPlus). Tat SeasonsClock.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            Object manager = seasons.getClass().getMethod("getSeasonManager").invoke(seasons);
            Class<?> cls = manager.getClass();
            Method get = cls.getMethod("getCurrentSeason");
            Class<?> sc = get.getReturnType();
            Method set = cls.getMethod("setSeason", sc);
            Field duration = cls.getDeclaredField("seasonDuration");
            duration.setAccessible(true);

            Object api = seasons.getClass().getMethod("getTemperatureAPI").invoke(seasons);
            Method temp = api.getClass().getMethod("getPlayerTemperature", Player.class);

            this.getCurrentSeason = get;
            this.seasonClass = sc;
            this.setSeason = set;
            this.durationField = duration;
            this.temperatureApi = api;
            this.playerTemperature = temp;
            this.seasonManager = manager;
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("Khong tuong thich voi phien ban Seasons nay: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (data.contains("epoch-day")) {
            epochDay = data.getLong("epoch-day");
        } else {
            epochDay = getConfig().getBoolean("start-from-world-start", false) ? 0L : worldDay();
            saveData();
        }

        loadParticles();

        expansion = new ClockExpansion(this);
        expansion.register();

        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::thermalTick, 40L, 40L);
        long interval = Math.max(2, getConfig().getInt("particles.interval-ticks", 10));
        getServer().getScheduler().runTaskTimer(this, this::particleTick, 40L, interval);
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            expansion.unregister();
        }
    }

    private void saveData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("epoch-day", epochDay);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Khong the luu data.yml: " + e);
        }
    }

    // ================= mua theo ngay game =================

    /** Chay moi giay: khoa bo dem thoi gian that cua SeasonsPlus, ep mua theo ngay game, thong bao doi mua. */
    private void tick() {
        String expected = expectedSeason();
        try {
            if (durationField.getInt(seasonManager) != Integer.MAX_VALUE) {
                durationField.setInt(seasonManager, Integer.MAX_VALUE);
            }
            if (!expected.equals(currentSeason())) {
                for (Object c : seasonClass.getEnumConstants()) {
                    if (((Enum<?>) c).name().equals(expected)) {
                        setSeason.invoke(seasonManager, c);
                        break;
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            if (!warned) {
                warned = true;
                getLogger().warning("Khong the dieu khien mua cua SeasonsPlus: " + e);
            }
        }

        if (lastAnnounced == null) {
            lastAnnounced = expected;
        } else if (!expected.equals(lastAnnounced)) {
            lastAnnounced = expected;
            announce(expected);
        }
    }

    private void announce(String season) {
        if (!getConfig().getBoolean("announcements.enabled", true)) {
            return;
        }
        String base = "announcements.seasons." + season + ".";
        String title = color(getConfig().getString(base + "title", ""));
        String subtitle = color(getConfig().getString(base + "subtitle", ""));
        String message = color(getConfig().getString(base + "message", ""));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!title.isEmpty() || !subtitle.isEmpty()) {
                p.sendTitle(title, subtitle, 10, 70, 20);
            }
            if (!message.isEmpty()) {
                p.sendMessage(message);
            }
        }
    }

    private String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    private String currentSeason() {
        try {
            Object s = getCurrentSeason.invoke(seasonManager);
            return s == null ? "" : ((Enum<?>) s).name();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    // ================= hieu ung nong / lanh theo giai doan =================

    private void thermalTick() {
        if (!getConfig().getBoolean("thermal-effects.enabled", true)) {
            return;
        }
        try {
            int duration = Math.max(20, getConfig().getInt("thermal-effects.duration-ticks", 100));
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                GameMode gm = p.getGameMode();
                if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                    heatStage.remove(id);
                    coldStage.remove(id);
                    continue;
                }
                double t = ((Number) playerTemperature.invoke(temperatureApi, p)).doubleValue();
                applyStage(p, t, true, heatStage, duration);
                applyStage(p, t, false, coldStage, duration);
            }
        } catch (ReflectiveOperationException e) {
            if (!thermalWarned) {
                thermalWarned = true;
                getLogger().warning("Khong the doc nhiet do tu SeasonsPlus: " + e);
            }
        }
    }

    private void applyStage(Player p, double t, boolean heat, Map<UUID, String> state, int duration) {
        UUID id = p.getUniqueId();
        ConfigurationSection stages = getConfig().getConfigurationSection(
                "thermal-effects." + (heat ? "heat" : "cold") + ".stages");
        String best = null;
        double bestTemp = 0.0;
        if (stages != null) {
            for (String key : stages.getKeys(false)) {
                double st = stages.getDouble(key + ".temp");
                boolean hit = heat ? t >= st : t <= st;
                if (!hit) {
                    continue;
                }
                if (best == null || (heat ? st > bestTemp : st < bestTemp)) {
                    best = key;
                    bestTemp = st;
                }
            }
        }
        if (best == null) {
            state.remove(id);
            return;
        }
        if (!best.equals(state.get(id))) {
            state.put(id, best);
            String msg = color(stages.getString(best + ".message", ""));
            if (!msg.isEmpty()) {
                p.sendMessage(msg);
            }
        }
        for (String entry : stages.getStringList(best + ".effects")) {
            String[] parts = entry.split(":");
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(parts[0].trim().toLowerCase()));
            if (type == null) {
                continue;
            }
            int amplifier = 0;
            if (parts.length > 1) {
                try {
                    amplifier = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    amplifier = 0;
                }
            }
            p.addPotionEffect(new PotionEffect(type, duration, amplifier, true, false, true));
        }
    }

    // ================= hat la roi / tuyet roi =================

    private Particle particleByKey(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.trim().toLowerCase()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void loadParticles() {
        leafParticle = particleByKey(getConfig().getString("particles.autumn.particle", "tinted_leaves"));
        snowParticle = particleByKey(getConfig().getString("particles.winter.particle", "snowflake"));
        if (leafParticle == null) {
            getLogger().warning("Khong tim thay hat la roi (particles.autumn.particle). Hay doi ten hat trong config.yml.");
        }
        if (snowParticle == null) {
            getLogger().warning("Khong tim thay hat tuyet (particles.winter.particle). Hay doi ten hat trong config.yml.");
        }
        leafColors.clear();
        for (String hex : getConfig().getStringList("particles.autumn.colors")) {
            try {
                String h = hex.trim();
                if (h.startsWith("#")) {
                    h = h.substring(1);
                }
                leafColors.add(Color.fromRGB(Integer.parseInt(h, 16)));
            } catch (RuntimeException ignored) {
                // bo qua mau loi
            }
        }
        if (leafColors.isEmpty()) {
            leafColors.add(Color.fromRGB(0xD2691E));
        }
    }

    private void particleTick() {
        if (!getConfig().getBoolean("particles.enabled", true)) {
            return;
        }
        String season = currentSeason();
        boolean autumn = "AUTUMN".equals(season);
        boolean winter = "WINTER".equals(season);
        if (!autumn && !winter) {
            return;
        }
        int radius = Math.max(4, getConfig().getInt("particles.radius", 14));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            if (autumn && leafParticle != null) {
                spawnLeaves(p, radius, Math.max(1, getConfig().getInt("particles.autumn.samples-per-run", 4)));
            } else if (winter && snowParticle != null) {
                spawnSnow(p, radius, Math.max(1, getConfig().getInt("particles.winter.flakes-per-run", 12)));
            }
        }
    }

    /** La roi tu tan cay: chi lay mau tai nhung cot co la o tren cung. */
    private void spawnLeaves(Player p, int radius, int samples) {
        World w = p.getWorld();
        Location base = p.getLocation();
        for (int i = 0; i < samples; i++) {
            int x = base.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = base.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = w.getHighestBlockYAt(x, z);
            if (!Tag.LEAVES.isTagged(w.getBlockAt(x, y, z).getType())) {
                continue;
            }
            Location loc = new Location(w, x + random.nextDouble(), y + 0.1, z + random.nextDouble());
            Color c = leafColors.get(random.nextInt(leafColors.size()));
            spawn(p, leafParticle, loc, c);
        }
    }

    /** Tuyet roi o noi troi thoang (tuyet roi tren mai nha, khong roi trong nha / duoi hang). */
    private void spawnSnow(Player p, int radius, int flakes) {
        World w = p.getWorld();
        Location base = p.getLocation();
        for (int i = 0; i < flakes; i++) {
            int x = base.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = base.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int top = w.getHighestBlockYAt(x, z);
            if (top - base.getY() > 20) {
                continue;
            }
            double y = top + 3 + random.nextInt(5);
            if (y - base.getY() > 12) {
                continue;
            }
            if (w.getTemperature(x, top, z) > 1.0) {
                continue;
            }
            Location loc = new Location(w, x + random.nextDouble(), y, z + random.nextDouble());
            spawn(p, snowParticle, loc, null);
        }
    }

    private void spawn(Player p, Particle particle, Location loc, Color color) {
        try {
            if (color != null && particle.getDataType() == Color.class) {
                p.spawnParticle(particle, loc, 1, 0.0, 0.0, 0.0, 0.0, color);
            } else {
                p.spawnParticle(particle, loc, 1, 0.0, 0.0, 0.0, 0.0);
            }
        } catch (IllegalArgumentException ignored) {
            // hat can du lieu khac, bo qua
        }
    }

    // ================= lich theo ngay game =================

    private World calendarWorld() {
        String name = getConfig().getString("world", "");
        World w = (name == null || name.isEmpty()) ? null : Bukkit.getWorld(name);
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        return w;
    }

    /** So ngay game da troi qua tu luc the gioi duoc tao (ngu qua dem cung tinh 1 ngay). */
    private long worldDay() {
        World w = calendarWorld();
        return w == null ? 0L : Math.max(0L, w.getFullTime() / 24000L);
    }

    private long gameDays() {
        return Math.max(0L, worldDay() - epochDay);
    }

    private long daysPerMonth() {
        return Math.max(1, getConfig().getInt("days-per-month", 30));
    }

    private long monthsPerSeason() {
        return Math.max(1, getConfig().getInt("months-per-season", 3));
    }

    private long daysPerSeason() {
        return daysPerMonth() * monthsPerSeason();
    }

    private String expectedSeason() {
        return ORDER[(int) ((gameDays() / daysPerSeason()) % 4L)];
    }

    // ================= API cho placeholder =================

    /** So ngay con lai cua mua (tinh ca ngay hom nay); ngay cuoi cua mua tra ve 1. */
    public long daysLeftInSeason() {
        long per = daysPerSeason();
        return per - (gameDays() % per);
    }

    /** Phan tram mua da troi qua (theo so ngay tron ven), nhay 1 lan moi ngay. */
    public int seasonProgressPercent() {
        long per = daysPerSeason();
        return (int) ((gameDays() % per) * 100L / per);
    }

    /** Tra ve {ngay, thang, nam}. */
    public int[] calendar() {
        long d = gameDays();
        long dpm = daysPerMonth();
        long monthsPerYear = monthsPerSeason() * 4L;
        int day = (int) (d % dpm) + 1;
        int month = (int) ((d / dpm) % monthsPerYear) + 1;
        int year = getConfig().getInt("start-year", 1) + (int) (d / (dpm * monthsPerYear));
        return new int[]{day, month, year};
    }

    public String monthName() {
        int month = calendar()[1];
        List<String> names = getConfig().getStringList("month-names");
        if (month >= 1 && month <= names.size()) {
            return names.get(month - 1);
        }
        return "Thang " + month;
    }

    private String fillDate(String format) {
        int[] c = calendar();
        return format
                .replace("{day}", String.valueOf(c[0]))
                .replace("{monthname}", monthName())
                .replace("{month}", String.valueOf(c[1]))
                .replace("{year}", String.valueOf(c[2]));
    }

    public String formatDate() {
        return fillDate(getConfig().getString("date-format", "{day}/{month}/{year}"));
    }

    public String formatFullDate() {
        return fillDate(getConfig().getString("full-date-format", "{day} {monthname} {year}"));
    }

    public String formatTimeLeft() {
        return getConfig().getString("time-left-format", "{days}")
                .replace("{days}", String.valueOf(daysLeftInSeason()));
    }
}
