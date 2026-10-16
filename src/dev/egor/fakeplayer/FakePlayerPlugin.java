package dev.egor.fakeplayer;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

public final class FakePlayerPlugin extends JavaPlugin implements Listener {
    // ponytail: hard cap, each bot loads chunks like a real player. Make it a config value if 5 isn't enough.
    static final int MAX_BOTS = 5;
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    private final Set<String> spawning = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        PlayerCommand command = new PlayerCommand(this);
        getCommand("player").setExecutor(command);
        getCommand("player").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // our quit listener no longer fires once disabling started, so clean up here
        new ArrayList<>(bots.values()).forEach(b -> {
            b.despawn(Component.literal("Plugin disabled"));
            botTeam().removeEntry(b.getBukkitEntity().getName());
        });
        bots.clear();
    }

    Bot bot(String name) {
        return bots.get(name.toLowerCase(Locale.ROOT));
    }

    Set<String> names() {
        return bots.keySet();
    }

    void spawn(CommandSender sender, String name, Location at) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            sender.sendMessage("§cName must be 1-16 letters, digits or _");
        } else if (bots.containsKey(key) || spawning.contains(key)) {
            sender.sendMessage("§cBot " + name + " already exists");
        } else if (Bukkit.getPlayerExact(name) != null) {
            sender.sendMessage("§c" + name + " is a real player and is online");
        } else if (bots.size() + spawning.size() >= MAX_BOTS) {
            sender.sendMessage("§cBot limit reached (" + MAX_BOTS + ")");
        } else {
            spawning.add(key);
            Property fallbackSkin = sender instanceof Player p ? textures(((CraftPlayer) p).getHandle().getGameProfile()) : null;
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                Property skin = lookupSkin(name);
                Property finalSkin = skin != null ? skin : fallbackSkin;
                getServer().getScheduler().runTask(this, () -> {
                    spawning.remove(key);
                    if (Bukkit.getPlayerExact(name) != null) {
                        sender.sendMessage("§c" + name + " joined while the bot was spawning");
                        return;
                    }
                    botTeam().addEntry(name); // before joining, so the join message has the prefix too
                    try {
                        Bot bot = Bot.spawn(this, profile(name, finalSkin), ((CraftWorld) at.getWorld()).getHandle(),
                            at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
                        bots.put(key, bot);
                        Player player = bot.getBukkitEntity();
                        player.setSleepingIgnored(true); // don't block night skipping
                        sender.sendMessage("§aSpawned " + name + (skin != null ? "" : finalSkin != null ? " §7(no Mojang skin for that name, using yours)" : " §7(default skin)"));
                    } catch (Exception e) {
                        botTeam().removeEntry(name);
                        getSLF4JLogger().error("Failed to spawn bot {}", name, e);
                        sender.sendMessage("§cFailed to spawn " + name + ": " + e);
                    }
                });
            });
        }
    }

    /**
     * Bots get their own offline-style UUID (same one 1.15 used for names without a Mojang account),
     * so they never share playerdata with a real player of the same name.
     */
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
        if (team == null) {
            team = scoreboard.registerNewTeam("fakeplayers");
            team.prefix(net.kyori.adventure.text.Component.text("[Bot] ", NamedTextColor.GRAY));
        }
        return team;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (((CraftPlayer) event.getPlayer()).getHandle() instanceof Bot bot) {
            bots.values().remove(bot);
            botTeam().removeEntry(event.getPlayer().getName());
        }
    }

    /** A real player is joining with a bot's name: free the name before they're placed in the world. */
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String key = event.getName().toLowerCase(Locale.ROOT);
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
