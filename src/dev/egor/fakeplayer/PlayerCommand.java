package dev.egor.fakeplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
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
        "hotbar", "move", "look", "turn", "sneak", "unsneak", "sprint", "unsprint", "inventory", "stop", "remove");
    private static final List<String> MODES = List.of("once", "continuous", "interval");
    private static final String[] HELP = {
        "§6=== FakePlayer ===",
        "§7/player spawn <name> [x y z [world]] §f- spawn a bot (skin of that Mojang name, else yours)",
        "§7/player remove <name> §f| §7/player list",
        "§7/player <name> attack|use|jump [once|continuous|interval <ticks>] §f- click what it looks at",
        "§8  attack continuous = hold left click (mines), attack interval 20 = hit mobs every second",
        "§7/player <name> mine §f- same as attack continuous",
        "§7/player <name> drop|dropstack|swaphands §f| §7hotbar <1-9> §f| §7inventory",
        "§7/player <name> move forward|back|left|right|stop §f| §7sneak|unsneak|sprint|unsprint",
        "§7/player <name> look north|south|east|west|up|down|here|at <player>|<yaw> <pitch>",
        "§7/player <name> turn left|right|back §f| §7stop §f- stop all actions",
    };

    private final FakePlayerPlugin plugin;

    PlayerCommand(FakePlayerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(HELP);
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, Math.min(2, args.length), args.length);
        switch (first) {
            case "list" -> sender.sendMessage(plugin.names().isEmpty() ? "§7No bots"
                : "§6Bots (" + plugin.names().size() + "/" + FakePlayerPlugin.MAX_BOTS + "): §f" + String.join(", ", botNames()));
            case "spawn" -> {
                if (args.length < 2) sender.sendMessage("§cUsage: /player spawn <name> [x y z [world]]");
                else spawn(sender, args[1], rest);
            }
            case "remove", "kill" -> {
                Bot bot = args.length < 2 ? null : plugin.bot(args[1]);
                if (bot == null) sender.sendMessage("§cUsage: /player remove <bot>");
                else bot.despawn(Component.literal("Removed by " + sender.getName()));
            }
            default -> {
                Bot bot = plugin.bot(args[0]);
                if (args.length >= 2 && args[1].equalsIgnoreCase("spawn") && bot == null) spawn(sender, args[0], rest);
                else if (bot == null) sender.sendMessage("§cNo bot named " + args[0] + ". /player help");
                else if (args.length < 2) sender.sendMessage("§cUsage: /player " + args[0] + " <" + String.join("|", SUBCOMMANDS) + ">");
                else control(sender, bot, args[1].toLowerCase(Locale.ROOT), rest);
            }
        }
        return true;
    }

    private void spawn(CommandSender sender, String name, String[] pos) {
        Location at;
        if (pos.length >= 3) {
            try {
                World world = pos.length >= 4 ? Bukkit.getWorld(pos[3])
                    : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
                if (world == null) {
                    sender.sendMessage("§cUnknown world " + pos[3]);
                    return;
                }
                at = new Location(world, Double.parseDouble(pos[0]), Double.parseDouble(pos[1]), Double.parseDouble(pos[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cBad coordinates");
                return;
            }
        } else if (sender instanceof Player p) {
            at = p.getLocation();
        } else {
            sender.sendMessage("§cFrom console: /player spawn <name> <x> <y> <z> [world]");
            return;
        }
        plugin.spawn(sender, name, at);
    }

    private void control(CommandSender sender, Bot bot, String sub, String[] args) {
        String name = bot.getBukkitEntity().getName();
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
                sender.sendMessage("§a" + name + " stopped");
            }
            case "remove", "kill" -> bot.despawn(Component.literal("Removed by " + sender.getName()));
            case "sneak", "unsneak" -> bot.sneaking = sub.equals("sneak");
            case "sprint", "unsprint" -> bot.sprinting = sub.equals("sprint");
            case "hotbar" -> {
                int slot = args.length > 0 ? parseInt(args[0]) : -1;
                if (slot < 1 || slot > 9) sender.sendMessage("§cUsage: /player " + name + " hotbar <1-9>");
                else bot.getBukkitEntity().getInventory().setHeldItemSlot(slot - 1);
            }
            case "inventory", "inv" -> {
                if (sender instanceof Player p) p.openInventory(bot.getBukkitEntity().getInventory());
                else sender.sendMessage("§cPlayers only");
            }
            case "move" -> {
                switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
                    case "forward" -> bot.forward = 1;
                    case "back", "backward" -> bot.forward = -1;
                    case "left" -> bot.strafe = 1;
                    case "right" -> bot.strafe = -1;
                    case "stop" -> bot.forward = bot.strafe = 0;
                    default -> sender.sendMessage("§cUsage: /player " + name + " move forward|back|left|right|stop");
                }
            }
            case "turn" -> {
                float yaw = bot.getYRot();
                switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
                    case "left" -> bot.look(yaw - 90, bot.getXRot());
                    case "right" -> bot.look(yaw + 90, bot.getXRot());
                    case "back" -> bot.look(yaw + 180, bot.getXRot());
                    default -> sender.sendMessage("§cUsage: /player " + name + " turn left|right|back");
                }
            }
            case "look" -> look(sender, bot, args);
            default -> sender.sendMessage("§cUnknown: " + sub + ". /player help");
        }
    }

    /** [] or once, continuous, interval <ticks> */
    private void schedule(CommandSender sender, Bot bot, Bot.Action action, String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "once";
        String name = bot.getBukkitEntity().getName();
        String what = action.name().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "once" -> bot.setAction(action, 1, true);
            case "continuous" -> bot.setAction(action, 0, false);
            case "interval" -> {
                int ticks = args.length > 1 ? parseInt(args[1]) : -1;
                if (ticks < 1) {
                    sender.sendMessage("§cUsage: /player " + name + " " + what + " interval <ticks>");
                    return;
                }
                bot.setAction(action, ticks, false);
            }
            default -> {
                sender.sendMessage("§cUsage: /player " + name + " " + what + " [once|continuous|interval <ticks>]");
                return;
            }
        }
        if (!mode.equals("once")) sender.sendMessage("§a" + name + ": " + what + " " + String.join(" ", args));
    }

    private void look(CommandSender sender, Bot bot, String[] args) {
        String dir = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (dir) {
            case "north" -> bot.look(180, 0);
            case "south" -> bot.look(0, 0);
            case "east" -> bot.look(-90, 0);
            case "west" -> bot.look(90, 0);
            case "up" -> bot.look(bot.getYRot(), -90);
            case "down" -> bot.look(bot.getYRot(), 90);
            case "here" -> {
                if (sender instanceof Player p) bot.look(p.getYaw(), p.getPitch());
                else sender.sendMessage("§cPlayers only");
            }
            case "at" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    sender.sendMessage("§cUsage: /player <bot> look at <online player>");
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
                    sender.sendMessage("§cUsage: /player <bot> look north|south|east|west|up|down|here|at <player>|<yaw> <pitch>");
                }
            }
        }
    }

    private List<String> botNames() {
        return plugin.names().stream().map(k -> plugin.bot(k)).filter(b -> b != null).map(b -> b.getBukkitEntity().getName()).sorted().toList();
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
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (args.length) {
            case 1 -> {
                options.addAll(List.of("spawn", "remove", "list", "help"));
                options.addAll(botNames());
            }
            case 2 -> {
                if (args[0].equalsIgnoreCase("remove")) options.addAll(botNames());
                else if (plugin.bot(args[0]) != null) options.addAll(SUBCOMMANDS);
            }
            case 3 -> {
                switch (sub) {
                    case "attack", "use", "jump", "drop", "dropstack", "swaphands" -> options.addAll(MODES);
                    case "move" -> options.addAll(List.of("forward", "back", "left", "right", "stop"));
                    case "turn" -> options.addAll(List.of("left", "right", "back"));
                    case "look" -> options.addAll(List.of("north", "south", "east", "west", "up", "down", "here", "at"));
                    case "hotbar" -> options.addAll(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
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
