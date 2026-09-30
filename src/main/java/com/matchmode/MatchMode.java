package com.matchmode;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "matchmode")
public class MatchMode {
    static double centerX = 0.0;
    static double centerZ = 0.0;
    static double startSize = 400.0;
    static double endSize = 20.0;
    static int shrinkSeconds = 600;
    static State state = State.LOBBY;
    static int timer = 0;
    static int playersAtStart = 0;
    static double savedBorderSize = 6.0E7;
    static final Set<UUID> ready = new HashSet<>();
    static final Map<UUID, Vec3> origins = new HashMap<>();
    static final Map<UUID, Vec3> deathPos = new HashMap<>();
    static final Map<UUID, ListTag> savedInventories = new HashMap<>();
    static final Map<UUID, UUID> killers = new HashMap<>();
    static final Map<UUID, String> assignedPresets = new HashMap<>();
    static final int DARKEN_TICKS = 40;
    static final int INTRO_TICKS = 240;
    static final int SHUFFLE_FLASHES = 12;
    static final int SHUFFLE_INTERVAL = 5;
    static final int OUTRO_TICKS = 80;

    static File presetDir() {
        File d = new File(FMLPaths.CONFIGDIR.get().toFile(), "shooterpresets");
        d.mkdirs();
        return d;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(
            Commands.literal("match")
                .then(Commands.literal("ready").executes(c -> {
                    toggleReady(c.getSource().getPlayerOrException(), c.getSource().getServer());
                    return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(c -> {
                    endMatch(c.getSource().getServer(), "Матч остановлен");
                    return 1;
                }))
                .then(Commands.literal("center").requires(s -> s.hasPermission(2)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    centerX = p.getX();
                    centerZ = p.getZ();
                    c.getSource().sendSuccess(() -> Component.literal("Центр зоны: " + (int) centerX + ", " + (int) centerZ), false);
                    return 1;
                }))
                .then(Commands.literal("size").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("start", DoubleArgumentType.doubleArg(10.0))
                        .then(Commands.argument("end", DoubleArgumentType.doubleArg(1.0))
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(10)).executes(c -> {
                                startSize = DoubleArgumentType.getDouble(c, "start");
                                endSize = DoubleArgumentType.getDouble(c, "end");
                                shrinkSeconds = IntegerArgumentType.getInteger(c, "seconds");
                                c.getSource().sendSuccess(() -> Component.literal("Барьер: " + startSize + " -> " + endSize + " за " + shrinkSeconds + " с"), false);
                                return 1;
                            })))))
        );

        e.getDispatcher().register(
            Commands.literal("preset")
                .then(Commands.literal("save")
                    .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        String name = StringArgumentType.getString(c, "name");
                        savePreset(p, name);
                        p.sendSystemMessage(Component.literal("Пресет «" + name + "» сохранён"));
                        return 1;
                    })))
                .then(Commands.literal("list").executes(c -> {
                    String[] names = presetNames();
                    if (names.length == 0) {
                        c.getSource().sendSuccess(() -> Component.literal("Пресеты не найдены"), false);
                    } else {
                        c.getSource().sendSuccess(() -> Component.literal("Пресеты: " + String.join(", ", names)), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        String name = StringArgumentType.getString(c, "name");
                        if (!new File(presetDir(), name + ".nbt").exists()) {
                            p.sendSystemMessage(Component.literal("Пресет не найден: " + name));
                            return 0;
                        }
                        loadPreset(p, name);
                        p.sendSystemMessage(Component.literal("Загружен пресет «" + name + "» от " + presetAuthorName(name)));
                        return 1;
                    })))
                .then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        boolean ok = new File(presetDir(), name + ".nbt").delete();
                        c.getSource().sendSuccess(() -> Component.literal(ok ? "Удалён: " + name : "Нет такого пресета"), false);
                        return ok ? 1 : 0;
                    })))
        );
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = e.getServer();
        if (state == State.INTRO) {
            if (++timer == 40) {
                scatterAsSpectators(server);
            }
            if (timer > 40 && timer < 240) {
                int shuffleTick = timer - 40;
                if (shuffleTick % 5 == 0) {
                    int flash = shuffleTick / 5;
                    if (flash < 12) {
                        flashShuffleTitles(server, flash == 11);
                    }
                }
            }
            if (timer >= 240) {
                beginRound(server);
            }
        } else if (state == State.PLAYING) {
            if (++timer % 20 == 0 && playersAtStart > 1) {
                long alive = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                    .count();
                if (alive <= 1L) {
                    startOutro(server, "Матч окончен!");
                }
            }
        } else if (state == State.OUTRO && ++timer >= 80) {
            finishEndMatch(server);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (state != State.PLAYING) {
            return;
        }
        LivingEntity entity = e.getEntity();
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }

        // Cancel death → no death screen, go straight to spectator
        e.setCanceled(true);

        deathPos.put(victim.getUUID(), victim.position());

        Entity source = e.getSource().getEntity();
        if (source instanceof ServerPlayer killer && killer != victim) {
            killers.put(victim.getUUID(), killer.getUUID());
        } else {
            LivingEntity last = victim.getLastHurtByMob();
            if (last instanceof ServerPlayer killer2 && killer2 != victim) {
                killers.put(victim.getUUID(), killer2.getUUID());
            }
        }

        // Restore health and switch to spectator immediately
        victim.setHealth(20.0f);
        victim.clearFire();
        victim.setGameMode(GameType.SPECTATOR);

        // Show "ты 200" title on screen
        title(victim, "§c§lты 200");

        // Camera on killer if available
        UUID killerId = killers.get(victim.getUUID());
        if (killerId != null) {
            ServerPlayer k = victim.getServer().getPlayerList().getPlayer(killerId);
            if (k != null && k.isAlive() && k.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                victim.setCamera(k);
                victim.sendSystemMessage(Component.literal(
                    "§7Килл-камера: " + k.getDisplayName().getString() + " §8(F5 / колесо мыши — переключить)"));
                return;
            }
        }
        Vec3 d = deathPos.get(victim.getUUID());
        if (d != null) {
            victim.teleportTo((ServerLevel) victim.level(), d.x, d.y + 1.0, d.z, victim.getYRot(), victim.getXRot());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        // Fallback if somehow respawn still happens
        if (state != State.PLAYING && state != State.OUTRO) {
            return;
        }
        Player player = e.getEntity();
        if (!(player instanceof ServerPlayer p)) {
            return;
        }
        p.setGameMode(GameType.SPECTATOR);
        UUID killerId = killers.get(p.getUUID());
        if (killerId != null) {
            ServerPlayer killer = p.getServer().getPlayerList().getPlayer(killerId);
            if (killer != null && killer.isAlive() && killer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                p.setCamera(killer);
                p.sendSystemMessage(Component.literal(
                    "§7Килл-камера: " + killer.getDisplayName().getString() + " §8(F5 / колесо мыши — переключить)"));
                return;
            }
        }
        Vec3 d = deathPos.remove(p.getUUID());
        if (d != null) {
            p.teleportTo((ServerLevel) p.level(), d.x, d.y + 1.0, d.z, p.getYRot(), p.getXRot());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        ready.remove(e.getEntity().getUUID());
        if (state == State.LOBBY && e.getEntity().level().getServer() != null) {
            checkVote(e.getEntity().level().getServer());
        }
    }

    static void toggleReady(ServerPlayer p, MinecraftServer server) {
        if (state != State.LOBBY) {
            p.sendSystemMessage(Component.literal("§cГолосование только в лобби"));
            return;
        }
        UUID id = p.getUUID();
        if (ready.contains(id)) {
            ready.remove(id);
            p.sendSystemMessage(Component.literal("§eГотовность снята"));
        } else {
            ready.add(id);
            p.sendSystemMessage(Component.literal("§aТы готов!"));
        }
        checkVote(server);
    }

    static void checkVote(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        long readyCount = players.stream().filter(p -> ready.contains(p.getUUID())).count();
        if (readyCount >= players.size() && players.size() >= 1) {
            startIntro(server);
        } else {
            broadcast(server, "§eГотовы: " + readyCount + "/" + players.size());
        }
    }

    static void startIntro(MinecraftServer server) {
        state = State.INTRO;
        timer = 0;
        ready.clear();
        deathPos.clear();
        killers.clear();
        assignedPresets.clear();
        origins.clear();

        ServerLevel level = server.overworld();
        WorldBorder border = level.getWorldBorder();
        savedBorderSize = border.getSize();
        border.setCenter(centerX, centerZ);
        border.setSize(startSize);

        playersAtStart = server.getPlayerList().getPlayers().size();

        ArrayList<String> deck = new ArrayList<>(Arrays.asList(presetNames()));
        Collections.shuffle(deck, new Random());

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            origins.put(p.getUUID(), p.position());
            savedInventories.put(p.getUUID(), p.getInventory().save(new ListTag()));
            if (!deck.isEmpty()) {
                assignedPresets.put(p.getUUID(), deck.remove(0));
            }
            p.setGameMode(GameType.ADVENTURE);
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DARKEN_TICKS + 20, 0, false, false));
            title(p, "Игра начинается...");
        }
        broadcast(server, "§aМатч начинается!");
    }

    static void scatterAsSpectators(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Random rnd = new Random();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SPECTATOR);
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = startSize * 0.35 * (0.5 + rnd.nextDouble() * 0.5);
            double x = centerX + Math.cos(angle) * dist;
            double z = centerZ + Math.sin(angle) * dist;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z) + 5;
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        }
    }

    static void flashShuffleTitles(MinecraftServer server, boolean finalFlash) {
        String[] all = presetNames();
        if (all.length == 0) return;
        Random rnd = new Random();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String show;
            if (finalFlash) {
                show = assignedPresets.getOrDefault(p.getUUID(), all[0]);
            } else {
                show = all[rnd.nextInt(all.length)];
            }
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 15, 5));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§e" + show)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7от " + presetAuthorName(show))));
        }
    }

    static void beginRound(MinecraftServer server) {
        state = State.PLAYING;
        timer = 0;
        ServerLevel level = server.overworld();
        WorldBorder border = level.getWorldBorder();
        border.setCenter(centerX, centerZ);
        border.lerpSizeBetween(startSize, endSize, shrinkSeconds * 1000L);

        Random rnd = new Random();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
            p.removeAllEffects();
            p.setHealth(20.0f);
            p.getFoodData().setFoodLevel(20);

            String preset = assignedPresets.get(p.getUUID());
            if (preset != null) {
                loadPreset(p, preset);
            }

            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = startSize * 0.3 * rnd.nextDouble();
            double x = centerX + Math.cos(angle) * dist;
            double z = centerZ + Math.sin(angle) * dist;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z) + 1;
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
            title(p, "В БОЙ!");
        }
        broadcast(server, "§c§lБой начался! Барьер сжимается.");
    }

    static void startOutro(MinecraftServer server, String msg) {
        if (state == State.OUTRO || state == State.LOBBY) return;
        state = State.OUTRO;
        timer = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            title(p, "Игра окончена");
            p.sendSystemMessage(Component.literal("§e" + msg));
        }
        broadcast(server, "§e" + msg);
    }

    static void finishEndMatch(MinecraftServer server) {
        endMatch(server, null);
    }

    static void endMatch(MinecraftServer server, String msg) {
        state = State.LOBBY;
        timer = 0;
        ready.clear();
        deathPos.clear();
        killers.clear();
        assignedPresets.clear();

        ServerLevel level = server.overworld();
        WorldBorder border = level.getWorldBorder();
        border.setSize(savedBorderSize);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
            p.removeAllEffects();
            ListTag inv = savedInventories.remove(p.getUUID());
            if (inv != null) {
                p.getInventory().load(inv);
                p.inventoryMenu.broadcastChanges();
            }
            Vec3 o = origins.remove(p.getUUID());
            if (o != null) {
                p.teleportTo(level, o.x, o.y, o.z, p.getYRot(), p.getXRot());
            }
        }
        savedInventories.clear();
        origins.clear();
        if (msg != null) {
            broadcast(server, "§e" + msg);
        }
    }

    static void savePreset(ServerPlayer p, String name) {
        try {
            CompoundTag root = new CompoundTag();
            root.put("inv", p.getInventory().save(new ListTag()));
            root.putString("author", p.getUUID().toString());
            root.putString("authorName", p.getDisplayName().getString());
            NbtIo.write(root, new File(presetDir(), name + ".nbt"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static String[] presetNames() {
        String[] files = presetDir().list((dir, n) -> n.endsWith(".nbt"));
        if (files == null) {
            return new String[0];
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; ++i) {
            names[i] = files[i].substring(0, files[i].length() - 4);
        }
        Arrays.sort(names);
        return names;
    }

    static CompoundTag readPreset(String name) {
        try {
            return NbtIo.read(new File(presetDir(), name + ".nbt"));
        } catch (Exception ex) {
            return new CompoundTag();
        }
    }

    static String presetAuthorName(String name) {
        String n = readPreset(name).getString("authorName");
        return n.isEmpty() ? "неизвестного автора" : n;
    }

    static void loadPreset(ServerPlayer p, String name) {
        try {
            CompoundTag root = NbtIo.read(new File(presetDir(), name + ".nbt"));
            p.getInventory().load(root.getList("inv", 10));
            p.inventoryMenu.broadcastChanges();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static void title(ServerPlayer p, String text) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text)));
    }

    static void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }

    static enum State {
        LOBBY,
        INTRO,
        PLAYING,
        OUTRO
    }
}
