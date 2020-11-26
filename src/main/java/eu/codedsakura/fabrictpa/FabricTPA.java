package eu.codedsakura.fabrictpa;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.gamerule.v1.rule.DoubleRule;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.command.argument.EntityArgumentType.getPlayer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class FabricTPA implements ModInitializer {
    private static final ArrayList<TPARequest> activeTPA = new ArrayList<>();
    protected static double tpaTimeoutSeconds = 60;
    protected static double tpaStandStillSeconds = 5;

    @Nullable
    private static CompletableFuture<Suggestions> getSuggestionsFromActiveTargets(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder, ServerCommandSource scs, List<String> activeTargets) {
        List<String> others = Arrays.stream(scs.getMinecraftServer().getPlayerNames())
                .filter(s -> !s.equals(scs.getName()) && !activeTargets.contains(s))
                .collect(Collectors.toList());
        if (context.getNodes().size() == 2) {
            String start = context.getNodes().get(1).getRange().get(context.getInput()).toLowerCase();
            others.stream().filter(s -> s.toLowerCase().startsWith(start)).forEach(builder::suggest);
            return builder.buildFuture();
        } else if (context.getNodes().size() == 1 && context.getInput().endsWith(" ")) {
            others.forEach(builder::suggest);
            return builder.buildFuture();
        }
        return null;
    }

    private static CompletableFuture<Suggestions> getTPAInitSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = Stream.concat(
                activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getName().asString()),
                activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getName().asString())
        ).collect(Collectors.toList());
        return getSuggestionsFromActiveTargets(context, builder, scs, activeTargets);
    }

    private static CompletableFuture<Suggestions> getTPATargetSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getName().asString()).collect(Collectors.toList());
        return getSuggestionsFromActiveTargets(context, builder, scs, activeTargets);
    }

    private static CompletableFuture<Suggestions> getTPASenderSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getName().asString()).collect(Collectors.toList());
        return getSuggestionsFromActiveTargets(context, builder, scs, activeTargets);
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("tpa").then(
                    argument("target", EntityArgumentType.player()).suggests(FabricTPA::getTPAInitSuggestions)
                            .executes(ctx -> tpaInit(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpahere").then(
                    argument("target", EntityArgumentType.player()).suggests(FabricTPA::getTPAInitSuggestions)
                            .executes(ctx -> tpaHere(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpaaccept").then(
                    argument("target", EntityArgumentType.player()).suggests(FabricTPA::getTPATargetSuggestions)
                            .executes(ctx -> tpaAccept(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaAccept(ctx, null)));

            dispatcher.register(literal("tpadeny").then(
                    argument("target", EntityArgumentType.player()).suggests(FabricTPA::getTPATargetSuggestions)
                            .executes(ctx -> tpaDeny(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaDeny(ctx, null)));

            dispatcher.register(literal("tpacancel").then(
                    argument("target", EntityArgumentType.player()).suggests(FabricTPA::getTPASenderSuggestions)
                            .executes(ctx -> tpaCancel(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaCancel(ctx, null)));
        });


        GameRules.Key<DoubleRule> tpaTimeout = GameRuleRegistry.register("tpaTimeout", GameRules.Category.PLAYER,
                GameRuleFactory.createDoubleRule(60,  1, (minecraftServer, doubleRule) -> tpaTimeoutSeconds = doubleRule.get()));

        GameRules.Key<DoubleRule> tpaStandStillTime = GameRuleRegistry.register("tpaStandStillTime", GameRules.Category.PLAYER,
                GameRuleFactory.createDoubleRule(5, 0, (minecraftServer, doubleRule) -> tpaStandStillSeconds = doubleRule.get()));

        ServerLifecycleEvents.SERVER_STARTED.register(minecraftServer -> {
            tpaTimeoutSeconds = minecraftServer.getGameRules().get(tpaTimeout).get();
            tpaStandStillSeconds = minecraftServer.getGameRules().get(tpaStandStillTime).get();
        });
    }

    public static int tpaInit(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tTo) throws CommandSyntaxException {
        final ServerPlayerEntity tFrom = ctx.getSource().getPlayer();

        if (tFrom.equals(tTo)) {
            tFrom.sendMessage(new LiteralText("You cannot request to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

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

    public static int tpaHere(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tFrom) throws CommandSyntaxException {
        final ServerPlayerEntity tTo = ctx.getSource().getPlayer();

        if (tTo.equals(tFrom)) {
            tTo.sendMessage(new LiteralText("You cannot request for you to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        TPARequest tr = new TPARequest(tTo, tFrom, true);
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


    private static TPARequest getTPARequest(ServerPlayerEntity rFrom, ServerPlayerEntity rTo) {
        Optional<TPARequest> otr = activeTPA.stream()
                .filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom) && tpaRequest.rTo.equals(rTo)).findFirst();

        if (!otr.isPresent()) {
            System.out.println(rFrom.toString() + " => " + rTo.toString() + "\n" + activeTPA.stream().map(TPARequest::toString));
            rTo.sendMessage(new LiteralText("Something went wrong!").formatted(Formatting.RED), false);
            return null;
        }

        return otr.get();
    }

    public static int tpaAccept(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
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
        final PlayerPos lastPos = PlayerPos.getFromPlayer(tr.tFrom);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (counter[0] == 0) {
                    tr.tFrom.sendMessage(new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE), true);
                    tr.tFrom.teleport(tr.tTo.getServerWorld(), tr.tTo.getX(), tr.tTo.getY(), tr.tTo.getZ(), tr.tTo.yaw, tr.tTo.pitch);
                    timer.cancel();
                    return;
                }
                PlayerPos currPos = PlayerPos.getFromPlayer(tr.tFrom);

                if (lastPos.equals(currPos)) {
                    counter[0] -= .25;
                } else {
                    lastPos.updatePos(currPos);
                    counter[0] = tpaStandStillSeconds - 1;
                }

                tr.tFrom.sendMessage(new LiteralText("Stand still for ").formatted(Formatting.LIGHT_PURPLE)
                        .append(new LiteralText(Integer.toString((int) Math.floor(counter[0] + 1))).formatted(Formatting.GOLD))
                        .append(new LiteralText(" more seconds!").formatted(Formatting.LIGHT_PURPLE)), true);
            }
        }, 0, 250);
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(new LiteralText("You have accepted the teleport request!"), false);
        tr.rFrom.sendMessage(new LiteralText(tr.rTo.getName().asString()).formatted(Formatting.AQUA)
                .append(new LiteralText(" has accepted the teleportation request!").formatted(Formatting.LIGHT_PURPLE)), false);

        return 1;
    }


    public static int tpaDeny(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
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

    public static int tpaCancel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rTo) throws CommandSyntaxException {
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

    static class PlayerPos {
        double x, y, z;

        static PlayerPos getFromPlayer(ServerPlayerEntity player) {
            return new PlayerPos(player.getX(), player.getY(), player.getZ());
        }

        public PlayerPos(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void updatePos(PlayerPos p) {
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PlayerPos playerPos = (PlayerPos) o;
            return Double.compare(playerPos.x, x) == 0 &&
                    Double.compare(playerPos.y, y) == 0 &&
                    Double.compare(playerPos.z, z) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }

        @Override
        public String toString() {
            return "PlayerPos{" + "x=" + x +
                    ", y=" + y +
                    ", z=" + z +
                    '}';
        }
    }
}
