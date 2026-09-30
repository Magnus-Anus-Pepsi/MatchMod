package com.matchmode;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.util.*;

/**
 * Режим матча: голосование -> затемнение -> наблюдатели -> пресеты -> сжимающийся барьер.
 * Регистрируется сам через @EventBusSubscriber (шина Forge по умолчанию).
 */
@Mod.EventBusSubscriber(modid = "matchmode")
public class MatchMode {

    enum State { LOBBY, INTRO, PLAYING }

    // --- Настройки зоны ---
    static double centerX = 0, centerZ = 0;
    static double startSize = 400, endSize = 20;
    static int shrinkSeconds = 600;

    // --- Состояние ---
    static State state = State.LOBBY;
    static int timer = 0;
    static int playersAtStart = 0;
    static double savedBorderSize = 60000000;
    static final Set<UUID> ready = new HashSet<>();
    static final Map<UUID, Vec3> origins = new HashMap<>();
    static final Map<UUID, Vec3> deathPos = new HashMap<>();

    static final int DARKEN_TICKS = 40;       // 2 сек затемнения
    static final int INTRO_TICKS = 40 + 200;  // потом 10 сек наблюдателями

    static File presetDir() {
        File d = new File(FMLPaths.CONFIGDIR.get().toFile(), "shooterpresets");
        d.mkdirs();
        return d;
    }

    // ================= Команды =================

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("match")
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
                c.getSource().sendSuccess(() -> Component.literal(
                    "Центр зоны: " + (int) centerX + ", " + (int) centerZ), false);
                return 1;
            }))
            .then(Commands.literal("size").requires(s -> s.hasPermission(2))
                .then(Commands.argument("start", DoubleArgumentType.doubleArg(10))
                .then(Commands.argument("end", DoubleArgumentType.doubleArg(1))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(10)).executes(c -> {
                    startSize = DoubleArgumentType.getDouble(c, "start");
                    endSize = DoubleArgumentType.getDouble(c, "end");
                    shrinkSeconds = IntegerArgumentType.getInteger(c, "seconds");
                    c.getSource().sendSuccess(() -> Component.literal(
                        "Барьер: " + startSize + " -> " + endSize + " за " + shrinkSeconds + " с"), false);
                    return 1;
                }))))));

        e.getDispatcher().register(Commands.literal("preset")
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
                    "Пресетов: " + names.length + (names.length > 0 ? " — " + String.join(", ", names) : "")), false);
                return 1;
            }))
            .then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c, "name");
                    boolean ok = new File(presetDir(), name + ".nbt").delete();
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "Удалён: " + name : "Нет такого пресета"), false);
                    return ok ? 1 : 0;
                }))));
    }

    // ================= События =================

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();

        if (state == State.INTRO) {
            timer++;
            if (timer == DARKEN_TICKS) scatterAsSpectators(server);
            if (timer >= INTRO_TICKS) beginRound(server);
        } else if (state == State.PLAYING) {
            timer++;
            // проверка победителя раз в секунду; в соло-тесте матч сам не закрывается
            if (timer % 20 == 0 && playersAtStart > 1) {
                long alive = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL).count();
                if (alive <= 1) endMatch(server, "Матч окончен!");
            }
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (state == State.PLAYING && e.getEntity() instanceof ServerPlayer p) {
            deathPos.put(p.getUUID(), p.position());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (state == State.PLAYING && e.getEntity() instanceof ServerPlayer p) {
            p.setGameMode(GameType.SPECTATOR);
            Vec3 d = deathPos.remove(p.getUUID());
            if (d != null) p.teleportTo((ServerLevel) p.level(), d.x, d.y + 1, d.z, p.getYRot(), p.getXRot());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        ready.remove(e.getEntity().getUUID());
        if (state == State.LOBBY && e.getEntity().level().getServer() != null) {
            checkVote(e.getEntity().level().getServer());
        }
    }

    // ================= Голосование =================

    static void toggleReady(ServerPlayer p, MinecraftServer server) {
        if (state != State.LOBBY) {
            p.sendSystemMessage(Component.literal("Матч уже идёт"));
            return;
        }
        boolean nowReady = ready.add(p.getUUID());
        if (!nowReady) ready.remove(p.getUUID());
        broadcast(server, p.getName().getString() + (nowReady ? " готов" : " не готов")
            + " (" + ready.size() + "/" + server.getPlayerList().getPlayerCount() + ")");
        checkVote(server);
    }

    static void checkVote(MinecraftServer server) {
        int online = server.getPlayerList().getPlayerCount();
        if (online > 0 && ready.size() >= online) startIntro(server);
    }

    // ================= Ход матча =================

    static void startIntro(MinecraftServer server) {
        state = State.INTRO;
        timer = 0;
        origins.clear();
        deathPos.clear();
        playersAtStart = server.getPlayerList().getPlayerCount();

        // Если на сервере стоит killscore, сбрасываем его раунд (First Blood, серии и т.д.)
        if (server.getCommands().getDispatcher().getRoot().getChild("killscore") != null) {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withPermission(4), "killscore reset");
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            origins.put(p.getUUID(), p.position());
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DARKEN_TICKS + 20, 0, false, false, false));
            title(p, "Игра начинается...");
        }
    }

    static void scatterAsSpectators(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        double r = startSize / 2.0 * 0.8;
        double offset = new Random().nextDouble() * Math.PI * 2;

        WorldBorder border = level.getWorldBorder();
        savedBorderSize = border.getSize();
        border.setCenter(centerX, centerZ);
        border.setSize(startSize);

        for (int i = 0; i < players.size(); i++) {
            double a = offset + 2 * Math.PI * i / players.size();
            double x = centerX + Math.cos(a) * r;
            double z = centerZ + Math.sin(a) * r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
            ServerPlayer p = players.get(i);
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, x, y + 1, z, 0, 0);
        }
    }

    static void beginRound(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<String> deck = new ArrayList<>(); // колода случайных пресетов
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
            p.removeEffect(MobEffects.BLINDNESS);
            p.getInventory().clearContent();
            String preset = drawPreset(deck, p);
            if (preset != null) {
                loadPreset(p, preset);
                p.sendSystemMessage(Component.literal("Тебе выпал набор «" + preset
                    + "» от " + presetAuthorName(preset)));
            }
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            title(p, "В БОЙ!");
        }
        level.getWorldBorder().lerpSizeBetween(startSize, endSize, shrinkSeconds * 1000L);
        state = State.PLAYING;
        timer = 0;
    }

    static void endMatch(MinecraftServer server, String msg) {
        ServerLevel level = server.overworld();
        level.getWorldBorder().setSize(savedBorderSize);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.removeEffect(MobEffects.BLINDNESS);
            p.setGameMode(GameType.SURVIVAL);
            Vec3 o = origins.get(p.getUUID());
            if (o != null) p.teleportTo(level, o.x, o.y, o.z, p.getYRot(), p.getXRot());
        }
        ready.clear();
        origins.clear();
        deathPos.clear();
        state = State.LOBBY;
        broadcast(server, msg);
    }

    // ================= Пресеты =================

    static void savePreset(ServerPlayer p, String name) {
        try {
            CompoundTag root = new CompoundTag();
            root.put("inv", p.getInventory().save(new ListTag()));
            root.putString("author", p.getUUID().toString());
            root.putString("authorName", p.getName().getString());
            NbtIo.writeCompressed(root, new File(presetDir(), name + ".nbt"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static String[] presetNames() {
        String[] files = presetDir().list((dir, n) -> n.endsWith(".nbt"));
        if (files == null) return new String[0];
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].substring(0, files[i].length() - 4);
        Arrays.sort(names);
        return names;
    }

    static CompoundTag readPreset(String name) {
        try {
            return NbtIo.readCompressed(new File(presetDir(), name + ".nbt"));
        } catch (Exception ex) {
            return new CompoundTag();
        }
    }

    static String presetAuthorName(String name) {
        String n = readPreset(name).getString("authorName");
        return n.isEmpty() ? "неизвестного автора" : n;
    }

    /** Берёт случайный пресет из колоды; по возможности не тот, что собрал сам игрок. */
    static String drawPreset(List<String> deck, ServerPlayer p) {
        String[] all = presetNames();
        if (all.length == 0) return null;
        if (deck.isEmpty()) {
            deck.addAll(Arrays.asList(all));
            Collections.shuffle(deck);
        }
        String me = p.getUUID().toString();
        for (int i = 0; i < deck.size(); i++) {
            if (!me.equals(readPreset(deck.get(i)).getString("author"))) return deck.remove(i);
        }
        return deck.remove(0); // остались только его собственные
    }

    static void loadPreset(ServerPlayer p, String name) {
        try {
            CompoundTag root = NbtIo.readCompressed(new File(presetDir(), name + ".nbt"));
            p.getInventory().load(root.getList("inv", Tag.TAG_COMPOUND));
            p.inventoryMenu.broadcastChanges();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // ================= Утилиты =================

    static void title(ServerPlayer p, String text) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text)));
    }

    static void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }
}
