package dev.egor.fakeplayer;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

public final class FakePlayerPlugin extends JavaPlugin implements Listener {
    record Settings(int maxBots, int maxPerPlayer, net.kyori.adventure.text.Component prefix, boolean joinLeaveMessages,
                    boolean mojangSkins, boolean spawnerSkin, boolean ignoreSleep, boolean pickUpItems, boolean rejoin,
                    boolean autoRespawn, boolean autoEat, int eatBelow, boolean follow, double followDistance,
                    double sprintDistance, double teleportDistance, Set<String> disabledActions) {
        static Settings load(FileConfiguration c) {
            return new Settings(c.getInt("limits.max-bots"), c.getInt("limits.max-bots-per-player"),
                MiniMessage.miniMessage().deserialize(c.getString("bots.prefix", "")), c.getBoolean("bots.join-leave-messages"),
                c.getBoolean("bots.mojang-skins"), c.getBoolean("bots.fallback-to-spawner-skin"), c.getBoolean("bots.ignore-sleep"),
                c.getBoolean("bots.pick-up-items"), c.getBoolean("rejoin-after-restart"), c.getBoolean("death.auto-respawn"),
                c.getBoolean("auto-eat.enabled"), c.getInt("auto-eat.below-food-level"), c.getBoolean("follow.enabled"),
                c.getDouble("follow.distance"), c.getDouble("follow.sprint-distance"), c.getDouble("follow.teleport-distance"),
                Set.copyOf(c.getStringList("disabled-actions").stream().map(a -> a.toLowerCase(Locale.ROOT)).toList()));
        }
    }

    private static final UUID CONSOLE = new UUID(0, 0);
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    private final Map<String, UUID> spawning = new ConcurrentHashMap<>(); // name -> owner (or CONSOLE) while the skin loads
    private final Set<String> respawning = new HashSet<>();
    private File rosterFile;
    private Settings settings;

    @Override
    public void onEnable() {
        String supported = getPluginMeta().getAPIVersion();
        if (!Bukkit.getMinecraftVersion().equals(supported)) {
            getSLF4JLogger().error("This build is for Minecraft {} only, the server runs {}. Download the matching version.",
                supported, Bukkit.getMinecraftVersion());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        rosterFile = new File(getDataFolder(), "bots.yml");
        reload();
        PlayerCommand command = new PlayerCommand(this);
        getCommand("player").setExecutor(command);
        getCommand("player").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(this, this);
        if (settings.rejoin()) getServer().getScheduler().runTask(this, this::rejoin);
    }

    @Override
    public void onDisable() {
        if (rosterFile == null) return; // wrong version, never enabled
        saveRoster(); // our quit listener doesn't fire from here on, so the roster survives the despawns below
        new ArrayList<>(bots.values()).forEach(b -> {
            b.despawn(Component.literal("Server stopping"));
            botTeam().removeEntry(name(b));
        });
        bots.clear();
    }

    void reload() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true); // options added by updates show up in old config files
        saveConfig();
        settings = Settings.load(getConfig());
        botTeam().prefix(settings.prefix());
        bots.values().forEach(this::applySettings);
    }

    Settings settings() {
        return settings;
    }

    // ---- messages ----

    void send(CommandSender to, String key, String... placeholders) {
        TagResolver.Builder tags = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) tags.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        to.sendMessage(MiniMessage.miniMessage().deserialize(getConfig().getString("messages." + key, key), tags.build()));
    }

    void sendHelp(CommandSender to) {
        getConfig().getStringList("messages.help").forEach(line -> to.sendMessage(MiniMessage.miniMessage().deserialize(line)));
    }

    // ---- bots ----

    Bot bot(String name) {
        return bots.get(key(name));
    }

    List<Bot> bots() {
        return List.copyOf(bots.values());
    }

    static String name(Bot bot) {
        return bot.getGameProfile().name();
    }

    boolean canControl(CommandSender sender, Bot bot) {
        return !(sender instanceof Player p) || p.getUniqueId().equals(bot.owner) || sender.hasPermission("fakeplayer.others");
    }

    void spawn(CommandSender sender, String name, Location at) {
        String key = key(name);
        UUID owner = sender instanceof Player p ? p.getUniqueId() : null;
        UUID ownerKey = owner == null ? CONSOLE : owner;
        long owned = bots.values().stream().filter(b -> Objects.equals(b.owner, owner)).count()
            + spawning.values().stream().filter(ownerKey::equals).count();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            send(sender, "invalid-name");
        } else if (bots.containsKey(key) || spawning.containsKey(key)) {
            send(sender, "already-exists", "name", name);
        } else if (Bukkit.getPlayerExact(name) != null) {
            send(sender, "real-player-online", "name", name);
        } else if (bots.size() + spawning.size() >= settings.maxBots()) {
            send(sender, "server-limit", "max", String.valueOf(settings.maxBots()));
        } else if (owner != null && owned >= settings.maxPerPlayer() && !sender.hasPermission("fakeplayer.bypass-limit")) {
            send(sender, "player-limit", "max", String.valueOf(settings.maxPerPlayer()));
        } else {
            spawning.put(key, ownerKey);
            Property spawnerSkin = settings.spawnerSkin() && sender instanceof Player p ? textures(((CraftPlayer) p).getHandle().getGameProfile()) : null;
            boolean lookup = settings.mojangSkins();
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                Property mojang = lookup ? lookupSkin(name) : null;
                Property skin = mojang != null ? mojang : spawnerSkin;
                getServer().getScheduler().runTask(this, () -> {
                    spawning.remove(key);
                    if (Bukkit.getPlayerExact(name) != null) {
                        send(sender, "real-player-online", "name", name);
                        return;
                    }
                    try {
                        spawnNow(new Bot.State(name, owner, at, skin, Map.of(), 0, 0, false, false, null));
                        send(sender, mojang != null ? "spawned" : skin != null ? "spawned-your-skin" : "spawned-default-skin", "name", name);
                    } catch (Exception e) {
                        getSLF4JLogger().error("Failed to spawn bot {}", name, e);
                        send(sender, "spawn-failed", "name", name, "error", String.valueOf(e));
                    }
                });
            });
        }
    }

    private void spawnNow(Bot.State state) {
        String name = state.name();
        botTeam().addEntry(name); // before joining, so the join message has the prefix too
        try {
            Bot bot = Bot.spawn(this, profile(name, state.skin()), state.owner(), state.location());
            bots.put(key(name), bot);
            applySettings(bot);
            bot.restore(state);
            saveRoster();
        } catch (RuntimeException e) {
            botTeam().removeEntry(name);
            throw e;
        }
    }

    private void applySettings(Bot bot) {
        Player player = bot.getBukkitEntity();
        player.setSleepingIgnored(settings.ignoreSleep());
        player.setCanPickupItems(settings.pickUpItems());
    }

    /** Called the tick after a bot died. */
    void botDied(Bot bot) {
        if (!settings.autoRespawn()) {
            bot.despawn(Component.literal("Died"));
            return;
        }
        String key = key(name(bot));
        Location respawn = bot.getBukkitEntity().getRespawnLocation();
        Bot.State state = bot.state().at(respawn != null ? respawn : Bukkit.getWorlds().getFirst().getSpawnLocation());
        respawning.add(key);
        try {
            bot.despawn(Component.literal("Respawning"));
            spawnNow(state);
        } catch (Exception e) {
            getSLF4JLogger().error("Failed to respawn bot {}", state.name(), e);
        } finally {
            respawning.remove(key);
        }
    }

    /** Bots get their own offline-style UUID, so they never share playerdata with a real player of the same name. */
    private static GameProfile profile(String name, Property skin) {
        UUID uuid = UUID.nameUUIDFromBytes(("FakePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(uuid, name, skin == null ? PropertyMap.EMPTY : new PropertyMap(ImmutableMultimap.of("textures", skin)));
    }

    /** Straight from Mojang: the server's user cache maps bot names to the bots' own UUIDs once they've joined. */
    private Property lookupSkin(String name) {
        try {
            var services = MinecraftServer.getServer().services();
            return services.profileRepository().findProfileByName(name)
                .map(account -> services.sessionService().fetchProfile(account.id(), true))
                .map(result -> textures(result.profile()))
                .orElse(null);
        } catch (Exception e) {
            getSLF4JLogger().warn("Skin lookup for {} failed: {}", name, e.toString());
            return null;
        }
    }

    private static Property textures(GameProfile profile) {
        return profile.properties().get("textures").stream().findFirst().orElse(null);
    }

    private static Team botTeam() {
        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam("fakeplayers");
        return team != null ? team : scoreboard.registerNewTeam("fakeplayers");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ---- surviving restarts ----

    private void saveRoster() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (settings.rejoin()) {
            for (Bot bot : bots.values()) {
                Bot.State st = bot.state();
                ConfigurationSection s = yaml.createSection("bots." + st.name());
                s.set("owner", st.owner() == null ? null : st.owner().toString());
                s.set("location", st.location());
                if (st.skin() != null) {
                    s.set("skin.value", st.skin().value());
                    s.set("skin.signature", st.skin().signature());
                }
                st.actions().forEach((action, interval) -> s.set("actions." + action.name(), interval));
                s.set("forward", st.forward());
                s.set("strafe", st.strafe());
                s.set("sneaking", st.sneaking());
                s.set("sprinting", st.sprinting());
                s.set("follow", st.follow() == null ? null : st.follow().toString());
            }
        }
        try {
            yaml.save(rosterFile);
        } catch (IOException e) {
            getSLF4JLogger().error("Couldn't save {}", rosterFile, e);
        }
    }

    private void rejoin() {
        ConfigurationSection all = YamlConfiguration.loadConfiguration(rosterFile).getConfigurationSection("bots");
        if (all == null) return;
        for (String name : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(name);
            try {
                Map<Bot.Action, Integer> actions = new EnumMap<>(Bot.Action.class);
                ConfigurationSection a = s.getConfigurationSection("actions");
                if (a != null) a.getKeys(false).forEach(k -> actions.put(Bot.Action.valueOf(k), a.getInt(k)));
                String owner = s.getString("owner"), follow = s.getString("follow"), skin = s.getString("skin.value");
                spawnNow(new Bot.State(name, owner == null ? null : UUID.fromString(owner), Objects.requireNonNull(s.getLocation("location")),
                    skin == null ? null : new Property("textures", skin, s.getString("skin.signature")), actions,
                    (float) s.getDouble("forward"), (float) s.getDouble("strafe"), s.getBoolean("sneaking"), s.getBoolean("sprinting"),
                    follow == null ? null : UUID.fromString(follow)));
            } catch (Exception e) {
                getSLF4JLogger().warn("Couldn't bring back bot {}: {}", name, e.toString());
            }
        }
    }

    // ---- events ----

    private static Bot asBot(Player player) {
        return ((CraftPlayer) player).getHandle() instanceof Bot bot ? bot : null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bot bot = asBot(event.getPlayer());
        if (bot != null && (!settings.joinLeaveMessages() || respawning.contains(key(name(bot))))) event.joinMessage(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bot bot = asBot(event.getPlayer());
        if (bot == null) return;
        String key = key(name(bot));
        boolean respawn = respawning.contains(key);
        if (!settings.joinLeaveMessages() || respawn) event.quitMessage(null);
        bots.remove(key, bot);
        if (!respawn) {
            botTeam().removeEntry(name(bot));
            saveRoster();
        }
    }

    /** A real player is joining with a bot's name: free the name before they're placed in the world. */
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String key = key(event.getName());
        if (!bots.containsKey(key)) return;
        try {
            getServer().getScheduler().callSyncMethod(this, () -> {
                Bot bot = bots.get(key);
                if (bot != null) bot.despawn(Component.literal("Real player " + event.getName() + " joined"));
                return null;
            }).get();
        } catch (Exception ignored) {
        }
    }
}
