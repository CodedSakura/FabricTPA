package eu.codedsakura.mods;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.CommandBossBar;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Timer;
import java.util.TimerTask;

public class TeleportUtils {
    public static void genericTeleport(boolean bossBar, double standStillTime, ServerPlayerEntity who, Runnable onCounterDone) {
        MinecraftServer server = who.server;
        final double[] counter = {standStillTime};
        final Vec3d[] lastPos = {who.getPos()};
        CommandBossBar standStillBar = null;
        if (bossBar) {
//            Collection<CommandBossBar> bossBars = server.getBossBarManager().getAll();
//            bossBars.forEach(commandBossBar -> server.getBossBarManager().remove(commandBossBar));

            standStillBar = server.getBossBarManager().add(new Identifier("standstill-" + who.getUuidAsString()), LiteralText.EMPTY);
            standStillBar.addPlayer(who);
            standStillBar.setColor(BossBar.Color.PINK);

//            bossBars.forEach(commandBossBar -> {
//                CommandBossBar newBossBar = server.getBossBarManager().add(commandBossBar.getId(), commandBossBar.getName());
//                newBossBar.addPlayers(commandBossBar.getPlayers());
//                newBossBar.setMaxValue(commandBossBar.getMaxValue());
//                newBossBar.setValue(commandBossBar.getValue());
//                newBossBar.setVisible(commandBossBar.isVisible());
//                newBossBar.setColor(commandBossBar.getColor());
//            });
        }
        who.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 10, 5));
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
                            whoFinal[0].networkHandler.sendPacket(new ClearTitleS2CPacket(true));
                        }
                    }, 500);
                    timer.cancel();
                    server.submit(onCounterDone);
                    return;
                }

                Vec3d currPos = whoFinal[0].getPos();
                if (whoFinal[0].isRemoved()) {
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
                whoFinal[0].networkHandler.sendPacket(new SubtitleS2CPacket(new LiteralText("Please stand still...")
                        .formatted(Formatting.RED, Formatting.ITALIC)));
                whoFinal[0].networkHandler.sendPacket(new TitleS2CPacket(new LiteralText("Teleporting!")
                        .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
            }
        }, 0, 250);
    }
}
