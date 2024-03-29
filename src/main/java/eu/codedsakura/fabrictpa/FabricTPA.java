package eu.codedsakura.fabrictpa;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.codedsakura.mods.ConfigUtils;
import eu.codedsakura.mods.TeleportUtils;
import eu.codedsakura.mods.fpapiutils.FPAPIUtilsWrapper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.command.argument.EntityArgumentType.getPlayer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class FabricTPA implements ModInitializer {
    private static final Logger logger = LogManager.getLogger("FabricTPA");
    private static final String CONFIG_NAME = "FabricTPA.properties";

    private final ArrayList<TPARequest> activeTPA = new ArrayList<>();
    private final HashMap<UUID, Long> recentRequests = new HashMap<>();
    private ConfigUtils config;

    @Nullable
    private static CompletableFuture<Suggestions> filterSuggestionsByInput(SuggestionsBuilder builder, List<String> values) {
        String start = builder.getRemaining().toLowerCase();
        values.stream().filter(s -> s.toLowerCase().startsWith(start)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> getTPAInitSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = Stream.concat(
                activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName()),
                activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName())
        ).toList();
        List<String> others = Arrays.stream(scs.getServer().getPlayerNames())
                .filter(s -> !s.equals(scs.getName()) && !activeTargets.contains(s))
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, others);
    }

    private CompletableFuture<Suggestions> getTPATargetSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getTPASenderSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    static class CooldownModeConfigValue extends ConfigUtils.IConfigValue<TPACooldownMode> {
        public CooldownModeConfigValue(@NotNull String name, TPACooldownMode defaultValue, @Nullable ConfigUtils.Command command) {
            super(name, defaultValue, null, command, (context, builder) -> {
                List<String> tcmValues = Arrays.stream(TPACooldownMode.values()).map(String::valueOf).collect(Collectors.toList());
                return filterSuggestionsByInput(builder, tcmValues);
            });
        }

        @Override
        public TPACooldownMode getFromProps(Properties props) {
            return TPACooldownMode.valueOf(props.getProperty(name));
        }

        @Override
        public ArgumentType<?> getArgumentType() {
            return StringArgumentType.string();
        }

        @Override
        public TPACooldownMode parseArgumentValue(CommandContext<ServerCommandSource> ctx) {
            return TPACooldownMode.valueOf(StringArgumentType.getString(ctx, name));
        }
    }

    @Override
    public void onInitialize() {
        logger.info("Initializing...");

        config = new ConfigUtils(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME).toFile(), logger, Arrays.asList(new ConfigUtils.IConfigValue[] {
                new ConfigUtils.IntegerConfigValue("timeout", 60, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Timeout is %s seconds", "Timeout set to %s seconds")),
                new ConfigUtils.IntegerConfigValue("stand-still", 5, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Stand-Still time is %s seconds", "Stand-Still time set to %s seconds")),
                new ConfigUtils.IntegerConfigValue("cooldown", 5, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Cooldown is %s seconds", "Cooldown set to %s seconds")),
                new ConfigUtils.BooleanConfigValue("bossbar", true,
                        new ConfigUtils.Command("Boss-Bar on: %s", "Boss-Bar is now: %s")),
                new CooldownModeConfigValue("cooldown-mode", TPACooldownMode.WhoTeleported,
                        new ConfigUtils.Command("Cooldown Mode is %s", "Cooldown Mode set to %s"))
        }));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("tpa")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaInit(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpahere")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaHere(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpaaccept")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaAccept(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaAccept(ctx, null)));

            dispatcher.register(literal("tpadeny")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaDeny(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaDeny(ctx, null)));

            dispatcher.register(literal("tpacancel")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPASenderSuggestions)
                            .executes(ctx -> tpaCancel(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaCancel(ctx, null)));

            dispatcher.register(config.generateCommand("tpaconfig", FPAPIUtilsWrapper.require("fabrictpa.config", 2)));
        });

    }

    public int tpaInit(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tTo) throws CommandSyntaxException {
        final ServerPlayerEntity tFrom = ctx.getSource().getPlayer();

        if (tFrom.equals(tTo)) {
            tFrom.sendMessage(Text.literal("You cannot request to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (checkCooldown(tFrom)) return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, false, (int) config.getValue("timeout") * 1000);
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tFrom.sendMessage(Text.literal("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tFrom.sendMessage(Text.literal("Your teleport request to " + tTo.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
            tTo.sendMessage(Text.literal("Teleport request from " + tFrom.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
        });
        activeTPA.add(tr);

        tFrom.sendMessage(
                Text.literal("You have requested to teleport to ").formatted(Formatting.LIGHT_PURPLE)
                        .append(Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal("\nTo cancel type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + config.getValue("timeout") + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);

        tTo.sendMessage(
                Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" has requested to teleport to you!").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("\nTo accept type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nTo deny type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + config.getValue("timeout") + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    public int tpaHere(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tFrom) throws CommandSyntaxException {
        final ServerPlayerEntity tTo = ctx.getSource().getPlayer();

        if (tTo.equals(tFrom)) {
            tTo.sendMessage(Text.literal("You cannot request for you to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (checkCooldown(tFrom)) return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, true, (int) config.getValue("timeout") * 1000);
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tTo.sendMessage(Text.literal("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tTo.sendMessage(Text.literal("Your teleport request for " + tFrom.getEntityName() + " to you has timed out!").formatted(Formatting.RED), false);
            tFrom.sendMessage(Text.literal("Teleport request for you to " + tTo.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
        });
        activeTPA.add(tr);

        tTo.sendMessage(
                Text.literal("You have requested for ").formatted(Formatting.LIGHT_PURPLE)
                        .append(Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal(" to teleport to you!\nTo cancel type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + config.getValue("timeout") + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);

        tFrom.sendMessage(
                Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" has requested for you to teleport to them!").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("\nTo accept type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nTo deny type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + config.getValue("timeout") + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    private boolean checkCooldown(ServerPlayerEntity tFrom) {
        if (recentRequests.containsKey(tFrom.getUuid())) {
            long diff = Instant.now().getEpochSecond() - recentRequests.get(tFrom.getUuid());
            if (diff < (int) config.getValue("cooldown")) {
                tFrom.sendMessage(Text.literal("You cannot make a request for ").append(String.valueOf((int) config.getValue("cooldown") - diff))
                        .append(" more seconds!").formatted(Formatting.RED), false);
                return true;
            }
        }
        return false;
    }

    private enum TPAAction {
        ACCEPT, DENY, CANCEL
    }

    private TPARequest getTPARequest(ServerPlayerEntity rFrom, ServerPlayerEntity rTo, TPAAction action) {
        Optional<TPARequest> otr = activeTPA.stream()
                .filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom) && tpaRequest.rTo.equals(rTo)).findFirst();

        if (otr.isEmpty()) {
            if (action == TPAAction.CANCEL) {
                rFrom.sendMessage(Text.literal("No ongoing request!").formatted(Formatting.RED), false);
            } else {
                rTo.sendMessage(Text.literal("No ongoing request!").formatted(Formatting.RED), false);
            }
            return null;
        }

        return otr.get();
    }

    public int tpaAccept(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();

        if (rFrom == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify whose request to accept.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.ACCEPT);
        if (tr == null) return 1;
        TeleportUtils.genericTeleport((boolean) config.getValue("bossbar"), (int) config.getValue("stand-still"), rFrom, () -> {
            if (tr.tFrom.isRemoved() || tr.tTo.isRemoved()) tr.refreshPlayers();
            tr.tFrom.teleport(tr.tTo.getWorld(), tr.tTo.getX(), tr.tTo.getY(), tr.tTo.getZ(), tr.tTo.getYaw(), tr.tTo.getPitch());
            switch ((TPACooldownMode) config.getValue("cooldown-mode")) {
                case BothUsers -> {
                    recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                    recentRequests.put(tr.tTo.getUuid(), Instant.now().getEpochSecond());
                }
                case WhoInitiated -> recentRequests.put(tr.rFrom.getUuid(), Instant.now().getEpochSecond());
                case WhoTeleported -> recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
            }
        });

        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("You have accepted the teleport request!"), false);
        tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has accepted the teleportation request!").formatted(Formatting.LIGHT_PURPLE)), false);
        return 1;
    }


    public int tpaDeny(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();

        if (rFrom == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify whose request to deny.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.DENY);
        if (tr == null) return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("You have cancelled the teleport request!"), false);
        tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
        return 1;
    }

    public int tpaCancel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rTo) throws CommandSyntaxException {
        final ServerPlayerEntity rFrom = ctx.getSource().getPlayer();

        if (rTo == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify which request to cancel.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rTo.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rFrom.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rFrom.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rTo = candidates[0].rTo;
        }

        System.out.printf("%s -> %s\n", rFrom.getEntityName(), rTo.getEntityName());
        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.CANCEL);
        if (tr == null) return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rFrom.sendMessage(Text.literal("You have cancelled the teleport request!").formatted(Formatting.RED), false);
        tr.rTo.sendMessage(Text.literal(tr.rFrom.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
        return 1;
    }


    enum TPACooldownMode {
        WhoTeleported, WhoInitiated, BothUsers
    }

    static class TPARequest {
        ServerPlayerEntity tFrom;
        ServerPlayerEntity tTo;

        ServerPlayerEntity rFrom;
        ServerPlayerEntity rTo;

        boolean tpaHere;
        long timeout;

        Timer timer;

        public TPARequest(ServerPlayerEntity tFrom, ServerPlayerEntity tTo, boolean tpaHere, int timeoutMS) {
            this.tFrom = tFrom;
            this.tTo = tTo;
            this.tpaHere = tpaHere;
            this.timeout = timeoutMS;
            this.rFrom = tpaHere ? tTo : tFrom;
            this.rTo = tpaHere ? tFrom : tTo;
        }

        void setTimeoutCallback(Timeout callback) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    callback.onTimeout();
                }
            }, timeout);
        }

        void cancelTimeout() {
            timer.cancel();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TPARequest that = (TPARequest) o;
            return tFrom.equals(that.tFrom) &&
                    tTo.equals(that.tTo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tFrom, tTo);
        }

        @Override
        public String toString() {
            return "TPARequest{" + "tFrom=" + tFrom +
                    ", tTo=" + tTo +
                    ", rFrom=" + rFrom +
                    ", rTo=" + rTo +
                    ", tpaHere=" + tpaHere +
                    '}';
        }

        public void refreshPlayers() {
            this.tFrom = tFrom.server.getPlayerManager().getPlayer(tFrom.getUuid());
            this.tTo = tTo.server.getPlayerManager().getPlayer(tTo.getUuid());
            this.rFrom = this.tpaHere ? tTo : tFrom;
            this.rTo = this.tpaHere ? tFrom : tTo;
            assert tFrom != null && tTo != null;
        }
    }

    interface Timeout {
        void onTimeout();
    }
}
