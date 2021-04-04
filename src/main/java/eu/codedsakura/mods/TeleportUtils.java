package eu.codedsakura.mods;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.CommandBossBar;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import us.spaceclouds.fabrictpafixtitles.FabricTPAFixTitles;

import java.util.Timer;
import java.util.TimerTask;

import static eu.codedsakura.fabrictpa.FabricTPA.pre21w08a;

public class TeleportUtils {
    public static void genericTeleport(boolean bossBar, double standStillTime, ServerPlayerEntity who, Runnable onCounterDone) {
        MinecraftServer server = who.server;
        final double[] counter = {standStillTime};
        final Vec3d[] lastPos = {who.getPos()};
        CommandBossBar standStillBar = null;
        if (bossBar) {
            standStillBar = server.getBossBarManager().add(new Identifier("standstill"), LiteralText.EMPTY);
            standStillBar.addPlayer(who);
            standStillBar.setColor(BossBar.Color.PINK);
        }
        if (pre21w08a) {
            who.networkHandler.sendPacket(new TitleS2CPacket(0, 10, 5));
        } else {
            FabricTPAFixTitles.setFade(who, 0, 10, 5);
        }
        CommandBossBar finalStandStillBar = standStillBar;

        final ServerPlayerEntity[] whoFinal = {who};
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (counter[0] == 0) {
                    if (bossBar) {
                        finalStandStillBar.removePlayer(whoFinal[0]);
                        server.getBossBarManager().remove(finalStandStillBar);
                    } else {
                        whoFinal[0].sendMessage(new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE), true);
                    }
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            if (pre21w08a) {
                                whoFinal[0].networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.RESET, null));
                            } else {
                                FabricTPAFixTitles.clearTitle(whoFinal[0]);
                            }
                        }
                    }, 500);
                    timer.cancel();
                    server.submit(onCounterDone);
                    return;
                }

                Vec3d currPos = whoFinal[0].getPos();
                if (whoFinal[0].removed) {
                    whoFinal[0] = server.getPlayerManager().getPlayer(whoFinal[0].getUuid());
                    assert whoFinal[0] != null;
                } else if (lastPos[0].equals(currPos)) {
                    counter[0] -= .25;
                } else {
                    lastPos[0] = currPos;
                    counter[0] = standStillTime;
                }

                if (bossBar) {
                    finalStandStillBar.setPercent((float) (counter[0] / standStillTime));
                } else {
                    whoFinal[0].sendMessage(new LiteralText("Stand still for ").formatted(Formatting.LIGHT_PURPLE)
                            .append(new LiteralText(Integer.toString((int) Math.floor(counter[0] + 1))).formatted(Formatting.GOLD))
                            .append(new LiteralText(" more seconds!").formatted(Formatting.LIGHT_PURPLE)), true);
                }
                if (pre21w08a) {
                    whoFinal[0].networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE,
                            new LiteralText("Please stand still...").formatted(Formatting.RED, Formatting.ITALIC)));
                    whoFinal[0].networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE,
                            new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
                } else {
                    FabricTPAFixTitles.setSubTitle(whoFinal[0], new LiteralText("Please stand still...").formatted(Formatting.RED, Formatting.ITALIC));
                    FabricTPAFixTitles.setTitle(whoFinal[0], new LiteralText("Teleporting!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
                }
            }
        }, 0, 250);
    }
}
