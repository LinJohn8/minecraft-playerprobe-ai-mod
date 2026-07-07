package com.example.playerprobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class PlayerProbeClient implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_PORT = 8765;
    private static final int DEFAULT_RADIUS = 2;
    private static final int MAX_RADIUS = 8;
    private static final int MAX_ACTION_RADIUS = 32;
    private static final int MAX_PATH_NODES = 12000;

    private HttpServer server;
    private final ControlledInput controlledInput = new ControlledInput();
    private ClientInput originalInput;
    private volatile ActionState actionState = ActionState.idle("idle");
    private volatile MenuState menuState = MenuState.idle("idle");
    private volatile TaskState taskState = TaskState.idle();
    private final Object eventLock = new Object();
    private final ArrayDeque<JsonObject> recentEvents = new ArrayDeque<>();
    private ObservationSnapshot lastObservation;

    @Override
    public void onInitializeClient() {
        int port = Integer.getInteger("playerprobe.port", DEFAULT_PORT);

        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/health", this::handleHealth);
            server.createContext("/player", exchange -> handleSnapshot(exchange, false));
            server.createContext("/blocks", exchange -> handleBlocks(exchange));
            server.createContext("/raycast", this::handleRaycast);
            server.createContext("/entities", this::handleEntities);
            server.createContext("/inventory", this::handleInventory);
            server.createContext("/recipes", this::handleRecipes);
            server.createContext("/snapshot", exchange -> handleSnapshot(exchange, true));
            server.createContext("/watch", this::handleWatch);
            server.createContext("/menu/status", this::handleMenuStatus);
            server.createContext("/menu/back", this::handleMenuBack);
            server.createContext("/menu/open/title", this::handleMenuOpenTitle);
            server.createContext("/menu/open/singleplayer", this::handleMenuOpenSingleplayer);
            server.createContext("/menu/open/createWorld", this::handleMenuOpenCreateWorld);
            server.createContext("/menu/worlds", this::handleMenuWorlds);
            server.createContext("/menu/worlds/detail", this::handleMenuWorldDetail);
            server.createContext("/menu/worlds/create", this::handleMenuCreateWorld);
            server.createContext("/menu/worlds/delete", this::handleMenuDeleteWorld);
            server.createContext("/menu/worlds/rename", this::handleMenuRenameWorld);
            server.createContext("/menu/worlds/backup", this::handleMenuBackupWorld);
            server.createContext("/menu/worlds/leave", this::handleMenuLeaveWorld);
            server.createContext("/menu/worlds/load", this::handleMenuLoadWorld);
            server.createContext("/menu/worlds/switch", this::handleMenuSwitchWorld);
            server.createContext("/action/status", this::handleActionStatus);
            server.createContext("/action/stop", this::handleActionStop);
            server.createContext("/action/look", this::handleActionLook);
            server.createContext("/action/lookAt", this::handleActionLookAt);
            server.createContext("/action/move", this::handleActionMove);
            server.createContext("/action/planPath", this::handleActionPlanPath);
            server.createContext("/action/goto", this::handleActionGoto);
            server.createContext("/action/findBlock", this::handleActionFindBlock);
            server.createContext("/action/findBlocks", this::handleActionFindBlocks);
            server.createContext("/action/gotoBlock", this::handleActionGotoBlock);
            server.createContext("/action/mineBlock", this::handleActionMineBlock);
            server.createContext("/action/attackEntity", this::handleActionAttackEntity);
            server.createContext("/action/interact", this::handleActionInteract);
            server.createContext("/action/useItem", this::handleActionUseItem);
            server.createContext("/action/placeBlock", this::handleActionPlaceBlock);
            server.createContext("/action/pickupItems", this::handleActionPickupItems);
            server.createContext("/craft", this::handleCraft);
            server.createContext("/task/start", this::handleTaskStart);
            server.createContext("/task/status", this::handleTaskStatus);
            server.createContext("/task/cancel", this::handleTaskCancel);
            server.createContext("/inventory/find", this::handleInventoryFind);
            server.createContext("/inventory/knowledge", this::handleInventoryKnowledge);
            server.createContext("/inventory/selectHotbar", this::handleInventorySelectHotbar);
            server.createContext("/inventory/equipBest", this::handleInventoryEquipBest);
            server.createContext("/inventory/open", this::handleInventoryOpen);
            server.createContext("/inventory/click", this::handleInventoryClick);
            server.createContext("/inventory/drop", this::handleInventoryDrop);
            server.createContext("/container", this::handleContainer);
            server.createContext("/container/transfer", this::handleContainerTransfer);
            server.createContext("/craft/check", this::handleCraftCheck);
            server.createContext("/screen/status", this::handleScreenStatus);
            server.createContext("/screen/close", this::handleScreenClose);
            server.createContext("/events", this::handleEvents);
            server.createContext("/chat/send", this::handleChatSend);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "PlayerProbe HTTP");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            startActionLoop();
            LOGGER.info("Player Probe HTTP server started at http://127.0.0.1:{}/snapshot", port);
        } catch (IOException error) {
            LOGGER.error("Could not start Player Probe HTTP server on port {}", port, error);
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("mod", "playerprobe");
        root.addProperty("status", "running");
        root.addProperty("endpoints", "/health, /player, /blocks?radius=2, /snapshot?radius=2, /watch?radius=2&intervalMs=1000");
        sendJson(exchange, 200, root);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("time", Instant.now().toString());
        root.addProperty("playerReady", Minecraft.getInstance().player != null);
        sendJson(exchange, 200, root);
    }

    private void handleSnapshot(HttpExchange exchange, boolean includeBlocks) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        int radius = readRadius(exchange);
        JsonObject snapshot = readOnGameThread(radius, includeBlocks);
        sendJson(exchange, snapshot.has("error") ? 503 : 200, snapshot);
    }

    private void handleBlocks(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        int radius = readRadius(exchange);
        JsonObject snapshot = readOnGameThread(radius, true);
        JsonObject root = new JsonObject();
        root.addProperty("time", Instant.now().toString());
        root.add("playerBlock", snapshot.get("playerBlock"));
        root.add("lookingAt", snapshot.get("lookingAt"));
        root.add("nearbyBlocks", snapshot.get("nearbyBlocks"));
        sendJson(exchange, snapshot.has("error") ? 503 : 200, root);
    }

    private void handleRaycast(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        int distance = clamp(readInt(query(exchange).get("distance"), 6), 1, 32);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("time", Instant.now().toString());
            root.addProperty("distance", distance);
            root.add("raycast", raycastJson(client, player, level, distance));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleEntities(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        int radius = clamp(readInt(query(exchange).get("radius"), 12), 1, 64);
        int limit = clamp(readInt(query(exchange).get("limit"), 32), 1, 128);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("time", Instant.now().toString());
            root.addProperty("radius", radius);
            root.addProperty("limit", limit);
            root.add("entities", nearbyEntitiesJson(level, player, radius, limit));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleInventory(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("time", Instant.now().toString());
            root.add("inventory", inventoryJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleRecipes(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        Map<String, String> params = query(exchange);
        String itemId = normalizeItemId(params.getOrDefault("item", ""));
        int limit = clamp(readInt(params.get("limit"), 32), 1, 128);

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("time", Instant.now().toString());
            root.addProperty("item", itemId);
            root.add("recipes", recipesJson(client, player, level, itemId, limit));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleWatch(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        int radius = readRadius(exchange);
        int intervalMs = clamp(readInt(query(exchange).get("intervalMs"), 1000), 200, 10000);

        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://127.0.0.1");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream body = exchange.getResponseBody()) {
            while (!Thread.currentThread().isInterrupted()) {
                JsonObject snapshot = readOnGameThread(radius, true);
                byte[] line = (GSON.toJson(snapshot) + "\n").getBytes(StandardCharsets.UTF_8);
                body.write(line);
                body.flush();
                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The terminal closed curl or the browser tab went away.
        }
    }

    private void handleMenuStatus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(this::createMenuStatus);
        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleMenuBack(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            Screen screen = client.screen;
            if (screen == null) {
                return errorJson("NoScreen", "No menu screen is currently open.");
            }

            screen.onClose();
            menuState = MenuState.success("back", "Requested menu back.");
            JsonObject root = createMenuStatus(client);
            root.addProperty("ok", true);
            return root;
        });

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleMenuOpenTitle(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            if (client.level != null) {
                client.disconnect(new TitleScreen(), false);
            } else {
                client.setScreen(new TitleScreen());
            }
            menuState = MenuState.success("openTitle", "Opened title screen.");
            JsonObject root = createMenuStatus(client);
            root.addProperty("ok", true);
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleMenuOpenSingleplayer(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            Screen parent = defaultMenuParent(client);
            client.setScreen(new SelectWorldScreen(parent));
            menuState = MenuState.success("openSingleplayer", "Opened singleplayer world selection.");
            JsonObject root = createMenuStatus(client);
            root.addProperty("ok", true);
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleMenuOpenCreateWorld(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            CreateWorldScreen.openFresh(client, () -> client.setScreen(new SelectWorldScreen(defaultMenuParent(client))));
            menuState = MenuState.success("openCreateWorld", "Opened create world screen.");
            JsonObject root = createMenuStatus(client);
            root.addProperty("ok", true);
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleMenuWorlds(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            JsonObject root = new JsonObject();
            root.addProperty("time", Instant.now().toString());
            root.add("menu", createMenuStatus(client));
            root.add("worlds", loadWorldSummariesJson(client));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleMenuWorldDetail(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> describeWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleMenuCreateWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> createWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 400 : 200, result);
    }

    private void handleMenuDeleteWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> deleteWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleMenuRenameWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> renameWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleMenuBackupWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> backupWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleMenuLeaveWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> leaveWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleMenuLoadWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> loadWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleMenuSwitchWorld(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> switchWorldFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionStatus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        sendJson(exchange, 200, actionState.toJson());
    }

    private void handleActionStop(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        actionState = ActionState.idle("stopped");
        Minecraft.getInstance().execute(() -> restoreNormalInput(Minecraft.getInstance().player));
        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        sendJson(exchange, 200, root);
    }

    private void handleActionLook(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            float yaw;
            float pitch;
            if (hasNumber(request, "x") && hasNumber(request, "y") && hasNumber(request, "z")) {
                LookAngles angles = calculateLookAngles(player.getEyePosition(), new Vec3(
                    request.get("x").getAsDouble(),
                    request.get("y").getAsDouble(),
                    request.get("z").getAsDouble()
                ));
                yaw = angles.yaw();
                pitch = angles.pitch();
            } else {
                yaw = getFloat(request, "yaw", player.getYRot());
                pitch = getFloat(request, "pitch", player.getXRot());
            }

            applyLook(player, yaw, pitch);
            actionState = ActionState.idle("look applied");

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("yaw", player.getYRot());
            root.addProperty("pitch", player.getXRot());
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleActionLookAt(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            Vec3 target = resolveLookTarget(client, request);
            if (target == null) {
                return errorJson("TargetNotFound", "Could not resolve look target.");
            }

            lookAt(player, target);
            actionState = ActionState.idle("lookAt applied");

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("yaw", player.getYRot());
            root.addProperty("pitch", player.getXRot());
            root.add("raycast", raycastJson(client, player, level, 6));
            return root;
        });

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionMove(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        int durationMs = clamp(getInt(request, "durationMs", 500), 50, 30000);
        ManualControl control = new ManualControl(
            getBoolean(request, "forward", false),
            getBoolean(request, "backward", false),
            getBoolean(request, "left", false),
            getBoolean(request, "right", false),
            getBoolean(request, "jump", false),
            getBoolean(request, "shift", false),
            getBoolean(request, "sprint", false)
        );

        actionState = ActionState.manual(control, System.currentTimeMillis() + durationMs, "manual movement");

        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        sendJson(exchange, 200, root);
    }

    private void handleActionPlanPath(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> planPathFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionGoto(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            BlockPos target = blockPosFromJson(request, player.blockPosition());
            int searchRadius = clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS);
            Optional<List<BlockPos>> path = findPath(level, player.blockPosition(), target, searchRadius);
            if (path.isEmpty()) {
                return errorJson("NoPath", "No walkable path found to " + target.toShortString());
            }

            actionState = ActionState.path(path.get(), target, "goto " + target.toShortString());
            JsonObject root = actionState.toJson();
            root.addProperty("ok", true);
            return root;
        });

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionFindBlock(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String id = getString(request, "id", "minecraft:stone");
            int radius = clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS);
            Optional<BlockPos> found = findNearestBlock(level, player.blockPosition(), id, radius);

            JsonObject root = new JsonObject();
            root.addProperty("ok", found.isPresent());
            root.addProperty("id", id);
            root.addProperty("radius", radius);
            found.ifPresent(pos -> {
                root.add("pos", blockPosJson(pos));
                root.add("block", blockJson(level, pos, null));
            });
            if (found.isEmpty()) {
                root.addProperty("message", "No matching block in radius.");
            }
            return root;
        });

        sendJson(exchange, 200, result);
    }

    private void handleActionFindBlocks(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String id = getString(request, "id", getString(request, "blockId", "minecraft:stone"));
            int radius = clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS);
            int limit = clamp(getInt(request, "limit", 16), 1, 128);

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("id", normalizeBlockId(id));
            root.addProperty("radius", radius);
            root.addProperty("limit", limit);
            root.add("matches", findBlocksJson(level, player.blockPosition(), id, radius, limit));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleActionGotoBlock(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String id = getString(request, "id", "minecraft:stone");
            int radius = clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS);
            int standRange = clamp(getInt(request, "standRange", 2), 1, 5);
            Optional<BlockPos> found = findNearestBlock(level, player.blockPosition(), id, radius);
            if (found.isEmpty()) {
                return errorJson("BlockNotFound", "No " + id + " in radius " + radius + ".");
            }

            Optional<PathToBlock> path = findPathToStandNear(level, player.blockPosition(), found.get(), radius, standRange);
            if (path.isEmpty()) {
                return errorJson("NoPath", "Found " + id + " but no walkable standing spot near it.");
            }

            actionState = ActionState.path(path.get().path(), path.get().targetBlock(), "gotoBlock " + id);
            JsonObject root = actionState.toJson();
            root.addProperty("ok", true);
            root.addProperty("id", id);
            root.add("block", blockJson(level, found.get(), null));
            root.add("standPos", blockPosJson(path.get().standPos()));
            return root;
        });

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionMineBlock(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionMineBlockInternal(client, request));

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleActionAttackEntity(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionAttackEntityInternal(client, request));

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionInteract(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionInteractInternal(client, request));

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleActionUseItem(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionUseItemInternal(client, request));

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleInventorySelectHotbar(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            int slot = clamp(getInt(request, "slot", 0), 0, 8);
            player.getInventory().setSelectedSlot(slot);

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("selectedSlot", slot);
            root.add("selected", itemJson(player.getInventory().getSelectedItem()));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleInventoryEquipBest(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String purpose = getString(request, "purpose", "mine");
            int slot = findBestSlotForPurpose(player, level, purpose, request);
            if (slot < 0) {
                return errorJson("NoSuitableItem", "Could not find a suitable hotbar/inventory item for purpose '" + purpose + "'.");
            }

            player.getInventory().setSelectedSlot(slot);
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("selectedSlot", slot);
            root.addProperty("purpose", purpose);
            root.add("item", itemJson(player.getInventory().getSelectedItem()));
            return root;
        });

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleInventoryOpen(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            player.sendOpenInventory();
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("screen", screenName(client.screen));
            root.add("menu", currentContainerJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleInventoryClick(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            MultiPlayerGameMode gameMode = client.gameMode;
            if (player == null || gameMode == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            AbstractContainerMenu menu = player.containerMenu;
            int slot = getInt(request, "slot", -1);
            if (slot < -999 || slot >= menu.slots.size()) {
                return errorJson("InvalidSlot", "Slot out of range for current menu.");
            }

            int button = clamp(getInt(request, "button", 0), 0, 8);
            ContainerInput input = parseContainerInput(getString(request, "mode", "pickup"));
            gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
            menu.broadcastChanges();

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("slot", slot);
            root.addProperty("button", button);
            root.addProperty("mode", input.name().toLowerCase(Locale.ROOT));
            root.add("menu", currentContainerJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleInventoryDrop(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            Inventory inventory = player.getInventory();
            int count = clamp(getInt(request, "count", 1), 1, 64);
            Integer slot = request.has("slot") ? clamp(getInt(request, "slot", 0), 0, inventory.getContainerSize() - 1) : null;
            String itemId = normalizeItemId(getString(request, "itemId", ""));

            int removedFrom = -1;
            ItemStack extracted = ItemStack.EMPTY;
            if (slot != null) {
                extracted = inventory.removeItem(slot, count);
                removedFrom = slot;
            } else if (!itemId.isBlank()) {
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack candidate = inventory.getItem(i);
                    if (!candidate.isEmpty() && BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString().equals(itemId)) {
                        extracted = inventory.removeItem(i, count);
                        removedFrom = i;
                        break;
                    }
                }
            } else {
                int selected = inventory.getSelectedSlot();
                extracted = inventory.removeItem(selected, count);
                removedFrom = selected;
            }

            if (extracted.isEmpty()) {
                return errorJson("ItemNotFound", "Could not remove matching item from inventory.");
            }

            player.drop(extracted, true);
            inventory.setChanged();
            player.containerMenu.broadcastChanges();

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("slot", removedFrom);
            root.add("dropped", itemJson(extracted));
            root.add("inventory", inventoryJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleContainer(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.add("container", currentContainerJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleContainerTransfer(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            MultiPlayerGameMode gameMode = client.gameMode;
            if (player == null || gameMode == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            AbstractContainerMenu menu = player.containerMenu;
            int from = getInt(request, "from", -1);
            if (from < 0 || from >= menu.slots.size()) {
                return errorJson("InvalidFromSlot", "Source slot out of range.");
            }

            int count = clamp(getInt(request, "count", 1), 1, 64);
            JsonArray moves = new JsonArray();
            for (int i = 0; i < count; i++) {
                Slot slot = menu.getSlot(from);
                if (!slot.hasItem()) {
                    break;
                }
                ItemStack before = slot.getItem().copy();
                gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.QUICK_MOVE, player);
                menu.broadcastChanges();
                JsonObject move = new JsonObject();
                move.add("before", itemJson(before));
                move.add("after", itemJson(menu.getSlot(from).getItem()));
                moves.add(move);
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", moves.size() > 0);
            root.addProperty("from", from);
            root.add("moves", moves);
            root.add("container", currentContainerJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleActionPlaceBlock(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionPlaceBlockInternal(client, request));

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleActionPickupItems(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> actionPickupItemsInternal(client, request));

        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleCraft(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> craftFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleTaskStart(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> startTaskRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleTaskStatus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        sendJson(exchange, 200, taskState.toJson());
    }

    private void handleTaskCancel(HttpExchange exchange) throws IOException {
        if (!isPost(exchange) && !isGet(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        actionState = ActionState.idle("task cancelled");
        taskState = taskState.cancelled("task cancelled");
        Minecraft.getInstance().execute(() -> restoreNormalInput(Minecraft.getInstance().player));
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("cancelled", true);
        root.add("action", actionState.toJson());
        root.add("task", taskState.toJson());
        sendJson(exchange, 200, root);
    }

    private void handleInventoryFind(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String itemId = normalizeItemId(getString(request, "itemId", ""));
            int limit = clamp(getInt(request, "limit", 16), 1, 64);

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("itemId", itemId);
            root.add("matches", findInventoryItemsJson(player.getInventory(), itemId, limit));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleInventoryKnowledge(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("time", Instant.now().toString());
            root.add("knowledge", inventoryKnowledgeJson(player));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleScreenClose(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            if (client.screen == null) {
                JsonObject root = new JsonObject();
                root.addProperty("ok", true);
                root.addProperty("screen", "none");
                return root;
            }

            client.screen.onClose();
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("screen", screenName(client.screen));
            root.add("menu", createMenuStatus(client));
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleCraftCheck(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> craftCheckFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleScreenStatus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("screen", screenName(client.screen));
            root.addProperty("title", screenTitle(client.screen));
            root.addProperty("opened", client.screen != null);
            root.addProperty("canClose", client.screen != null && client.screen.shouldCloseOnEsc());
            if (player != null) {
                root.add("menu", currentContainerJson(player));
            }
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        long since = Long.parseLong(query(exchange).getOrDefault("since", "0"));
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("time", Instant.now().toString());
        JsonArray events = new JsonArray();
        synchronized (eventLock) {
            for (JsonObject event : recentEvents) {
                if (event.get("atMs").getAsLong() >= since) {
                    events.add(event.deepCopy());
                }
            }
        }
        root.add("events", events);
        sendJson(exchange, 200, root);
    }

    private void handleChatSend(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> {
            LocalPlayer player = client.player;
            if (player == null || client.getConnection() == null) {
                return errorJson("PlayerNotReady", "Enter a world first.");
            }

            String text = getString(request, "text", "").trim();
            if (text.isBlank()) {
                return errorJson("InvalidText", "Field 'text' is required.");
            }

            if (text.startsWith("/")) {
                client.getConnection().sendCommand(text.substring(1));
            } else {
                client.getConnection().sendChat(text);
            }

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("sent", text);
            return root;
        });

        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void startActionLoop() {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> tickAction(client));
                    Thread.sleep(50L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    LOGGER.warn("Player Probe action loop error", error);
                }
            }
        }, "PlayerProbe Action Loop");
        thread.setDaemon(true);
        thread.start();
    }

    private void tickAction(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            controlledInput.clear();
            return;
        }

        tickTask(client);

        ActionState state = actionState;
        if (state.kind() == ActionKind.IDLE) {
            restoreNormalInput(player);
            return;
        }

        installControlledInput(player);
        if (state.kind() == ActionKind.MANUAL) {
            if (System.currentTimeMillis() > state.untilMs()) {
                actionState = ActionState.idle("manual movement finished");
                controlledInput.clear();
                restoreNormalInput(player);
                return;
            }
            controlledInput.set(state.manual());
            return;
        }

        if (state.kind() == ActionKind.PATH) {
            if (state.pickupMode()) {
                tickPickup(client, player, state);
                return;
            }
            tickPath(player, state);
            return;
        }

        if (state.kind() == ActionKind.MINE) {
            tickMine(client, player, state);
            return;
        }

        if (state.kind() == ActionKind.ATTACK) {
            tickAttack(client, player, state);
        }
    }

    private void tickTask(Minecraft client) {
        if (!taskState.running()) {
            return;
        }

        if (taskState.currentStepIndex() >= taskState.steps().size()) {
            taskState = taskState.completed(createSnapshot(client, DEFAULT_RADIUS, true));
            return;
        }

        JsonObject stepRequest = taskState.steps().get(taskState.currentStepIndex());
        String action = getString(stepRequest, "action", "").trim();
        if (!taskState.stepStarted()) {
            JsonObject stepResult = runNamedTaskAction(client, action, stepRequest);
            stepResult.addProperty("stepIndex", taskState.currentStepIndex());
            stepResult.addProperty("action", action);
            taskState.recordStep(stepResult);
            if (stepResult.has("error") || (stepResult.has("ok") && !stepResult.get("ok").getAsBoolean())) {
                taskState = taskState.failed("Task stopped at step " + taskState.currentStepIndex() + " (" + action + ").", stepResult);
                return;
            }
            if (!taskState.stepStarted()) {
                taskState.markStepStarted(action, actionState.kind() != ActionKind.IDLE);
            }
            if (!taskState.waitingForAction() && !taskState.waitingForCondition()) {
                taskState.advance();
            }
            if (taskState.currentStepIndex() >= taskState.steps().size()) {
                taskState = taskState.completed(createSnapshot(client, DEFAULT_RADIUS, true));
            }
            return;
        }

        if (taskState.waitingForAction()) {
            taskState.markActionSnapshot(actionState.toJson());
            if (actionState.kind() != ActionKind.IDLE) {
                return;
            }
            taskState.finishWaiting();
        }

        if (taskState.waitingForCondition()) {
            taskState.markConditionSnapshot(taskConditionSnapshot(client));
            if (!isTaskConditionSatisfied(client, taskState)) {
                if (taskState.isConditionTimedOut()) {
                    JsonObject failure = errorJson("TaskConditionTimeout", "Task condition wait timed out.");
                    failure.addProperty("stepIndex", taskState.currentStepIndex());
                    failure.addProperty("action", action);
                    taskState = taskState.failed("Task stopped at step " + taskState.currentStepIndex() + " (" + action + ").", failure);
                }
                return;
            }
            taskState.finishConditionWait();
        }

        taskState.advance();
        if (taskState.breakRequested()) {
            while (taskState.currentStepIndex() < taskState.steps().size()) {
                taskState.advance();
            }
        }
        if (taskState.currentStepIndex() >= taskState.steps().size()) {
            taskState = taskState.completed(createSnapshot(client, DEFAULT_RADIUS, true));
        }
    }

    private void tickPath(LocalPlayer player, ActionState state) {
        List<BlockPos> path = state.path();
        int index = state.pathIndex();
        if (index >= path.size()) {
            controlledInput.clear();
            if (state.lookTarget() != null) {
                lookAt(player, state.lookTarget().getCenter());
            }
            actionState = ActionState.idle("path finished");
            return;
        }

        BlockPos next = path.get(index);
        Vec3 target = next.getBottomCenter();
        Vec3 pos = player.position();
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double dy = next.getY() - player.blockPosition().getY();

        if (horizontalDistance < 0.35D && Math.abs(dy) <= 1.2D) {
            state.advancePathIndex();
            controlledInput.set(ManualControl.stop());
            return;
        }

        lookAt(player, new Vec3(target.x, player.getEyePosition().y, target.z));
        boolean jump = dy > 0.1D || shouldJumpOverSmallObstacle(player);
        controlledInput.set(new ManualControl(true, false, false, false, jump, false, true));
    }

    private void tickPickup(Minecraft client, LocalPlayer player, ActionState state) {
        ClientLevel level = client.level;
        if (level == null) {
            actionState = ActionState.idle("pickup cancelled");
            return;
        }

        if (System.currentTimeMillis() > state.untilMs()) {
            controlledInput.clear();
            actionState = ActionState.idle("pickup finished");
            restoreNormalInput(player);
            return;
        }

        AABB area = player.getBoundingBox().inflate(state.pickupRadius());
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area, Entity::isAlive);
        if (items.isEmpty()) {
            controlledInput.clear();
            actionState = ActionState.idle("pickup finished");
            restoreNormalInput(player);
            return;
        }

        items.sort(Comparator.comparingDouble(player::distanceToSqr));
        ItemEntity nearest = items.get(0);
        Vec3 target = nearest.position();
        lookAt(player, new Vec3(target.x, player.getEyePosition().y, target.z));
        boolean jump = shouldJumpOverSmallObstacle(player);
        controlledInput.set(new ManualControl(true, false, false, false, jump, false, true));
    }

    private void tickMine(Minecraft client, LocalPlayer player, ActionState state) {
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (level == null || gameMode == null || state.targetBlock() == null) {
            actionState = ActionState.idle("mine cancelled");
            restoreNormalInput(player);
            return;
        }

        BlockPos target = state.targetBlock();
        BlockState block = level.getBlockState(target);
        if (block.isAir()) {
            gameMode.stopDestroyBlock();
            actionState = ActionState.idle("mine finished");
            restoreNormalInput(player);
            return;
        }

        Direction face = state.targetFace() == null ? resolveBlockFace(player, target) : state.targetFace();
        lookAt(player, target.getCenter());
        gameMode.continueDestroyBlock(target, face);
        controlledInput.set(ManualControl.stop());
    }

    private void tickAttack(Minecraft client, LocalPlayer player, ActionState state) {
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (level == null || gameMode == null || state.targetEntityUuid() == null) {
            actionState = ActionState.idle("attack cancelled");
            restoreNormalInput(player);
            return;
        }

        Entity target = findEntityByUuid(level, state.targetEntityUuid());
        if (target == null || !target.isAlive()) {
            actionState = ActionState.idle("attack finished");
            restoreNormalInput(player);
            return;
        }

        lookAt(player, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        controlledInput.set(ManualControl.stop());

        long now = System.currentTimeMillis();
        if (now >= state.untilMs()) {
            gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            state.markAttackSwing(now + 650L);
            state.decrementAttacksRemaining();
        }

        if (state.attacksRemaining() <= 0) {
            actionState = ActionState.idle("attack finished");
            restoreNormalInput(player);
        }
    }

    private boolean shouldJumpOverSmallObstacle(LocalPlayer player) {
        Direction direction = Direction.fromYRot(player.getYRot());
        BlockPos front = player.blockPosition().relative(direction);
        ClientLevel level = Minecraft.getInstance().level;
        return level != null
            && !isPassable(level, front)
            && isPassable(level, front.above())
            && player.onGround();
    }

    private void installControlledInput(LocalPlayer player) {
        if (player.input != controlledInput) {
            originalInput = player.input;
            player.input = controlledInput;
        }
    }

    private void restoreNormalInput(LocalPlayer player) {
        if (player != null && player.input == controlledInput && originalInput != null) {
            player.input = originalInput;
        }
        originalInput = null;
        controlledInput.clear();
    }

    private JsonObject createMenuStatus(Minecraft client) {
        JsonObject root = new JsonObject();
        root.addProperty("time", Instant.now().toString());
        root.addProperty("inWorld", client.level != null);
        root.addProperty("playerReady", client.player != null);
        root.addProperty("loadingWorld", client.screen instanceof LevelLoadingScreen);
        root.addProperty("hasScreen", client.screen != null);
        root.addProperty("screen", screenName(client.screen));
        root.addProperty("screenTitle", screenTitle(client.screen));
        root.addProperty("canCloseCurrentScreen", client.screen != null && client.screen.shouldCloseOnEsc());
        root.add("lastMenuAction", menuState.toJson());
        if (client.getCurrentServer() != null) {
            JsonObject server = new JsonObject();
            server.addProperty("name", client.getCurrentServer().name);
            server.addProperty("address", client.getCurrentServer().ip);
            root.add("server", server);
        }
        return root;
    }

    private JsonArray loadWorldSummariesJson(Minecraft client) {
        JsonArray worlds = new JsonArray();
        try {
            LevelStorageSource levelSource = client.getLevelSource();
            LevelStorageSource.LevelCandidates candidates = levelSource.findLevelCandidates();
            List<LevelSummary> summaries = levelSource.loadLevelSummaries(candidates).get(5, TimeUnit.SECONDS);
            summaries.sort(Comparator.naturalOrder());
            for (LevelSummary summary : summaries) {
                worlds.add(worldSummaryJson(levelSource, summary));
            }
        } catch (Exception error) {
            JsonObject failure = errorJson(error.getClass().getSimpleName(), error.getMessage());
            failure.addProperty("worldsReadFailed", true);
            worlds.add(failure);
        }
        return worlds;
    }

    private JsonObject worldSummaryJson(LevelStorageSource levelSource, LevelSummary summary) {
        JsonObject world = new JsonObject();
        world.addProperty("id", summary.getLevelId());
        world.addProperty("name", summary.getLevelName());
        world.addProperty("displayName", summary.getLevelName().isBlank() ? summary.getLevelId() : summary.getLevelName());
        world.addProperty("path", levelSource.getLevelPath(summary.getLevelId()).toString());
        world.addProperty("lastPlayed", summary.getLastPlayed());
        world.addProperty("gameMode", summary.getGameMode().getName());
        world.addProperty("hardcore", summary.isHardcore());
        world.addProperty("allowCommands", summary.hasCommands());
        world.addProperty("locked", summary.isLocked());
        world.addProperty("disabled", summary.isDisabled());
        world.addProperty("compatible", summary.isCompatible());
        world.addProperty("experimental", summary.isExperimental());
        world.addProperty("requiresManualConversion", summary.requiresManualConversion());
        world.addProperty("requiresFileFixing", summary.requiresFileFixing());
        world.addProperty("shouldBackup", summary.shouldBackup());
        world.addProperty("versionName", summary.getWorldVersionName().getString());
        world.addProperty("info", summary.getInfo().getString());
        return world;
    }

    private JsonObject createWorldFromRequest(Minecraft client, JsonObject request) {
        String worldName = getString(request, "name", "").trim();
        if (worldName.isBlank()) {
            return errorJson("InvalidWorldName", "Field 'name' is required.");
        }

        String worldId = sanitizeWorldId(getString(request, "id", worldName));
        if (worldId.isBlank()) {
            return errorJson("InvalidWorldId", "World id is empty after sanitization.");
        }

        LevelStorageSource levelSource = client.getLevelSource();
        if (levelSource.levelExists(worldId)) {
            return errorJson("WorldExists", "A local world with id '" + worldId + "' already exists.");
        }
        if (!levelSource.isNewLevelIdAcceptable(worldId)) {
            return errorJson("InvalidWorldId", "Minecraft rejected the requested world id '" + worldId + "'.");
        }

        String preset = normalizePreset(getString(request, "preset", "normal"));

        LevelSettings settings = new LevelSettings(
            worldName,
            parseGameType(getString(request, "gameMode", "survival")),
            new LevelSettings.DifficultySettings(
                parseDifficulty(getString(request, "difficulty", "normal")),
                getBoolean(request, "hardcore", false),
                getBoolean(request, "difficultyLocked", false)
            ),
            getBoolean(request, "allowCommands", false),
            net.minecraft.world.level.WorldDataConfiguration.DEFAULT
        );

        WorldOptions worldOptions = createWorldOptions(request);
        Screen parent = defaultMenuParent(client);
        client.createWorldOpenFlows().createFreshLevel(
            worldId,
            settings,
            worldOptions,
            provider -> createDimensionsForPreset(provider, preset),
            parent
        );

        menuState = MenuState.success("createWorld", "Creating world '" + worldName + "' (" + worldId + ").");

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("name", worldName);
        root.addProperty("id", worldId);
        root.addProperty("gameMode", settings.gameType().getName());
        root.addProperty("difficulty", settings.difficultySettings().difficulty().getSerializedName());
        root.addProperty("hardcore", settings.difficultySettings().hardcore());
        root.addProperty("allowCommands", settings.allowCommands());
        root.addProperty("bonusChest", worldOptions.generateBonusChest());
        root.addProperty("generateStructures", worldOptions.generateStructures());
        root.addProperty("seed", worldOptions.seed());
        root.addProperty("preset", preset);
        root.add("menu", createMenuStatus(client));
        return root;
    }

    private JsonObject describeWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject resolved = resolveWorldLoadTarget(client, request);
        if (resolved.has("error")) {
            return resolved;
        }

        JsonObject world = resolved.getAsJsonObject("world");
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("world", world);
        root.addProperty("inCurrentWorld", client.level != null && matchesCurrentWorld(client, world.get("id").getAsString()));
        return root;
    }

    private JsonObject deleteWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject resolved = resolveWorldLoadTarget(client, request);
        if (resolved.has("error")) {
            return resolved;
        }

        JsonObject world = resolved.getAsJsonObject("world");
        String levelId = world.get("id").getAsString();
        if (client.level != null && matchesCurrentWorld(client, levelId)) {
            return errorJson("WorldLoaded", "Leave the current world before deleting it.");
        }

        try (LevelStorageSource.LevelStorageAccess access = client.getLevelSource().validateAndCreateAccess(levelId)) {
            access.deleteLevel();
        } catch (Exception error) {
            return errorJson(error.getClass().getSimpleName(), "Failed to delete world '" + levelId + "': " + error.getMessage());
        }

        menuState = MenuState.success("deleteWorld", "Deleted world '" + levelId + "'.");
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("deletedWorldId", levelId);
        root.add("world", world);
        root.add("remainingWorlds", loadWorldSummariesJson(client));
        return root;
    }

    private JsonObject renameWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject resolved = resolveWorldLoadTarget(client, request);
        if (resolved.has("error")) {
            return resolved;
        }

        String newName = getString(request, "newName", "").trim();
        if (newName.isBlank()) {
            return errorJson("InvalidWorldName", "Field 'newName' is required.");
        }

        JsonObject world = resolved.getAsJsonObject("world");
        String levelId = world.get("id").getAsString();
        try (LevelStorageSource.LevelStorageAccess access = client.getLevelSource().validateAndCreateAccess(levelId)) {
            access.renameLevel(newName);
        } catch (Exception error) {
            return errorJson(error.getClass().getSimpleName(), "Failed to rename world '" + levelId + "': " + error.getMessage());
        }

        menuState = MenuState.success("renameWorld", "Renamed world '" + levelId + "' to '" + newName + "'.");
        JsonObject refreshed = describeWorldFromRequest(client, requestForWorldId(levelId));
        refreshed.addProperty("renamedTo", newName);
        refreshed.addProperty("ok", true);
        return refreshed;
    }

    private JsonObject backupWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject resolved = resolveWorldLoadTarget(client, request);
        if (resolved.has("error")) {
            return resolved;
        }

        JsonObject world = resolved.getAsJsonObject("world");
        String levelId = world.get("id").getAsString();
        long backupSize;
        try (LevelStorageSource.LevelStorageAccess access = client.getLevelSource().validateAndCreateAccess(levelId)) {
            backupSize = access.makeWorldBackup();
        } catch (Exception error) {
            return errorJson(error.getClass().getSimpleName(), "Failed to backup world '" + levelId + "': " + error.getMessage());
        }

        menuState = MenuState.success("backupWorld", "Created backup for world '" + levelId + "'.");
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("world", world);
        root.addProperty("backupSizeBytes", backupSize);
        root.addProperty("backupDirectory", client.getLevelSource().getBackupPath().toString());
        return root;
    }

    private JsonObject leaveWorldFromRequest(Minecraft client, JsonObject request) {
        if (client.level == null) {
            return errorJson("NotInWorld", "Minecraft is not currently inside a world.");
        }

        boolean toTitle = getBoolean(request, "toTitle", false);
        String target = toTitle ? "title" : "singleplayer";
        Screen nextScreen = toTitle ? new TitleScreen() : new SelectWorldScreen(new TitleScreen());

        actionState = ActionState.idle("world left");
        restoreNormalInput(client.player);
        client.disconnect(nextScreen, true);
        menuState = MenuState.success("leaveWorld", "Saving and leaving current world to " + target + ".");

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("leftWorld", true);
        root.addProperty("targetScreen", target);
        root.add("menu", createMenuStatus(client));
        return root;
    }

    private JsonObject loadWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject target = resolveWorldLoadTarget(client, request);
        if (target.has("error")) {
            return target;
        }

        JsonObject world = target.getAsJsonObject("world");
        String levelId = world.get("id").getAsString();
        client.createWorldOpenFlows().openWorld(levelId, () -> client.setScreen(new SelectWorldScreen(defaultMenuParent(client))));
        menuState = MenuState.success("loadWorld", "Opening world '" + levelId + "'.");

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("world", world);
        root.add("menu", createMenuStatus(client));
        return root;
    }

    private JsonObject switchWorldFromRequest(Minecraft client, JsonObject request) {
        JsonObject loadResult = resolveWorldLoadTarget(client, request);
        if (loadResult.has("error")) {
            return loadResult;
        }

        JsonObject world = loadResult.getAsJsonObject("world");
        String levelId = world.get("id").getAsString();
        boolean leftCurrentWorld = client.level != null;

        actionState = ActionState.idle("switch world");
        restoreNormalInput(client.player);

        if (client.level != null) {
            client.disconnect(new SelectWorldScreen(new TitleScreen()), true);
        }

        client.createWorldOpenFlows().openWorld(levelId, () -> client.setScreen(new SelectWorldScreen(new TitleScreen())));
        menuState = MenuState.success("switchWorld", "Switching to world '" + levelId + "'.");

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("leftCurrentWorld", leftCurrentWorld);
        root.add("world", world);
        root.add("menu", createMenuStatus(client));
        return root;
    }

    private JsonObject resolveWorldLoadTarget(Minecraft client, JsonObject request) {
        LevelStorageSource levelSource = client.getLevelSource();
        List<LevelSummary> summaries;
        try {
            summaries = levelSource.loadLevelSummaries(levelSource.findLevelCandidates()).get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            return errorJson(error.getClass().getSimpleName(), "Failed to read local worlds: " + error.getMessage());
        }

        String requestedId = getString(request, "id", "").trim();
        String requestedName = getString(request, "name", "").trim();
        Optional<LevelSummary> match = summaries.stream()
            .filter(summary -> matchesWorld(summary, requestedId, requestedName))
            .findFirst();

        if (match.isEmpty()) {
            return errorJson("WorldNotFound", "No local world matched id/name '" + requestedId + "/" + requestedName + "'.");
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("world", worldSummaryJson(levelSource, match.get()));
        return root;
    }

    private JsonObject requestForWorldId(String levelId) {
        JsonObject request = new JsonObject();
        request.addProperty("id", levelId);
        return request;
    }

    private boolean matchesCurrentWorld(Minecraft client, String levelId) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        java.nio.file.Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        java.nio.file.Path worldDir = worldRoot == null ? null : worldRoot.getParent();
        return worldDir != null && worldDir.getFileName() != null && worldDir.getFileName().toString().equalsIgnoreCase(levelId);
    }

    private boolean matchesWorld(LevelSummary summary, String requestedId, String requestedName) {
        if (!requestedId.isBlank() && summary.getLevelId().equalsIgnoreCase(requestedId)) {
            return true;
        }
        return !requestedName.isBlank()
            && (summary.getLevelName().equalsIgnoreCase(requestedName) || summary.getLevelId().equalsIgnoreCase(requestedName));
    }

    private Screen defaultMenuParent(Minecraft client) {
        if (client.screen != null) {
            return client.screen;
        }
        if (client.level != null) {
            return new PauseScreen(true);
        }
        return new TitleScreen();
    }

    private String screenName(Screen screen) {
        return taskScreenName(screen);
    }

    private static String taskScreenName(Screen screen) {
        if (screen == null) {
            return "none";
        }
        if (screen instanceof TitleScreen) {
            return "title";
        }
        if (screen instanceof SelectWorldScreen) {
            return "select_world";
        }
        if (screen instanceof CreateWorldScreen) {
            return "create_world";
        }
        if (screen instanceof JoinMultiplayerScreen) {
            return "multiplayer";
        }
        if (screen instanceof PauseScreen) {
            return "pause";
        }
        if (screen instanceof LevelLoadingScreen) {
            return "level_loading";
        }
        return screen.getClass().getSimpleName();
    }

    private String screenTitle(Screen screen) {
        if (screen == null || screen.getTitle() == null) {
            return "";
        }
        return screen.getTitle().getString();
    }

    private String sanitizeWorldId(String raw) {
        String cleaned = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9._-]+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        cleaned = cleaned.replaceAll("^[_\\-.]+|[_\\-.]+$", "");
        return cleaned;
    }

    private GameType parseGameType(String raw) {
        return switch ((raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT))) {
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
    }

    private Difficulty parseDifficulty(String raw) {
        return switch ((raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT))) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "hard" -> Difficulty.HARD;
            default -> Difficulty.NORMAL;
        };
    }

    private WorldOptions createWorldOptions(JsonObject request) {
        OptionalLong seed = parseSeed(getString(request, "seed", ""));
        WorldOptions options = WorldOptions.defaultWithRandomSeed().withSeed(seed);
        options = options.withStructures(getBoolean(request, "generateStructures", true));
        options = options.withBonusChest(getBoolean(request, "bonusChest", false));
        return options;
    }

    private OptionalLong parseSeed(String rawSeed) {
        if (rawSeed == null || rawSeed.isBlank()) {
            return OptionalLong.empty();
        }
        OptionalLong parsed = WorldOptions.parseSeed(rawSeed.trim());
        if (parsed.isPresent()) {
            return parsed;
        }
        return OptionalLong.of(rawSeed.trim().hashCode());
    }

    private WorldDimensions createDimensionsForPreset(net.minecraft.core.HolderLookup.Provider provider, String preset) {
        return switch (preset) {
            case "flat" -> WorldPresets.createFlatWorldDimensions(provider);
            default -> WorldPresets.createNormalWorldDimensions(provider);
        };
    }

    private String normalizePreset(String raw) {
        String preset = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (preset) {
            case "flat" -> "flat";
            default -> "normal";
        };
    }

    private JsonObject runOnGameThread(Function<Minecraft, JsonObject> action) {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(action.apply(client));
            } catch (Throwable error) {
                future.complete(errorJson(error.getClass().getSimpleName(), error.getMessage()));
            }
        });

        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            return errorJson(error.getClass().getSimpleName(), "Timed out waiting for Minecraft client thread.");
        }
    }

    private JsonObject planPathFromRequest(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        BlockPos start = player.blockPosition();
        int radius = clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS);
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.add("start", blockPosJson(start));
        root.addProperty("radius", radius);

        if (request.has("blockId") || request.has("id")) {
            String blockId = getString(request, "blockId", getString(request, "id", "minecraft:stone"));
            int standRange = clamp(getInt(request, "standRange", 2), 1, 5);
            Optional<BlockPos> found = findNearestBlock(level, start, blockId, radius);
            if (found.isEmpty()) {
                root.addProperty("message", "No matching block found.");
                root.addProperty("blockId", normalizeBlockId(blockId));
                return root;
            }
            Optional<PathToBlock> path = findPathToStandNear(level, start, found.get(), radius, standRange);
            if (path.isEmpty()) {
                root.addProperty("message", "No standable path found near target block.");
                root.add("targetBlock", blockPosJson(found.get()));
                return root;
            }
            root.addProperty("ok", true);
            root.addProperty("mode", "block");
            root.addProperty("blockId", normalizeBlockId(blockId));
            root.add("targetBlock", blockPosJson(found.get()));
            root.add("standPos", blockPosJson(path.get().standPos()));
            root.add("path", pathJson(path.get().path()));
            root.addProperty("pathLength", path.get().path().size());
            return root;
        }

        BlockPos target = blockPosFromJson(request, start);
        Optional<List<BlockPos>> path = findPath(level, start, target, radius);
        if (path.isEmpty()) {
            root.addProperty("message", "No walkable path found.");
            root.add("target", blockPosJson(target));
            return root;
        }

        root.addProperty("ok", true);
        root.addProperty("mode", "position");
        root.add("target", blockPosJson(target));
        root.add("path", pathJson(path.get()));
        root.addProperty("pathLength", path.get().size());
        return root;
    }

    private Optional<BlockPos> findNearestBlock(ClientLevel level, BlockPos center, String rawId, int radius) {
        String id = normalizeBlockId(rawId);
        List<BlockPos> matches = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockPos immutable = pos.immutable();
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(immutable).getBlock());
            if (blockId.toString().equals(id)) {
                matches.add(immutable);
            }
        }

        matches.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
        return matches.stream().findFirst();
    }

    private JsonArray findBlocksJson(ClientLevel level, BlockPos center, String rawId, int radius, int limit) {
        String id = normalizeBlockId(rawId);
        List<BlockPos> matches = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockPos immutable = pos.immutable();
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(immutable).getBlock());
            if (blockId.toString().equals(id)) {
                matches.add(immutable);
            }
        }

        matches.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
        JsonArray results = new JsonArray();
        for (BlockPos pos : matches) {
            if (results.size() >= limit) {
                break;
            }
            JsonObject match = blockJson(level, pos, null);
            match.addProperty("distance", Math.sqrt(pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
            results.add(match);
        }
        return results;
    }

    private Optional<PathToBlock> findPathToStandNear(ClientLevel level, BlockPos start, BlockPos targetBlock, int radius, int standRange) {
        List<BlockPos> standPositions = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(
            targetBlock.offset(-standRange, -2, -standRange),
            targetBlock.offset(standRange, 2, standRange)
        )) {
            BlockPos candidate = pos.immutable();
            if (!candidate.equals(targetBlock) && isStandable(level, candidate)) {
                standPositions.add(candidate);
            }
        }

        standPositions.sort(Comparator
            .comparingInt((BlockPos pos) -> pos.distManhattan(start))
            .thenComparingInt(pos -> pos.distManhattan(targetBlock)));

        for (BlockPos standPos : standPositions) {
            Optional<List<BlockPos>> path = findPath(level, start, standPos, radius);
            if (path.isPresent()) {
                return Optional.of(new PathToBlock(targetBlock, standPos, path.get()));
            }
        }

        return Optional.empty();
    }

    private Optional<List<BlockPos>> findPath(ClientLevel level, BlockPos rawStart, BlockPos rawTarget, int radius) {
        BlockPos start = normalizeStandPosition(level, rawStart);
        BlockPos target = normalizeStandPosition(level, rawTarget);

        if (!isStandable(level, target)) {
            return Optional.empty();
        }
        if (start.equals(target)) {
            return Optional.of(List.of());
        }

        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();

        open.add(start);
        visited.add(start);

        while (!open.isEmpty() && visited.size() < MAX_PATH_NODES) {
            BlockPos current = open.remove();
            for (BlockPos next : walkNeighbors(level, current, start, radius)) {
                if (!visited.add(next)) {
                    continue;
                }

                parent.put(next, current);
                if (next.equals(target)) {
                    return Optional.of(reconstructPath(parent, start, target));
                }
                open.add(next);
            }
        }

        return Optional.empty();
    }

    private List<BlockPos> walkNeighbors(ClientLevel level, BlockPos current, BlockPos origin, int radius) {
        List<BlockPos> neighbors = new ArrayList<>(12);
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        for (Direction direction : directions) {
            BlockPos base = current.relative(direction);
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos candidate = base.offset(0, dy, 0).immutable();
                if (candidate.distChessboard(origin) <= radius && canStep(level, current, candidate)) {
                    neighbors.add(candidate);
                }
            }
        }

        neighbors.sort(Comparator.comparingInt(pos -> pos.getY() == current.getY() ? 0 : 1));
        return neighbors;
    }

    private boolean canStep(ClientLevel level, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (dy > 1 || dy < -1) {
            return false;
        }
        if (!isStandable(level, to)) {
            return false;
        }
        if (dy > 0 && !isPassable(level, from.above())) {
            return false;
        }
        return true;
    }

    private List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> parent, BlockPos start, BlockPos target) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = target;

        while (cursor != null && !cursor.equals(start)) {
            path.add(cursor);
            cursor = parent.get(cursor);
        }

        Collections.reverse(path);
        return path;
    }

    private BlockPos normalizeStandPosition(ClientLevel level, BlockPos pos) {
        if (isStandable(level, pos)) {
            return pos.immutable();
        }
        if (isStandable(level, pos.above())) {
            return pos.above().immutable();
        }
        if (isStandable(level, pos.below())) {
            return pos.below().immutable();
        }
        return pos.immutable();
    }

    private boolean isStandable(ClientLevel level, BlockPos feet) {
        return isPassable(level, feet)
            && isPassable(level, feet.above())
            && isFloor(level, feet.below());
    }

    private boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean isFloor(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty() && state.blocksMotion();
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        LookAngles angles = calculateLookAngles(player.getEyePosition(), target);
        applyLook(player, angles.yaw(), angles.pitch());
    }

    private LookAngles calculateLookAngles(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new LookAngles(wrapDegrees(yaw), clampFloat(pitch, -90.0F, 90.0F));
    }

    private void applyLook(LocalPlayer player, float yaw, float pitch) {
        float clampedPitch = clampFloat(pitch, -90.0F, 90.0F);
        player.setYRot(wrapDegrees(yaw));
        player.setXRot(clampedPitch);
        player.setYHeadRot(wrapDegrees(yaw));
        player.setYBodyRot(wrapDegrees(yaw));
    }

    private JsonObject readOnGameThread(int radius, boolean includeBlocks) {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(createSnapshot(client, radius, includeBlocks));
            } catch (Throwable error) {
                JsonObject root = new JsonObject();
                root.addProperty("error", error.getClass().getSimpleName());
                root.addProperty("message", error.getMessage());
                future.complete(root);
            }
        });

        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            JsonObject root = new JsonObject();
            root.addProperty("error", error.getClass().getSimpleName());
            root.addProperty("message", "Timed out waiting for Minecraft client thread.");
            return root;
        }
    }

    private JsonObject createSnapshot(Minecraft client, int radius, boolean includeBlocks) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        JsonObject root = new JsonObject();
        root.addProperty("time", Instant.now().toString());
        root.addProperty("radius", radius);

        if (player == null || level == null) {
            root.addProperty("error", "PlayerNotReady");
            root.addProperty("message", "Enter a world first, then request this endpoint again.");
            return root;
        }

        BlockPos playerBlock = player.blockPosition();
        root.add("world", worldJson(level));
        root.add("player", playerJson(player));
        root.add("playerBlock", blockPosJson(playerBlock));
        root.add("lookingAt", lookingAtJson(client, level));
        root.add("raycast", raycastJson(client, player, level, 6));
        root.add("entities", nearbyEntitiesJson(level, player, Math.max(radius * 2, 8), 24));
        root.add("inventorySummary", inventorySummaryJson(player));
        root.add("action", actionState.toJson());

        if (includeBlocks) {
            root.add("nearbyBlocks", nearbyBlocksJson(level, playerBlock, radius));
        }

        recordObservation(client, player, level);

        return root;
    }

    private JsonObject worldJson(ClientLevel level) {
        JsonObject world = new JsonObject();
        world.addProperty("dimension", level.dimension().identifier().toString());
        world.addProperty("gameTime", level.getGameTime());
        world.addProperty("overworldClockTime", level.getOverworldClockTime());
        world.addProperty("defaultClockTime", level.getDefaultClockTime());
        world.addProperty("isClientSide", level.isClientSide());
        return world;
    }

    private JsonObject playerJson(LocalPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("name", player.getName().getString());
        root.addProperty("uuid", player.getUUID().toString());
        root.add("position", vecJson(player.position()));
        root.add("eyePosition", vecJson(player.getEyePosition()));
        root.add("velocity", vecJson(player.getDeltaMovement()));
        root.add("lookVector", vecJson(player.getLookAngle()));
        root.addProperty("yaw", player.getYRot());
        root.addProperty("pitch", player.getXRot());
        root.addProperty("direction", player.getDirection().getName());
        root.addProperty("horizontalFacing", Direction.fromYRot(player.getYRot()).getName());
        root.addProperty("health", player.getHealth());
        root.addProperty("maxHealth", player.getMaxHealth());
        root.addProperty("armor", player.getArmorValue());
        root.addProperty("food", player.getFoodData().getFoodLevel());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());
        root.addProperty("air", player.getAirSupply());
        root.addProperty("experienceLevel", player.experienceLevel);
        root.addProperty("experienceProgress", player.experienceProgress);
        root.addProperty("onGround", player.onGround());
        root.addProperty("swimming", player.isSwimming());
        root.addProperty("sneaking", player.isCrouching());
        root.addProperty("sprinting", player.isSprinting());
        root.addProperty("creative", player.isCreative());
        root.addProperty("spectator", player.isSpectator());
        root.addProperty("dimension", player.level().dimension().identifier().toString());
        root.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
        root.add("boundingBox", boxJson(player.getBoundingBox()));
        root.add("mainHand", itemJson(player.getMainHandItem()));
        root.add("offHand", itemJson(player.getOffhandItem()));
        root.add("equipment", equipmentJson(player));
        return root;
    }

    private JsonObject lookingAtJson(Minecraft client, ClientLevel level) {
        JsonObject root = new JsonObject();
        HitResult hit = client.hitResult;

        if (hit == null) {
            root.addProperty("type", "none");
            return root;
        }

        root.addProperty("type", hit.getType().name().toLowerCase());
        root.add("location", vecJson(hit.getLocation()));

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            root.add("blockPos", blockPosJson(pos));
            root.addProperty("side", blockHit.getDirection().getName());
            root.add("block", blockJson(level, pos, blockHit.getDirection()));
        }

        return root;
    }

    private JsonObject raycastJson(Minecraft client, LocalPlayer player, ClientLevel level, int distance) {
        JsonObject root = new JsonObject();
        HitResult hit = player.raycastHitResult(1.0F, player);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            root.addProperty("hit", false);
            root.addProperty("type", "miss");
            root.addProperty("distance", distance);
            return root;
        }

        root.addProperty("hit", true);
        root.addProperty("type", hit.getType().name().toLowerCase(Locale.ROOT));
        root.add("location", vecJson(hit.getLocation()));
        root.addProperty("distance", player.getEyePosition().distanceTo(hit.getLocation()));

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            root.add("blockPos", blockPosJson(pos));
            root.addProperty("face", blockHit.getDirection().getName());
            root.add("block", blockJson(level, pos, blockHit.getDirection()));
        } else if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            root.addProperty("entityId", entity.getId());
            root.addProperty("entityUuid", entity.getUUID().toString());
            root.add("entity", entityJson(player, entity));
        }

        return root;
    }

    private JsonArray nearbyEntitiesJson(ClientLevel level, LocalPlayer player, int radius, int limit) {
        JsonArray entities = new JsonArray();
        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> nearby = level.getEntities(player, area, entity -> entity != null && entity != player && entity.isAlive());
        nearby.sort(Comparator.comparingDouble(player::distanceToSqr));

        for (Entity entity : nearby) {
            if (entities.size() >= limit) {
                break;
            }
            entities.add(entityJson(player, entity));
        }

        return entities;
    }

    private JsonObject entityJson(LocalPlayer player, Entity entity) {
        JsonObject root = new JsonObject();
        root.addProperty("id", entity.getId());
        root.addProperty("uuid", entity.getUUID().toString());
        root.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        root.addProperty("name", entity.getName().getString());
        root.add("pos", vecJson(entity.position()));
        root.addProperty("distance", Math.sqrt(player.distanceToSqr(entity)));
        root.addProperty("onGround", entity.onGround());
        root.addProperty("hostile", entity instanceof Mob mob && mob.canAttack(player));
        root.addProperty("living", entity instanceof LivingEntity);
        if (entity instanceof LivingEntity living) {
            root.addProperty("health", living.getHealth());
            root.addProperty("maxHealth", living.getMaxHealth());
        }
        return root;
    }

    private JsonObject inventoryJson(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        JsonObject root = new JsonObject();
        root.addProperty("selectedSlot", inventory.getSelectedSlot());
        root.add("selected", itemJson(inventory.getSelectedItem()));
        root.add("hotbar", slotsJson(inventory, 0, 9));
        root.add("main", slotsJson(inventory, 9, 36));
        root.add("armor", equipmentJson(player));
        root.add("offhand", itemJson(player.getOffhandItem()));
        root.addProperty("containerSize", inventory.getContainerSize());
        root.add("menu", currentContainerJson(player));
        return root;
    }

    private JsonArray recipesJson(Minecraft client, LocalPlayer player, ClientLevel level, String itemId, int limit) {
        JsonArray recipes = new JsonArray();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return recipes;
        }

        RecipeManager recipeManager = server.getRecipeManager();
        List<RecipeHolder<?>> matching = recipeManager.getRecipes().stream()
            .filter(holder -> recipeMatchesOutput(level, holder, itemId))
            .limit(limit)
            .toList();

        for (RecipeHolder<?> holder : matching) {
            JsonObject recipe = recipeJson(level, player, recipeManager, holder);
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    private JsonObject inventorySummaryJson(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        JsonObject root = new JsonObject();
        root.addProperty("selectedSlot", inventory.getSelectedSlot());
        root.add("selected", itemJson(inventory.getSelectedItem()));
        root.add("hotbar", slotsJson(inventory, 0, 9));
        root.addProperty("hasFood", hasFood(inventory));

        JsonObject tools = new JsonObject();
        tools.addProperty("pickaxe", hasToolKeyword(inventory, "pickaxe"));
        tools.addProperty("axe", hasToolKeyword(inventory, "axe"));
        tools.addProperty("shovel", hasToolKeyword(inventory, "shovel"));
        tools.addProperty("sword", hasToolKeyword(inventory, "sword"));
        root.add("tools", tools);
        return root;
    }

    private JsonObject inventoryKnowledgeJson(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        Map<String, Integer> counts = inventoryItemCounts(inventory);
        JsonObject root = new JsonObject();
        root.add("summary", inventorySummaryJson(player));

        JsonArray items = new JsonArray();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .forEach(entry -> {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.getKey());
                item.addProperty("count", entry.getValue());
                items.add(item);
            });
        root.add("itemCounts", items);

        JsonObject categories = new JsonObject();
        categories.addProperty("hasFood", hasFood(inventory));
        categories.add("tools", classifyInventoryTools(inventory));
        categories.add("blocks", inventoryBlockCountsJson(inventory));
        root.add("categories", categories);
        return root;
    }

    private JsonArray findInventoryItemsJson(Inventory inventory, String itemId, int limit) {
        JsonArray matches = new JsonArray();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String currentId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!itemId.isBlank() && !currentId.equals(itemId)) {
                continue;
            }
            JsonObject entry = itemJson(stack);
            entry.addProperty("slot", slot);
            entry.addProperty("selected", slot == inventory.getSelectedSlot());
            matches.add(entry);
            if (matches.size() >= limit) {
                break;
            }
        }
        return matches;
    }

    private JsonObject classifyInventoryTools(Inventory inventory) {
        JsonObject tools = new JsonObject();
        tools.addProperty("pickaxe", hasToolKeyword(inventory, "pickaxe"));
        tools.addProperty("axe", hasToolKeyword(inventory, "axe"));
        tools.addProperty("shovel", hasToolKeyword(inventory, "shovel"));
        tools.addProperty("sword", hasToolKeyword(inventory, "sword"));
        tools.addProperty("hoe", hasToolKeyword(inventory, "hoe"));
        tools.addProperty("bow", hasToolKeyword(inventory, "bow"));
        return tools;
    }

    private JsonArray inventoryBlockCountsJson(Inventory inventory) {
        JsonArray blocks = new JsonArray();
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String raw = itemId.toString();
            Identifier maybeBlockId = Identifier.tryParse(raw);
            if (raw.contains("block") || (maybeBlockId != null && BuiltInRegistries.BLOCK.getOptional(maybeBlockId).isPresent())) {
                counts.merge(raw, stack.getCount(), Integer::sum);
            }
        }
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> {
                JsonObject block = new JsonObject();
                block.addProperty("id", entry.getKey());
                block.addProperty("count", entry.getValue());
                blocks.add(block);
            });
        return blocks;
    }

    private JsonObject craftCheckFromRequest(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", ""));
        boolean preferTable = getBoolean(request, "useTable", false);
        RecipeSelection selection = selectRecipeForCraft(client, player, level, itemId, preferTable);
        if (selection == null) {
            JsonObject root = errorJson("RecipeNotFound", "No usable recipe found for " + itemId + ".");
            root.add("recipes", recipesJson(client, player, level, itemId, 16));
            return root;
        }

        int maxCrafts = selection.entry().canCraft(inventoryContents(player.getInventory())) ? maxCraftableCount(player.getInventory(), selection.holder()) : 0;
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("itemId", itemId);
        root.addProperty("menuType", selection.menuType());
        root.addProperty("craftableNow", maxCrafts > 0);
        root.addProperty("maxCraftRuns", maxCrafts);
        root.addProperty("requiresCraftingTable", "crafting_table".equals(selection.menuType()));
        root.addProperty("recipeDisplayId", selection.entry().id().index());
        root.add("inventory", inventoryKnowledgeJson(player));
        return root;
    }

    private JsonArray pathJson(List<BlockPos> path) {
        JsonArray array = new JsonArray();
        for (BlockPos pos : path) {
            array.add(blockPosJson(pos));
        }
        return array;
    }

    private JsonArray slotsJson(Inventory inventory, int startInclusive, int endExclusive) {
        JsonArray slots = new JsonArray();
        for (int slot = startInclusive; slot < Math.min(endExclusive, inventory.getContainerSize()); slot++) {
            JsonObject entry = itemJson(inventory.getItem(slot));
            entry.addProperty("slot", slot);
            slots.add(entry);
        }
        return slots;
    }

    private boolean hasFood(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getComponents().has(net.minecraft.core.component.DataComponents.FOOD)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolKeyword(Inventory inventory, String keyword) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Vec3 resolveLookTarget(Minecraft client, JsonObject request) {
        if (hasNumber(request, "x") && hasNumber(request, "y") && hasNumber(request, "z")) {
            return new Vec3(request.get("x").getAsDouble(), request.get("y").getAsDouble(), request.get("z").getAsDouble());
        }

        if (request.has("blockPos") && request.get("blockPos").isJsonObject()) {
            return blockPosFromJson(request.getAsJsonObject("blockPos"), BlockPos.ZERO).getCenter();
        }

        Entity entity = resolveEntity(client, request);
        if (entity != null) {
            return entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
        }

        return null;
    }

    private Entity resolveEntity(Minecraft client, JsonObject request) {
        ClientLevel level = client.level;
        if (level == null) {
            return null;
        }

        if (request.has("entityId")) {
            return level.getEntity(getInt(request, "entityId", -1));
        }

        if (request.has("entityUuid")) {
            return findEntityByUuid(level, getString(request, "entityUuid", ""));
        }

        return null;
    }

    private Entity findEntityByUuid(ClientLevel level, String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return null;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().toString().equalsIgnoreCase(rawUuid)) {
                return entity;
            }
        }

        return null;
    }

    private JsonObject interactionResultJson(String mode, InteractionResult interaction, Minecraft client) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", interaction.consumesAction() || interaction == InteractionResult.SUCCESS || interaction == InteractionResult.SUCCESS_SERVER);
        root.addProperty("mode", mode);
        root.addProperty("result", interaction.toString().toLowerCase(Locale.ROOT));
        root.addProperty("screen", screenName(client.screen));
        root.addProperty("openedScreen", client.screen != null);
        return root;
    }

    private JsonObject stepJson(String stepName, java.util.function.Consumer<JsonObject> data) {
        JsonObject step = new JsonObject();
        step.addProperty("step", stepName);
        data.accept(step);
        return step;
    }

    private JsonObject verifyMineState(ClientLevel level, BlockPos target) {
        JsonObject root = new JsonObject();
        BlockState state = level.getBlockState(target);
        root.addProperty("targetStillPresent", !state.isAir());
        root.add("currentBlock", blockJson(level, target, null));
        return root;
    }

    private JsonObject verifyAttackState(LocalPlayer player, Entity target) {
        JsonObject root = new JsonObject();
        root.addProperty("targetAlive", target.isAlive());
        root.add("target", entityJson(player, target));
        return root;
    }

    private JsonObject verifyScreenAndRaycast(Minecraft client, LocalPlayer player, ClientLevel level) {
        JsonObject root = new JsonObject();
        root.addProperty("screen", screenName(client.screen));
        root.addProperty("openedScreen", client.screen != null);
        root.add("raycast", raycastJson(client, player, level, 6));
        return root;
    }

    private JsonObject verifyUseItemState(Minecraft client, LocalPlayer player, ClientLevel level) {
        JsonObject root = new JsonObject();
        root.add("inventory", inventorySummaryJson(player));
        root.add("raycast", raycastJson(client, player, level, 6));
        root.addProperty("screen", screenName(client.screen));
        return root;
    }

    private JsonObject verifyPlacedBlock(ClientLevel level, BlockPos target) {
        JsonObject root = new JsonObject();
        root.add("block", blockJson(level, target, null));
        root.addProperty("placed", !level.getBlockState(target).isAir());
        return root;
    }

    private JsonArray nearbyItemEntitiesJson(ClientLevel level, LocalPlayer player, int radius, int limit) {
        JsonArray items = new JsonArray();
        AABB area = player.getBoundingBox().inflate(radius);
        List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, area, Entity::isAlive);
        nearby.sort(Comparator.comparingDouble(player::distanceToSqr));
        for (ItemEntity entity : nearby) {
            if (items.size() >= limit) {
                break;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", entity.getId());
            item.addProperty("uuid", entity.getUUID().toString());
            item.add("pos", vecJson(entity.position()));
            item.addProperty("distance", Math.sqrt(player.distanceToSqr(entity)));
            item.add("stack", itemJson(entity.getItem()));
            items.add(item);
        }
        return items;
    }

    private JsonObject nearbyItemPickupVerify(ClientLevel level, LocalPlayer player, int radius) {
        JsonObject root = new JsonObject();
        JsonArray items = nearbyItemEntitiesJson(level, player, radius, 16);
        root.addProperty("remainingItemCount", items.size());
        root.add("remainingItems", items);
        return root;
    }

    private JsonObject startTaskRequest(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        if (!request.has("steps") || !request.get("steps").isJsonArray()) {
            return errorJson("InvalidTask", "Field 'steps' must be a JSON array.");
        }

        JsonArray inputSteps = request.getAsJsonArray("steps");
        List<JsonObject> steps = new ArrayList<>();
        for (int i = 0; i < inputSteps.size(); i++) {
            JsonElement element = inputSteps.get(i);
            if (!element.isJsonObject()) {
                return errorJson("InvalidTaskStep", "Task step at index " + i + " is not an object.");
            }
            steps.add(element.getAsJsonObject().deepCopy());
        }

        String taskId = "task-" + System.currentTimeMillis();
        taskState = TaskState.running(taskId, steps, getString(request, "name", "task"));
        JsonObject root = taskState.toJson();
        root.addProperty("ok", true);
        return root;
    }

    private JsonObject runNamedTaskAction(Minecraft client, String action, JsonObject stepRequest) {
        return switch (action) {
            case "retry" -> runRetryTask(client, stepRequest);
            case "if" -> runIfTask(client, stepRequest);
            case "repeat" -> runRepeatTask(client, stepRequest);
            case "breakIf" -> runBreakIfTask(client, stepRequest);
            case "wait" -> runWaitTask(stepRequest);
            case "waitForScreen" -> runWaitForScreenTask(client, stepRequest);
            case "waitForActionIdle" -> runWaitForActionIdleTask(stepRequest);
            case "verifyBlock" -> runVerifyBlockTask(client, stepRequest);
            case "verifyInventoryItem" -> runVerifyInventoryItemTask(client, stepRequest);
            case "verifyScreen" -> runVerifyScreenTask(client, stepRequest);
            case "verifyContainer" -> runVerifyContainerTask(client, stepRequest);
            case "selectHotbar" -> runSelectHotbarTask(client, stepRequest);
            case "equipBest" -> runEquipBestTask(client, stepRequest);
            case "openInventory" -> runOpenInventoryTask(client);
            case "inventoryClick" -> runInventoryClickTask(client, stepRequest);
            case "containerTransfer" -> runContainerTransferTask(client, stepRequest);
            case "openNearbyCraftingTable" -> runOpenNearbyCraftingTableTask(client, stepRequest);
            case "openNearbyContainer" -> runOpenNearbyContainerTask(client, stepRequest);
            case "containerTransferProcess" -> runContainerTransferProcessTask(client, stepRequest);
            case "containerTransferProcessAutoRepair" -> runContainerTransferProcessAutoRepairTask(client, stepRequest);
            case "craftInventoryProcess" -> runCraftInventoryProcessTask(client, stepRequest);
            case "craftInventoryProcessAutoRepair" -> runCraftInventoryProcessAutoRepairTask(client, stepRequest);
            case "craftTableProcess" -> runCraftTableProcessTask(client, stepRequest);
            case "craftTableProcessAutoRepair" -> runCraftTableProcessAutoRepairTask(client, stepRequest);
            case "lookAt" -> runActionLookAtTask(client, stepRequest);
            case "goto" -> runActionGotoTask(client, stepRequest);
            case "gotoBlock" -> runActionGotoBlockTask(client, stepRequest);
            case "interact" -> runActionInteractTask(client, stepRequest);
            case "craft" -> craftFromRequest(client, stepRequest);
            case "useItem" -> runActionUseItemTask(client, stepRequest);
            case "placeBlock" -> runActionPlaceBlockTask(client, stepRequest);
            case "mineBlock" -> runActionMineBlockTask(client, stepRequest);
            case "pickupItems" -> runActionPickupItemsTask(client, stepRequest);
            case "closeScreen" -> runCloseScreenTask(client);
            default -> errorJson("UnsupportedTaskAction", "Unsupported task action '" + action + "'.");
        };
    }

    private JsonObject runRetryTask(Minecraft client, JsonObject request) {
        if (!request.has("step") || !request.get("step").isJsonObject()) {
            return errorJson("InvalidRetryStep", "Field 'step' must be a JSON object.");
        }

        JsonObject nested = request.getAsJsonObject("step").deepCopy();
        String nestedAction = getString(nested, "action", "").trim();
        if (nestedAction.isBlank()) {
            return errorJson("InvalidRetryStep", "Nested retry step must include an 'action'.");
        }

        int maxAttempts = clamp(getInt(request, "maxAttempts", 3), 1, 32);
        JsonArray attempts = new JsonArray();
        JsonObject last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            JsonObject result = runNamedTaskAction(client, nestedAction, nested);
            result.addProperty("attempt", attempt);
            result.addProperty("nestedAction", nestedAction);
            attempts.add(result.deepCopy());
            last = result;
            if (!result.has("error") && (!result.has("ok") || result.get("ok").getAsBoolean())) {
                JsonObject root = result.deepCopy();
                root.addProperty("ok", true);
                root.addProperty("attemptsUsed", attempt);
                root.addProperty("maxAttempts", maxAttempts);
                root.add("attempts", attempts);
                return root;
            }
        }

        JsonObject root = last == null ? errorJson("RetryFailed", "Retry step failed.") : last.deepCopy();
        root.addProperty("attemptsUsed", maxAttempts);
        root.addProperty("maxAttempts", maxAttempts);
        root.add("attempts", attempts);
        return root;
    }

    private JsonObject runIfTask(Minecraft client, JsonObject request) {
        if (!request.has("condition") || !request.get("condition").isJsonObject()) {
            return errorJson("InvalidIfCondition", "Field 'condition' must be a JSON object.");
        }

        JsonObject condition = request.getAsJsonObject("condition");
        JsonObject evaluation = evaluateTaskCondition(client, condition);
        if (evaluation.has("error")) {
            return evaluation;
        }

        boolean matched = evaluation.has("ok") && evaluation.get("ok").getAsBoolean();
        List<JsonObject> selected = matched
            ? parseTaskStepsFromField(request, "then")
            : parseTaskStepsFromField(request, "else");
        taskState.insertStepsAfterCurrent(selected);

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("matched", matched);
        root.addProperty("insertedStepCount", selected.size());
        root.add("condition", evaluation);
        return root;
    }

    private JsonObject runRepeatTask(Minecraft client, JsonObject request) {
        if (!request.has("step") || !request.get("step").isJsonObject()) {
            return errorJson("InvalidRepeatStep", "Field 'step' must be a JSON object.");
        }

        JsonObject nested = request.getAsJsonObject("step").deepCopy();
        String nestedAction = getString(nested, "action", "").trim();
        if (nestedAction.isBlank()) {
            return errorJson("InvalidRepeatStep", "Nested repeat step must include an 'action'.");
        }

        int times = clamp(getInt(request, "times", 1), 1, 128);
        JsonArray results = new JsonArray();
        for (int index = 0; index < times; index++) {
            JsonObject result = runNamedTaskAction(client, nestedAction, nested);
            result.addProperty("iteration", index + 1);
            result.addProperty("nestedAction", nestedAction);
            results.add(result.deepCopy());
            if (result.has("error") || (result.has("ok") && !result.get("ok").getAsBoolean())) {
                JsonObject root = result.deepCopy();
                root.addProperty("times", times);
                root.add("results", results);
                return root;
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("times", times);
        root.add("results", results);
        return root;
    }

    private JsonObject runBreakIfTask(Minecraft client, JsonObject request) {
        if (!request.has("condition") || !request.get("condition").isJsonObject()) {
            return errorJson("InvalidBreakCondition", "Field 'condition' must be a JSON object.");
        }

        JsonObject evaluation = evaluateTaskCondition(client, request.getAsJsonObject("condition"));
        if (evaluation.has("error")) {
            return evaluation;
        }

        boolean matched = evaluation.has("ok") && evaluation.get("ok").getAsBoolean();
        if (matched) {
            taskState.requestBreak();
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("matched", matched);
        root.addProperty("breakRequested", matched);
        root.add("condition", evaluation);
        return root;
    }

    private JsonObject runWaitTask(JsonObject request) {
        int durationMs = clamp(getInt(request, "durationMs", 250), 0, 120000);
        taskState.startTimedWait(durationMs, "wait");
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.addProperty("durationMs", durationMs);
        return root;
    }

    private JsonObject runWaitForScreenTask(Minecraft client, JsonObject request) {
        String expected = getString(request, "screen", "").trim();
        if (expected.isBlank()) {
            return errorJson("InvalidScreen", "Field 'screen' is required.");
        }

        String current = screenName(client.screen);
        if (current.equalsIgnoreCase(expected)) {
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("screen", current);
            root.addProperty("alreadyMatched", true);
            return root;
        }

        int timeoutMs = clamp(getInt(request, "timeoutMs", 5000), 0, 120000);
        taskState.startScreenWait(expected, timeoutMs);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("screen", current);
        root.addProperty("waitingFor", expected);
        root.addProperty("timeoutMs", timeoutMs);
        return root;
    }

    private JsonObject runWaitForActionIdleTask(JsonObject request) {
        if (actionState.kind() == ActionKind.IDLE) {
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("alreadyIdle", true);
            return root;
        }

        int timeoutMs = clamp(getInt(request, "timeoutMs", 15000), 0, 120000);
        taskState.startActionIdleWait(timeoutMs);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("waitingForActionIdle", true);
        root.addProperty("timeoutMs", timeoutMs);
        root.add("action", actionState.toJson());
        return root;
    }

    private boolean isTaskConditionSatisfied(Minecraft client, TaskState state) {
        return switch (state.waitConditionKind()) {
            case "action_idle" -> actionState.kind() == ActionKind.IDLE;
            default -> state.isConditionSatisfied(client);
        };
    }

    private JsonObject runVerifyBlockTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        BlockPos pos = blockPosFromJson(request, player.blockPosition());
        String expectedId = normalizeBlockId(getString(request, "blockId", getString(request, "id", "")));
        boolean shouldBeAir = getBoolean(request, "shouldBeAir", false);
        BlockState state = level.getBlockState(pos);
        String actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean ok = shouldBeAir ? state.isAir() : (expectedId.isBlank() || expectedId.equals(actualId));

        JsonObject root = new JsonObject();
        root.addProperty("ok", ok);
        root.add("pos", blockPosJson(pos));
        root.addProperty("expectedBlockId", expectedId);
        root.addProperty("shouldBeAir", shouldBeAir);
        root.add("block", blockJson(level, pos, null));
        if (!ok) {
            root.addProperty("message", "Block verification failed.");
        }
        return root;
    }

    private JsonObject runVerifyInventoryItemTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", ""));
        int minCount = clamp(getInt(request, "minCount", 1), 0, 2304);
        int total = inventoryItemCounts(player.getInventory()).getOrDefault(itemId, 0);
        boolean ok = total >= minCount;

        JsonObject root = new JsonObject();
        root.addProperty("ok", ok);
        root.addProperty("itemId", itemId);
        root.addProperty("minCount", minCount);
        root.addProperty("actualCount", total);
        root.add("matches", findInventoryItemsJson(player.getInventory(), itemId, 64));
        if (!ok) {
            root.addProperty("message", "Inventory item verification failed.");
        }
        return root;
    }

    private JsonObject runVerifyScreenTask(Minecraft client, JsonObject request) {
        String expected = getString(request, "screen", "").trim();
        String actual = screenName(client.screen);
        boolean opened = client.screen != null;
        boolean ok = expected.isBlank() ? opened : actual.equalsIgnoreCase(expected);

        JsonObject root = new JsonObject();
        root.addProperty("ok", ok);
        root.addProperty("screen", actual);
        root.addProperty("opened", opened);
        root.addProperty("expectedScreen", expected);
        root.addProperty("title", screenTitle(client.screen));
        if (!ok) {
            root.addProperty("message", "Screen verification failed.");
        }
        return root;
    }

    private JsonObject runVerifyContainerTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonObject container = currentContainerJson(player);
        String expectedType = getString(request, "type", "").trim();
        int minSlots = Math.max(0, getInt(request, "minSlots", 0));
        String actualType = container.get("type").getAsString();
        int actualSlots = container.get("slotCount").getAsInt();
        boolean ok = (expectedType.isBlank() || actualType.equalsIgnoreCase(expectedType)) && actualSlots >= minSlots;

        JsonObject root = new JsonObject();
        root.addProperty("ok", ok);
        root.addProperty("expectedType", expectedType);
        root.addProperty("actualType", actualType);
        root.addProperty("minSlots", minSlots);
        root.addProperty("actualSlots", actualSlots);
        root.add("container", container);
        if (!ok) {
            root.addProperty("message", "Container verification failed.");
        }
        return root;
    }

    private JsonObject runSelectHotbarTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        int slot = clamp(getInt(request, "slot", player.getInventory().getSelectedSlot()), 0, 8);
        player.getInventory().setSelectedSlot(slot);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("selectedSlot", slot);
        root.add("selected", itemJson(player.getInventory().getSelectedItem()));
        return root;
    }

    private JsonObject runEquipBestTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String purpose = getString(request, "purpose", "mine");
        int slot = findBestSlotForPurpose(player, level, purpose, request);
        if (slot < 0) {
            return errorJson("NoSuitableItem", "Could not find a suitable hotbar/inventory item for purpose '" + purpose + "'.");
        }

        player.getInventory().setSelectedSlot(slot);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("selectedSlot", slot);
        root.addProperty("purpose", purpose);
        root.add("item", itemJson(player.getInventory().getSelectedItem()));
        return root;
    }

    private JsonObject runOpenInventoryTask(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        player.sendOpenInventory();
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("screen", screenName(client.screen));
        root.add("menu", currentContainerJson(player));
        return root;
    }

    private JsonObject runInventoryClickTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        AbstractContainerMenu menu = player.containerMenu;
        int slot = getInt(request, "slot", -1);
        if (slot < -999 || slot >= menu.slots.size()) {
            return errorJson("InvalidSlot", "Slot out of range for current menu.");
        }

        int button = clamp(getInt(request, "button", 0), 0, 8);
        ContainerInput input = parseContainerInput(getString(request, "mode", "pickup"));
        gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
        menu.broadcastChanges();

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("slot", slot);
        root.addProperty("button", button);
        root.addProperty("mode", input.name().toLowerCase(Locale.ROOT));
        root.add("menu", currentContainerJson(player));
        return root;
    }

    private JsonObject runContainerTransferTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        AbstractContainerMenu menu = player.containerMenu;
        int from = getInt(request, "from", -1);
        if (from < 0 || from >= menu.slots.size()) {
            return errorJson("InvalidFromSlot", "Source slot out of range.");
        }

        int count = clamp(getInt(request, "count", 1), 1, 64);
        JsonArray moves = new JsonArray();
        for (int i = 0; i < count; i++) {
            Slot slot = menu.getSlot(from);
            if (!slot.hasItem()) {
                break;
            }
            ItemStack before = slot.getItem().copy();
            gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.QUICK_MOVE, player);
            menu.broadcastChanges();
            JsonObject move = new JsonObject();
            move.add("before", itemJson(before));
            move.add("after", itemJson(menu.getSlot(from).getItem()));
            moves.add(move);
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", moves.size() > 0);
        root.addProperty("from", from);
        root.add("moves", moves);
        root.add("container", currentContainerJson(player));
        return root;
    }

    private JsonObject runOpenNearbyCraftingTableTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        Optional<PathToBlock> tablePath = findPathToNearbyCraftingTable(level, player);
        if (tablePath.isEmpty()) {
            return errorJson("CraftingTableNotFound", "No reachable nearby crafting table found.");
        }

        PathToBlock path = tablePath.get();
        JsonArray steps = new JsonArray();
        steps.add(stepJson("resolve_table", step -> {
            step.addProperty("ok", true);
            step.add("tablePos", blockPosJson(path.targetBlock()));
            step.add("standPos", blockPosJson(path.standPos()));
        }));

        if (!player.blockPosition().equals(path.standPos())) {
            actionState = ActionState.path(path.path(), path.targetBlock(), "goto crafting table");
            steps.add(stepJson("start_goto_table", step -> {
                step.addProperty("ok", true);
                step.addProperty("pathLength", path.path().size());
            }));
            taskState.startActionIdleWait(clamp(getInt(request, "timeoutMs", 15000), 0, 120000));
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("processMode", "player_like");
            root.addProperty("waitingForActionIdle", true);
            root.add("steps", steps);
            root.add("action", actionState.toJson());
            return root;
        }

        JsonObject open = openCraftingTableAt(client, player, path.targetBlock());
        steps.add(open);
        JsonObject root = new JsonObject();
        root.addProperty("ok", open.get("ok").getAsBoolean());
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        root.add("menu", currentContainerJson(player));
        return root;
    }

    private JsonObject runOpenNearbyContainerTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String blockId = getString(request, "blockId", getString(request, "id", "minecraft:chest"));
        int radius = clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS);
        int standRange = clamp(getInt(request, "standRange", 2), 1, 5);
        Optional<BlockPos> found = findNearestBlock(level, player.blockPosition(), blockId, radius);
        if (found.isEmpty()) {
            return errorJson("ContainerNotFound", "No matching nearby container block found.");
        }

        Optional<PathToBlock> path = findPathToStandNear(level, player.blockPosition(), found.get(), radius, standRange);
        if (path.isEmpty()) {
            return errorJson("NoPath", "Found container but no walkable standing spot near it.");
        }

        JsonArray steps = new JsonArray();
        steps.add(stepJson("resolve_container", step -> {
            step.addProperty("ok", true);
            step.addProperty("blockId", normalizeBlockId(blockId));
            step.add("containerPos", blockPosJson(found.get()));
            step.add("standPos", blockPosJson(path.get().standPos()));
        }));

        if (!player.blockPosition().equals(path.get().standPos())) {
            actionState = ActionState.path(path.get().path(), found.get(), "goto container " + normalizeBlockId(blockId));
            steps.add(stepJson("start_goto_container", step -> {
                step.addProperty("ok", true);
                step.addProperty("pathLength", path.get().path().size());
            }));
            taskState.startActionIdleWait(clamp(getInt(request, "timeoutMs", 15000), 0, 120000));
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("processMode", "player_like");
            root.addProperty("waitingForActionIdle", true);
            root.add("steps", steps);
            root.add("action", actionState.toJson());
            return root;
        }

        JsonObject open = interactBlockAt(client, player, level, found.get(), parseDirection(getString(request, "face", "up"), Direction.UP), parseHand(getString(request, "hand", "main")));
        steps.add(open);
        JsonObject root = new JsonObject();
        root.addProperty("ok", open.get("ok").getAsBoolean());
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        root.add("menu", currentContainerJson(player));
        return root;
    }

    private JsonObject runContainerTransferProcessTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject open = runOpenNearbyContainerTask(client, request);
        steps.add(stepJson("open_nearby_container_process", step -> mergeInto(step, open)));
        if (open.has("error") || (open.has("ok") && !open.get("ok").getAsBoolean())) {
            JsonObject root = errorJson("ContainerOpenFailed", open.has("message") ? open.get("message").getAsString() : "Could not open nearby container.");
            root.add("steps", steps);
            return root;
        }
        if (open.has("waitingForActionIdle") && open.get("waitingForActionIdle").getAsBoolean()) {
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("waitingForActionIdle", true);
            root.addProperty("processMode", "player_like");
            root.add("steps", steps);
            return root;
        }

        JsonObject verify = runVerifyContainerTask(client, requestForContainerType(""));
        steps.add(stepJson("verify_container_opened", step -> mergeInto(step, verify)));
        if (!verify.get("ok").getAsBoolean()) {
            JsonObject root = errorJson("ContainerVerifyFailed", "Container did not open as expected.");
            root.add("steps", steps);
            return root;
        }

        JsonObject transfer = runContainerTransferTask(client, request);
        steps.add(stepJson("transfer_items", step -> mergeInto(step, transfer)));
        if (transfer.has("error") || (transfer.has("ok") && !transfer.get("ok").getAsBoolean())) {
            JsonObject root = errorJson("ContainerTransferFailed", transfer.has("message") ? transfer.get("message").getAsString() : "Transfer failed.");
            root.add("steps", steps);
            return root;
        }

        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("close_screen", step -> mergeInto(step, close)));

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        return root;
    }

    private JsonObject runContainerTransferProcessAutoRepairTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject first = runContainerTransferProcessTask(client, request);
        steps.add(stepJson("first_attempt", step -> mergeInto(step, first)));
        if (!first.has("error") && (!first.has("ok") || first.get("ok").getAsBoolean())) {
            JsonObject root = first.deepCopy();
            root.addProperty("autoRepairUsed", false);
            root.add("steps", steps);
            return root;
        }

        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("repair_close_screen", step -> mergeInto(step, close)));

        JsonObject second = runContainerTransferProcessTask(client, request);
        steps.add(stepJson("second_attempt", step -> mergeInto(step, second)));
        JsonObject root = second.deepCopy();
        root.addProperty("autoRepairUsed", true);
        root.add("steps", steps);
        return root;
    }

    private JsonObject runCraftInventoryProcessTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject open = runOpenInventoryTask(client);
        steps.add(stepJson("open_inventory", step -> mergeInto(step, open)));
        if (open.has("error") || (open.has("ok") && !open.get("ok").getAsBoolean())) {
            JsonObject root = errorJson("OpenInventoryFailed", open.has("message") ? open.get("message").getAsString() : "Could not open inventory.");
            root.add("steps", steps);
            return root;
        }

        JsonObject verify = requestForScreen("InventoryScreen");
        JsonObject verifyScreen = runVerifyContainerTask(client, requestForContainerType("inventory"));
        steps.add(stepJson("verify_inventory_menu", step -> mergeInto(step, verifyScreen)));
        if (verifyScreen.has("error") || (verifyScreen.has("ok") && !verifyScreen.get("ok").getAsBoolean())) {
            JsonObject root = errorJson("InventoryVerifyFailed", "Inventory menu did not open as expected.");
            root.add("steps", steps);
            return root;
        }

        JsonObject craftRequest = request.deepCopy();
        craftRequest.addProperty("useTable", false);
        JsonObject craft = craftFromRequest(client, craftRequest);
        steps.add(stepJson("craft_inventory", step -> mergeInto(step, craft)));
        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("close_screen", step -> mergeInto(step, close)));

        JsonObject root = craft.deepCopy();
        root.add("steps", steps);
        return root;
    }

    private JsonObject runCraftInventoryProcessAutoRepairTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject first = runCraftInventoryProcessTask(client, request);
        steps.add(stepJson("first_attempt", step -> mergeInto(step, first)));
        if (!first.has("error") && (!first.has("ok") || first.get("ok").getAsBoolean())) {
            JsonObject root = first.deepCopy();
            root.addProperty("autoRepairUsed", false);
            root.add("steps", steps);
            return root;
        }

        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("repair_close_screen", step -> mergeInto(step, close)));

        JsonObject reopen = runOpenInventoryTask(client);
        steps.add(stepJson("repair_reopen_inventory", step -> mergeInto(step, reopen)));

        JsonObject second = runCraftInventoryProcessTask(client, request);
        steps.add(stepJson("second_attempt", step -> mergeInto(step, second)));
        JsonObject root = second.deepCopy();
        root.addProperty("autoRepairUsed", true);
        root.add("steps", steps);
        return root;
    }

    private JsonObject runCraftTableProcessTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject open = runOpenNearbyCraftingTableTask(client, request);
        steps.add(stepJson("open_nearby_crafting_table", step -> mergeInto(step, open)));
        if (open.has("error") || (open.has("ok") && !open.get("ok").getAsBoolean())) {
            JsonObject root = open.deepCopy();
            root.add("steps", steps);
            return root;
        }
        if (open.has("waitingForActionIdle") && open.get("waitingForActionIdle").getAsBoolean()) {
            JsonObject root = open.deepCopy();
            root.add("steps", steps);
            return root;
        }

        JsonObject verify = runVerifyContainerTask(client, requestForContainerType("crafting"));
        steps.add(stepJson("verify_crafting_menu", step -> mergeInto(step, verify)));
        if (verify.has("error") || (verify.has("ok") && !verify.get("ok").getAsBoolean())) {
            JsonObject root = errorJson("CraftingTableVerifyFailed", "Crafting table menu did not open as expected.");
            root.add("steps", steps);
            return root;
        }

        JsonObject craftRequest = request.deepCopy();
        craftRequest.addProperty("useTable", true);
        JsonObject craft = craftFromRequest(client, craftRequest);
        steps.add(stepJson("craft_table", step -> mergeInto(step, craft)));
        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("close_screen", step -> mergeInto(step, close)));

        JsonObject root = craft.deepCopy();
        root.add("steps", steps);
        return root;
    }

    private JsonObject runCraftTableProcessAutoRepairTask(Minecraft client, JsonObject request) {
        JsonArray steps = new JsonArray();
        JsonObject first = runCraftTableProcessTask(client, request);
        steps.add(stepJson("first_attempt", step -> mergeInto(step, first)));
        if (!first.has("error") && (!first.has("ok") || first.get("ok").getAsBoolean())) {
            JsonObject root = first.deepCopy();
            root.addProperty("autoRepairUsed", false);
            root.add("steps", steps);
            return root;
        }

        JsonObject close = runCloseScreenTask(client);
        steps.add(stepJson("repair_close_screen", step -> mergeInto(step, close)));

        JsonObject second = runCraftTableProcessTask(client, request);
        steps.add(stepJson("second_attempt", step -> mergeInto(step, second)));
        JsonObject root = second.deepCopy();
        root.addProperty("autoRepairUsed", true);
        root.add("steps", steps);
        return root;
    }

    private JsonObject runActionLookAtTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }
        Vec3 target = resolveLookTarget(client, request);
        if (target == null) {
            return errorJson("TargetNotFound", "Could not resolve look target.");
        }
        lookAt(player, target);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("raycast", raycastJson(client, player, level, 6));
        return root;
    }

    private JsonObject actionMineBlockInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || level == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        BlockPos target = blockPosFromJson(request, player.blockPosition());
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return errorJson("NoBlock", "Target block is already air.");
        }

        JsonArray steps = new JsonArray();
        Direction face = resolveBlockFace(player, target);
        steps.add(stepJson("resolve_target", step -> {
            step.addProperty("ok", true);
            step.add("target", blockPosJson(target));
            step.addProperty("face", face.getName());
            step.add("block", blockJson(level, target, null));
        }));

        lookAt(player, target.getCenter());
        steps.add(stepJson("look_at_target", step -> {
            step.addProperty("ok", true);
            step.add("raycast", raycastJson(client, player, level, 6));
        }));

        boolean started = gameMode.startDestroyBlock(target, face);
        if (!started) {
            JsonObject root = errorJson("MineStartFailed", "Minecraft refused to start breaking the target block.");
            root.add("steps", steps);
            return root;
        }

        gameMode.continueDestroyBlock(target, face);
        actionState = ActionState.mine(target, face, "mineBlock " + target.toShortString());
        steps.add(stepJson("start_breaking", step -> {
            step.addProperty("ok", true);
            step.addProperty("actionKind", actionState.kind().name().toLowerCase(Locale.ROOT));
        }));

        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("target", blockPosJson(target));
        root.add("steps", steps);
        root.add("verify", verifyMineState(level, target));
        return root;
    }

    private JsonObject actionAttackEntityInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || level == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        Entity target = resolveEntity(client, request);
        if (target == null) {
            return errorJson("EntityNotFound", "Could not resolve entity target.");
        }
        if (target == player) {
            return errorJson("InvalidTarget", "Cannot attack the local player.");
        }

        int count = clamp(getInt(request, "count", 1), 1, 20);
        JsonArray steps = new JsonArray();
        steps.add(stepJson("resolve_target", step -> {
            step.addProperty("ok", true);
            step.add("target", entityJson(player, target));
        }));

        lookAt(player, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        steps.add(stepJson("look_at_target", step -> {
            step.addProperty("ok", true);
            step.add("raycast", raycastJson(client, player, level, 6));
        }));
        actionState = ActionState.attack(target.getId(), target.getUUID().toString(), count, "attackEntity " + target.getName().getString());
        steps.add(stepJson("start_attack", step -> {
            step.addProperty("ok", true);
            step.addProperty("count", count);
        }));

        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("target", entityJson(player, target));
        root.add("steps", steps);
        root.add("verify", verifyAttackState(player, target));
        return root;
    }

    private JsonObject actionInteractInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || level == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        InteractionHand hand = parseHand(getString(request, "hand", "main"));
        JsonArray steps = new JsonArray();
        Entity entity = resolveEntity(client, request);
        if (entity != null) {
            lookAt(player, entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D));
            steps.add(stepJson("look_at_entity", step -> {
                step.addProperty("ok", true);
                step.add("target", entityJson(player, entity));
            }));
            EntityHitResult hit = new EntityHitResult(entity);
            InteractionResult interaction = gameMode.interact(player, entity, hit, hand);
            JsonObject root = interactionResultJson("entity", interaction, client);
            root.addProperty("processMode", "player_like");
            root.add("steps", steps);
            root.add("target", entityJson(player, entity));
            root.add("verify", verifyScreenAndRaycast(client, player, level));
            return root;
        }

        BlockPos target = blockPosFromJson(request, player.blockPosition());
        Direction face = parseDirection(getString(request, "face", ""), Direction.UP);
        Vec3 location = target.getCenter().add(
            face.getStepX() * 0.5D,
            face.getStepY() * 0.5D,
            face.getStepZ() * 0.5D
        );
        lookAt(player, location);
        steps.add(stepJson("look_at_block", step -> {
            step.addProperty("ok", true);
            step.add("target", blockPosJson(target));
            step.add("raycast", raycastJson(client, player, level, 6));
        }));
        BlockHitResult hit = new BlockHitResult(location, face, target, false);
        InteractionResult interaction = gameMode.useItemOn(player, hand, hit);
        JsonObject root = interactionResultJson("block", interaction, client);
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        root.add("target", blockPosJson(target));
        root.add("verify", verifyScreenAndRaycast(client, player, level));
        return root;
    }

    private JsonObject actionUseItemInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        ClientLevel level = client.level;
        if (player == null || level == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonArray steps = new JsonArray();
        if (request.has("slot")) {
            int requestedSlot = clamp(getInt(request, "slot", player.getInventory().getSelectedSlot()), 0, 8);
            player.getInventory().setSelectedSlot(requestedSlot);
            steps.add(stepJson("select_slot", step -> {
                step.addProperty("ok", true);
                step.addProperty("selectedSlot", requestedSlot);
                step.add("selected", itemJson(player.getInventory().getSelectedItem()));
            }));
        }

        InteractionHand hand = parseHand(getString(request, "hand", "main"));
        InteractionResult interaction = gameMode.useItem(player, hand);
        JsonObject root = interactionResultJson("item", interaction, client);
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        root.add("inventory", inventorySummaryJson(player));
        root.add("verify", verifyUseItemState(client, player, level));
        return root;
    }

    private JsonObject actionPlaceBlockInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || level == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonArray steps = new JsonArray();
        if (request.has("slot")) {
            int requestedSlot = clamp(getInt(request, "slot", player.getInventory().getSelectedSlot()), 0, 8);
            player.getInventory().setSelectedSlot(requestedSlot);
            steps.add(stepJson("select_slot", step -> {
                step.addProperty("ok", true);
                step.addProperty("selectedSlot", requestedSlot);
            }));
        } else if (request.has("blockId")) {
            int found = findMatchingHotbarSlot(player.getInventory(), normalizeItemId(getString(request, "blockId", "")));
            if (found < 0) {
                return errorJson("ItemNotFound", "Requested block item not found in hotbar.");
            }
            player.getInventory().setSelectedSlot(found);
            steps.add(stepJson("select_block_item", step -> {
                step.addProperty("ok", true);
                step.addProperty("selectedSlot", found);
                step.add("selected", itemJson(player.getInventory().getSelectedItem()));
            }));
        }

        BlockPos target = blockPosFromJson(request, player.blockPosition());
        Direction face = parseDirection(getString(request, "face", "up"), Direction.UP);
        BlockPos support = target.relative(face.getOpposite());
        Vec3 hitLocation = support.getCenter().add(
            face.getStepX() * 0.5D,
            face.getStepY() * 0.5D,
            face.getStepZ() * 0.5D
        );

        if (getBoolean(request, "lookAtFirst", true)) {
            lookAt(player, hitLocation);
            steps.add(stepJson("look_at_support_face", step -> {
                step.addProperty("ok", true);
                step.add("support", blockPosJson(support));
                step.addProperty("face", face.getName());
                step.add("raycast", raycastJson(client, player, level, 6));
            }));
        }

        BlockHitResult hit = new BlockHitResult(hitLocation, face, support, false);
        InteractionHand hand = parseHand(getString(request, "hand", "main"));
        InteractionResult interaction = gameMode.useItemOn(player, hand, hit);

        JsonObject root = interactionResultJson("placeBlock", interaction, client);
        root.addProperty("processMode", "player_like");
        root.add("steps", steps);
        root.add("target", blockPosJson(target));
        root.add("support", blockPosJson(support));
        root.add("placedBlock", blockJson(level, target, null));
        root.add("verify", verifyPlacedBlock(level, target));
        return root;
    }

    private JsonObject actionPickupItemsInternal(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        int radius = clamp(getInt(request, "radius", 6), 1, 24);
        int timeoutMs = clamp(getInt(request, "timeoutMs", 3000), 200, 30000);
        long untilMs = System.currentTimeMillis() + timeoutMs;
        List<BlockPos> path = List.of(player.blockPosition());
        JsonArray steps = new JsonArray();
        JsonArray targets = nearbyItemEntitiesJson(level, player, radius, 16);
        steps.add(stepJson("scan_nearby_items", step -> {
            step.addProperty("ok", targets.size() > 0);
            step.addProperty("radius", radius);
            step.add("items", targets);
        }));
        actionState = ActionState.path(path, null, "pickupItems");
        actionState.setPickupMode(radius, untilMs);

        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.addProperty("radius", radius);
        root.addProperty("timeoutMs", timeoutMs);
        root.add("steps", steps);
        root.add("verify", nearbyItemPickupVerify(level, player, radius));
        return root;
    }

    private JsonObject runActionGotoTask(Minecraft client, JsonObject request) {
        return startGotoAction(client, request);
    }

    private JsonObject runActionGotoBlockTask(Minecraft client, JsonObject request) {
        return startGotoBlockAction(client, request);
    }

    private JsonObject runActionInteractTask(Minecraft client, JsonObject request) {
        return actionInteractInternal(client, request);
    }

    private JsonObject runActionUseItemTask(Minecraft client, JsonObject request) {
        return actionUseItemInternal(client, request);
    }

    private JsonObject runActionPlaceBlockTask(Minecraft client, JsonObject request) {
        return actionPlaceBlockInternal(client, request);
    }

    private JsonObject runActionMineBlockTask(Minecraft client, JsonObject request) {
        return actionMineBlockInternal(client, request);
    }

    private JsonObject runActionPickupItemsTask(Minecraft client, JsonObject request) {
        return actionPickupItemsInternal(client, request);
    }

    private JsonObject runCloseScreenTask(Minecraft client) {
        if (client.screen == null) {
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("screen", "none");
            return root;
        }
        client.screen.onClose();
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("screen", screenName(client.screen));
        return root;
    }

    private JsonObject startGotoAction(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        BlockPos target = blockPosFromJson(request, player.blockPosition());
        int searchRadius = clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS);
        Optional<List<BlockPos>> path = findPath(level, player.blockPosition(), target, searchRadius);
        if (path.isEmpty()) {
            return errorJson("NoPath", "No walkable path found to " + target.toShortString());
        }

        actionState = ActionState.path(path.get(), target, "goto " + target.toShortString());
        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("target", blockPosJson(target));
        root.add("path", pathJson(path.get()));
        return root;
    }

    private JsonObject startGotoBlockAction(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String id = getString(request, "id", getString(request, "blockId", "minecraft:stone"));
        int radius = clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS);
        int standRange = clamp(getInt(request, "standRange", 2), 1, 5);
        Optional<BlockPos> found = findNearestBlock(level, player.blockPosition(), id, radius);
        if (found.isEmpty()) {
            return errorJson("BlockNotFound", "No " + id + " in radius " + radius + ".");
        }
        Optional<PathToBlock> path = findPathToStandNear(level, player.blockPosition(), found.get(), radius, standRange);
        if (path.isEmpty()) {
            return errorJson("NoPath", "Found " + id + " but no walkable standing spot near it.");
        }

        actionState = ActionState.path(path.get().path(), path.get().targetBlock(), "gotoBlock " + id);
        JsonObject root = actionState.toJson();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.addProperty("id", normalizeBlockId(id));
        root.add("block", blockJson(level, found.get(), null));
        root.add("standPos", blockPosJson(path.get().standPos()));
        root.add("path", pathJson(path.get().path()));
        return root;
    }

    private JsonObject openCraftingTableAt(Minecraft client, LocalPlayer player, BlockPos tablePos) {
        JsonObject step = new JsonObject();
        step.addProperty("step", "open_crafting_table");
        if (client.gameMode == null) {
            step.addProperty("ok", false);
            step.addProperty("message", "Game mode is not ready.");
            return step;
        }

        Direction face = resolveBlockFace(player, tablePos);
        Vec3 location = tablePos.getCenter().add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        lookAt(player, location);
        InteractionResult interaction = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(location, face, tablePos, false));
        step.addProperty("interaction", interaction.toString().toLowerCase(Locale.ROOT));
        step.addProperty("ok", currentCraftingMenu(player) instanceof CraftingMenu);
        step.add("tablePos", blockPosJson(tablePos));
        step.addProperty("screen", screenName(client.screen));
        if (player.containerMenu != null) {
            step.add("menu", currentContainerJson(player));
        }
        if (!step.get("ok").getAsBoolean()) {
            step.addProperty("message", "Crafting table did not open.");
        }
        return step;
    }

    private JsonObject interactBlockAt(Minecraft client, LocalPlayer player, ClientLevel level, BlockPos target, Direction face, InteractionHand hand) {
        JsonObject step = new JsonObject();
        step.addProperty("step", "interact_block");
        if (client.gameMode == null) {
            step.addProperty("ok", false);
            step.addProperty("message", "Game mode is not ready.");
            return step;
        }

        Vec3 location = target.getCenter().add(
            face.getStepX() * 0.5D,
            face.getStepY() * 0.5D,
            face.getStepZ() * 0.5D
        );
        lookAt(player, location);
        InteractionResult interaction = client.gameMode.useItemOn(player, hand, new BlockHitResult(location, face, target, false));
        step.addProperty("ok", interaction.consumesAction() || interaction == InteractionResult.SUCCESS || interaction == InteractionResult.SUCCESS_SERVER || client.screen != null);
        step.addProperty("interaction", interaction.toString().toLowerCase(Locale.ROOT));
        step.add("target", blockPosJson(target));
        step.add("verify", verifyScreenAndRaycast(client, player, level));
        return step;
    }

    private JsonObject evaluateTaskCondition(Minecraft client, JsonObject condition) {
        String action = getString(condition, "action", "").trim();
        if (action.isBlank()) {
            return errorJson("InvalidCondition", "Condition must include an 'action'.");
        }
        return switch (action) {
            case "verifyBlock" -> runVerifyBlockTask(client, condition);
            case "verifyInventoryItem" -> runVerifyInventoryItemTask(client, condition);
            case "verifyScreen" -> runVerifyScreenTask(client, condition);
            case "verifyContainer" -> runVerifyContainerTask(client, condition);
            default -> errorJson("UnsupportedCondition", "Unsupported condition action '" + action + "'.");
        };
    }

    private List<JsonObject> parseTaskStepsFromField(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonArray()) {
            return List.of();
        }

        JsonArray array = request.getAsJsonArray(key);
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                steps.add(element.getAsJsonObject().deepCopy());
            }
        }
        return steps;
    }

    private void mergeInto(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private JsonObject requestForContainerType(String type) {
        JsonObject request = new JsonObject();
        request.addProperty("type", type);
        return request;
    }

    private JsonObject requestForScreen(String screen) {
        JsonObject request = new JsonObject();
        request.addProperty("screen", screen);
        return request;
    }

    private JsonObject taskConditionSnapshot(Minecraft client) {
        JsonObject root = new JsonObject();
        root.addProperty("screen", screenName(client.screen));
        root.addProperty("time", Instant.now().toString());
        root.add("action", actionState.toJson());
        return root;
    }

    private InteractionHand parseHand(String raw) {
        return "off".equalsIgnoreCase(raw) || "off_hand".equalsIgnoreCase(raw) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private Direction parseDirection(String raw, Direction fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> fallback;
        };
    }

    private Direction resolveBlockFace(LocalPlayer player, BlockPos target) {
        Vec3 delta = target.getCenter().subtract(player.getEyePosition());
        double ax = Math.abs(delta.x);
        double ay = Math.abs(delta.y);
        double az = Math.abs(delta.z);
        if (ay >= ax && ay >= az) {
            return delta.y > 0 ? Direction.DOWN : Direction.UP;
        }
        if (ax >= az) {
            return delta.x > 0 ? Direction.WEST : Direction.EAST;
        }
        return delta.z > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private int findBestSlotForPurpose(LocalPlayer player, ClientLevel level, String purpose, JsonObject request) {
        Inventory inventory = player.getInventory();
        String normalized = purpose == null ? "" : purpose.trim().toLowerCase(Locale.ROOT);
        BlockPos targetBlock = request.has("blockPos") && request.get("blockPos").isJsonObject()
            ? blockPosFromJson(request.getAsJsonObject("blockPos"), player.blockPosition())
            : player.blockPosition();
        BlockState state = level.getBlockState(targetBlock);

        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < Math.min(inventory.getContainerSize(), 9); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            double score = switch (normalized) {
                case "combat" -> combatScore(stack);
                case "food" -> foodScore(stack);
                default -> miningScore(stack, state);
            };

            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private double miningScore(ItemStack stack, BlockState state) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        double score = stack.getDestroySpeed(state);
        if (id.contains("pickaxe")) score += 6;
        if (id.contains("axe")) score += 4;
        if (id.contains("shovel")) score += 3;
        if (stack.isDamageableItem()) score -= stack.getDamageValue() * 0.001D;
        return score;
    }

    private double combatScore(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        double score = 0;
        if (id.contains("sword")) score += 10;
        if (id.contains("axe")) score += 7;
        if (id.contains("bow")) score += 6;
        if (id.contains("trident")) score += 9;
        score += stack.getCount() * 0.01D;
        return score;
    }

    private double foodScore(ItemStack stack) {
        if (stack.getComponents().has(net.minecraft.core.component.DataComponents.FOOD)) {
            return 10 + stack.getCount() * 0.01D;
        }
        return 0;
    }

    private JsonObject currentContainerJson(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        JsonObject root = new JsonObject();
        root.addProperty("containerId", menu.containerId);
        root.addProperty("stateId", menu.getStateId());
        root.addProperty("screen", screenName(Minecraft.getInstance().screen));
        root.addProperty("type", menuTypeName(menu));
        root.addProperty("title", screenTitle(Minecraft.getInstance().screen));
        root.addProperty("inventoryMenu", menu == player.inventoryMenu);
        root.addProperty("slotCount", menu.slots.size());
        root.add("carried", itemJson(menu.getCarried()));
        root.add("slots", containerSlotsJson(player, menu));
        return root;
    }

    private JsonArray containerSlotsJson(LocalPlayer player, AbstractContainerMenu menu) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            JsonObject entry = itemJson(slot.getItem());
            entry.addProperty("slot", i);
            entry.addProperty("inventoryIndex", slot.index);
            entry.addProperty("x", slot.x);
            entry.addProperty("y", slot.y);
            entry.addProperty("mayPickup", slot.mayPickup(player));
            entry.addProperty("hasItem", slot.hasItem());
            slots.add(entry);
        }
        return slots;
    }

    private ContainerInput parseContainerInput(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContainerInput.PICKUP;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "quick_move", "shift", "shift_click" -> ContainerInput.QUICK_MOVE;
            case "swap", "hotbar" -> ContainerInput.SWAP;
            case "clone" -> ContainerInput.CLONE;
            case "throw", "drop" -> ContainerInput.THROW;
            case "quick_craft", "drag" -> ContainerInput.QUICK_CRAFT;
            case "pickup_all" -> ContainerInput.PICKUP_ALL;
            default -> ContainerInput.PICKUP;
        };
    }

    private int findMatchingHotbarSlot(Inventory inventory, String itemId) {
        for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    private JsonObject craftFromRequest(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", ""));
        int craftTimes = clamp(getInt(request, "count", 1), 1, 64);
        boolean preferTable = getBoolean(request, "useTable", false);

        RecipeSelection selection = selectRecipeForCraft(client, player, level, itemId, preferTable);
        if (selection == null) {
            JsonObject root = errorJson("RecipeNotFound", "No usable recipe found for " + itemId + ".");
            root.add("recipes", recipesJson(client, player, level, itemId, 16));
            return root;
        }

        JsonArray steps = new JsonArray();
        JsonObject prepare = prepareCraftMenu(client, player, level, selection);
        steps.add(prepare);
        if (!prepare.get("ok").getAsBoolean()) {
            JsonObject root = errorJson("CraftPrepareFailed", prepare.has("message") ? prepare.get("message").getAsString() : "Could not prepare crafting menu.");
            root.add("steps", steps);
            return root;
        }

        AbstractCraftingMenu menu = currentCraftingMenu(player);
        if (menu == null) {
            JsonObject root = errorJson("CraftMenuMissing", "Crafting menu is not open after preparation.");
            root.add("steps", steps);
            return root;
        }

        int maxCrafts = selection.entry().canCraft(inventoryContents(player.getInventory())) ? maxCraftableCount(player.getInventory(), selection.holder()) : 0;
        if (maxCrafts <= 0) {
            JsonObject root = errorJson("MissingIngredients", "Current inventory does not satisfy the selected recipe.");
            root.add("steps", steps);
            return root;
        }

        int runs = Math.min(craftTimes, maxCrafts);
        for (int i = 0; i < runs; i++) {
            JsonObject placeStep = placeRecipeIntoCurrentMenu(client, player, selection, false);
            steps.add(placeStep);
            if (!placeStep.get("ok").getAsBoolean()) {
                JsonObject root = errorJson("CraftPlaceFailed", placeStep.has("message") ? placeStep.get("message").getAsString() : "Failed to place recipe into crafting grid.");
                root.add("steps", steps);
                return root;
            }

            JsonObject takeStep = takeCraftResult(client, player);
            steps.add(takeStep);
            if (!takeStep.get("ok").getAsBoolean()) {
                JsonObject root = errorJson("CraftTakeFailed", takeStep.has("message") ? takeStep.get("message").getAsString() : "Failed to take crafted result.");
                root.add("steps", steps);
                return root;
            }
        }

        ItemStack resultStack = selection.entry().resultItems(SlotDisplayContext.fromLevel(level)).stream().findFirst().orElse(ItemStack.EMPTY);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("itemId", itemId);
        root.addProperty("countRequested", craftTimes);
        root.addProperty("craftRuns", runs);
        root.addProperty("craftedCount", runs * Math.max(1, resultStack.getCount()));
        root.addProperty("menuType", selection.menuType());
        root.addProperty("processMode", "player_like");
        root.addProperty("usedCraftingTable", "crafting_table".equals(selection.menuType()));
        root.addProperty("recipeDisplayId", selection.entry().id().index());
        root.add("craftedItem", itemJson(resultStack));
        root.add("steps", steps);
        root.add("inventory", inventoryJson(player));
        return root;
    }

    private JsonArray nearbyBlocksJson(ClientLevel level, BlockPos center, int radius) {
        JsonArray blocks = new JsonArray();

        for (int y = -radius; y <= radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!state.isAir()) {
                        blocks.add(blockJson(level, pos, null));
                    }
                }
            }
        }

        return blocks;
    }

    private JsonObject blockJson(ClientLevel level, BlockPos pos, Direction hitSide) {
        BlockState state = level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        JsonObject root = new JsonObject();
        root.add("pos", blockPosJson(pos));
        root.addProperty("id", blockId.toString());
        root.addProperty("name", state.getBlock().getName().getString());
        root.addProperty("state", state.toString());
        root.addProperty("isAir", state.isAir());
        root.addProperty("isSolidRender", state.isSolidRender());
        root.addProperty("destroySpeed", state.getDestroySpeed(level, pos));
        root.addProperty("lightDampening", state.getLightDampening());
        root.addProperty("emittedLight", state.getLightEmission());
        root.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
        root.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));

        if (hitSide != null) {
            root.addProperty("hitSide", hitSide.getName());
        }

        JsonObject properties = new JsonObject();
        state.getValues().forEach(value -> properties.addProperty(value.property().getName(), value.valueName()));
        root.add("properties", properties);

        JsonObject fluid = new JsonObject();
        fluid.addProperty("empty", state.getFluidState().isEmpty());
        fluid.addProperty("type", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
        root.add("fluid", fluid);

        return root;
    }

    private JsonObject equipmentJson(LocalPlayer player) {
        JsonObject root = new JsonObject();
        root.add("head", itemJson(player.getItemBySlot(EquipmentSlot.HEAD)));
        root.add("chest", itemJson(player.getItemBySlot(EquipmentSlot.CHEST)));
        root.add("legs", itemJson(player.getItemBySlot(EquipmentSlot.LEGS)));
        root.add("feet", itemJson(player.getItemBySlot(EquipmentSlot.FEET)));
        return root;
    }

    private JsonObject itemJson(ItemStack stack) {
        JsonObject root = new JsonObject();
        root.addProperty("empty", stack.isEmpty());
        root.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        root.addProperty("name", stack.getHoverName().getString());
        root.addProperty("count", stack.getCount());
        root.addProperty("damage", stack.getDamageValue());
        root.addProperty("maxDamage", stack.getMaxDamage());
        return root;
    }

    private JsonObject vecJson(Vec3 vec) {
        JsonObject root = new JsonObject();
        root.addProperty("x", vec.x);
        root.addProperty("y", vec.y);
        root.addProperty("z", vec.z);
        return root;
    }

    private JsonObject blockPosJson(BlockPos pos) {
        JsonObject root = new JsonObject();
        root.addProperty("x", pos.getX());
        root.addProperty("y", pos.getY());
        root.addProperty("z", pos.getZ());
        return root;
    }

    private JsonObject boxJson(AABB box) {
        JsonObject root = new JsonObject();
        root.addProperty("minX", box.minX);
        root.addProperty("minY", box.minY);
        root.addProperty("minZ", box.minZ);
        root.addProperty("maxX", box.maxX);
        root.addProperty("maxY", box.maxY);
        root.addProperty("maxZ", box.maxZ);
        return root;
    }

    private JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }

            if (body.isEmpty()) {
                return new JsonObject();
            }

            JsonElement parsed = JsonParser.parseString(body.toString());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
    }

    private JsonObject queryJson(HttpExchange exchange) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String> entry : query(exchange).entrySet()) {
            root.addProperty(entry.getKey(), entry.getValue());
        }
        return root;
    }

    private JsonObject errorJson(String error, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("error", error == null ? "Error" : error);
        root.addProperty("message", message == null ? "" : message);
        return root;
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        sendText(exchange, status, GSON.toJson(json) + "\n", "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://127.0.0.1");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(data);
        }
    }

    private boolean isGet(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private boolean isPost(HttpExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private int readRadius(HttpExchange exchange) {
        return clamp(readInt(query(exchange).get("radius"), DEFAULT_RADIUS), 0, MAX_RADIUS);
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();

        if (raw == null || raw.isBlank()) {
            return values;
        }

        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String key = separator >= 0 ? part.substring(0, separator) : part;
            String value = separator >= 0 ? part.substring(separator + 1) : "";
            values.put(urlDecode(key), urlDecode(value));
        }

        return values;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int readInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private boolean hasNumber(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isNumber();
    }

    private String getString(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsString();
    }

    private int getInt(JsonObject json, String key, int fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException error) {
            return readInt(json.get(key).getAsString(), fallback);
        }
    }

    private float getFloat(JsonObject json, String key, float fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsFloat();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private boolean getBoolean(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException error) {
            return Boolean.parseBoolean(json.get(key).getAsString());
        }
    }

    private BlockPos blockPosFromJson(JsonObject json, BlockPos fallback) {
        return new BlockPos(
            getInt(json, "x", fallback.getX()),
            getInt(json, "y", fallback.getY()),
            getInt(json, "z", fallback.getZ())
        );
    }

    private String normalizeBlockId(String rawId) {
        String id = rawId == null || rawId.isBlank() ? "minecraft:stone" : rawId.trim();
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private String normalizeItemId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "";
        }
        String id = rawId.trim();
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private boolean recipeMatchesOutput(ClientLevel level, RecipeHolder<?> holder, String itemId) {
        try {
            ItemStack assembled = holder.value().assemble(null);
            return !assembled.isEmpty() && BuiltInRegistries.ITEM.getKey(assembled.getItem()).toString().equals(itemId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private JsonObject recipeJson(ClientLevel level, LocalPlayer player, RecipeManager recipeManager, RecipeHolder<?> holder) {
        JsonObject root = new JsonObject();
        root.addProperty("id", holder.id().identifier().toString());
        root.addProperty("type", holder.value().getType().toString());
        root.addProperty("group", holder.value().group());
        root.addProperty("special", holder.value().isSpecial());
        root.addProperty("category", holder.value().recipeBookCategory().toString());

        try {
            ItemStack result = holder.value().assemble(null);
            root.add("result", itemJson(result));
        } catch (Throwable ignored) {
            root.add("result", itemJson(ItemStack.EMPTY));
        }

        List<Ingredient> ingredients = recipeIngredients(holder);
        JsonArray ingredientsJson = new JsonArray();
        for (Ingredient ingredient : ingredients) {
            ingredientsJson.add(ingredientJson(ingredient));
        }
        root.add("ingredients", ingredientsJson);
        root.addProperty("craftableNow", maxCraftableCount(player.getInventory(), holder) > 0);

        JsonArray displays = new JsonArray();
        recipeManager.listDisplaysForRecipe(holder.id(), entry -> displays.add(recipeDisplayJson(level, player, entry)));
        root.add("displays", displays);
        return root;
    }

    private JsonObject ingredientJson(Ingredient ingredient) {
        JsonObject root = new JsonObject();
        root.addProperty("empty", ingredient == null || ingredient.isEmpty());
        JsonArray options = new JsonArray();
        if (ingredient != null) {
            ingredient.items().forEach(holder -> {
                JsonObject option = new JsonObject();
                option.addProperty("id", BuiltInRegistries.ITEM.getKey(holder.value()).toString());
                option.addProperty("name", holder.value().getName(ItemStack.EMPTY).getString());
                options.add(option);
            });
        }
        root.add("options", options);
        return root;
    }

    private JsonObject recipeDisplayJson(ClientLevel level, LocalPlayer player, RecipeDisplayEntry entry) {
        JsonObject root = new JsonObject();
        root.addProperty("displayId", entry.id().index());
        root.addProperty("category", entry.category().toString());
        root.addProperty("craftable", entry.canCraft(inventoryContents(player.getInventory())));
        root.add("results", itemListJson(entry.resultItems(SlotDisplayContext.fromLevel(level))));
        JsonArray requirements = new JsonArray();
        if (entry.craftingRequirements().isPresent()) {
            for (Ingredient ingredient : entry.craftingRequirements().get()) {
                requirements.add(ingredientJson(ingredient));
            }
        }
        root.add("requirements", requirements);
        return root;
    }

    private RecipeSelection selectRecipeForCraft(Minecraft client, LocalPlayer player, ClientLevel level, String itemId, boolean preferTable) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return null;
        }

        RecipeManager recipeManager = server.getRecipeManager();
        List<RecipeSelection> selections = new ArrayList<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (!recipeMatchesOutput(level, holder, itemId)) {
                continue;
            }
            recipeManager.listDisplaysForRecipe(holder.id(), entry -> {
                String menuType = inferRecipeMenuType(holder);
                selections.add(new RecipeSelection(holder, entry, menuType));
            });
        }

        selections.sort(Comparator
            .comparingInt((RecipeSelection selection) -> selection.entry().canCraft(inventoryContents(player.getInventory())) ? 0 : 1)
            .thenComparingInt(selection -> preferTable == "crafting_table".equals(selection.menuType()) ? 0 : 1)
            .thenComparingInt(selection -> "inventory".equals(selection.menuType()) ? 0 : 1));

        for (RecipeSelection selection : selections) {
            if (selection.entry().canCraft(inventoryContents(player.getInventory()))) {
                return selection;
            }
        }
        return selections.isEmpty() ? null : selections.get(0);
    }

    private String inferRecipeMenuType(RecipeHolder<?> holder) {
        if (holder.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            if (shaped.getWidth() > 2 || shaped.getHeight() > 2) {
                return "crafting_table";
            }
        }
        return "inventory";
    }

    private JsonObject prepareCraftMenu(Minecraft client, LocalPlayer player, ClientLevel level, RecipeSelection selection) {
        JsonObject step = new JsonObject();
        step.addProperty("step", "prepare_menu");
        step.addProperty("targetMenuType", selection.menuType());

        if ("inventory".equals(selection.menuType())) {
            if (!(client.screen instanceof InventoryScreen)) {
                player.sendOpenInventory();
            }
            step.addProperty("ok", currentCraftingMenu(player) instanceof InventoryMenu);
            step.addProperty("screen", screenName(client.screen));
            step.add("menu", currentContainerJson(player));
            if (!step.get("ok").getAsBoolean()) {
                step.addProperty("message", "Inventory crafting screen is not open.");
            }
            return step;
        }

        if (currentCraftingMenu(player) instanceof CraftingMenu) {
            step.addProperty("ok", true);
            step.addProperty("screen", screenName(client.screen));
            step.add("menu", currentContainerJson(player));
            return step;
        }

        Optional<PathToBlock> tablePath = findPathToNearbyCraftingTable(level, player);
        if (tablePath.isEmpty()) {
            step.addProperty("ok", false);
            step.addProperty("message", "No reachable nearby crafting table found.");
            return step;
        }

        PathToBlock path = tablePath.get();
        BlockPos tablePos = path.targetBlock();
        BlockPos standPos = path.standPos();
        if (!player.blockPosition().equals(standPos)) {
            step.addProperty("ok", false);
            step.addProperty("message", "Player is not yet standing near the crafting table.");
            step.add("tablePos", blockPosJson(tablePos));
            step.add("standPos", blockPosJson(standPos));
            step.addProperty("nextActionHint", "Use /action/gotoBlock with minecraft:crafting_table or walk to the provided standPos first.");
            return step;
        }

        Direction face = resolveBlockFace(player, tablePos);
        Vec3 location = tablePos.getCenter().add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        lookAt(player, location);
        InteractionResult interaction = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(location, face, tablePos, false));
        step.addProperty("interaction", interaction.toString().toLowerCase(Locale.ROOT));
        step.addProperty("ok", currentCraftingMenu(player) instanceof CraftingMenu);
        step.addProperty("screen", screenName(client.screen));
        step.add("tablePos", blockPosJson(tablePos));
        step.add("menu", currentContainerJson(player));
        if (!step.get("ok").getAsBoolean()) {
            step.addProperty("message", "Crafting table did not open.");
        }
        return step;
    }

    private JsonObject placeRecipeIntoCurrentMenu(Minecraft client, LocalPlayer player, RecipeSelection selection, boolean craftAll) {
        JsonObject step = new JsonObject();
        step.addProperty("step", "place_recipe");
        AbstractCraftingMenu menu = currentCraftingMenu(player);
        if (menu == null) {
            step.addProperty("ok", false);
            step.addProperty("message", "No crafting menu is currently open.");
            return step;
        }

        client.gameMode.handlePlaceRecipe(menu.containerId, selection.entry().id(), craftAll);
        menu.broadcastChanges();
        step.addProperty("ok", true);
        step.addProperty("recipeDisplayId", selection.entry().id().index());
        step.add("menu", currentContainerJson(player));
        return step;
    }

    private JsonObject takeCraftResult(Minecraft client, LocalPlayer player) {
        JsonObject step = new JsonObject();
        step.addProperty("step", "take_result");
        AbstractCraftingMenu menu = currentCraftingMenu(player);
        if (menu == null) {
            step.addProperty("ok", false);
            step.addProperty("message", "No crafting menu is currently open.");
            return step;
        }

        Slot resultSlot = menu.getResultSlot();
        if (!resultSlot.hasItem()) {
            step.addProperty("ok", false);
            step.addProperty("message", "Craft result slot is empty.");
            step.add("menu", currentContainerJson(player));
            return step;
        }

        ItemStack before = resultSlot.getItem().copy();
        client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, player);
        menu.broadcastChanges();

        step.addProperty("ok", true);
        step.add("taken", itemJson(before));
        step.add("menu", currentContainerJson(player));
        return step;
    }

    private AbstractCraftingMenu currentCraftingMenu(LocalPlayer player) {
        return player.containerMenu instanceof AbstractCraftingMenu menu ? menu : null;
    }

    private Optional<PathToBlock> findPathToNearbyCraftingTable(ClientLevel level, LocalPlayer player) {
        Optional<BlockPos> found = findNearestBlock(level, player.blockPosition(), "minecraft:crafting_table", 16);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return findPathToStandNear(level, player.blockPosition(), found.get(), 16, 2);
    }

    private JsonArray itemListJson(List<ItemStack> items) {
        JsonArray array = new JsonArray();
        for (ItemStack item : items) {
            array.add(itemJson(item));
        }
        return array;
    }

    private List<Ingredient> recipeIngredients(RecipeHolder<?> holder) {
        List<Ingredient> ingredients = new ArrayList<>();
        RecipeManager.ServerDisplayInfo ignored = null;
        if (holder.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            for (Optional<Ingredient> ingredient : shaped.getIngredients()) {
                ingredient.ifPresent(ingredients::add);
            }
        } else if (holder.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe shapeless) {
            if (!shapeless.display().isEmpty()) {
                // fall through to display-driven requirements below
            }
        }
        return ingredients;
    }

    private int maxCraftableCount(Inventory inventory, RecipeHolder<?> holder) {
        List<Ingredient> ingredients = recipeIngredients(holder);
        if (ingredients.isEmpty()) {
            return 0;
        }
        Map<String, Integer> available = inventoryItemCounts(inventory);
        int min = Integer.MAX_VALUE;
        for (Ingredient ingredient : ingredients) {
            int best = ingredient.items()
                .map(holderRef -> BuiltInRegistries.ITEM.getKey(holderRef.value()).toString())
                .mapToInt(id -> available.getOrDefault(id, 0))
                .max()
                .orElse(0);
            min = Math.min(min, best);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private Map<String, Integer> inventoryItemCounts(Inventory inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private boolean consumeIngredientFromInventory(Inventory inventory, Ingredient ingredient, int amount) {
        if (ingredient == null || ingredient.isEmpty()) {
            return true;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack) && stack.getCount() >= amount) {
                inventory.removeItem(i, amount);
                return true;
            }
        }
        return false;
    }

    private StackedItemContents inventoryContents(Inventory inventory) {
        StackedItemContents contents = new StackedItemContents();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            contents.accountStack(inventory.getItem(i));
        }
        return contents;
    }

    private String menuTypeName(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) return "inventory";
        if (menu instanceof CraftingMenu) return "crafting";
        if (menu instanceof FurnaceMenu) return "furnace";
        return menu.getType() == null ? menu.getClass().getSimpleName() : menu.getType().toString();
    }

    private void recordObservation(Minecraft client, LocalPlayer player, ClientLevel level) {
        ObservationSnapshot current = ObservationSnapshot.capture(client, player, level);
        ObservationSnapshot previous = lastObservation;
        lastObservation = current;
        if (previous == null) {
            return;
        }

        if (!previous.screen().equals(current.screen())) {
            addEvent("screen_changed", event -> {
                event.addProperty("from", previous.screen());
                event.addProperty("to", current.screen());
            });
        }
        if (previous.selectedSlot() != current.selectedSlot()) {
            addEvent("selected_slot_changed", event -> {
                event.addProperty("from", previous.selectedSlot());
                event.addProperty("to", current.selectedSlot());
            });
        }
        if (current.health() < previous.health()) {
            addEvent("player_damaged", event -> {
                event.addProperty("from", previous.health());
                event.addProperty("to", current.health());
            });
        }
        if (!previous.lookingBlockId().equals(current.lookingBlockId())) {
            addEvent("looking_block_changed", event -> {
                event.addProperty("from", previous.lookingBlockId());
                event.addProperty("to", current.lookingBlockId());
            });
        }
    }

    private void addEvent(String type, java.util.function.Consumer<JsonObject> data) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("atMs", System.currentTimeMillis());
        event.addProperty("time", Instant.now().toString());
        data.accept(event);
        synchronized (eventLock) {
            recentEvents.addLast(event);
            while (recentEvents.size() > 256) {
                recentEvents.removeFirst();
            }
        }
    }

    private static final class MenuState {
        private final boolean ok;
        private final String action;
        private final String message;
        private final long atMs;

        private MenuState(boolean ok, String action, String message) {
            this.ok = ok;
            this.action = action;
            this.message = message;
            this.atMs = System.currentTimeMillis();
        }

        static MenuState idle(String message) {
            return new MenuState(true, "idle", message);
        }

        static MenuState success(String action, String message) {
            return new MenuState(true, action, message);
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("ok", ok);
            root.addProperty("action", action);
            root.addProperty("message", message);
            root.addProperty("atMs", atMs);
            return root;
        }
    }

    private static final class TaskState {
        private final String taskId;
        private final String name;
        private final long startedAtMs;
        private final List<JsonObject> steps;
        private final JsonArray results;
        private final boolean running;
        private final boolean completed;
        private final boolean cancelled;
        private final String message;
        private int currentStepIndex;
        private boolean stepStarted;
        private boolean waitingForAction;
        private boolean waitingForCondition;
        private String currentAction;
        private String waitConditionKind;
        private String waitConditionValue;
        private long waitUntilMs;
        private boolean breakRequested;
        private JsonObject currentActionSnapshot;
        private JsonObject currentConditionSnapshot;
        private JsonObject finalSnapshot;

        private TaskState(String taskId, String name, List<JsonObject> steps, boolean running, boolean completed, boolean cancelled, String message) {
            this.taskId = taskId;
            this.name = name;
            this.startedAtMs = System.currentTimeMillis();
            this.steps = steps == null ? List.of() : new ArrayList<>(steps);
            this.results = new JsonArray();
            this.running = running;
            this.completed = completed;
            this.cancelled = cancelled;
            this.message = message;
            this.currentStepIndex = 0;
            this.stepStarted = false;
            this.waitingForAction = false;
            this.waitingForCondition = false;
            this.currentAction = "";
            this.waitConditionKind = "";
            this.waitConditionValue = "";
            this.waitUntilMs = 0L;
            this.breakRequested = false;
            this.currentActionSnapshot = null;
            this.currentConditionSnapshot = null;
        }

        static TaskState idle() {
            return new TaskState("", "idle", List.of(), false, false, false, "idle");
        }

        static TaskState running(String taskId, List<JsonObject> steps, String name) {
            return new TaskState(taskId, name, steps, true, false, false, "running");
        }

        TaskState cancelled(String message) {
            TaskState next = copyWith(false, false, true, message);
            next.currentStepIndex = this.currentStepIndex;
            next.stepStarted = this.stepStarted;
            next.waitingForAction = this.waitingForAction;
            next.waitingForCondition = this.waitingForCondition;
            next.currentAction = this.currentAction;
            next.waitConditionKind = this.waitConditionKind;
            next.waitConditionValue = this.waitConditionValue;
            next.waitUntilMs = this.waitUntilMs;
            next.breakRequested = this.breakRequested;
            next.currentActionSnapshot = this.currentActionSnapshot == null ? null : this.currentActionSnapshot.deepCopy();
            next.currentConditionSnapshot = this.currentConditionSnapshot == null ? null : this.currentConditionSnapshot.deepCopy();
            next.results.addAll(this.results);
            next.finalSnapshot = this.finalSnapshot;
            return next;
        }

        TaskState failed(String message, JsonObject failure) {
            TaskState next = copyWith(false, false, false, message);
            next.currentStepIndex = this.currentStepIndex;
            next.stepStarted = this.stepStarted;
            next.waitingForAction = this.waitingForAction;
            next.waitingForCondition = this.waitingForCondition;
            next.currentAction = this.currentAction;
            next.waitConditionKind = this.waitConditionKind;
            next.waitConditionValue = this.waitConditionValue;
            next.waitUntilMs = this.waitUntilMs;
            next.breakRequested = this.breakRequested;
            next.currentActionSnapshot = this.currentActionSnapshot == null ? null : this.currentActionSnapshot.deepCopy();
            next.currentConditionSnapshot = this.currentConditionSnapshot == null ? null : this.currentConditionSnapshot.deepCopy();
            next.results.addAll(this.results);
            if (failure != null) {
                next.results.add(failure.deepCopy());
            }
            next.finalSnapshot = this.finalSnapshot;
            return next;
        }

        TaskState completed(JsonObject snapshot) {
            TaskState next = copyWith(false, true, false, "completed");
            next.currentStepIndex = this.currentStepIndex;
            next.stepStarted = false;
            next.waitingForAction = false;
            next.waitingForCondition = false;
            next.currentAction = "";
            next.waitConditionKind = "";
            next.waitConditionValue = "";
            next.waitUntilMs = 0L;
            next.breakRequested = false;
            next.currentActionSnapshot = this.currentActionSnapshot == null ? null : this.currentActionSnapshot.deepCopy();
            next.currentConditionSnapshot = this.currentConditionSnapshot == null ? null : this.currentConditionSnapshot.deepCopy();
            next.results.addAll(this.results);
            next.finalSnapshot = snapshot == null ? null : snapshot.deepCopy();
            return next;
        }

        private TaskState copyWith(boolean running, boolean completed, boolean cancelled, String message) {
            return new TaskState(taskId, name, steps, running, completed, cancelled, message);
        }

        void recordStep(JsonObject result) {
            this.results.add(result.deepCopy());
        }

        void advance() {
            this.currentStepIndex++;
            this.stepStarted = false;
            this.waitingForAction = false;
            this.waitingForCondition = false;
            this.currentAction = "";
            this.waitConditionKind = "";
            this.waitConditionValue = "";
            this.waitUntilMs = 0L;
            this.breakRequested = false;
            this.currentActionSnapshot = null;
            this.currentConditionSnapshot = null;
        }

        boolean running() {
            return running;
        }

        int currentStepIndex() {
            return currentStepIndex;
        }

        List<JsonObject> steps() {
            return steps;
        }

        void insertStepsAfterCurrent(List<JsonObject> newSteps) {
            if (newSteps == null || newSteps.isEmpty()) {
                return;
            }
            int insertAt = Math.min(currentStepIndex + 1, steps.size());
            steps.addAll(insertAt, newSteps.stream().map(JsonObject::deepCopy).toList());
        }

        void requestBreak() {
            this.breakRequested = true;
        }

        boolean breakRequested() {
            return breakRequested;
        }

        boolean stepStarted() {
            return stepStarted;
        }

        boolean waitingForAction() {
            return waitingForAction;
        }

        boolean waitingForCondition() {
            return waitingForCondition;
        }

        void markStepStarted(String action, boolean waitingForAction) {
            this.stepStarted = true;
            this.waitingForAction = waitingForAction;
            this.currentAction = action == null ? "" : action;
        }

        void finishWaiting() {
            this.waitingForAction = false;
        }

        void startTimedWait(int durationMs, String action) {
            this.stepStarted = true;
            this.waitingForAction = false;
            this.waitingForCondition = true;
            this.currentAction = action == null ? "" : action;
            this.waitConditionKind = "time";
            this.waitConditionValue = "";
            this.waitUntilMs = System.currentTimeMillis() + durationMs;
        }

        void startScreenWait(String expectedScreen, int timeoutMs) {
            this.stepStarted = true;
            this.waitingForAction = false;
            this.waitingForCondition = true;
            this.currentAction = "waitForScreen";
            this.waitConditionKind = "screen";
            this.waitConditionValue = expectedScreen == null ? "" : expectedScreen;
            this.waitUntilMs = System.currentTimeMillis() + timeoutMs;
        }

        void startActionIdleWait(int timeoutMs) {
            this.stepStarted = true;
            this.waitingForAction = false;
            this.waitingForCondition = true;
            this.currentAction = "waitForActionIdle";
            this.waitConditionKind = "action_idle";
            this.waitConditionValue = "";
            this.waitUntilMs = System.currentTimeMillis() + timeoutMs;
        }

        boolean isConditionSatisfied(Minecraft client) {
            return switch (waitConditionKind) {
                case "time" -> System.currentTimeMillis() >= waitUntilMs;
                case "screen" -> taskScreenName(client.screen).equalsIgnoreCase(waitConditionValue);
                default -> true;
            };
        }

        String waitConditionKind() {
            return waitConditionKind;
        }

        boolean isConditionTimedOut() {
            return waitingForCondition
                && !"time".equals(waitConditionKind)
                && waitUntilMs > 0L
                && System.currentTimeMillis() > waitUntilMs;
        }

        void finishConditionWait() {
            this.waitingForCondition = false;
            this.waitConditionKind = "";
            this.waitConditionValue = "";
            this.waitUntilMs = 0L;
        }

        void markActionSnapshot(JsonObject actionSnapshot) {
            this.currentActionSnapshot = actionSnapshot == null ? null : actionSnapshot.deepCopy();
        }

        void markConditionSnapshot(JsonObject conditionSnapshot) {
            this.currentConditionSnapshot = conditionSnapshot == null ? null : conditionSnapshot.deepCopy();
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("taskId", taskId);
            root.addProperty("name", name);
            root.addProperty("running", running);
            root.addProperty("completed", completed);
            root.addProperty("cancelled", cancelled);
            root.addProperty("message", message);
            root.addProperty("startedAtMs", startedAtMs);
            root.addProperty("currentStepIndex", currentStepIndex);
            root.addProperty("stepCount", steps.size());
            root.addProperty("stepStarted", stepStarted);
            root.addProperty("waitingForAction", waitingForAction);
            root.addProperty("waitingForCondition", waitingForCondition);
            root.addProperty("currentAction", currentAction);
            root.addProperty("waitConditionKind", waitConditionKind);
            root.addProperty("waitConditionValue", waitConditionValue);
            root.addProperty("waitUntilMs", waitUntilMs);
            root.addProperty("breakRequested", breakRequested);
            JsonArray stepDescriptors = new JsonArray();
            for (JsonObject step : steps) {
                stepDescriptors.add(step.deepCopy());
            }
            root.add("steps", stepDescriptors);
            root.add("results", results.deepCopy());
            if (currentActionSnapshot != null) {
                root.add("currentActionState", currentActionSnapshot.deepCopy());
            }
            if (currentConditionSnapshot != null) {
                root.add("currentConditionState", currentConditionSnapshot.deepCopy());
            }
            if (finalSnapshot != null) {
                root.add("snapshot", finalSnapshot.deepCopy());
            }
            return root;
        }
    }

    private enum ActionKind {
        IDLE,
        MANUAL,
        PATH,
        MINE,
        ATTACK
    }

    private record ManualControl(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
        static ManualControl stop() {
            return new ManualControl(false, false, false, false, false, false, false);
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("forward", forward);
            root.addProperty("backward", backward);
            root.addProperty("left", left);
            root.addProperty("right", right);
            root.addProperty("jump", jump);
            root.addProperty("shift", shift);
            root.addProperty("sprint", sprint);
            return root;
        }
    }

    private static final class ControlledInput extends ClientInput {
        private ManualControl control = ManualControl.stop();

        void set(ManualControl control) {
            this.control = control;
            tick();
        }

        void clear() {
            set(ManualControl.stop());
        }

        @Override
        public void tick() {
            this.keyPresses = new Input(
                control.forward(),
                control.backward(),
                control.left(),
                control.right(),
                control.jump(),
                control.shift(),
                control.sprint()
            );
            float forwardImpulse = impulse(control.forward(), control.backward());
            float sidewaysImpulse = impulse(control.left(), control.right());
            this.moveVector = new Vec2(sidewaysImpulse, forwardImpulse).normalized();
        }

        private float impulse(boolean positive, boolean negative) {
            if (positive == negative) {
                return 0.0F;
            }
            return positive ? 1.0F : -1.0F;
        }
    }

    private static final class ActionState {
        private final ActionKind kind;
        private final String message;
        private final long startedAtMs;
        private long untilMs;
        private final ManualControl manual;
        private final List<BlockPos> path;
        private final BlockPos lookTarget;
        private final BlockPos targetBlock;
        private final Direction targetFace;
        private final int targetEntityId;
        private final String targetEntityUuid;
        private int pickupRadius;
        private boolean pickupMode;
        private int attacksRemaining;
        private int pathIndex;

        private ActionState(
            ActionKind kind,
            String message,
            long untilMs,
            ManualControl manual,
            List<BlockPos> path,
            BlockPos lookTarget,
            BlockPos targetBlock,
            Direction targetFace,
            int targetEntityId,
            String targetEntityUuid,
            int attacksRemaining
        ) {
            this.kind = kind;
            this.message = message;
            this.startedAtMs = System.currentTimeMillis();
            this.untilMs = untilMs;
            this.manual = manual;
            this.path = path == null ? List.of() : List.copyOf(path);
            this.lookTarget = lookTarget;
            this.targetBlock = targetBlock;
            this.targetFace = targetFace;
            this.targetEntityId = targetEntityId;
            this.targetEntityUuid = targetEntityUuid;
            this.pickupRadius = 0;
            this.pickupMode = false;
            this.attacksRemaining = attacksRemaining;
            this.pathIndex = 0;
        }

        static ActionState idle(String message) {
            return new ActionState(ActionKind.IDLE, message, 0L, ManualControl.stop(), List.of(), null, null, null, -1, null, 0);
        }

        static ActionState manual(ManualControl manual, long untilMs, String message) {
            return new ActionState(ActionKind.MANUAL, message, untilMs, manual, List.of(), null, null, null, -1, null, 0);
        }

        static ActionState path(List<BlockPos> path, BlockPos lookTarget, String message) {
            return new ActionState(ActionKind.PATH, message, 0L, ManualControl.stop(), path, lookTarget, null, null, -1, null, 0);
        }

        static ActionState mine(BlockPos targetBlock, Direction targetFace, String message) {
            return new ActionState(ActionKind.MINE, message, 0L, ManualControl.stop(), List.of(), null, targetBlock, targetFace, -1, null, 0);
        }

        static ActionState attack(int targetEntityId, String targetEntityUuid, int count, String message) {
            return new ActionState(ActionKind.ATTACK, message, 0L, ManualControl.stop(), List.of(), null, null, null, targetEntityId, targetEntityUuid, count);
        }

        ActionKind kind() {
            return kind;
        }

        long untilMs() {
            return untilMs;
        }

        ManualControl manual() {
            return manual;
        }

        List<BlockPos> path() {
            return path;
        }

        int pathIndex() {
            return pathIndex;
        }

        void advancePathIndex() {
            pathIndex++;
        }

        BlockPos lookTarget() {
            return lookTarget;
        }

        BlockPos targetBlock() {
            return targetBlock;
        }

        Direction targetFace() {
            return targetFace;
        }

        int targetEntityId() {
            return targetEntityId;
        }

        String targetEntityUuid() {
            return targetEntityUuid;
        }

        int attacksRemaining() {
            return attacksRemaining;
        }

        void markAttackSwing(long nextAttackAtMs) {
            this.untilMs = nextAttackAtMs;
        }

        void decrementAttacksRemaining() {
            if (attacksRemaining > 0) {
                attacksRemaining--;
            }
        }

        void setPickupMode(int radius, long untilMs) {
            this.pickupMode = true;
            this.pickupRadius = radius;
            this.untilMs = untilMs;
        }

        boolean pickupMode() {
            return pickupMode;
        }

        int pickupRadius() {
            return pickupRadius;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("kind", kind.name().toLowerCase());
            root.addProperty("message", message);
            root.addProperty("startedAtMs", startedAtMs);
            root.addProperty("running", kind != ActionKind.IDLE);

            if (kind == ActionKind.MANUAL) {
                root.addProperty("untilMs", untilMs);
                root.add("manual", manual.toJson());
            }

            if (kind == ActionKind.PATH) {
                root.addProperty("pathIndex", pathIndex);
                root.addProperty("pathLength", path.size());
                root.addProperty("remaining", Math.max(0, path.size() - pathIndex));
                if (lookTarget != null) {
                    JsonObject target = new JsonObject();
                    target.addProperty("x", lookTarget.getX());
                    target.addProperty("y", lookTarget.getY());
                    target.addProperty("z", lookTarget.getZ());
                    root.add("lookTarget", target);
                }

                JsonArray preview = new JsonArray();
                for (int i = pathIndex; i < Math.min(path.size(), pathIndex + 8); i++) {
                    BlockPos pos = path.get(i);
                    JsonObject step = new JsonObject();
                    step.addProperty("x", pos.getX());
                    step.addProperty("y", pos.getY());
                    step.addProperty("z", pos.getZ());
                    preview.add(step);
                }
                root.add("pathPreview", preview);
                if (pickupMode) {
                    root.addProperty("pickupMode", true);
                    root.addProperty("pickupRadius", pickupRadius);
                    root.addProperty("untilMs", untilMs);
                }
            }

            if (kind == ActionKind.MINE && targetBlock != null) {
                root.addProperty("targetType", "block");
                JsonObject target = new JsonObject();
                target.addProperty("x", targetBlock.getX());
                target.addProperty("y", targetBlock.getY());
                target.addProperty("z", targetBlock.getZ());
                root.add("targetBlock", target);
                if (targetFace != null) {
                    root.addProperty("targetFace", targetFace.getName());
                }
            }

            if (kind == ActionKind.ATTACK) {
                root.addProperty("targetType", "entity");
                root.addProperty("targetEntityId", targetEntityId);
                if (targetEntityUuid != null) {
                    root.addProperty("targetEntityUuid", targetEntityUuid);
                }
                root.addProperty("attacksRemaining", attacksRemaining);
                root.addProperty("nextAttackAtMs", untilMs);
            }

            return root;
        }
    }

    private record LookAngles(float yaw, float pitch) {
    }

    private record PathToBlock(BlockPos targetBlock, BlockPos standPos, List<BlockPos> path) {
    }

    private record RecipeSelection(RecipeHolder<?> holder, RecipeDisplayEntry entry, String menuType) {
    }

    private record ObservationSnapshot(String screen, int selectedSlot, float health, String lookingBlockId) {
        static ObservationSnapshot capture(Minecraft client, LocalPlayer player, ClientLevel level) {
            String lookingBlock = "none";
            HitResult hit = client.hitResult;
            if (hit instanceof BlockHitResult blockHit) {
                lookingBlock = BuiltInRegistries.BLOCK.getKey(level.getBlockState(blockHit.getBlockPos()).getBlock()).toString();
            }
            return new ObservationSnapshot(
                screenNameStatic(client.screen),
                player.getInventory().getSelectedSlot(),
                player.getHealth(),
                lookingBlock
            );
        }

        private static String screenNameStatic(Screen screen) {
            if (screen == null) {
                return "none";
            }
            return screen.getClass().getSimpleName();
        }
    }
}
