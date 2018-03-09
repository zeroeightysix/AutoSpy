package me.zeroeightsix.autospy;

import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.gamemode.GameModes;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.chat.ChatTypes;
import org.spongepowered.api.text.format.TextColors;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Created by 086 on 2/03/2018.
 */

@Plugin(name = "AutoSpy", id = "auto-spy", version = "1.0", description = "Automatically teleport and look at players")
public class AutoSpy {

    // Keeps track of our scheduled spying tasks
    HashMap<Player, Pair<Task, Iterator<Player>>> taskHashMap = new HashMap<>();

    private CommandSpec spySpec = CommandSpec.builder()
            .description(Text.of(AutoSpy.class.getAnnotation(Plugin.class).description())) // hackerman
            .executor(new SpyExecutor(this))
            .permission("autospy.spyall")
            .arguments(
                    GenericArguments.optional(GenericArguments.integer(Text.of("seconds"))),
                    GenericArguments.optional(GenericArguments.integer(Text.of("loadinterval")))
            )
            .build();
    private Logger logger;

    @Inject
    public AutoSpy(Logger logger) {
        this.logger = logger;
    }

    @Listener
    public void onServerStart(GameStartedServerEvent event){
        Sponge.getCommandManager().register(this, spySpec, "spyall", "autospy"); // Register our beloved /shrug command so we can actually use our beloved /shrug command
    }

    @Listener
    public void onPlayerLeave(ClientConnectionEvent.Disconnect event) {
        if (taskHashMap.containsKey(event.getTargetEntity()))
            removePlayer(event.getTargetEntity());
    }

    /**
     * Remove a player from the task map, cancel the task and return if the task was cancelled successfully
     * @param player
     * @return
     */
    private boolean removePlayer(Player player) {
        Task task = taskHashMap.get(player).key;
        boolean failed = task.cancel();
        if (failed) return false;
        logger.info("Removing " + player.getName() + " from autospy.");
        taskHashMap.remove(player);
        return true;
    }

    /**
     * Returns a new iterator that iterates over the online player list.
     * One is stored with each spying player and is used to determine who to spy next.
     * @return
     */
    private Iterator<Player> getPlayerIterator() {
        return Sponge.getServer().getOnlinePlayers().iterator();
    }

    /**
     * Supply the spying player with a new player iterator
     * @param spying
     */
    private void refreshIterator(Player spying) {
        taskHashMap.put(spying, new Pair<>(taskHashMap.get(spying).key, getPlayerIterator()));
    }

    private class SpyExecutor implements CommandExecutor {
        AutoSpy pluginInstance;

        public SpyExecutor(AutoSpy pluginInstance) {
            this.pluginInstance = pluginInstance;
        }

        @Override
        public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
            if (src instanceof Player) {
                if (taskHashMap.containsKey(src)) {
                    // The player is already in autospy, so we'll abort the task, send them a message and remove them from the map.
                    if (removePlayer((Player) src))
                        src.sendMessage(Text.builder("No longer autospying.").color(TextColors.GREEN).build());
                    else
                        src.sendMessage(Text.builder("Oops! Something went wrong aborting the spying task! Try again?").color(TextColors.RED).build()); // please don't happen
                }else{
                    int seconds = 5; // Our default is 5 seconds
                    if (args.hasAny("seconds")) seconds = args.<Integer>getOne("seconds").orElse(null); // This **should** also never produce a NPE, if it does, blame sponge!!
                    int interval = 5; // Our default is 5 seconds
                    if (args.hasAny("loadinterval")) interval = args.<Integer>getOne("loadinterval").orElse(null); // Same as ^

                    Player player = (Player) src;
                    // Set the player's gamemode to spectator. If they are to manually go back to another mode, autospying is terminated.
                    player.offer(player.gameMode().set(GameModes.SPECTATOR));
                    // Add this player to the task map
                    taskHashMap.put(player, new Pair<>(null, getPlayerIterator()));
                    int finalInterval = interval; // lambda -> interval needs to be final
                    Task task = Task.builder().execute(() -> spy(player, finalInterval))
                            .async()
                            .interval(seconds, TimeUnit.SECONDS)
                            .name("SpyAll for " + src.getName())
                            .submit(pluginInstance);
                    // Update the entry with the task we just started.
                    // The entry has to be there already when the task starts to avoid a NPE when getting the iterator!
                    taskHashMap.put(player, new Pair<>(task, getPlayerIterator()));
                    player.sendMessage(Text.builder("Now autospying.").color(TextColors.AQUA).build());
                }
            }else{
                src.sendMessage(Text.builder("Sorry, but only a player can execute that command!").color(TextColors.RED).build());
            }
            return CommandResult.success();
        }

        private void spy(Player src, int interval) {
            if (src.gameMode().get() != GameModes.SPECTATOR) { // Our spying player has manually removed themselves from spectator mode, we assume they don't quite feel like spying anymore.
                removePlayer(src);
                src.sendMessage(Text.builder("No longer autospying.").color(TextColors.DARK_GREEN).build());
                return;
            }
            // If all players online should be ignored for spying, quit
            if (Sponge.getServer().getOnlinePlayers().stream().allMatch(AutoSpy.this::ignorePlayer))
                return;

            Iterator<Player> iterator = taskHashMap.get(src).value;

            Player toSpy = null;
            while (toSpy==null) {
                if (!iterator.hasNext()) { // If we have reached the end of the player list, 'refresh' the iterator to start from the beginning again.
                    refreshIterator(src);
                    iterator = taskHashMap.get(src).value;
                }
                toSpy = iterator.next();
                if (ignorePlayer(toSpy)) toSpy = null; // If the chosen player is to be ignored, loop to the next
            }

            Player finalToSpy = toSpy;
            Task.builder().execute(() -> src.setSpectatorTarget(null)).submit(pluginInstance);
            Task.builder().delayTicks(2).execute(() -> src.setLocation(finalToSpy.getLocation())).submit(pluginInstance);
            Task.builder().delayTicks(interval).execute(() -> {
                src.setSpectatorTarget(finalToSpy);
                src.sendMessage(ChatTypes.ACTION_BAR, Text.builder("Now spectating: ")
                        .color(TextColors.GRAY)
                        .append(Text.builder(finalToSpy.getName()).color(TextColors.WHITE).build()) // Required to set colour to WHITE because otherwise it inherits the previous colour
                        .build());
            }).submit(pluginInstance);
        }
    }

    private boolean ignorePlayer(Player player) {
        return !player.isOnline() || player.hasPermission("autospy.exempt") || player.hasPermission("autospy.spyall") || taskHashMap.containsKey(player);
    }

    private class Pair<T, S> {
        T key;
        S value;

        public Pair(T key, S value) {
            this.key = key;
            this.value = value;
        }
    }
}
