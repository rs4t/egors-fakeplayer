package dev.egor.fakeplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class PlayerCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("attack", "use", "jump", "mine", "drop", "dropstack", "swaphands",
        "hotbar", "move", "look", "turn", "sneak", "unsneak", "sprint", "unsprint", "follow", "unfollow", "mount", "dismount",
        "inventory", "stop", "remove");
    private static final List<String> MODES = List.of("once", "continuous", "interval");

    private final FakePlayerPlugin plugin;

    PlayerCommand(FakePlayerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.sendHelp(sender);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, Math.min(2, args.length), args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "reload" -> {
                if (!sender.hasPermission("fakeplayer.admin")) plugin.send(sender, "no-permission");
                else {
                    plugin.reload();
                    plugin.send(sender, "reloaded");
                }
            }
            case "spawn" -> {
                if (args.length < 2) plugin.send(sender, "usage", "usage", "/" + label + " spawn <name> [x y z [world]]");
                else spawn(sender, args[1], rest);
            }
            case "remove", "kill" -> {
                Bot bot = args.length < 2 ? null : controllable(sender, args[1]);
                if (args.length < 2) plugin.send(sender, "usage", "usage", "/" + label + " remove <bot>");
                else if (bot != null) remove(sender, bot);
            }
            default -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("spawn") && plugin.bot(args[0]) == null) {
                    spawn(sender, args[0], rest);
                    return true;
                }
                Bot bot = controllable(sender, args[0]);
                if (bot == null) return true;
                if (args.length < 2) plugin.send(sender, "usage", "usage", "/" + label + " " + args[0] + " <" + String.join("|", SUBCOMMANDS) + ">");
                else control(sender, bot, args[1].toLowerCase(Locale.ROOT), rest);
            }
        }
        return true;
    }

    /** The bot, if it exists and the sender may control it (otherwise tells them why). */
    private Bot controllable(CommandSender sender, String name) {
        Bot bot = plugin.bot(name);
        if (bot == null) plugin.send(sender, "unknown-bot", "name", name);
        else if (!plugin.canControl(sender, bot)) plugin.send(sender, "not-your-bot", "name", FakePlayerPlugin.name(bot));
        else return bot;
        return null;
    }

    private void list(CommandSender sender) {
        List<Bot> bots = plugin.bots();
        if (bots.isEmpty()) {
            plugin.send(sender, "list-empty");
            return;
        }
        plugin.send(sender, "list-header", "count", String.valueOf(bots.size()), "max", String.valueOf(plugin.settings().maxBots()));
        bots.stream().sorted((a, b) -> FakePlayerPlugin.name(a).compareToIgnoreCase(FakePlayerPlugin.name(b))).forEach(bot -> {
            Location at = bot.getBukkitEntity().getLocation();
            String owner = bot.owner == null ? "console" : String.valueOf(Bukkit.getOfflinePlayer(bot.owner).getName());
            plugin.send(sender, "list-entry", "name", FakePlayerPlugin.name(bot), "owner", owner, "world", at.getWorld().getName(),
                "x", String.valueOf(at.getBlockX()), "y", String.valueOf(at.getBlockY()), "z", String.valueOf(at.getBlockZ()));
        });
    }

    private void remove(CommandSender sender, Bot bot) {
        String name = FakePlayerPlugin.name(bot);
        bot.despawn(Component.literal("Removed by " + sender.getName()));
        plugin.send(sender, "removed", "name", name);
    }

    private void spawn(CommandSender sender, String name, String[] pos) {
        if (!sender.hasPermission("fakeplayer.spawn")) {
            plugin.send(sender, "no-permission");
            return;
        }
        Location at;
        if (pos.length >= 3) {
            if (!sender.hasPermission("fakeplayer.spawn.location")) {
                plugin.send(sender, "no-permission");
                return;
            }
            World world = pos.length >= 4 ? Bukkit.getWorld(pos[3]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
            if (world == null) {
                plugin.send(sender, "unknown-world", "world", pos[3]);
                return;
            }
            try {
                at = new Location(world, Double.parseDouble(pos[0]), Double.parseDouble(pos[1]), Double.parseDouble(pos[2]));
            } catch (NumberFormatException e) {
                plugin.send(sender, "bad-coordinates");
                return;
            }
        } else if (sender instanceof Player p) {
            at = p.getLocation();
        } else {
            plugin.send(sender, "console-spawn", "name", name);
            return;
        }
        plugin.spawn(sender, name, at);
    }

    /** Permission / config name of a subcommand (unsneak is controlled by "sneak" etc.). */
    private static String actionName(String sub) {
        return switch (sub) {
            case "unsneak" -> "sneak";
            case "unsprint" -> "sprint";
            case "unfollow" -> "follow";
            case "dismount" -> "mount";
            case "inv" -> "inventory";
            default -> sub;
        };
    }

    private void control(CommandSender sender, Bot bot, String sub, String[] args) {
        String name = FakePlayerPlugin.name(bot);
        String action = actionName(sub);
        if (!sub.equals("stop") && !sub.equals("remove") && !sub.equals("kill")) {
            if (plugin.settings().disabledActions().contains(action)) {
                plugin.send(sender, "action-disabled");
                return;
            }
            if (!sender.hasPermission("fakeplayer.action." + action)) {
                plugin.send(sender, "no-permission");
                return;
            }
        }
        switch (sub) {
            case "attack" -> schedule(sender, bot, Bot.Action.ATTACK, args);
            case "use" -> schedule(sender, bot, Bot.Action.USE, args);
            case "jump" -> schedule(sender, bot, Bot.Action.JUMP, args);
            case "drop" -> schedule(sender, bot, Bot.Action.DROP, args);
            case "dropstack" -> schedule(sender, bot, Bot.Action.DROP_STACK, args);
            case "swaphands" -> schedule(sender, bot, Bot.Action.SWAP_HANDS, args);
            case "mine" -> schedule(sender, bot, Bot.Action.ATTACK, new String[] {"continuous"});
            case "stop" -> {
                bot.stopAll();
                plugin.send(sender, "stopped", "name", name);
            }
            case "remove", "kill" -> remove(sender, bot);
            case "sneak", "unsneak" -> bot.sneaking = sub.equals("sneak");
            case "sprint", "unsprint" -> bot.sprinting = sub.equals("sprint");
            case "hotbar" -> {
                int slot = args.length > 0 ? parseInt(args[0]) : -1;
                if (slot < 1 || slot > 9) plugin.send(sender, "usage", "usage", "/player " + name + " hotbar <1-9>");
                else bot.getBukkitEntity().getInventory().setHeldItemSlot(slot - 1);
            }
            case "inventory", "inv" -> {
                if (sender instanceof Player p) p.openInventory(bot.getBukkitEntity().getInventory());
                else plugin.send(sender, "players-only");
            }
            case "follow" -> follow(sender, bot, args);
            case "unfollow" -> {
                bot.follow = null;
                bot.forward = 0;
                bot.sprinting = false;
                plugin.send(sender, "not-following", "name", name);
            }
            case "mount" -> {
                Entity vehicle = bot.mount();
                if (vehicle == null) plugin.send(sender, "nothing-to-mount", "name", name);
                else plugin.send(sender, "mounted", "name", name, "vehicle", vehicle.getBukkitEntity().getName());
            }
            case "dismount" -> bot.stopRiding();
            case "move" -> {
                bot.follow = null;
                switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
                    case "forward" -> bot.forward = 1;
                    case "back", "backward" -> bot.forward = -1;
                    case "left" -> bot.strafe = 1;
                    case "right" -> bot.strafe = -1;
                    case "stop" -> bot.forward = bot.strafe = 0;
                    default -> plugin.send(sender, "usage", "usage", "/player " + name + " move forward|back|left|right|stop");
                }
            }
            case "turn" -> {
                float yaw = bot.getYRot();
                switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
                    case "left" -> bot.look(yaw - 90, bot.getXRot());
                    case "right" -> bot.look(yaw + 90, bot.getXRot());
                    case "back" -> bot.look(yaw + 180, bot.getXRot());
                    default -> plugin.send(sender, "usage", "usage", "/player " + name + " turn left|right|back");
                }
            }
            case "look" -> look(sender, bot, args);
            default -> plugin.sendHelp(sender);
        }
    }

    private void follow(CommandSender sender, Bot bot, String[] args) {
        String name = FakePlayerPlugin.name(bot);
        if (!plugin.settings().follow()) {
            plugin.send(sender, "follow-disabled");
            return;
        }
        Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player p ? p : null;
        if (target == null) {
            if (args.length > 0) plugin.send(sender, "player-not-found", "target", args[0]);
            else plugin.send(sender, "usage", "usage", "/player " + name + " follow <player>");
            return;
        }
        bot.follow = target.getUniqueId();
        plugin.send(sender, "following", "name", name, "target", target.getName());
    }

    /** [] or once, continuous, interval <ticks> */
    private void schedule(CommandSender sender, Bot bot, Bot.Action action, String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "once";
        String name = FakePlayerPlugin.name(bot);
        String what = action.name().toLowerCase(Locale.ROOT).replace("_", "");
        switch (mode) {
            case "once" -> bot.setAction(action, 1, true);
            case "continuous" -> bot.setAction(action, 0, false);
            case "interval" -> {
                int ticks = args.length > 1 ? parseInt(args[1]) : -1;
                if (ticks < 1) {
                    plugin.send(sender, "usage", "usage", "/player " + name + " " + what + " interval <ticks>");
                    return;
                }
                bot.setAction(action, ticks, false);
            }
            default -> {
                plugin.send(sender, "usage", "usage", "/player " + name + " " + what + " [once|continuous|interval <ticks>]");
                return;
            }
        }
        if (!mode.equals("once")) plugin.send(sender, "action-set", "name", name, "action", what + " " + String.join(" ", args));
    }

    private void look(CommandSender sender, Bot bot, String[] args) {
        String usage = "/player " + FakePlayerPlugin.name(bot) + " look north|south|east|west|up|down|here|at <player>|<yaw> <pitch>";
        switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
            case "north" -> bot.look(180, 0);
            case "south" -> bot.look(0, 0);
            case "east" -> bot.look(-90, 0);
            case "west" -> bot.look(90, 0);
            case "up" -> bot.look(bot.getYRot(), -90);
            case "down" -> bot.look(bot.getYRot(), 90);
            case "here" -> {
                if (sender instanceof Player p) bot.look(p.getYaw(), p.getPitch());
                else plugin.send(sender, "players-only");
            }
            case "at" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    plugin.send(sender, "player-not-found", "target", args.length > 1 ? args[1] : "?");
                    return;
                }
                Vector d = target.getEyeLocation().toVector().subtract(bot.getBukkitEntity().getEyeLocation().toVector());
                bot.look((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())),
                    (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))));
            }
            default -> {
                try {
                    bot.look(Float.parseFloat(args[0]), Float.parseFloat(args[1]));
                } catch (RuntimeException e) {
                    plugin.send(sender, "usage", "usage", usage);
                }
            }
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        List<String> mine = plugin.bots().stream().filter(b -> plugin.canControl(sender, b)).map(FakePlayerPlugin::name).toList();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (args.length) {
            case 1 -> {
                options.addAll(List.of("spawn", "remove", "list", "help"));
                if (sender.hasPermission("fakeplayer.admin")) options.add("reload");
                options.addAll(mine);
            }
            case 2 -> {
                if (args[0].equalsIgnoreCase("remove")) options.addAll(mine);
                else if (mine.stream().anyMatch(args[0]::equalsIgnoreCase)) options.addAll(SUBCOMMANDS);
            }
            case 3 -> {
                switch (sub) {
                    case "attack", "use", "jump", "drop", "dropstack", "swaphands" -> options.addAll(MODES);
                    case "move" -> options.addAll(List.of("forward", "back", "left", "right", "stop"));
                    case "turn" -> options.addAll(List.of("left", "right", "back"));
                    case "look" -> options.addAll(List.of("north", "south", "east", "west", "up", "down", "here", "at"));
                    case "hotbar" -> options.addAll(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
                    case "follow" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                    default -> {}
                }
            }
            case 4 -> {
                if (args[2].equalsIgnoreCase("interval")) options.addAll(List.of("10", "20", "40"));
                else if (sub.equals("look") && args[2].equalsIgnoreCase("at")) Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            }
            default -> {}
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
