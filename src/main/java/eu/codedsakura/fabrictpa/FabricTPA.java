package eu.codedsakura.fabrictpa;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.CommandBossBar;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.*;
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

    private final ArrayList<TPARequest> activeTPA = new ArrayList<>();
    private final HashMap<UUID, Long> recentRequests = new HashMap<>();
    private final String CONFIG_NAME = "FabricTPA.properties";

    protected static int tpaTimeoutSeconds = 60;
    protected static int tpaStandStillSeconds = 5;
    protected static int tpaCooldownSeconds = 5;
    protected static boolean tpaDisableBossBar = false;
    protected static TPACooldownMode tpaCooldownMode = TPACooldownMode.WhoTeleported;

    @Nullable
    private static CompletableFuture<Suggestions> filterSuggestionsByInput(SuggestionsBuilder builder, List<String> values) {
        String start = builder.getRemaining().toLowerCase();
        values.stream().filter(s -> s.toLowerCase().startsWith(start)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> getTPAInitSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = Stream.concat(
                activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getName().asString()),
                activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getName().asString())
        ).collect(Collectors.toList());
        List<String> others = Arrays.stream(scs.getMinecraftServer().getPlayerNames())
                .filter(s -> !s.equals(scs.getName()) && !activeTargets.contains(s))
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, others);
    }

    private CompletableFuture<Suggestions> getTPATargetSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getName().asString()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getTPASenderSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getName().asString()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getTCMSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> tcmValues = Arrays.stream(TPACooldownMode.values()).map(String::valueOf).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, tcmValues);
    }

    @Override
    public void onInitialize() {
        logger.info("Initializing...");
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("tpa")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaInit(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpahere")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaHere(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpaaccept")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaAccept(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaAccept(ctx, null)));

            dispatcher.register(literal("tpadeny")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaDeny(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaDeny(ctx, null)));

            dispatcher.register(literal("tpacancel")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPASenderSuggestions)
                            .executes(ctx -> tpaCancel(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaCancel(ctx, null)));

            dispatcher.register(literal("tpaconfig").requires(source -> source.hasPermissionLevel(4))
                    .then(literal("timeout")
                            .then(argument("timeout", IntegerArgumentType.integer(0))
                                    .executes(ctx -> {
                                        tpaTimeoutSeconds = IntegerArgumentType.getInteger(ctx, "timeout");
                                        ctx.getSource().sendFeedback(new LiteralText("Timeout is now ").append(String.valueOf(tpaTimeoutSeconds)), true);
                                        saveSettings();
                                        return 1;
                                    }))
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(new LiteralText("Timeout is ").append(String.valueOf(tpaTimeoutSeconds)), false);
                                return 1;
                            }))
                    .then(literal("stand-still")
                            .then(argument("stand-still", IntegerArgumentType.integer(0))
                                    .executes(ctx -> {
                                        tpaStandStillSeconds = IntegerArgumentType.getInteger(ctx, "stand-still");
                                        ctx.getSource().sendFeedback(new LiteralText("Stand-Still time is now ").append(String.valueOf(tpaStandStillSeconds)), true);
                                        saveSettings();
                                        return 1;
                                    }))
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(new LiteralText("Stand-Still time is ").append(String.valueOf(tpaStandStillSeconds)), false);
                                return 1;
                            }))
                    .then(literal("cooldown")
                            .then(argument("cooldown", IntegerArgumentType.integer(0))
                                    .executes(ctx -> {
                                        tpaCooldownSeconds = IntegerArgumentType.getInteger(ctx, "cooldown");
                                        ctx.getSource().sendFeedback(new LiteralText("Cooldown is now ").append(String.valueOf(tpaCooldownSeconds)), true);
                                        saveSettings();
                                        return 1;
                                    }))
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(new LiteralText("Cooldown is ").append(String.valueOf(tpaCooldownSeconds)), false);
                                return 1;
                            }))
                    .then(literal("disable-bossbar")
                            .then(argument("state", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        tpaDisableBossBar = BoolArgumentType.getBool(ctx, "state");
                                        ctx.getSource().sendFeedback(new LiteralText("BossBar disabled set to ").append(String.valueOf(tpaDisableBossBar)), true);
                                        saveSettings();
                                        return 1;
                                    }))
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(new LiteralText("BossBar disabled: ").append(String.valueOf(tpaDisableBossBar)), false);
                                return 1;
                            }))
                    .then(literal("cooldown-mode")
                            .then(argument("cooldown-mode", StringArgumentType.string()).suggests(this::getTCMSuggestions)
                                    .executes(ctx -> {
                                        try {
                                            tpaCooldownMode = TPACooldownMode.valueOf(StringArgumentType.getString(ctx, "cooldown-mode"));
                                            ctx.getSource().sendFeedback(new LiteralText("Cooldown Mode set to ").append(String.valueOf(tpaCooldownMode)), true);
                                            saveSettings();
                                            return 1;
                                        } catch (Exception ex) {
                                            throw new SimpleCommandExceptionType(new LiteralText("That's not a valid Cooldown Mode!")).create();
                                        }
                                    }))
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(new LiteralText("Cooldown Mode: ").append(String.valueOf(tpaCooldownMode)), false);
                                return 1;
                            }))
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(new LiteralText("Timeout is ").append(String.valueOf(tpaTimeoutSeconds)), false);
                        ctx.getSource().sendFeedback(new LiteralText("Stand-Still time is ").append(String.valueOf(tpaStandStillSeconds)), false);
                        ctx.getSource().sendFeedback(new LiteralText("Cooldown is ").append(String.valueOf(tpaCooldownSeconds)), false);
                        ctx.getSource().sendFeedback(new LiteralText("Cooldown Mode: ").append(String.valueOf(tpaCooldownMode)), false);
                        ctx.getSource().sendFeedback(new LiteralText("BossBar disabled: ").append(String.valueOf(tpaDisableBossBar)), false);
                        return 1;
                    }));
        });

        File propFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME).toFile();
        logger.debug("Config file: {}", propFile);
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(propFile)) {
            props.load(input);

            tpaTimeoutSeconds = (int) Double.parseDouble(props.getProperty("timeout", String.valueOf(tpaTimeoutSeconds)));
            tpaStandStillSeconds = (int) Double.parseDouble(props.getProperty("standStill", String.valueOf(tpaStandStillSeconds)));
            tpaCooldownSeconds = (int) Double.parseDouble(props.getProperty("cooldown", String.valueOf(tpaCooldownSeconds)));
            tpaCooldownMode = TPACooldownMode.valueOf(props.getProperty("cooldownMode", String.valueOf(tpaCooldownMode)));
            tpaDisableBossBar = Boolean.parseBoolean(props.getProperty("disableBossBar", String.valueOf(tpaDisableBossBar)));

            logger.debug("Reading config...");
        } catch (FileNotFoundException ignored) {
            setDefaultPropValues(props);
            logger.debug("Initialising config...");
        } catch (IOException e) {
            logger.fatal("Failed to load config file!");
            e.printStackTrace();
        } catch (Exception e) {
            logger.fatal("Failed to parse the config!");
            e.printStackTrace();
        }

        try (OutputStream output = new FileOutputStream(propFile)) {
            logger.debug("Writing config...");
            props.store(output, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveSettings() {
        File propFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME).toFile();
        Properties props = new Properties();
        setDefaultPropValues(props);
        logger.debug("Updating config...");
        try (OutputStream output = new FileOutputStream(propFile)) {
            props.store(output, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setDefaultPropValues(Properties props) {
        props.setProperty("timeout", String.valueOf(tpaTimeoutSeconds));
        props.setProperty("standStill", String.valueOf(tpaStandStillSeconds));
        props.setProperty("cooldown", String.valueOf(tpaCooldownSeconds));
        props.setProperty("cooldownMode", String.valueOf(tpaCooldownMode));
        props.setProperty("cooldownMode.comment", "Must be one of \"WhoTeleported\", \"WhoInitiated\", \"BothUsers\"");
        props.setProperty("disableBossBar", String.valueOf(tpaDisableBossBar));
    }

    public int tpaInit(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tTo) throws CommandSyntaxException {
        final ServerPlayerEntity tFrom = ctx.getSource().getPlayer();

        if (tFrom.equals(tTo)) {
            tFrom.sendMessage(new LiteralText("You cannot request to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (checkCooldown(tFrom)) return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, false);
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tFrom.sendMessage(new LiteralText("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tFrom.sendMessage(new LiteralText("Your teleport request to " + tTo.getName().asString() + " has timed out!").formatted(Formatting.RED), false);
            tTo.sendMessage(new LiteralText("Teleport request from " + tFrom.getName().asString() + " has timed out!").formatted(Formatting.RED), false);
        });
        activeTPA.add(tr);

        tFrom.sendMessage(
                new LiteralText("You have requested to teleport to ").formatted(Formatting.LIGHT_PURPLE)
                        .append(new LiteralText(tTo.getName().asString()).formatted(Formatting.AQUA))
                        .append(new LiteralText("\nTo cancel type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tTo.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpacancel " + tTo.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nThis request will timeout in " + tpaTimeoutSeconds + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);

        tTo.sendMessage(
                new LiteralText(tFrom.getName().asString()).formatted(Formatting.AQUA)
                        .append(new LiteralText(" has requested to teleport to you!").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("\nTo accept type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tFrom.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpaaccept " + tFrom.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nTo deny type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tFrom.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpadeny " + tFrom.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nThis request will timeout in " + tpaTimeoutSeconds + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    public int tpaHere(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tFrom) throws CommandSyntaxException {
        final ServerPlayerEntity tTo = ctx.getSource().getPlayer();

        if (tTo.equals(tFrom)) {
            tTo.sendMessage(new LiteralText("You cannot request for you to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (checkCooldown(tFrom)) return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, true);
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tTo.sendMessage(new LiteralText("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tTo.sendMessage(new LiteralText("Your teleport request for " + tFrom.getName().asString() + " to you has timed out!").formatted(Formatting.RED), false);
            tFrom.sendMessage(new LiteralText("Teleport request for you to " + tTo.getName().asString() + " has timed out!").formatted(Formatting.RED), false);
        });
        activeTPA.add(tr);

        tTo.sendMessage(
                new LiteralText("You have requested for ").formatted(Formatting.LIGHT_PURPLE)
                        .append(new LiteralText(tFrom.getName().asString()).formatted(Formatting.AQUA))
                        .append(new LiteralText(" to teleport to you!\nTo cancel type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tFrom.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpacancel " + tFrom.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nThis request will timeout in " + tpaTimeoutSeconds + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);

        tFrom.sendMessage(
                new LiteralText(tTo.getName().asString()).formatted(Formatting.AQUA)
                        .append(new LiteralText(" has requested to for you to them!").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("\nTo accept type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tTo.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpaaccept " + tTo.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nTo deny type ").formatted(Formatting.LIGHT_PURPLE))
                        .append(new LiteralText("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tTo.getName().asString()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpadeny " + tTo.getName().asString())))
                                        .withColor(Formatting.GOLD)))
                        .append(new LiteralText("\nThis request will timeout in " + tpaTimeoutSeconds + " seconds.").formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    private boolean checkCooldown(ServerPlayerEntity tFrom) {
        if (recentRequests.containsKey(tFrom.getUuid())) {
            long diff = Instant.now().getEpochSecond() - recentRequests.get(tFrom.getUuid());
            if (diff < tpaCooldownSeconds) {
                tFrom.sendMessage(new LiteralText("You cannot make a request for ").append(String.valueOf(tpaCooldownSeconds - diff))
                        .append(" more seconds!").formatted(Formatting.RED), false);
                return true;
            }
        }
        return false;
    }


    private TPARequest getTPARequest(ServerPlayerEntity rFrom, ServerPlayerEntity rTo) {
        Optional<TPARequest> otr = activeTPA.stream()
                .filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom) && tpaRequest.rTo.equals(rTo)).findFirst();

        if (!otr.isPresent()) {
            rTo.sendMessage(new LiteralText("Something went wrong!").formatted(Formatting.RED), false);
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
                MutableText text = new LiteralText("You currently have multiple active teleport requests! Please specify whose request to accept.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getName().asString()).forEach(name ->
                        text.append(new LiteralText(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpaaccept " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(new LiteralText("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo);
        if (tr == null) return 1;
        Timer timer = new Timer();
        final double[] counter = {tpaStandStillSeconds};
        final Vec3d[] lastPos = {tr.tFrom.getPos()};
        CommandBossBar standStillBar = null;
        if (!tpaDisableBossBar) {
            standStillBar = rTo.server.getBossBarManager().add(new Identifier("standstill"), LiteralText.EMPTY);
            standStillBar.addPlayer(tr.tFrom);
            standStillBar.setColor(BossBar.Color.PINK);
        }
        tr.tFrom.networkHandler.sendPacket(new TitleS2CPacket(0, 10, 5));
        CommandBossBar finalStandStillBar = standStillBar;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (counter[0] == 0) {
                    tr.tFrom.teleport(tr.tTo.getServerWorld(), tr.tTo.getX(), tr.tTo.getY(), tr.tTo.getZ(), tr.tTo.yaw, tr.tTo.pitch);
                    if (!tpaDisableBossBar && finalStandStillBar != null) {
                        finalStandStillBar.removePlayer(tr.tFrom);
                        rTo.server.getBossBarManager().remove(finalStandStillBar);
                    } else {
                        tr.tFrom.sendMessage(new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE), true);
                    }
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            tr.tFrom.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.RESET, null));
                        }
                    }, 500);
                    switch (tpaCooldownMode) {
                        case BothUsers:
                            recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                            recentRequests.put(tr.tTo.getUuid(), Instant.now().getEpochSecond());
                            break;
                        case WhoInitiated:
                            recentRequests.put(tr.rFrom.getUuid(), Instant.now().getEpochSecond());
                            break;
                        case WhoTeleported:
                            recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                            break;
                    }
                    timer.cancel();
                    return;
                }

                Vec3d currPos = tr.tFrom.getPos();
                if (lastPos[0].equals(currPos)) {
                    counter[0] -= .25;
                } else {
                    lastPos[0] = currPos;
                    counter[0] = tpaStandStillSeconds;
                }

                if (!tpaDisableBossBar && finalStandStillBar != null) {
                    finalStandStillBar.setPercent((float) (counter[0] / tpaStandStillSeconds));
                } else {
                    tr.tFrom.sendMessage(new LiteralText("Stand still for ").formatted(Formatting.LIGHT_PURPLE)
                            .append(new LiteralText(Integer.toString((int) Math.floor(counter[0] + 1))).formatted(Formatting.GOLD))
                            .append(new LiteralText(" more seconds!").formatted(Formatting.LIGHT_PURPLE)), true);
                }
                tr.tFrom.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE,
                        new LiteralText("Please stand still...").formatted(Formatting.RED, Formatting.ITALIC)));
                tr.tFrom.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE,
                        new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
            }
        }, 0, 250);
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(new LiteralText("You have accepted the teleport request!"), false);
        tr.rFrom.sendMessage(new LiteralText(tr.rTo.getName().asString()).formatted(Formatting.AQUA)
                .append(new LiteralText(" has accepted the teleportation request!").formatted(Formatting.LIGHT_PURPLE)), false);

        return 1;
    }


    public int tpaDeny(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();

        if (rFrom == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = new LiteralText("You currently have multiple active teleport requests! Please specify whose request to deny.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getName().asString()).forEach(name ->
                        text.append(new LiteralText(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpadeny " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(new LiteralText("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo);
        if (tr == null) return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(new LiteralText("You have cancelled the teleport request!"), false);
        tr.rFrom.sendMessage(new LiteralText(tr.rTo.getName().asString()).formatted(Formatting.AQUA)
                .append(new LiteralText(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
        return 1;
    }

    public int tpaCancel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rTo) throws CommandSyntaxException {
        final ServerPlayerEntity rFrom = ctx.getSource().getPlayer();

        if (rTo == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = new LiteralText("You currently have multiple active teleport requests! Please specify which request to cancel.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getName().asString()).forEach(name ->
                        text.append(new LiteralText(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("/tpacancel " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rFrom.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rFrom.sendMessage(new LiteralText("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rTo = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo);
        if (tr == null) return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rFrom.sendMessage(new LiteralText("You have cancelled the teleport request!"), false);
        tr.rTo.sendMessage(new LiteralText(tr.rFrom.getName().asString()).formatted(Formatting.AQUA)
                .append(new LiteralText(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
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
        long time;

        Timer timer;

        public TPARequest(ServerPlayerEntity tFrom, ServerPlayerEntity tTo, boolean tpaHere) {
            this.tFrom = tFrom;
            this.tTo = tTo;
            this.tpaHere = tpaHere;
            this.rFrom = tpaHere ? tTo : tFrom;
            this.rTo = tpaHere ? tFrom : tTo;
            this.time = System.currentTimeMillis();
        }

        void setTimeoutCallback(Timeout callback) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    callback.onTimeout();
                }
            }, 60000);
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
    }

    interface Timeout {
        void onTimeout();
    }
}
