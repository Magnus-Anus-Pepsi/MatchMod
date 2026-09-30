package com.matchmode;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
    // Players eliminated by lethal damage stay spectators until the round ends.
    static final Set<UUID> eliminated = new HashSet<>();

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
                                c.getSource().sendSuccess(() -> Component.literal(
                                    "Барьер: " + startSize + " -> " + endSize + " за " + shrinkSeconds + " с"), false);
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
                        p.sendSystemMessage(Component.literal("Пресет сохранён: " + name));
                        return 1;
                    })))
                .then(Commands.literal("list").executes(c -> {
                    String[] names = presetNames();
                    c.getSource().sendSuccess(() -> Component.literal(
                        "Пресетов: " + names.length + (names.length > 0 ? " — " + String.join(", ", names) : "")
                    ), false);
                    return 1;
                }))
                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                        if (state != State.LOBBY) {
                            c.getSource().sendFailure(Component.literal("Нельзя загружать пресет во время матча"));
                            return 0;
                        }
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        String name = StringArgumentType.getString(c, "name");
                        if (!new File(presetDir(), name + ".nbt").exists()) {
                            c.getSource().sendFailure(Component.literal("Пресет не найден: " + name));
                            return 0;
                        }
                        loadPreset(p, name);
                        p.sendSystemMessage(Component.literal(
                            "Загружен пресет «" + name + "» от " + presetAuthorName(name)));
                        return 1;
                    })))
                .then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        boolean ok = new File(presetDir(), name + ".nbt").delete();
                        c.getSource().sendSuccess(() -> Component.literal(
                            ok ? "Удалён: " + name : "Нет такого пресета"), false);
                        return ok ? 1 : 0;
                    })))
        );
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();

        if (state == State.INTRO) {
            timer++;
            if (timer == DARKEN_TICKS) {
                scatterAsSpectators(server);
            }
            if (timer > DARKEN_TICKS && timer < INTRO_TICKS) {
                int shuffleTick = timer - DARKEN_TICKS;
                if (shuffleTick % SHUFFLE_INTERVAL == 0) {
                    int flash = shuffleTick / SHUFFLE_INTERVAL;
                    if (flash < SHUFFLE_FLASHES) {
                        flashShuffleTitles(server, flash == SHUFFLE_FLASHES - 1);
                    }
                }
            }
            if (timer >= INTRO_TICKS) {
                beginRound(server);
            }
        } else if (state == State.PLAYING) {
            timer++;

            // Keep eliminated players in spectator for the entire round.
            for (UUID uuid : eliminated) {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null && p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                    p.setGameMode(GameType.SPECTATOR);
                }
            }
            if (timer % 20 == 0 && playersAtStart > 1) {
                long alive = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                    .count();
                if (alive <= 1) {
                    startOutro(server, "Матч окончен!");
                }
            }
        } else if (state == State.OUTRO) {
            timer++;
            if (timer >= OUTRO_TICKS) {
                finishEndMatch(server);
            }
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (state != State.PLAYING) return;
        Entity entity = e.getEntity();
        if (!(entity instanceof ServerPlayer victim)) return;

        // Any lethal damage source eliminates the player for this round.
        // Cancel the vanilla death so there is no death screen; spectator is immediate.
        e.setCanceled(true);
        eliminated.add(victim.getUUID());

        deathPos.put(victim.getUUID(), victim.position());

        Entity source = e.getSource().getEntity();
        if (source instanceof ServerPlayer killer && killer != victim) {
            killers.put(victim.getUUID(), killer.getUUID());
        } else {
            LivingEntity last = victim.getLastHurtByMob();
            if (last instanceof ServerPlayer killer && killer != victim) {
                killers.put(victim.getUUID(), killer.getUUID());
            }
        }

        victim.setGameMode(GameType.SPECTATOR);
        title(victim, "ты 200");

        ServerPlayer killer = null;
        UUID killerId = killers.get(victim.getUUID());
        if (killerId != null && victim.getServer() != null) {
            killer = victim.getServer().getPlayerList().getPlayer(killerId);
        }

        if (killer != null && killer.isAlive()
                && killer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
            victim.setCamera(killer);
            victim.sendSystemMessage(Component.literal(
                "§7Килл-камера: " + killer.getName().getString()
                    + " §8(F5 / колесо мыши — переключить)"));
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (state != State.PLAYING && state != State.OUTRO) return;
        Player player = e.getEntity();
        if (!(player instanceof ServerPlayer p)) return;

        p.setGameMode(GameType.SPECTATOR);

        UUID killerId = killers.get(p.getUUID());
        if (killerId != null) {
            ServerPlayer killer = p.getServer().getPlayerList().getPlayer(killerId);
            if (killer != null && killer.isAlive()
                    && killer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                p.setCamera(killer);
                p.sendSystemMessage(Component.literal(
                    "§7Килл-камера: " + killer.getName().getString()
                        + " §8(F5 / колесо мыши — переключить)"));
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
            p.sendSystemMessage(Component.literal("Матч уже идёт"));
            return;
        }
        boolean nowReady = ready.add(p.getUUID());
        if (!nowReady) {
            ready.remove(p.getUUID());
        }
        broadcast(server, p.getName().getString()
            + (nowReady ? " готов" : " не готов")
            + " (" + ready.size() + "/" + server.getPlayerList().getPlayerCount() + ")");
        checkVote(server);
    }

    static void checkVote(MinecraftServer server) {
        int online = server.getPlayerList().getPlayerCount();
        if (online > 0 && ready.size() >= online) {
            startIntro(server);
        }
    }

    static void startIntro(MinecraftServer server) {
        state = State.INTRO;
        timer = 0;
        origins.clear();
        deathPos.clear();
        killers.clear();
        assignedPresets.clear();
        savedInventories.clear();
        playersAtStart = server.getPlayerList().getPlayerCount();

        List<String> deck = new ArrayList<>(Arrays.asList(presetNames()));
        Collections.shuffle(deck);
        int idx = 0;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            origins.put(p.getUUID(), p.position());

            ListTag inv = new ListTag();
            p.getInventory().save(inv);
            savedInventories.put(p.getUUID(), inv);

            if (!deck.isEmpty()) {
                assignedPresets.put(p.getUUID(), deck.get(idx % deck.size()));
                idx++;
            }

            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, false));
            title(p, "Игра начинается...");
        }
    }

    static void scatterAsSpectators(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        double r = startSize / 2.0 * 0.8;
        double offset = new Random().nextDouble() * Math.PI * 2.0;
        WorldBorder border = level.getWorldBorder();
        savedBorderSize = border.getSize();
        border.setCenter(centerX, centerZ);
        border.setSize(startSize);

        for (int i = 0; i < players.size(); i++) {
            double a = offset + Math.PI * 2 * (double) i / (double) players.size();
            double x = centerX + Math.cos(a) * r;
            double z = centerZ + Math.sin(a) * r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
            ServerPlayer p = players.get(i);
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, x, y + 1, z, 0.0f, 0.0f);
        }
    }

    static void flashShuffleTitles(MinecraftServer server, boolean finalFlash) {
        String[] all = presetNames();
        if (all.length == 0) return;
        Random rng = new Random();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String show = finalFlash
                ? assignedPresets.getOrDefault(p.getUUID(), all[rng.nextInt(all.length)])
                : all[rng.nextInt(all.length)];
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 8, 2));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§e" + show)));
            if (finalFlash) {
                p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("§7от " + presetAuthorName(show))));
            }
        }
    }

    static void beginRound(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
            p.removeEffect(MobEffects.BLINDNESS);
            p.getInventory().clearContent();

            String preset = assignedPresets.get(p.getUUID());
            if (preset != null) {
                loadPreset(p, preset);
                p.sendSystemMessage(Component.literal(
                    "Тебе выпал набор «" + preset + "» от " + presetAuthorName(preset)));
            }

            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            title(p, "В БОЙ!");
        }
        level.getWorldBorder().lerpSizeBetween(startSize, endSize, (long) shrinkSeconds * 1000L);
        state = State.PLAYING;
        timer = 0;
        assignedPresets.clear();
    }

    static void startOutro(MinecraftServer server, String msg) {
        if (state == State.OUTRO || state == State.LOBBY) return;
        state = State.OUTRO;
        timer = 0;
        broadcast(server, msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.addEffect(new MobEffectInstance(
                MobEffects.BLINDNESS, OUTRO_TICKS + 20, 0, false, false, false));
            title(p, "Игра окончена");
        }
    }

    static void finishEndMatch(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.getWorldBorder().setSize(savedBorderSize);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.removeEffect(MobEffects.BLINDNESS);
            p.setCamera(p);
            p.setGameMode(GameType.SURVIVAL);

            ListTag inv = savedInventories.get(p.getUUID());
            if (inv != null) {
                p.getInventory().clearContent();
                p.getInventory().load(inv);
                p.inventoryMenu.broadcastChanges();
            }

            Vec3 o = origins.get(p.getUUID());
            if (o != null) {
                p.teleportTo(level, o.x, o.y, o.z, p.getYRot(), p.getXRot());
            }
        }

        ready.clear();
        eliminated.clear();
        origins.clear();
        deathPos.clear();
        killers.clear();
        savedInventories.clear();
        assignedPresets.clear();
        state = State.LOBBY;
    }

    static void endMatch(MinecraftServer server, String msg) {
        if (state == State.LOBBY) {
            broadcast(server, msg);
            return;
        }
        if (state == State.OUTRO) {
            finishEndMatch(server);
            broadcast(server, msg);
            return;
        }
        startOutro(server, msg);
    }

    static void savePreset(ServerPlayer p, String name) {
        try {
            CompoundTag root = new CompoundTag();
            root.put("inv", p.getInventory().save(new ListTag()));
            root.putString("author", p.getUUID().toString());
            root.putString("authorName", p.getName().getString());
            NbtIo.write(root, new File(presetDir(), name + ".nbt"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static String[] presetNames() {
        String[] files = presetDir().list((dir, n) -> n.endsWith(".nbt"));
        if (files == null) return new String[0];
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
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
            p.getInventory().load(root.getList("inv", Tag.TAG_COMPOUND));
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

    enum State {
        LOBBY,
        INTRO,
        PLAYING,
        OUTRO
    }
}
