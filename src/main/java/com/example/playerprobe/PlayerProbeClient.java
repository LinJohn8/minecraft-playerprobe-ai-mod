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
import java.util.function.BiFunction;
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
            server.createContext("/container/semantic", this::handleContainerSemantic);
            server.createContext("/container/clickRole", this::handleContainerClickRole);
            server.createContext("/container/button", this::handleContainerButton);
            server.createContext("/craft/check", this::handleCraftCheck);
            server.createContext("/survival/status", this::handleSurvivalStatus);
            server.createContext("/survival/missing", this::handleSurvivalMissing);
            server.createContext("/survival/craftTool", this::handleSurvivalCraftTool);
            server.createContext("/survival/craftMaterial", this::handleSurvivalCraftMaterial);
            server.createContext("/survival/chopTree", this::handleSurvivalChopTree);
            server.createContext("/survival/dig", this::handleSurvivalDig);
            server.createContext("/survival/build", this::handleSurvivalBuild);
            server.createContext("/survival/enchant", this::handleSurvivalEnchant);
            server.createContext("/survival/enchantApply", this::handleSurvivalEnchantApply);
            server.createContext("/survival/advancedPath", this::handleSurvivalAdvancedPath);
            server.createContext("/survival/recover", this::handleSurvivalRecover);
            server.createContext("/survival/smelt", this::handleSurvivalSmelt);
            server.createContext("/survival/experience", this::handleSurvivalExperience);
            server.createContext("/survival/combat", this::handleSurvivalCombat);
            server.createContext("/survival/decision", this::handleSurvivalDecision);
            server.createContext("/survival/farm", this::handleSurvivalFarm);
            server.createContext("/survival/mine", this::handleSurvivalMine);
            server.createContext("/survival/light", this::handleSurvivalLight);
            server.createContext("/survival/sleep", this::handleSurvivalSleep);
            server.createContext("/survival/placeWorkstation", this::handleSurvivalPlaceWorkstation);
            server.createContext("/survival/dimension", this::handleSurvivalDimension);
            server.createContext("/survival/redstone", this::handleSurvivalRedstone);
            server.createContext("/survival/trade", this::handleSurvivalTrade);
            server.createContext("/survival/tradeSelect", this::handleSurvivalTradeSelect);
            server.createContext("/survival/fish", this::handleSurvivalFish);
            server.createContext("/survival/brew", this::handleSurvivalBrew);
            server.createContext("/survival/anvil", this::handleSurvivalAnvil);
            server.createContext("/survival/anvilApply", this::handleSurvivalAnvilApply);
            server.createContext("/survival/explore", this::handleSurvivalExplore);
            server.createContext("/craft/tree", this::handleCraftTree);
            server.createContext("/storage/organize", this::handleStorageOrganize);
            server.createContext("/build/template", this::handleBuildTemplate);
            server.createContext("/build/refillHotbar", this::handleBuildRefillHotbar);
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

    private void handleContainerSemantic(HttpExchange exchange) throws IOException {
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
            root.add("semantic", currentContainerSemanticJson(player));
            root.add("container", currentContainerJson(player));
            return root;
        });
        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleContainerClickRole(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> runContainerClickRoleTask(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleContainerButton(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> runContainerButtonTask(client, request));
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

    private void handleSurvivalStatus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            sendText(exchange, 405, "Only GET is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject result = runOnGameThread(this::survivalStatusJson);
        sendJson(exchange, result.has("error") ? 503 : 200, result);
    }

    private void handleSurvivalMissing(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> survivalMissingFromRequest(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalCraftTool(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalCraftToolPlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalCraftMaterial(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalCraftMaterialPlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalChopTree(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalChopTreePlan(client, request));
        sendJson(exchange, result.has("error") ? 404 : 200, result);
    }

    private void handleSurvivalDig(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalDigPlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalBuild(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalBuildPlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalEnchant(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> survivalEnchantPlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleSurvivalEnchantApply(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalEnchantApplyPlan(client, request));
    }

    private void handleSurvivalAdvancedPath(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalAdvancedPathPlan(client, request));
    }

    private void handleSurvivalRecover(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalRecoverPlan(client, request));
    }

    private void handleSurvivalSmelt(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalSmeltPlan(client, request));
    }

    private void handleSurvivalExperience(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalExperiencePlan(client, request));
    }

    private void handleSurvivalCombat(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalCombatPlan(client, request));
    }

    private void handleSurvivalDecision(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalDecisionPlan(client, request));
    }

    private void handleSurvivalFarm(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalFarmPlan(client, request));
    }

    private void handleSurvivalMine(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalMinePlan(client, request));
    }

    private void handleSurvivalLight(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalLightPlan(client, request));
    }

    private void handleSurvivalSleep(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalSleepPlan(client, request));
    }

    private void handleSurvivalPlaceWorkstation(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalPlaceWorkstationPlan(client, request));
    }

    private void handleSurvivalDimension(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalDimensionPlan(client, request));
    }

    private void handleSurvivalRedstone(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalRedstonePlan(client, request));
    }

    private void handleSurvivalTrade(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalTradePlan(client, request));
    }

    private void handleSurvivalTradeSelect(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalTradeSelectPlan(client, request));
    }

    private void handleSurvivalFish(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalFishPlan(client, request));
    }

    private void handleSurvivalBrew(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalBrewPlan(client, request));
    }

    private void handleSurvivalAnvil(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalAnvilPlan(client, request));
    }

    private void handleSurvivalAnvilApply(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalAnvilApplyPlan(client, request));
    }

    private void handleSurvivalExplore(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> survivalExplorePlan(client, request));
    }

    private void handleCraftTree(HttpExchange exchange) throws IOException {
        if (!isGet(exchange) && !isPost(exchange)) {
            sendText(exchange, 405, "Only GET or POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = isPost(exchange) ? readJsonObject(exchange) : queryJson(exchange);
        JsonObject result = runOnGameThread(client -> craftTreePlan(client, request));
        sendJson(exchange, result.has("error") ? 409 : 200, result);
    }

    private void handleStorageOrganize(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> storageOrganizePlan(client, request));
    }

    private void handleBuildTemplate(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> buildTemplatePlan(client, request));
    }

    private void handleBuildRefillHotbar(HttpExchange exchange) throws IOException {
        handlePostPlanner(exchange, client -> request -> buildRefillHotbarPlan(client, request));
    }

    private void handlePostPlanner(HttpExchange exchange, Function<Minecraft, Function<JsonObject, JsonObject>> plannerFactory) throws IOException {
        if (!isPost(exchange)) {
            sendText(exchange, 405, "Only POST is supported.\n", "text/plain; charset=utf-8");
            return;
        }

        JsonObject request = readJsonObject(exchange);
        JsonObject result = runOnGameThread(client -> plannerFactory.apply(client).apply(request));
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
                if (handleTaskStuckAutoRecovery(client, taskState.currentStepIndex(), stepRequest, "waiting_for_action")) {
                    return;
                }
                return;
            }
            taskState.finishWaiting();
        }

        if (taskState.waitingForCondition()) {
            taskState.markConditionSnapshot(taskConditionSnapshot(client));
            if ("action_idle".equals(taskState.waitConditionKind())
                && actionState.kind() != ActionKind.IDLE
                && handleTaskStuckAutoRecovery(client, taskState.currentStepIndex(), previousTaskStep(taskState), "waiting_for_action_idle")) {
                return;
            }
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

    private JsonObject previousTaskStep(TaskState state) {
        int previousIndex = state.currentStepIndex() - 1;
        if (previousIndex < 0 || previousIndex >= state.steps().size()) {
            return null;
        }
        return state.steps().get(previousIndex);
    }

    private boolean handleTaskStuckAutoRecovery(Minecraft client, int currentStepIndex, JsonObject retryStep, String phase) {
        ActionState state = actionState;
        if (!state.stuck()) {
            return false;
        }
        if (!taskState.autoRecoverStuck()) {
            return false;
        }
        if (!taskState.canAutoRecover()) {
            JsonObject failure = errorJson("TaskStuck", "Action is stuck and automatic recovery attempts are exhausted.");
            failure.addProperty("stepIndex", currentStepIndex);
            failure.addProperty("phase", phase);
            failure.add("action", state.toJson());
            taskState = taskState.failed("Task stopped because the action is stuck.", failure);
            actionState = ActionState.idle("stuck action stopped after recovery attempts were exhausted");
            controlledInput.clear();
            if (client.player != null) {
                restoreNormalInput(client.player);
            }
            return true;
        }

        int attempt = taskState.noteAutoRecovery();
        JsonObject recoveryRequest = new JsonObject();
        recoveryRequest.addProperty("left", attempt % 2 == 1);
        List<JsonObject> inserted = new ArrayList<>(survivalRecoverSteps(client, recoveryRequest));
        JsonObject retry = retryableTaskStep(retryStep);
        if (retry != null) {
            inserted.add(retry);
        }
        taskState.insertStepsAfterCurrent(inserted);

        JsonObject event = new JsonObject();
        event.addProperty("ok", true);
        event.addProperty("autoRecovery", true);
        event.addProperty("attempt", attempt);
        event.addProperty("maxAutoRecoveries", taskState.maxAutoRecoveries());
        event.addProperty("stepIndex", currentStepIndex);
        event.addProperty("phase", phase);
        event.add("stuckAction", state.toJson());
        event.add("insertedSteps", taskStepsJson(inserted));
        taskState.recordStep(event);

        actionState = ActionState.idle("stuck action paused for automatic recovery");
        controlledInput.clear();
        if (client.player != null) {
            restoreNormalInput(client.player);
        }
        return true;
    }

    private JsonObject retryableTaskStep(JsonObject step) {
        if (step == null) {
            return null;
        }
        String action = getString(step, "action", "").trim();
        if (action.isBlank() || "waitForActionIdle".equals(action) || "recoverProcess".equals(action)) {
            return null;
        }
        JsonObject retry = step.deepCopy();
        retry.addProperty("autoRetry", true);
        return retry;
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
        state.observeProgress(horizontalDistance, "path_no_progress", 5000L);

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
        if (System.currentTimeMillis() - state.startedAtMs() > 20000L) {
            state.markStuck("mine_taking_too_long");
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

    private JsonObject survivalStatusJson(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.add("inventory", inventoryKnowledgeJson(player));
        root.add("nearbyUsefulBlocks", nearbyUsefulBlocksJson(level, player));
        root.add("crafting", survivalCraftingAccessJson(level, player));
        root.add("enchanting", survivalEnchantAccessJson(level, player));
        return root;
    }

    private JsonObject survivalMissingFromRequest(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonObject required = survivalRequirementsFromRequest(request);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("goal", getString(request, "goal", getString(request, "itemId", "custom")));
        root.add("required", required);
        root.add("missing", missingRequirementsJson(player.getInventory(), required));
        root.add("inventory", inventoryKnowledgeJson(player));
        return root;
    }

    private JsonObject survivalCraftToolPlan(Minecraft client, JsonObject request) {
        return survivalPlanResponse(client, request, "craftTool", survivalCraftToolSteps(client, request), survivalMissingFromRequest(client, toolRequirementRequest(request)));
    }

    private JsonObject survivalCraftMaterialPlan(Minecraft client, JsonObject request) {
        return survivalPlanResponse(client, request, "craftMaterial", survivalCraftMaterialSteps(client, request), survivalMissingFromRequest(client, materialRequirementRequest(request)));
    }

    private JsonObject survivalChopTreePlan(Minecraft client, JsonObject request) {
        return survivalPlanResponse(client, request, "chopTree", survivalChopTreeSteps(client, request), null);
    }

    private JsonObject survivalDigPlan(Minecraft client, JsonObject request) {
        return survivalPlanResponse(client, request, "dig", survivalDigSteps(client, request), null);
    }

    private JsonObject survivalBuildPlan(Minecraft client, JsonObject request) {
        SurvivalBuildPlan plan = survivalBuildSteps(client, request);
        JsonObject detail = new JsonObject();
        detail.addProperty("blockCount", plan.blockCount());
        detail.add("origin", blockPosJson(plan.origin()));
        detail.add("placements", plan.placements());
        detail.add("missing", plan.missing());
        return survivalPlanResponse(client, request, "build", plan.steps(), detail);
    }

    private JsonObject survivalEnchantPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        LocalPlayer player = client.player;
        if (player != null && client.level != null) {
            detail.addProperty("experienceLevel", player.experienceLevel);
            detail.addProperty("requiredLevel", clamp(getInt(request, "requiredLevel", 1), 1, 30));
            detail.addProperty("hasEnoughExperience", player.experienceLevel >= clamp(getInt(request, "requiredLevel", 1), 1, 30));
            detail.add("enchanting", survivalEnchantAccessJson(client.level, player));
            detail.add("missing", survivalEnchantMissingJson(player, client.level, request));
        }
        return survivalPlanResponse(client, request, "enchantPrepare", survivalEnchantSteps(client, request), detail);
    }

    private JsonObject survivalEnchantApplyPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("itemId", normalizeItemId(getString(request, "itemId", "")));
        detail.addProperty("lapisItemId", normalizeItemId(getString(request, "lapisItemId", "minecraft:lapis_lazuli")));
        detail.addProperty("option", clamp(getInt(request, "option", 0), 0, 2));
        detail.addProperty("notes", "Prepares/open enchanting table, quick-moves item/lapis, clicks enchant button, then quick-moves the item back.");
        return survivalPlanResponse(client, request, "enchantApply", survivalEnchantApplySteps(client, request), detail);
    }

    private JsonObject survivalAdvancedPathPlan(Minecraft client, JsonObject request) {
        List<JsonObject> steps = survivalAdvancedPathSteps(client, request);
        JsonObject detail = new JsonObject();
        detail.addProperty("mode", getString(request, "mode", "walk_or_recover"));
        detail.addProperty("allowBreak", getBoolean(request, "allowBreak", true));
        detail.addProperty("allowBridge", getBoolean(request, "allowBridge", true));
        detail.addProperty("notes", "Planner tries direct walking first, then emits bounded recovery/mining/bridging-style steps for LLM-controlled retries.");
        return survivalPlanResponse(client, request, "advancedPath", steps, detail);
    }

    private JsonObject survivalRecoverPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("strategy", getString(request, "strategy", "back_jump_replan"));
        detail.addProperty("purpose", "Clear stuck movement/action state, back up, jump, and let the next planner re-evaluate.");
        return survivalPlanResponse(client, request, "recover", survivalRecoverSteps(client, request), detail);
    }

    private JsonObject survivalSmeltPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("inputItemId", normalizeItemId(getString(request, "inputItemId", getString(request, "itemId", "minecraft:raw_iron"))));
        detail.addProperty("fuelItemId", normalizeItemId(getString(request, "fuelItemId", "minecraft:coal")));
        detail.addProperty("expectedOutput", smeltOutputGuess(getString(request, "inputItemId", getString(request, "itemId", "minecraft:raw_iron"))));
        detail.addProperty("waitMs", clamp(getInt(request, "waitMs", 11000), 1000, 120000));
        detail.addProperty("notes", "Uses furnace UI quick-move: open furnace, move input, move fuel, wait, quick-move result slot.");
        return survivalPlanResponse(client, request, "smelt", survivalSmeltSteps(client, request), detail);
    }

    private JsonObject survivalExperiencePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        String method = getString(request, "method", "auto");
        detail.addProperty("method", method);
        detail.addProperty("notes", "Experience can be gained through smelting, mining XP ores, or combat. The planner picks a bounded available chain.");
        return survivalPlanResponse(client, request, "experience", survivalExperienceSteps(client, request), detail);
    }

    private JsonObject survivalCombatPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("style", getString(request, "style", "safe_melee"));
        detail.addProperty("lowFoodRetreat", getBoolean(request, "lowFoodRetreat", true));
        detail.add("targets", combatTargetsJson(client, request));
        return survivalPlanResponse(client, request, "combat", survivalCombatSteps(client, request), detail);
    }

    private JsonObject survivalDecisionPlan(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonArray priorities = new JsonArray();
        if (player.getHealth() < 8.0F) {
            priorities.add(decisionJson("survive", 100, "Health is low; eat, retreat, or avoid combat."));
        }
        if (player.getFoodData().getFoodLevel() < 8) {
            priorities.add(decisionJson("food", 90, "Hunger is low; find/eat food."));
        }
        if (!hasToolKeyword(player.getInventory(), "pickaxe")) {
            priorities.add(decisionJson("craft_pickaxe", 80, "No pickaxe detected; craft one before mining."));
        }
        if (inventoryCountAny(player.getInventory(), logBlockIds()) < 2 && inventoryCountAny(player.getInventory(), plankItemIds()) < 8) {
            priorities.add(decisionJson("chop_tree", 70, "Wood supply is low."));
        }
        if (findNearestBlock(level, player.blockPosition(), "minecraft:crafting_table", 12).isEmpty()
            && inventoryCount(player.getInventory(), "minecraft:crafting_table") <= 0) {
            priorities.add(decisionJson("craft_table", 60, "No crafting table access."));
        }
        priorities.add(decisionJson("explore_or_build", 20, "Basic survival checks passed; continue goal-directed exploration/building."));

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "decision_only");
        root.add("priorities", priorities);
        root.add("status", survivalStatusJson(client));
        return root;
    }

    private JsonObject survivalFarmPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("mode", getString(request, "mode", "harvest"));
        detail.addProperty("crop", getString(request, "crop", "minecraft:wheat"));
        detail.addProperty("notes", "Supports bounded harvest/plant/feed style chains using existing mine/place/interact primitives.");
        return survivalPlanResponse(client, request, "farm", survivalFarmSteps(client, request), detail);
    }

    private JsonObject survivalMinePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("mode", getString(request, "mode", "stair"));
        detail.addProperty("targetOre", getString(request, "targetOre", "minecraft:coal_ore"));
        detail.addProperty("torchEvery", clamp(getInt(request, "torchEvery", 6), 0, 32));
        return survivalPlanResponse(client, request, "mine", survivalMineSteps(client, request), detail);
    }

    private JsonObject survivalLightPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("torchItemId", normalizeItemId(getString(request, "torchItemId", "minecraft:torch")));
        detail.addProperty("radius", clamp(getInt(request, "radius", 8), 1, MAX_ACTION_RADIUS));
        return survivalPlanResponse(client, request, "light", survivalLightSteps(client, request), detail);
    }

    private JsonObject survivalSleepPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("bedItemId", normalizeItemId(getString(request, "bedItemId", "minecraft:white_bed")));
        detail.addProperty("notes", "Finds nearby bed blocks or places a carried bed, then interacts.");
        return survivalPlanResponse(client, request, "sleep", survivalSleepSteps(client, request), detail);
    }

    private JsonObject survivalPlaceWorkstationPlan(Minecraft client, JsonObject request) {
        String blockId = normalizeItemId(getString(request, "blockId", getString(request, "workstation", "minecraft:crafting_table")));
        JsonObject detail = new JsonObject();
        detail.addProperty("blockId", blockId);
        detail.addProperty("notes", "Places common workstations and optionally opens them after placement.");
        return survivalPlanResponse(client, request, "placeWorkstation", survivalPlaceWorkstationSteps(client, request), detail);
    }

    private JsonObject survivalDimensionPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("dimensionGoal", getString(request, "dimension", "nether"));
        detail.addProperty("notes", "Nether support builds/uses a simple portal frame when obsidian and flint_and_steel are available; End support is exposed as requirements/planning only.");
        return survivalPlanResponse(client, request, "dimension", survivalDimensionSteps(client, request), detail);
    }

    private JsonObject survivalRedstonePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("template", getString(request, "template", "line"));
        detail.addProperty("notes", "Places simple redstone lines/levers/repeaters using block placement primitives.");
        return survivalPlanResponse(client, request, "redstone", survivalRedstoneSteps(client, request), detail);
    }

    private JsonObject survivalTradePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.add("villagers", villagerTargetsJson(client, request));
        detail.addProperty("notes", "Walks to and opens a villager. Exact trade slot selection remains exposed through container click interfaces.");
        return survivalPlanResponse(client, request, "trade", survivalTradeSteps(client, request), detail);
    }

    private JsonObject survivalTradeSelectPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("tradeIndex", clamp(getInt(request, "tradeIndex", 0), 0, 15));
        detail.addProperty("paymentItemId", normalizeItemId(getString(request, "paymentItemId", "minecraft:emerald")));
        detail.addProperty("notes", "Opens villager, selects trade button, quick-moves payment, and takes result if available.");
        return survivalPlanResponse(client, request, "tradeSelect", survivalTradeSelectSteps(client, request), detail);
    }

    private JsonObject survivalFishPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("waitMs", clamp(getInt(request, "waitMs", 20000), 1000, 120000));
        detail.addProperty("notes", "Selects fishing rod, casts, waits, reels in. Bite detection is still caller/LLM-polled via events/snapshot.");
        return survivalPlanResponse(client, request, "fish", survivalFishSteps(client, request), detail);
    }

    private JsonObject survivalBrewPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("ingredientItemId", normalizeItemId(getString(request, "ingredientItemId", "minecraft:nether_wart")));
        detail.addProperty("fuelItemId", normalizeItemId(getString(request, "fuelItemId", "minecraft:blaze_powder")));
        detail.addProperty("notes", "Opens brewing stand and quick-moves fuel, bottles, and ingredient. Exact slot picking can be refined with container APIs.");
        return survivalPlanResponse(client, request, "brew", survivalBrewSteps(client, request), detail);
    }

    private JsonObject survivalAnvilPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("mode", getString(request, "mode", "open"));
        detail.addProperty("notes", "Places/opens an anvil. Repair/name operation is exposed as UI/container steps for LLM control.");
        return survivalPlanResponse(client, request, "anvil", survivalAnvilSteps(client, request), detail);
    }

    private JsonObject survivalAnvilApplyPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("leftItemId", normalizeItemId(getString(request, "leftItemId", getString(request, "itemId", ""))));
        detail.addProperty("rightItemId", normalizeItemId(getString(request, "rightItemId", getString(request, "materialItemId", ""))));
        detail.addProperty("notes", "Opens/places anvil, quick-moves left/right items, and quick-moves the result. Rename text still needs UI text support.");
        return survivalPlanResponse(client, request, "anvilApply", survivalAnvilApplySteps(client, request), detail);
    }

    private JsonObject survivalExplorePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("pattern", getString(request, "pattern", "square"));
        detail.addProperty("radius", clamp(getInt(request, "radius", 16), 4, MAX_ACTION_RADIUS));
        detail.addProperty("notes", "Generates waypoint exploration with snapshot polling between legs.");
        return survivalPlanResponse(client, request, "explore", survivalExploreSteps(client, request), detail);
    }

    private JsonObject craftTreePlan(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", "minecraft:crafting_table"));
        int depth = clamp(getInt(request, "depth", 4), 0, 8);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("itemId", itemId);
        root.add("tree", recipeTreeJson(client, level, player, itemId, depth, new HashSet<>()));
        root.add("inventory", inventoryKnowledgeJson(player));
        return root;
    }

    private JsonObject storageOrganizePlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("mode", getString(request, "mode", "deposit"));
        detail.addProperty("containerBlockId", normalizeBlockId(getString(request, "blockId", "minecraft:chest")));
        detail.addProperty("notes", "Uses container quick-move by item id. Pull/deposit direction is controlled with inventoryOnly/containerOnly.");
        return survivalPlanResponse(client, request, "storageOrganize", storageOrganizeSteps(client, request), detail);
    }

    private JsonObject buildTemplatePlan(Minecraft client, JsonObject request) {
        SurvivalBuildPlan plan = buildTemplateSteps(client, request);
        JsonObject detail = new JsonObject();
        detail.addProperty("template", getString(request, "template", "house"));
        detail.addProperty("blockCount", plan.blockCount());
        detail.add("origin", blockPosJson(plan.origin()));
        detail.add("placements", plan.placements());
        detail.add("missing", plan.missing());
        return survivalPlanResponse(client, request, "buildTemplate", plan.steps(), detail);
    }

    private JsonObject buildRefillHotbarPlan(Minecraft client, JsonObject request) {
        JsonObject detail = new JsonObject();
        detail.addProperty("itemId", normalizeItemId(getString(request, "itemId", getString(request, "blockId", ""))));
        detail.addProperty("hotbarSlot", clamp(getInt(request, "hotbarSlot", 0), 0, 8));
        return survivalPlanResponse(client, request, "buildRefillHotbar", buildRefillHotbarSteps(request), detail);
    }

    private JsonObject survivalPlanResponse(Minecraft client, JsonObject request, String name, List<JsonObject> steps, JsonObject detail) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("name", name);
        root.addProperty("processMode", "player_like");
        root.addProperty("stepCount", steps.size());
        root.add("steps", taskStepsJson(steps));
        root.add("inventory", inventoryKnowledgeJson(player));
        if (detail != null) {
            root.add("detail", detail);
        }

        if (getBoolean(request, "start", false)) {
            if (steps.isEmpty()) {
                return errorJson("EmptyProcess", "No executable steps were generated.");
            }
            String taskId = "task-" + System.currentTimeMillis();
            taskState = TaskState.running(
                taskId,
                steps,
                name,
                getBoolean(request, "autoRecoverStuck", true),
                clamp(getInt(request, "maxAutoRecoveries", 1), 0, 8)
            );
            root.addProperty("started", true);
            root.add("task", taskState.toJson());
        } else {
            root.addProperty("started", false);
            root.addProperty("startHint", "Pass {\"start\":true} to execute this process through /task/status.");
        }
        return root;
    }

    private JsonObject runGeneratedSurvivalProcessTask(Minecraft client, String name, List<JsonObject> generatedSteps) {
        if (generatedSteps.isEmpty()) {
            return errorJson("EmptyProcess", "No executable steps were generated for " + name + ".");
        }
        taskState.insertStepsAfterCurrent(generatedSteps);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("processMode", "player_like");
        root.addProperty("insertedStepCount", generatedSteps.size());
        root.add("insertedSteps", taskStepsJson(generatedSteps));
        return root;
    }

    private List<JsonObject> survivalCraftToolSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null) {
            return List.of();
        }
        String itemId = resolveToolItemId(request);
        List<JsonObject> steps = new ArrayList<>();
        addCraftingPrerequisiteSteps(steps, player, client.level, itemId);
        JsonObject craft = taskStep("craftTableProcessAutoRepair");
        craft.addProperty("itemId", itemId);
        craft.addProperty("count", clamp(getInt(request, "count", 1), 1, 16));
        steps.add(craft);
        return steps;
    }

    private List<JsonObject> survivalCraftMaterialSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null) {
            return List.of();
        }
        String itemId = normalizeItemId(getString(request, "itemId", getString(request, "material", "minecraft:oak_planks")));
        int count = clamp(getInt(request, "count", 1), 1, 64);
        List<JsonObject> steps = new ArrayList<>();
        if (itemId.endsWith("_planks")) {
            JsonObject craft = taskStep("craftInventoryProcessAutoRepair");
            craft.addProperty("itemId", itemId);
            craft.addProperty("count", count);
            steps.add(craft);
            return steps;
        }
        if ("minecraft:stick".equals(itemId) || "minecraft:crafting_table".equals(itemId)) {
            String plankId = preferredPlanksForInventory(player.getInventory());
            if (inventoryCount(player.getInventory(), plankId) <= 0 && inventoryCountAny(player.getInventory(), logBlockIds()) > 0) {
                JsonObject planks = taskStep("craftInventoryProcessAutoRepair");
                planks.addProperty("itemId", preferredPlanksForInventory(player.getInventory()));
                planks.addProperty("count", 1);
                steps.add(planks);
            }
            JsonObject craft = taskStep("craftInventoryProcessAutoRepair");
            craft.addProperty("itemId", itemId);
            craft.addProperty("count", count);
            steps.add(craft);
            return steps;
        }
        if (requiresCraftingTable(itemId)) {
            addCraftingPrerequisiteSteps(steps, player, client.level, itemId);
            JsonObject craft = taskStep("craftTableProcessAutoRepair");
            craft.addProperty("itemId", itemId);
            craft.addProperty("count", count);
            steps.add(craft);
            return steps;
        }
        JsonObject craft = taskStep("craft");
        craft.addProperty("itemId", itemId);
        craft.addProperty("count", count);
        steps.add(craft);
        return steps;
    }

    private List<JsonObject> survivalChopTreeSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }

        int radius = clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS);
        int maxLogs = clamp(getInt(request, "maxLogs", 8), 1, 32);
        Optional<BlockPos> found = findNearestAnyBlock(level, player.blockPosition(), logBlockIds(), radius);
        if (found.isEmpty()) {
            return List.of();
        }

        String logId = blockIdAt(level, found.get());
        List<BlockPos> logs = collectVerticalLogs(level, found.get(), maxLogs);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject gotoLog = taskStep("gotoBlock");
        gotoLog.addProperty("id", logId);
        gotoLog.addProperty("radius", radius);
        gotoLog.addProperty("standRange", 2);
        steps.add(gotoLog);
        steps.add(waitForActionIdleStep(20000));

        for (BlockPos log : logs) {
            JsonObject equip = taskStep("equipBest");
            equip.addProperty("purpose", "mine");
            equip.add("blockPos", blockPosJson(log));
            steps.add(equip);

            JsonObject mine = taskStep("mineBlock");
            mine.addProperty("x", log.getX());
            mine.addProperty("y", log.getY());
            mine.addProperty("z", log.getZ());
            steps.add(mine);
            steps.add(waitForActionIdleStep(20000));

            JsonObject verify = taskStep("verifyBlock");
            verify.addProperty("x", log.getX());
            verify.addProperty("y", log.getY());
            verify.addProperty("z", log.getZ());
            verify.addProperty("shouldBeAir", true);
            steps.add(verify);
        }

        JsonObject pickup = taskStep("pickupItems");
        pickup.addProperty("radius", 8);
        pickup.addProperty("timeoutMs", 5000);
        steps.add(pickup);
        steps.add(waitForActionIdleStep(7000));
        return steps;
    }

    private List<JsonObject> survivalDigSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }

        BlockPos origin = request.has("x")
            ? blockPosFromJson(request, player.blockPosition())
            : player.blockPosition().relative(player.getDirection()).below();
        int width = clamp(getInt(request, "width", 1), 1, 16);
        int length = clamp(getInt(request, "length", 1), 1, 16);
        int depth = clamp(getInt(request, "depth", 1), 1, 32);
        int limit = clamp(getInt(request, "limit", 256), 1, 512);

        List<JsonObject> steps = new ArrayList<>();
        Optional<PathToBlock> path = findPathToStandNear(level, player.blockPosition(), origin, MAX_ACTION_RADIUS, 2);
        path.ifPresent(pathToBlock -> {
            JsonObject go = taskStep("goto");
            go.addProperty("x", pathToBlock.standPos().getX());
            go.addProperty("y", pathToBlock.standPos().getY());
            go.addProperty("z", pathToBlock.standPos().getZ());
            go.addProperty("radius", MAX_ACTION_RADIUS);
            steps.add(go);
            steps.add(waitForActionIdleStep(20000));
        });

        int added = 0;
        for (int dy = 0; dy < depth && added < limit; dy++) {
            for (int z = 0; z < length && added < limit; z++) {
                for (int x = 0; x < width && added < limit; x++) {
                    BlockPos pos = origin.offset(x, -dy, z);
                    if (level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    JsonObject equip = taskStep("equipBest");
                    equip.addProperty("purpose", "mine");
                    equip.add("blockPos", blockPosJson(pos));
                    steps.add(equip);

                    JsonObject mine = taskStep("mineBlock");
                    mine.addProperty("x", pos.getX());
                    mine.addProperty("y", pos.getY());
                    mine.addProperty("z", pos.getZ());
                    steps.add(mine);
                    steps.add(waitForActionIdleStep(20000));
                    added++;
                }
            }
        }
        JsonObject pickup = taskStep("pickupItems");
        pickup.addProperty("radius", 8);
        pickup.addProperty("timeoutMs", 5000);
        steps.add(pickup);
        steps.add(waitForActionIdleStep(7000));
        return steps;
    }

    private SurvivalBuildPlan survivalBuildSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return new SurvivalBuildPlan(List.of(), BlockPos.ZERO, 0, new JsonArray(), new JsonObject());
        }

        String blockId = normalizeItemId(getString(request, "blockId", getString(request, "itemId", "minecraft:oak_planks")));
        int width = clamp(getInt(request, "width", 5), 3, 16);
        int depth = clamp(getInt(request, "depth", 5), 3, 16);
        int height = clamp(getInt(request, "height", 3), 2, 8);
        boolean roof = getBoolean(request, "roof", true);
        int limit = clamp(getInt(request, "limit", 512), 1, 1024);
        BlockPos origin = request.has("x")
            ? blockPosFromJson(request, player.blockPosition())
            : player.blockPosition().relative(player.getDirection(), 3);

        List<BlockPos> placements = basicHousePlacements(origin, width, depth, height, roof, limit);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject select = taskStep("selectHotbar");
        int slot = findMatchingHotbarSlot(player.getInventory(), blockId);
        select.addProperty("slot", Math.max(0, slot));
        steps.add(select);

        for (BlockPos pos : placements) {
            JsonObject place = taskStep("placeBlock");
            place.addProperty("blockId", blockId);
            place.addProperty("x", pos.getX());
            place.addProperty("y", pos.getY());
            place.addProperty("z", pos.getZ());
            place.addProperty("face", "up");
            place.addProperty("lookAtFirst", true);
            steps.add(place);
            steps.add(waitForActionIdleStep(250));
        }

        JsonArray placementJson = new JsonArray();
        for (BlockPos pos : placements) {
            placementJson.add(blockPosJson(pos));
        }
        JsonObject required = new JsonObject();
        required.addProperty(blockId, placements.size());
        JsonObject missing = missingRequirementsJson(player.getInventory(), required);
        return new SurvivalBuildPlan(steps, origin, placements.size(), placementJson, missing);
    }

    private List<JsonObject> survivalEnchantSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }

        List<JsonObject> steps = new ArrayList<>();
        Optional<BlockPos> table = findNearestBlock(level, player.blockPosition(), "minecraft:enchanting_table", clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS));
        if (table.isPresent()) {
            JsonObject go = taskStep("gotoBlock");
            go.addProperty("id", "minecraft:enchanting_table");
            go.addProperty("radius", clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS));
            go.addProperty("standRange", 2);
            steps.add(go);
            steps.add(waitForActionIdleStep(20000));

            JsonObject interact = taskStep("interact");
            interact.addProperty("x", table.get().getX());
            interact.addProperty("y", table.get().getY());
            interact.addProperty("z", table.get().getZ());
            interact.addProperty("face", "up");
            steps.add(interact);
            steps.add(waitForScreenStep("EnchantmentScreen", 5000));
            return steps;
        }

        if (inventoryCount(player.getInventory(), "minecraft:enchanting_table") > 0) {
            BlockPos placeAt = player.blockPosition().relative(player.getDirection(), 2);
            JsonObject place = taskStep("placeBlock");
            place.addProperty("blockId", "minecraft:enchanting_table");
            place.addProperty("x", placeAt.getX());
            place.addProperty("y", placeAt.getY());
            place.addProperty("z", placeAt.getZ());
            place.addProperty("face", "up");
            steps.add(place);
            JsonObject interact = taskStep("interact");
            interact.addProperty("x", placeAt.getX());
            interact.addProperty("y", placeAt.getY());
            interact.addProperty("z", placeAt.getZ());
            interact.addProperty("face", "up");
            steps.add(interact);
            steps.add(waitForScreenStep("EnchantmentScreen", 5000));
        }
        return steps;
    }

    private List<JsonObject> survivalEnchantApplySteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>(survivalEnchantSteps(client, request));
        String itemId = normalizeItemId(getString(request, "itemId", ""));
        if (!itemId.isBlank()) {
            steps.add(quickMoveItemStep(itemId, 1, false, true));
        }
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "lapisItemId", "minecraft:lapis_lazuli")), 1, false, true));
        JsonObject button = taskStep("containerButton");
        button.addProperty("buttonId", clamp(getInt(request, "option", 0), 0, 2));
        steps.add(button);
        JsonObject take = taskStep("containerClickRole");
        take.addProperty("role", "item");
        take.addProperty("mode", "quick_move");
        steps.add(take);
        if (getBoolean(request, "close", true)) {
            steps.add(taskStep("closeScreen"));
        }
        return steps;
    }

    private List<JsonObject> survivalAdvancedPathSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }

        BlockPos target = request.has("blockId") || request.has("id")
            ? findNearestBlock(level, player.blockPosition(), getString(request, "blockId", getString(request, "id", "minecraft:stone")), clamp(getInt(request, "radius", MAX_ACTION_RADIUS), 1, MAX_ACTION_RADIUS)).orElse(player.blockPosition())
            : blockPosFromJson(request, player.blockPosition());
        int radius = clamp(getInt(request, "radius", MAX_ACTION_RADIUS), 1, MAX_ACTION_RADIUS);
        List<JsonObject> steps = new ArrayList<>();
        Optional<List<BlockPos>> direct = findPath(level, player.blockPosition(), target, radius);
        if (direct.isPresent()) {
            JsonObject go = taskStep("goto");
            go.addProperty("x", target.getX());
            go.addProperty("y", target.getY());
            go.addProperty("z", target.getZ());
            go.addProperty("radius", radius);
            steps.add(go);
            steps.add(waitForActionIdleStep(30000));
            return steps;
        }

        if (getBoolean(request, "recoverFirst", true)) {
            steps.addAll(survivalRecoverSteps(client, request));
        }
        if (getBoolean(request, "allowBreak", true)) {
            steps.addAll(lineMineTowardSteps(level, player.blockPosition(), target, clamp(getInt(request, "breakLimit", 12), 1, 64)));
        }
        if (getBoolean(request, "allowBridge", true)) {
            steps.addAll(bridgeTowardSteps(level, player.blockPosition(), target, normalizeItemId(getString(request, "bridgeBlockId", "minecraft:cobblestone")), clamp(getInt(request, "bridgeLimit", 12), 1, 64)));
        }
        JsonObject retry = taskStep("goto");
        retry.addProperty("x", target.getX());
        retry.addProperty("y", target.getY());
        retry.addProperty("z", target.getZ());
        retry.addProperty("radius", radius);
        steps.add(retry);
        steps.add(waitForActionIdleStep(30000));
        return steps;
    }

    private List<JsonObject> survivalRecoverSteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>();
        steps.add(taskStep("closeScreen"));
        JsonObject back = taskStep("move");
        back.addProperty("backward", true);
        back.addProperty("jump", true);
        back.addProperty("durationMs", clamp(getInt(request, "backMs", 700), 100, 3000));
        steps.add(back);
        steps.add(waitForActionIdleStep(4000));
        JsonObject side = taskStep("move");
        side.addProperty(getBoolean(request, "left", true) ? "left" : "right", true);
        side.addProperty("jump", true);
        side.addProperty("durationMs", clamp(getInt(request, "sideMs", 450), 100, 3000));
        steps.add(side);
        steps.add(waitForActionIdleStep(4000));
        return steps;
    }

    private List<JsonObject> survivalSmeltSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }

        String inputId = normalizeItemId(getString(request, "inputItemId", getString(request, "itemId", "minecraft:raw_iron")));
        String fuelId = normalizeItemId(getString(request, "fuelItemId", "minecraft:coal"));
        int count = clamp(getInt(request, "count", 1), 1, 64);
        List<JsonObject> steps = new ArrayList<>();
        addWorkstationAccessSteps(steps, player, level, "minecraft:furnace", "minecraft:furnace", getBoolean(request, "craftIfMissing", true));
        JsonObject open = taskStep("openNearbyContainer");
        open.addProperty("blockId", "minecraft:furnace");
        open.addProperty("radius", clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS));
        steps.add(open);
        steps.add(waitForActionIdleStep(20000));
        steps.add(quickMoveItemStep(inputId, count, false, true));
        steps.add(quickMoveItemStep(fuelId, 1, false, true));
        JsonObject wait = taskStep("wait");
        wait.addProperty("durationMs", clamp(getInt(request, "waitMs", 11000), 1000, 120000));
        steps.add(wait);
        JsonObject take = taskStep("containerTransfer");
        take.addProperty("from", 2);
        take.addProperty("count", count);
        steps.add(take);
        steps.add(taskStep("closeScreen"));
        return steps;
    }

    private List<JsonObject> storageOrganizeSteps(Minecraft client, JsonObject request) {
        String blockId = normalizeBlockId(getString(request, "blockId", "minecraft:chest"));
        List<JsonObject> steps = new ArrayList<>();
        JsonObject open = taskStep("openNearbyContainer");
        open.addProperty("blockId", blockId);
        open.addProperty("radius", clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS));
        steps.add(open);
        steps.add(waitForActionIdleStep(20000));
        boolean pull = "pull".equalsIgnoreCase(getString(request, "mode", "deposit"));
        for (String itemId : itemIdsFromRequest(request, "itemIds", getString(request, "itemId", ""))) {
            steps.add(quickMoveItemStep(itemId, clamp(getInt(request, "count", 1), 1, 64), pull, !pull));
        }
        if (getBoolean(request, "close", true)) {
            steps.add(taskStep("closeScreen"));
        }
        return steps;
    }

    private SurvivalBuildPlan buildTemplateSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return new SurvivalBuildPlan(List.of(), BlockPos.ZERO, 0, new JsonArray(), new JsonObject());
        }

        String template = getString(request, "template", "house").trim().toLowerCase(Locale.ROOT);
        BlockPos origin = request.has("x")
            ? blockPosFromJson(request, player.blockPosition())
            : player.blockPosition().relative(player.getDirection(), 3);
        List<BuildPlacement> placements = switch (template) {
            case "farm" -> farmTemplatePlacements(origin, normalizeItemId(getString(request, "blockId", "minecraft:farmland")));
            case "mine_stairs", "mineshaft" -> mineStairTemplatePlacements(origin, normalizeItemId(getString(request, "blockId", "minecraft:cobblestone")));
            case "portal" -> netherPortalPlacements(origin);
            case "redstone_line" -> redstoneLinePlacements(origin, clamp(getInt(request, "length", 8), 1, 32));
            default -> houseTemplatePlacements(origin, request);
        };

        int limit = clamp(getInt(request, "limit", 512), 1, 2048);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject counts = new JsonObject();
        JsonArray placementJson = new JsonArray();
        int added = 0;
        for (BuildPlacement placement : placements) {
            if (added >= limit) {
                break;
            }
            counts.addProperty(placement.blockId(), counts.has(placement.blockId()) ? counts.get(placement.blockId()).getAsInt() + 1 : 1);
            JsonObject refill = taskStep("refillHotbar");
            refill.addProperty("itemId", placement.blockId());
            refill.addProperty("hotbarSlot", 0);
            steps.add(refill);
            steps.add(placeBlockStep(placement.blockId(), placement.pos(), "up"));
            JsonObject entry = blockPosJson(placement.pos());
            entry.addProperty("blockId", placement.blockId());
            placementJson.add(entry);
            added++;
        }
        JsonObject missing = missingRequirementsJson(player.getInventory(), counts);
        return new SurvivalBuildPlan(steps, origin, added, placementJson, missing);
    }

    private List<JsonObject> buildRefillHotbarSteps(JsonObject request) {
        JsonObject refill = taskStep("refillHotbar");
        refill.addProperty("itemId", normalizeItemId(getString(request, "itemId", getString(request, "blockId", ""))));
        refill.addProperty("hotbarSlot", clamp(getInt(request, "hotbarSlot", 0), 0, 8));
        return List.of(refill);
    }

    private List<JsonObject> survivalExperienceSteps(Minecraft client, JsonObject request) {
        String method = getString(request, "method", "auto").trim().toLowerCase(Locale.ROOT);
        if ("smelt".equals(method)) {
            return survivalSmeltSteps(client, request);
        }
        if ("combat".equals(method)) {
            return survivalCombatSteps(client, request);
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player != null && level != null) {
            Optional<BlockPos> coal = findNearestAnyBlock(level, player.blockPosition(), xpOreBlockIds(), clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS));
            if (coal.isPresent()) {
                JsonObject mineReq = new JsonObject();
                mineReq.addProperty("targetOre", blockIdAt(level, coal.get()));
                mineReq.addProperty("radius", clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS));
                mineReq.addProperty("limit", clamp(getInt(request, "limit", 8), 1, 64));
                return survivalMineSteps(client, mineReq);
            }
        }
        return survivalSmeltSteps(client, request);
    }

    private List<JsonObject> survivalCombatSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<JsonObject> steps = new ArrayList<>();
        if (getBoolean(request, "eatIfHungry", true) && player.getFoodData().getFoodLevel() < clamp(getInt(request, "minFood", 10), 1, 20)) {
            JsonObject equipFood = taskStep("equipBest");
            equipFood.addProperty("purpose", "food");
            steps.add(equipFood);
            JsonObject eat = taskStep("useItem");
            eat.addProperty("hand", "main");
            steps.add(eat);
            JsonObject wait = taskStep("wait");
            wait.addProperty("durationMs", 1700);
            steps.add(wait);
        }
        Entity target = findNearestEntityByType(level, player, getString(request, "entityType", ""), clamp(getInt(request, "radius", 12), 1, 64), true);
        if (target == null) {
            return steps;
        }
        JsonObject equip = taskStep("equipBest");
        equip.addProperty("purpose", "combat");
        steps.add(equip);
        JsonObject go = taskStep("goto");
        BlockPos near = target.blockPosition();
        go.addProperty("x", near.getX());
        go.addProperty("y", near.getY());
        go.addProperty("z", near.getZ());
        go.addProperty("radius", clamp(getInt(request, "pathRadius", 24), 1, MAX_ACTION_RADIUS));
        steps.add(go);
        steps.add(waitForActionIdleStep(20000));
        JsonObject attack = taskStep("attackEntity");
        attack.addProperty("entityUuid", target.getUUID().toString());
        attack.addProperty("count", clamp(getInt(request, "hits", 3), 1, 20));
        steps.add(attack);
        steps.add(waitForActionIdleStep(15000));
        if (getBoolean(request, "retreatAfter", false)) {
            JsonObject back = taskStep("move");
            back.addProperty("backward", true);
            back.addProperty("sprint", true);
            back.addProperty("durationMs", 900);
            steps.add(back);
            steps.add(waitForActionIdleStep(3000));
        }
        return steps;
    }

    private List<JsonObject> survivalFarmSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        String mode = getString(request, "mode", "harvest").trim().toLowerCase(Locale.ROOT);
        List<JsonObject> steps = new ArrayList<>();
        if ("feed".equals(mode)) {
            Entity animal = findNearestEntityByType(level, player, getString(request, "entityType", "minecraft:cow"), clamp(getInt(request, "radius", 12), 1, 64), false);
            if (animal != null) {
                JsonObject food = taskStep("refillHotbar");
                food.addProperty("itemId", normalizeItemId(getString(request, "foodItemId", "minecraft:wheat")));
                food.addProperty("hotbarSlot", 0);
                steps.add(food);
                JsonObject interact = taskStep("interact");
                interact.addProperty("entityUuid", animal.getUUID().toString());
                interact.addProperty("hand", "main");
                steps.add(interact);
            }
            return steps;
        }
        if ("plant".equals(mode)) {
            String seedId = normalizeItemId(getString(request, "seedItemId", "minecraft:wheat_seeds"));
            BlockPos pos = request.has("x") ? blockPosFromJson(request, player.blockPosition()) : player.blockPosition().relative(player.getDirection(), 2);
            JsonObject refill = taskStep("refillHotbar");
            refill.addProperty("itemId", seedId);
            refill.addProperty("hotbarSlot", 0);
            steps.add(refill);
            steps.add(placeBlockStep(seedId, pos, "up"));
            return steps;
        }
        String cropId = normalizeBlockId(getString(request, "crop", "minecraft:wheat"));
        JsonArray found = findBlocksJson(level, player.blockPosition(), cropId, clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS), clamp(getInt(request, "limit", 8), 1, 64));
        for (JsonElement element : found) {
            JsonObject posJson = element.getAsJsonObject().getAsJsonObject("pos");
            BlockPos cropPos = blockPosFromJson(posJson, player.blockPosition());
            steps.add(mineBlockStep(cropPos));
            steps.add(waitForActionIdleStep(8000));
        }
        steps.add(pickupStep(8, 5000));
        return steps;
    }

    private List<JsonObject> survivalMineSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<JsonObject> steps = new ArrayList<>();
        String rawTargetOre = getString(request, "targetOre", "").trim();
        String targetOre = rawTargetOre.isBlank() ? "" : normalizeBlockId(rawTargetOre);
        if (!targetOre.isBlank()) {
            JsonArray ores = targetOre.isBlank() ? new JsonArray() : findBlocksJson(level, player.blockPosition(), targetOre, clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS), clamp(getInt(request, "limit", 8), 1, 64));
            for (JsonElement element : ores) {
                JsonObject posJson = element.getAsJsonObject().getAsJsonObject("pos");
                BlockPos orePos = blockPosFromJson(posJson, player.blockPosition());
                JsonObject go = taskStep("gotoBlock");
                go.addProperty("id", targetOre);
                go.addProperty("radius", clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS));
                go.addProperty("standRange", 2);
                steps.add(go);
                steps.add(waitForActionIdleStep(20000));
                steps.add(mineBlockStep(orePos));
                steps.add(waitForActionIdleStep(20000));
            }
            steps.add(pickupStep(8, 5000));
            return steps;
        }
        JsonObject dig = request.deepCopy();
        dig.addProperty("width", clamp(getInt(request, "width", 2), 1, 4));
        dig.addProperty("length", clamp(getInt(request, "length", 8), 1, 32));
        dig.addProperty("depth", clamp(getInt(request, "depth", 3), 1, 16));
        steps.addAll(survivalDigSteps(client, dig));
        int torchEvery = clamp(getInt(request, "torchEvery", 6), 0, 32);
        if (torchEvery > 0) {
            steps.addAll(survivalLightSteps(client, request));
        }
        return steps;
    }

    private List<JsonObject> survivalLightSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        String torchId = normalizeItemId(getString(request, "torchItemId", "minecraft:torch"));
        int radius = clamp(getInt(request, "radius", 8), 1, MAX_ACTION_RADIUS);
        int limit = clamp(getInt(request, "limit", 8), 1, 64);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject refill = taskStep("refillHotbar");
        refill.addProperty("itemId", torchId);
        refill.addProperty("hotbarSlot", 0);
        steps.add(refill);
        int added = 0;
        for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-radius, -2, -radius), player.blockPosition().offset(radius, 2, radius))) {
            if (added >= limit) break;
            BlockPos floor = pos.immutable();
            BlockPos target = floor.above();
            if (isFloor(level, floor) && level.getBlockState(target).isAir() && level.getBrightness(LightLayer.BLOCK, target) <= clamp(getInt(request, "maxBlockLight", 6), 0, 15)) {
                steps.add(placeBlockStep(torchId, target, "up"));
                added++;
            }
        }
        return steps;
    }

    private List<JsonObject> survivalSleepSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<JsonObject> steps = new ArrayList<>();
        Optional<BlockPos> bed = findNearestAnyBlock(level, player.blockPosition(), bedBlockIds(), clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS));
        BlockPos target;
        if (bed.isPresent()) {
            target = bed.get();
            JsonObject go = taskStep("gotoBlock");
            go.addProperty("id", blockIdAt(level, target));
            go.addProperty("radius", clamp(getInt(request, "radius", 24), 1, MAX_ACTION_RADIUS));
            go.addProperty("standRange", 2);
            steps.add(go);
            steps.add(waitForActionIdleStep(20000));
        } else {
            target = player.blockPosition().relative(player.getDirection(), 2);
            steps.add(placeBlockStep(normalizeItemId(getString(request, "bedItemId", "minecraft:white_bed")), target, "up"));
        }
        JsonObject interact = taskStep("interact");
        interact.addProperty("x", target.getX());
        interact.addProperty("y", target.getY());
        interact.addProperty("z", target.getZ());
        interact.addProperty("face", "up");
        steps.add(interact);
        return steps;
    }

    private List<JsonObject> survivalPlaceWorkstationSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return List.of();
        }
        String blockId = normalizeItemId(getString(request, "blockId", getString(request, "workstation", "minecraft:crafting_table")));
        BlockPos placeAt = request.has("x") ? blockPosFromJson(request, player.blockPosition()) : player.blockPosition().relative(player.getDirection(), 2);
        List<JsonObject> steps = new ArrayList<>();
        steps.add(placeBlockStep(blockId, placeAt, "up"));
        if (getBoolean(request, "openAfterPlace", true)) {
            JsonObject interact = taskStep("interact");
            interact.addProperty("x", placeAt.getX());
            interact.addProperty("y", placeAt.getY());
            interact.addProperty("z", placeAt.getZ());
            interact.addProperty("face", "up");
            steps.add(interact);
        }
        return steps;
    }

    private List<JsonObject> survivalDimensionSteps(Minecraft client, JsonObject request) {
        String dimension = getString(request, "dimension", "nether").trim().toLowerCase(Locale.ROOT);
        if ("end".equals(dimension)) {
            return List.of();
        }
        JsonObject portalRequest = request.deepCopy();
        portalRequest.addProperty("template", "portal");
        List<JsonObject> steps = new ArrayList<>(buildTemplateSteps(client, portalRequest).steps());
        JsonObject refill = taskStep("refillHotbar");
        refill.addProperty("itemId", "minecraft:flint_and_steel");
        refill.addProperty("hotbarSlot", 0);
        steps.add(refill);
        LocalPlayer player = client.player;
        BlockPos ignite = player == null ? BlockPos.ZERO : (request.has("x") ? blockPosFromJson(request, player.blockPosition()).offset(1, 1, 0) : player.blockPosition().relative(player.getDirection(), 3).offset(1, 1, 0));
        JsonObject igniteStep = taskStep("interact");
        igniteStep.addProperty("x", ignite.getX());
        igniteStep.addProperty("y", ignite.getY());
        igniteStep.addProperty("z", ignite.getZ());
        igniteStep.addProperty("face", "up");
        igniteStep.addProperty("hand", "main");
        steps.add(igniteStep);
        return steps;
    }

    private List<JsonObject> survivalRedstoneSteps(Minecraft client, JsonObject request) {
        JsonObject build = request.deepCopy();
        build.addProperty("template", getString(request, "template", "redstone_line"));
        return buildTemplateSteps(client, build).steps();
    }

    private List<JsonObject> survivalTradeSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        Entity villager = findNearestEntityByType(level, player, "minecraft:villager", clamp(getInt(request, "radius", 16), 1, 64), false);
        if (villager == null) {
            return List.of();
        }
        List<JsonObject> steps = new ArrayList<>();
        JsonObject go = taskStep("goto");
        BlockPos pos = villager.blockPosition();
        go.addProperty("x", pos.getX());
        go.addProperty("y", pos.getY());
        go.addProperty("z", pos.getZ());
        go.addProperty("radius", clamp(getInt(request, "pathRadius", 24), 1, MAX_ACTION_RADIUS));
        steps.add(go);
        steps.add(waitForActionIdleStep(20000));
        JsonObject interact = taskStep("interact");
        interact.addProperty("entityUuid", villager.getUUID().toString());
        steps.add(interact);
        return steps;
    }

    private List<JsonObject> survivalTradeSelectSteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>(survivalTradeSteps(client, request));
        JsonObject button = taskStep("containerButton");
        button.addProperty("buttonId", clamp(getInt(request, "tradeIndex", 0), 0, 15));
        steps.add(button);
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "paymentItemId", "minecraft:emerald")), clamp(getInt(request, "paymentCount", 1), 1, 64), false, true));
        String secondPayment = normalizeItemId(getString(request, "secondPaymentItemId", ""));
        if (!secondPayment.isBlank()) {
            steps.add(quickMoveItemStep(secondPayment, clamp(getInt(request, "secondPaymentCount", 1), 1, 64), false, true));
        }
        JsonObject take = taskStep("containerClickRole");
        take.addProperty("role", "result");
        take.addProperty("mode", "quick_move");
        steps.add(take);
        if (getBoolean(request, "close", true)) {
            steps.add(taskStep("closeScreen"));
        }
        return steps;
    }

    private List<JsonObject> survivalFishSteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>();
        JsonObject refill = taskStep("refillHotbar");
        refill.addProperty("itemId", "minecraft:fishing_rod");
        refill.addProperty("hotbarSlot", 0);
        steps.add(refill);
        JsonObject cast = taskStep("useItem");
        cast.addProperty("slot", 0);
        cast.addProperty("hand", "main");
        steps.add(cast);
        JsonObject wait = taskStep("wait");
        wait.addProperty("durationMs", clamp(getInt(request, "waitMs", 20000), 1000, 120000));
        steps.add(wait);
        JsonObject reel = taskStep("useItem");
        reel.addProperty("slot", 0);
        reel.addProperty("hand", "main");
        steps.add(reel);
        return steps;
    }

    private List<JsonObject> survivalBrewSteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>();
        JsonObject open = taskStep("openNearbyContainer");
        open.addProperty("blockId", "minecraft:brewing_stand");
        open.addProperty("radius", clamp(getInt(request, "radius", 16), 1, MAX_ACTION_RADIUS));
        steps.add(open);
        steps.add(waitForActionIdleStep(20000));
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "fuelItemId", "minecraft:blaze_powder")), 1, false, true));
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "bottleItemId", "minecraft:potion")), clamp(getInt(request, "bottleCount", 1), 1, 3), false, true));
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "ingredientItemId", "minecraft:nether_wart")), 1, false, true));
        JsonObject wait = taskStep("wait");
        wait.addProperty("durationMs", clamp(getInt(request, "waitMs", 22000), 1000, 120000));
        steps.add(wait);
        steps.add(quickMoveItemStep(normalizeItemId(getString(request, "resultItemId", "minecraft:potion")), clamp(getInt(request, "bottleCount", 1), 1, 3), true, false));
        return steps;
    }

    private List<JsonObject> survivalAnvilSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<JsonObject> steps = new ArrayList<>();
        if (findNearestBlock(level, player.blockPosition(), "minecraft:anvil", clamp(getInt(request, "radius", 12), 1, MAX_ACTION_RADIUS)).isEmpty()
            && inventoryCount(player.getInventory(), "minecraft:anvil") > 0) {
            steps.addAll(survivalPlaceWorkstationSteps(client, anvilWorkstationRequest(request)));
            return steps;
        }
        JsonObject open = taskStep("openNearbyContainer");
        open.addProperty("blockId", "minecraft:anvil");
        open.addProperty("radius", clamp(getInt(request, "radius", 12), 1, MAX_ACTION_RADIUS));
        steps.add(open);
        return steps;
    }

    private List<JsonObject> survivalAnvilApplySteps(Minecraft client, JsonObject request) {
        List<JsonObject> steps = new ArrayList<>(survivalAnvilSteps(client, request));
        String left = normalizeItemId(getString(request, "leftItemId", getString(request, "itemId", "")));
        String right = normalizeItemId(getString(request, "rightItemId", getString(request, "materialItemId", "")));
        if (!left.isBlank()) {
            steps.add(quickMoveItemStep(left, 1, false, true));
        }
        if (!right.isBlank()) {
            steps.add(quickMoveItemStep(right, 1, false, true));
        }
        JsonObject take = taskStep("containerClickRole");
        take.addProperty("role", "result");
        take.addProperty("mode", "quick_move");
        steps.add(take);
        if (getBoolean(request, "close", true)) {
            steps.add(taskStep("closeScreen"));
        }
        return steps;
    }

    private List<JsonObject> survivalExploreSteps(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return List.of();
        }
        int radius = clamp(getInt(request, "radius", 16), 4, MAX_ACTION_RADIUS);
        BlockPos origin = player.blockPosition();
        List<BlockPos> waypoints = List.of(
            origin.offset(radius, 0, 0),
            origin.offset(radius, 0, radius),
            origin.offset(0, 0, radius),
            origin.offset(-radius, 0, radius),
            origin.offset(-radius, 0, 0),
            origin.offset(-radius, 0, -radius),
            origin.offset(0, 0, -radius),
            origin.offset(radius, 0, -radius)
        );
        List<JsonObject> steps = new ArrayList<>();
        int limit = clamp(getInt(request, "waypoints", 8), 1, waypoints.size());
        for (int i = 0; i < limit; i++) {
            BlockPos point = waypoints.get(i);
            JsonObject go = taskStep("goto");
            go.addProperty("x", point.getX());
            go.addProperty("y", point.getY());
            go.addProperty("z", point.getZ());
            go.addProperty("radius", MAX_ACTION_RADIUS);
            steps.add(go);
            steps.add(waitForActionIdleStep(30000));
        }
        return steps;
    }

    private List<JsonObject> lineMineTowardSteps(ClientLevel level, BlockPos start, BlockPos target, int limit) {
        List<JsonObject> steps = new ArrayList<>();
        BlockPos cursor = start;
        int added = 0;
        while (!cursor.equals(target) && added < limit) {
            int dx = Integer.compare(target.getX(), cursor.getX());
            int dy = Integer.compare(target.getY(), cursor.getY());
            int dz = Integer.compare(target.getZ(), cursor.getZ());
            cursor = cursor.offset(dx, Math.abs(target.getY() - cursor.getY()) > 1 ? dy : 0, dz).immutable();
            if (!level.getBlockState(cursor).isAir()) {
                steps.add(mineBlockStep(cursor));
                steps.add(waitForActionIdleStep(20000));
                added++;
            }
            if (!level.getBlockState(cursor.above()).isAir()) {
                steps.add(mineBlockStep(cursor.above()));
                steps.add(waitForActionIdleStep(20000));
                added++;
            }
        }
        return steps;
    }

    private List<JsonObject> bridgeTowardSteps(ClientLevel level, BlockPos start, BlockPos target, String blockId, int limit) {
        List<JsonObject> steps = new ArrayList<>();
        BlockPos cursor = start;
        int added = 0;
        while (!cursor.equals(target) && added < limit) {
            int dx = Integer.compare(target.getX(), cursor.getX());
            int dz = Integer.compare(target.getZ(), cursor.getZ());
            cursor = cursor.offset(dx, 0, dz).immutable();
            BlockPos floor = cursor.below();
            if (level.getBlockState(floor).isAir()) {
                steps.add(placeBlockStep(blockId, floor, "up"));
                added++;
            }
        }
        return steps;
    }

    private void addWorkstationAccessSteps(List<JsonObject> steps, LocalPlayer player, ClientLevel level, String blockId, String itemId, boolean craftIfMissing) {
        if (findNearestBlock(level, player.blockPosition(), blockId, 16).isPresent()) {
            return;
        }
        if (inventoryCount(player.getInventory(), itemId) <= 0 && craftIfMissing) {
            JsonObject craft = taskStep(requiresCraftingTable(itemId) ? "craftTableProcessAutoRepair" : "craftInventoryProcessAutoRepair");
            craft.addProperty("itemId", itemId);
            craft.addProperty("count", 1);
            steps.add(craft);
        }
        if (inventoryCount(player.getInventory(), itemId) > 0 || craftIfMissing) {
            BlockPos placeAt = player.blockPosition().relative(player.getDirection(), 2);
            steps.add(placeBlockStep(itemId, placeAt, "up"));
            steps.add(waitForActionIdleStep(1000));
        }
    }

    private JsonObject quickMoveItemStep(String itemId, int count, boolean containerOnly, boolean inventoryOnly) {
        JsonObject step = taskStep("containerQuickMoveItem");
        step.addProperty("itemId", normalizeItemId(itemId));
        step.addProperty("count", count);
        step.addProperty("containerOnly", containerOnly);
        step.addProperty("inventoryOnly", inventoryOnly);
        return step;
    }

    private JsonObject placeBlockStep(String blockId, BlockPos pos, String face) {
        JsonObject step = taskStep("placeBlock");
        step.addProperty("blockId", normalizeItemId(blockId));
        step.addProperty("x", pos.getX());
        step.addProperty("y", pos.getY());
        step.addProperty("z", pos.getZ());
        step.addProperty("face", face == null || face.isBlank() ? "up" : face);
        step.addProperty("lookAtFirst", true);
        return step;
    }

    private JsonObject mineBlockStep(BlockPos pos) {
        JsonObject step = taskStep("mineBlock");
        step.addProperty("x", pos.getX());
        step.addProperty("y", pos.getY());
        step.addProperty("z", pos.getZ());
        return step;
    }

    private JsonObject pickupStep(int radius, int timeoutMs) {
        JsonObject step = taskStep("pickupItems");
        step.addProperty("radius", radius);
        step.addProperty("timeoutMs", timeoutMs);
        return step;
    }

    private List<String> itemIdsFromRequest(JsonObject request, String arrayKey, String fallback) {
        List<String> ids = new ArrayList<>();
        if (request.has(arrayKey) && request.get(arrayKey).isJsonArray()) {
            for (JsonElement element : request.getAsJsonArray(arrayKey)) {
                if (element.isJsonPrimitive()) {
                    String id = normalizeItemId(element.getAsString());
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        }
        String fallbackId = normalizeItemId(fallback);
        if (ids.isEmpty() && !fallbackId.isBlank()) {
            ids.add(fallbackId);
        }
        return ids.isEmpty() ? List.of("minecraft:cobblestone") : ids;
    }

    private JsonObject decisionJson(String action, int priority, String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("action", action);
        root.addProperty("priority", priority);
        root.addProperty("reason", reason);
        return root;
    }

    private JsonObject combatTargetsJson(Minecraft client, JsonObject request) {
        JsonObject root = new JsonObject();
        if (client.player == null || client.level == null) {
            return root;
        }
        Entity target = findNearestEntityByType(client.level, client.player, getString(request, "entityType", ""), clamp(getInt(request, "radius", 12), 1, 64), true);
        root.addProperty("found", target != null);
        if (target != null) {
            root.add("target", entityJson(client.player, target));
        }
        return root;
    }

    private JsonObject villagerTargetsJson(Minecraft client, JsonObject request) {
        JsonObject root = new JsonObject();
        if (client.player == null || client.level == null) {
            return root;
        }
        Entity target = findNearestEntityByType(client.level, client.player, "minecraft:villager", clamp(getInt(request, "radius", 16), 1, 64), false);
        root.addProperty("found", target != null);
        if (target != null) {
            root.add("target", entityJson(client.player, target));
        }
        return root;
    }

    private JsonObject recipeTreeJson(Minecraft client, ClientLevel level, LocalPlayer player, String itemId, int depth, Set<String> seen) {
        JsonObject root = new JsonObject();
        root.addProperty("itemId", itemId);
        root.addProperty("inventoryCount", inventoryCount(player.getInventory(), itemId));
        root.add("requirementsGuess", simpleRequirementsForItem(itemId, 1));
        if (depth <= 0 || !seen.add(itemId)) {
            root.addProperty("truncated", true);
            return root;
        }
        RecipeSelection selection = selectRecipeForCraft(client, player, level, itemId, false);
        root.addProperty("recipeFound", selection != null);
        if (selection == null) {
            return root;
        }
        root.addProperty("menuType", selection.menuType());
        root.addProperty("craftableNow", selection.entry().canCraft(inventoryContents(player.getInventory())));
        JsonArray children = new JsonArray();
        for (Ingredient ingredient : recipeIngredients(selection.holder())) {
            JsonObject child = ingredientJson(ingredient);
            ingredient.items().findFirst().ifPresent(holder -> child.add("tree", recipeTreeJson(client, level, player, BuiltInRegistries.ITEM.getKey(holder.value()).toString(), depth - 1, seen)));
            children.add(child);
        }
        root.add("ingredients", children);
        return root;
    }

    private String smeltOutputGuess(String rawInputId) {
        String inputId = normalizeItemId(rawInputId);
        return switch (inputId) {
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore" -> "minecraft:iron_ingot";
            case "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore" -> "minecraft:gold_ingot";
            case "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore" -> "minecraft:copper_ingot";
            case "minecraft:ancient_debris" -> "minecraft:netherite_scrap";
            case "minecraft:sand" -> "minecraft:glass";
            case "minecraft:cobblestone" -> "minecraft:stone";
            default -> "";
        };
    }

    private List<BuildPlacement> houseTemplatePlacements(BlockPos origin, JsonObject request) {
        String wall = normalizeItemId(getString(request, "wallBlockId", getString(request, "blockId", "minecraft:oak_planks")));
        String floor = normalizeItemId(getString(request, "floorBlockId", wall));
        String roofBlock = normalizeItemId(getString(request, "roofBlockId", wall));
        int width = clamp(getInt(request, "width", 5), 3, 24);
        int depth = clamp(getInt(request, "depth", 5), 3, 24);
        int height = clamp(getInt(request, "height", 3), 2, 12);
        List<BuildPlacement> placements = new ArrayList<>();
        for (BlockPos pos : basicHousePlacements(origin, width, depth, height, false, 2048)) {
            String block = pos.getY() == origin.getY() ? floor : wall;
            placements.add(new BuildPlacement(pos, block));
        }
        if (getBoolean(request, "roof", true)) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    placements.add(new BuildPlacement(origin.offset(x, height + 1, z), roofBlock));
                }
            }
        }
        if (getBoolean(request, "torches", true)) {
            placements.add(new BuildPlacement(origin.offset(1, 2, 1), "minecraft:torch"));
            placements.add(new BuildPlacement(origin.offset(width - 2, 2, depth - 2), "minecraft:torch"));
        }
        return placements;
    }

    private List<BuildPlacement> farmTemplatePlacements(BlockPos origin, String blockId) {
        List<BuildPlacement> placements = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                placements.add(new BuildPlacement(origin.offset(x, 0, z), blockId));
            }
        }
        placements.add(new BuildPlacement(origin.offset(4, 0, 4), "minecraft:water_bucket"));
        return placements;
    }

    private List<BuildPlacement> mineStairTemplatePlacements(BlockPos origin, String blockId) {
        List<BuildPlacement> placements = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            placements.add(new BuildPlacement(origin.offset(0, -i, i), blockId));
            if (i % 5 == 0) {
                placements.add(new BuildPlacement(origin.offset(1, -i + 1, i), "minecraft:torch"));
            }
        }
        return placements;
    }

    private List<BuildPlacement> netherPortalPlacements(BlockPos origin) {
        List<BuildPlacement> placements = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            placements.add(new BuildPlacement(origin.offset(0, y, 0), "minecraft:obsidian"));
            placements.add(new BuildPlacement(origin.offset(3, y, 0), "minecraft:obsidian"));
        }
        for (int x = 1; x < 3; x++) {
            placements.add(new BuildPlacement(origin.offset(x, 0, 0), "minecraft:obsidian"));
            placements.add(new BuildPlacement(origin.offset(x, 4, 0), "minecraft:obsidian"));
        }
        return placements;
    }

    private List<BuildPlacement> redstoneLinePlacements(BlockPos origin, int length) {
        List<BuildPlacement> placements = new ArrayList<>();
        placements.add(new BuildPlacement(origin, "minecraft:lever"));
        for (int i = 1; i <= length; i++) {
            placements.add(new BuildPlacement(origin.offset(i, 0, 0), "minecraft:redstone"));
        }
        return placements;
    }

    private Entity findNearestEntityByType(ClientLevel level, LocalPlayer player, String rawType, int radius, boolean hostileOnly) {
        String type = rawType == null ? "" : rawType.trim();
        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(player, area, entity -> entity != null && entity != player && entity.isAlive());
        entities.sort(Comparator.comparingDouble(player::distanceToSqr));
        for (Entity entity : entities) {
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            boolean hostile = entity instanceof Mob mob && mob.canAttack(player);
            if (hostileOnly && !hostile) {
                continue;
            }
            if (type.isBlank() || entityType.equalsIgnoreCase(type)) {
                return entity;
            }
        }
        return null;
    }

    private JsonObject anvilWorkstationRequest(JsonObject request) {
        JsonObject copy = request.deepCopy();
        copy.addProperty("blockId", "minecraft:anvil");
        copy.addProperty("openAfterPlace", true);
        return copy;
    }

    private List<String> xpOreBlockIds() {
        return List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:nether_quartz_ore"
        );
    }

    private List<String> bedBlockIds() {
        return List.of(
            "minecraft:white_bed", "minecraft:orange_bed", "minecraft:magenta_bed", "minecraft:light_blue_bed",
            "minecraft:yellow_bed", "minecraft:lime_bed", "minecraft:pink_bed", "minecraft:gray_bed",
            "minecraft:light_gray_bed", "minecraft:cyan_bed", "minecraft:purple_bed", "minecraft:blue_bed",
            "minecraft:brown_bed", "minecraft:green_bed", "minecraft:red_bed", "minecraft:black_bed"
        );
    }

    private void addCraftingPrerequisiteSteps(List<JsonObject> steps, LocalPlayer player, ClientLevel level, String itemId) {
        Inventory inventory = player.getInventory();
        String plankId = preferredPlanksForInventory(inventory);
        if (needsWoodParts(itemId) && inventoryCountAny(inventory, plankItemIds()) < 4 && inventoryCountAny(inventory, logBlockIds()) > 0) {
            JsonObject planks = taskStep("craftInventoryProcessAutoRepair");
            planks.addProperty("itemId", plankId);
            planks.addProperty("count", 1);
            steps.add(planks);
        }
        if (needsSticks(itemId) && inventoryCount(inventory, "minecraft:stick") < 2) {
            JsonObject sticks = taskStep("craftInventoryProcessAutoRepair");
            sticks.addProperty("itemId", "minecraft:stick");
            sticks.addProperty("count", 1);
            steps.add(sticks);
        }
        if (requiresCraftingTable(itemId) && !hasCraftingTableAccess(level, player)) {
            if (inventoryCount(inventory, "minecraft:crafting_table") <= 0) {
                JsonObject table = taskStep("craftInventoryProcessAutoRepair");
                table.addProperty("itemId", "minecraft:crafting_table");
                table.addProperty("count", 1);
                steps.add(table);
            }
            BlockPos tablePos = player.blockPosition().relative(player.getDirection(), 2);
            JsonObject place = taskStep("placeBlock");
            place.addProperty("blockId", "minecraft:crafting_table");
            place.addProperty("x", tablePos.getX());
            place.addProperty("y", tablePos.getY());
            place.addProperty("z", tablePos.getZ());
            place.addProperty("face", "up");
            place.addProperty("lookAtFirst", true);
            steps.add(place);
            steps.add(waitForActionIdleStep(1000));
        }
    }

    private JsonObject survivalRequirementsFromRequest(JsonObject request) {
        String goal = getString(request, "goal", "").trim();
        if ("build".equalsIgnoreCase(goal)) {
            String blockId = normalizeItemId(getString(request, "blockId", getString(request, "itemId", "minecraft:oak_planks")));
            int width = clamp(getInt(request, "width", 5), 3, 16);
            int depth = clamp(getInt(request, "depth", 5), 3, 16);
            int height = clamp(getInt(request, "height", 3), 2, 8);
            boolean roof = getBoolean(request, "roof", true);
            JsonObject required = new JsonObject();
            required.addProperty(blockId, basicHousePlacements(BlockPos.ZERO, width, depth, height, roof, 1024).size());
            return required;
        }

        String itemId = normalizeItemId(getString(request, "itemId", getString(request, "tool", getString(request, "material", ""))));
        if (itemId.isBlank() && !goal.isBlank()) {
            itemId = normalizeItemId(goal);
        }
        return simpleRequirementsForItem(itemId, clamp(getInt(request, "count", 1), 1, 64));
    }

    private JsonObject toolRequirementRequest(JsonObject request) {
        JsonObject copy = request.deepCopy();
        copy.addProperty("itemId", resolveToolItemId(request));
        return copy;
    }

    private JsonObject materialRequirementRequest(JsonObject request) {
        JsonObject copy = request.deepCopy();
        copy.addProperty("itemId", normalizeItemId(getString(request, "itemId", getString(request, "material", "minecraft:oak_planks"))));
        return copy;
    }

    private JsonObject simpleRequirementsForItem(String itemId, int count) {
        JsonObject required = new JsonObject();
        String id = normalizeItemId(itemId);
        int multiplier = Math.max(1, count);
        if (id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_sword") || id.endsWith("_hoe")) {
            String tier = id.substring("minecraft:".length(), id.lastIndexOf('_'));
            String type = id.substring(id.lastIndexOf('_') + 1);
            int head = switch (type) {
                case "sword" -> 2;
                case "shovel" -> 1;
                default -> 3;
            };
            required.addProperty(materialForTier(tier), head * multiplier);
            required.addProperty("minecraft:stick", (type.equals("hoe") ? 2 : type.equals("sword") || type.equals("shovel") ? 1 : 2) * multiplier);
            required.addProperty("crafting_table_access", 1);
            return required;
        }
        if (id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots")) {
            String material = armorMaterialForItem(id);
            int pieces = armorPieceCount(id);
            required.addProperty(material, pieces * multiplier);
            required.addProperty("crafting_table_access", 1);
            return required;
        }
        switch (id) {
            case "minecraft:stick" -> required.addProperty("any_planks", 2 * multiplier);
            case "minecraft:crafting_table" -> required.addProperty("any_planks", 4 * multiplier);
            case "minecraft:chest" -> required.addProperty("any_planks", 8 * multiplier);
            case "minecraft:barrel" -> {
                required.addProperty("any_planks", 6 * multiplier);
                required.addProperty("any_wooden_slab", 2 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:furnace" -> required.addProperty("minecraft:cobblestone", 8 * multiplier);
            case "minecraft:blast_furnace" -> {
                required.addProperty("minecraft:furnace", multiplier);
                required.addProperty("minecraft:iron_ingot", 5 * multiplier);
                required.addProperty("minecraft:smooth_stone", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:smoker" -> {
                required.addProperty("minecraft:furnace", multiplier);
                required.addProperty("any_logs", 4 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:campfire" -> {
                required.addProperty("minecraft:stick", 3 * multiplier);
                required.addProperty("coal_or_charcoal", multiplier);
                required.addProperty("any_logs", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:torch" -> {
                required.addProperty("minecraft:stick", multiplier);
                required.addProperty("coal_or_charcoal", multiplier);
            }
            case "minecraft:ladder" -> {
                required.addProperty("minecraft:stick", 7 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:oak_door", "minecraft:spruce_door", "minecraft:birch_door", "minecraft:jungle_door",
                 "minecraft:acacia_door", "minecraft:dark_oak_door", "minecraft:mangrove_door", "minecraft:cherry_door",
                 "minecraft:pale_oak_door", "minecraft:crimson_door", "minecraft:warped_door" -> {
                required.addProperty("any_planks", 6 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:oak_trapdoor", "minecraft:spruce_trapdoor", "minecraft:birch_trapdoor", "minecraft:jungle_trapdoor",
                 "minecraft:acacia_trapdoor", "minecraft:dark_oak_trapdoor", "minecraft:mangrove_trapdoor", "minecraft:cherry_trapdoor",
                 "minecraft:pale_oak_trapdoor", "minecraft:crimson_trapdoor", "minecraft:warped_trapdoor" -> {
                required.addProperty("any_planks", 6 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:birch_fence", "minecraft:jungle_fence",
                 "minecraft:acacia_fence", "minecraft:dark_oak_fence", "minecraft:mangrove_fence", "minecraft:cherry_fence",
                 "minecraft:pale_oak_fence", "minecraft:crimson_fence", "minecraft:warped_fence" -> {
                required.addProperty("any_planks", 4 * multiplier);
                required.addProperty("minecraft:stick", 2 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:bed", "minecraft:white_bed", "minecraft:orange_bed", "minecraft:magenta_bed", "minecraft:light_blue_bed",
                 "minecraft:yellow_bed", "minecraft:lime_bed", "minecraft:pink_bed", "minecraft:gray_bed",
                 "minecraft:light_gray_bed", "minecraft:cyan_bed", "minecraft:purple_bed", "minecraft:blue_bed",
                 "minecraft:brown_bed", "minecraft:green_bed", "minecraft:red_bed", "minecraft:black_bed" -> {
                required.addProperty("any_wool", 3 * multiplier);
                required.addProperty("any_planks", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:boat", "minecraft:oak_boat", "minecraft:spruce_boat", "minecraft:birch_boat", "minecraft:jungle_boat",
                 "minecraft:acacia_boat", "minecraft:dark_oak_boat", "minecraft:mangrove_boat", "minecraft:cherry_boat",
                 "minecraft:pale_oak_boat" -> {
                required.addProperty("any_planks", 5 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:shield" -> {
                required.addProperty("any_planks", 6 * multiplier);
                required.addProperty("minecraft:iron_ingot", multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:bow" -> {
                required.addProperty("minecraft:stick", 3 * multiplier);
                required.addProperty("minecraft:string", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:fishing_rod" -> {
                required.addProperty("minecraft:stick", 3 * multiplier);
                required.addProperty("minecraft:string", 2 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:arrow" -> {
                required.addProperty("minecraft:flint", multiplier);
                required.addProperty("minecraft:stick", multiplier);
                required.addProperty("minecraft:feather", multiplier);
            }
            case "minecraft:bucket" -> {
                required.addProperty("minecraft:iron_ingot", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:shears" -> required.addProperty("minecraft:iron_ingot", 2 * multiplier);
            case "minecraft:flint_and_steel" -> {
                required.addProperty("minecraft:iron_ingot", multiplier);
                required.addProperty("minecraft:flint", multiplier);
            }
            case "minecraft:compass" -> {
                required.addProperty("minecraft:iron_ingot", 4 * multiplier);
                required.addProperty("minecraft:redstone", multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:clock" -> {
                required.addProperty("minecraft:gold_ingot", 4 * multiplier);
                required.addProperty("minecraft:redstone", multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:book" -> {
                required.addProperty("minecraft:paper", 3 * multiplier);
                required.addProperty("minecraft:leather", multiplier);
            }
            case "minecraft:paper" -> required.addProperty("minecraft:sugar_cane", 3 * multiplier);
            case "minecraft:bookshelf" -> {
                required.addProperty("any_planks", 6 * multiplier);
                required.addProperty("minecraft:book", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:enchanting_table" -> {
                required.addProperty("minecraft:book", multiplier);
                required.addProperty("minecraft:diamond", 2 * multiplier);
                required.addProperty("minecraft:obsidian", 4 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:anvil" -> {
                required.addProperty("minecraft:iron_ingot", 31 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:lever" -> {
                required.addProperty("minecraft:stick", multiplier);
                required.addProperty("minecraft:cobblestone", multiplier);
            }
            case "minecraft:repeater" -> {
                required.addProperty("minecraft:redstone_torch", 2 * multiplier);
                required.addProperty("minecraft:redstone", multiplier);
                required.addProperty("minecraft:stone", 3 * multiplier);
                required.addProperty("crafting_table_access", 1);
            }
            case "minecraft:redstone_torch" -> {
                required.addProperty("minecraft:redstone", multiplier);
                required.addProperty("minecraft:stick", multiplier);
            }
            default -> {
                if (id.endsWith("_planks")) {
                    required.addProperty("any_logs", multiplier);
                } else if (id.endsWith("_slab")) {
                    required.addProperty("any_planks", 3 * multiplier);
                } else if (id.endsWith("_stairs")) {
                    required.addProperty("any_planks", 6 * multiplier);
                    required.addProperty("crafting_table_access", 1);
                } else {
                    required.addProperty(id, multiplier);
                }
            }
        }
        return required;
    }

    private JsonObject missingRequirementsJson(Inventory inventory, JsonObject required) {
        JsonArray missing = new JsonArray();
        boolean ok = true;
        for (Map.Entry<String, JsonElement> entry : required.entrySet()) {
            String id = entry.getKey();
            int needed = Math.max(0, entry.getValue().getAsInt());
            int available = availableForRequirement(inventory, id);
            if (available < needed) {
                ok = false;
                JsonObject item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("needed", needed);
                item.addProperty("available", available);
                item.addProperty("missing", needed - available);
                missing.add(item);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", ok);
        root.addProperty("missingCount", missing.size());
        root.add("items", missing);
        return root;
    }

    private int availableForRequirement(Inventory inventory, String id) {
        return switch (id) {
            case "any_logs" -> inventoryCountAny(inventory, logBlockIds());
            case "any_planks" -> inventoryCountAny(inventory, plankItemIds());
            case "coal_or_charcoal" -> inventoryCountAny(inventory, List.of("minecraft:coal", "minecraft:charcoal"));
            case "crafting_table_access" -> inventoryCount(inventory, "minecraft:crafting_table");
            case "any_wool" -> inventoryCountAny(inventory, woolItemIds());
            case "any_wooden_slab" -> inventoryCountAny(inventory, woodenSlabItemIds());
            default -> inventoryCount(inventory, normalizeItemId(id));
        };
    }

    private JsonObject nearbyUsefulBlocksJson(ClientLevel level, LocalPlayer player) {
        JsonObject root = new JsonObject();
        root.add("logs", findAnyBlocksJson(level, player.blockPosition(), logBlockIds(), 24, 8));
        root.add("craftingTables", findBlocksJson(level, player.blockPosition(), "minecraft:crafting_table", 24, 4));
        root.add("furnaces", findBlocksJson(level, player.blockPosition(), "minecraft:furnace", 24, 4));
        root.add("enchantingTables", findBlocksJson(level, player.blockPosition(), "minecraft:enchanting_table", 24, 4));
        return root;
    }

    private JsonObject survivalCraftingAccessJson(ClientLevel level, LocalPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("hasCraftingTableItem", inventoryCount(player.getInventory(), "minecraft:crafting_table") > 0);
        root.addProperty("nearCraftingTable", findNearestBlock(level, player.blockPosition(), "minecraft:crafting_table", 8).isPresent());
        root.addProperty("hasAccess", hasCraftingTableAccess(level, player));
        return root;
    }

    private JsonObject survivalEnchantAccessJson(ClientLevel level, LocalPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("experienceLevel", player.experienceLevel);
        root.addProperty("lapisCount", inventoryCount(player.getInventory(), "minecraft:lapis_lazuli"));
        root.addProperty("hasEnchantingTableItem", inventoryCount(player.getInventory(), "minecraft:enchanting_table") > 0);
        root.addProperty("nearEnchantingTable", findNearestBlock(level, player.blockPosition(), "minecraft:enchanting_table", 16).isPresent());
        root.addProperty("nearBookshelves", findBlocksJson(level, player.blockPosition(), "minecraft:bookshelf", 8, 32).size());
        return root;
    }

    private JsonObject survivalEnchantMissingJson(LocalPlayer player, ClientLevel level, JsonObject request) {
        JsonObject missing = new JsonObject();
        int requiredLevel = clamp(getInt(request, "requiredLevel", 1), 1, 30);
        JsonArray items = new JsonArray();
        if (player.experienceLevel < requiredLevel) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "experience_level");
            entry.addProperty("needed", requiredLevel);
            entry.addProperty("available", player.experienceLevel);
            entry.addProperty("missing", requiredLevel - player.experienceLevel);
            items.add(entry);
        }
        if (inventoryCount(player.getInventory(), "minecraft:lapis_lazuli") <= 0) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "minecraft:lapis_lazuli");
            entry.addProperty("needed", 1);
            entry.addProperty("available", 0);
            entry.addProperty("missing", 1);
            items.add(entry);
        }
        if (findNearestBlock(level, player.blockPosition(), "minecraft:enchanting_table", 16).isEmpty()
            && inventoryCount(player.getInventory(), "minecraft:enchanting_table") <= 0) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "minecraft:enchanting_table_or_nearby");
            entry.addProperty("needed", 1);
            entry.addProperty("available", 0);
            entry.addProperty("missing", 1);
            items.add(entry);
        }
        missing.addProperty("ok", items.size() == 0);
        missing.addProperty("missingCount", items.size());
        missing.add("items", items);
        return missing;
    }

    private List<BlockPos> basicHousePlacements(BlockPos origin, int width, int depth, int height, boolean roof, int limit) {
        List<BlockPos> placements = new ArrayList<>();
        int doorX = width / 2;
        for (int x = 0; x < width && placements.size() < limit; x++) {
            for (int z = 0; z < depth && placements.size() < limit; z++) {
                placements.add(origin.offset(x, 0, z));
            }
        }
        for (int y = 1; y <= height && placements.size() < limit; y++) {
            for (int x = 0; x < width && placements.size() < limit; x++) {
                for (int z = 0; z < depth && placements.size() < limit; z++) {
                    boolean wall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    boolean door = z == 0 && x == doorX && (y == 1 || y == 2);
                    if (wall && !door) {
                        placements.add(origin.offset(x, y, z));
                    }
                }
            }
        }
        if (roof) {
            for (int x = 0; x < width && placements.size() < limit; x++) {
                for (int z = 0; z < depth && placements.size() < limit; z++) {
                    placements.add(origin.offset(x, height + 1, z));
                }
            }
        }
        return placements;
    }

    private String resolveToolItemId(JsonObject request) {
        String explicit = normalizeItemId(getString(request, "itemId", getString(request, "tool", "")));
        if (!explicit.isBlank() && explicit.contains("_")) {
            return explicit;
        }
        String tier = getString(request, "tier", "wooden").trim().toLowerCase(Locale.ROOT);
        String type = getString(request, "type", explicit.isBlank() ? "pickaxe" : explicit.replace("minecraft:", "")).trim().toLowerCase(Locale.ROOT);
        return normalizeItemId(tier + "_" + type);
    }

    private boolean needsWoodParts(String itemId) {
        return needsSticks(itemId)
            || "minecraft:crafting_table".equals(itemId)
            || "minecraft:chest".equals(itemId)
            || "minecraft:barrel".equals(itemId)
            || itemId.endsWith("_door")
            || itemId.endsWith("_trapdoor")
            || itemId.endsWith("_fence")
            || itemId.endsWith("_boat")
            || "minecraft:shield".equals(itemId)
            || "minecraft:bookshelf".equals(itemId);
    }

    private boolean needsSticks(String itemId) {
        return itemId.endsWith("_pickaxe") || itemId.endsWith("_axe") || itemId.endsWith("_shovel") || itemId.endsWith("_sword") || itemId.endsWith("_hoe")
            || "minecraft:torch".equals(itemId) || "minecraft:ladder".equals(itemId)
            || "minecraft:bow".equals(itemId) || "minecraft:fishing_rod".equals(itemId)
            || "minecraft:arrow".equals(itemId) || "minecraft:lever".equals(itemId)
            || "minecraft:redstone_torch".equals(itemId);
    }

    private boolean requiresCraftingTable(String itemId) {
        return itemId.endsWith("_pickaxe") || itemId.endsWith("_axe") || itemId.endsWith("_hoe")
            || itemId.endsWith("_helmet") || itemId.endsWith("_chestplate") || itemId.endsWith("_leggings") || itemId.endsWith("_boots")
            || itemId.endsWith("_door") || itemId.endsWith("_trapdoor") || itemId.endsWith("_fence")
            || itemId.endsWith("_boat") || itemId.endsWith("_stairs")
            || "minecraft:chest".equals(itemId) || "minecraft:barrel".equals(itemId) || "minecraft:furnace".equals(itemId)
            || "minecraft:blast_furnace".equals(itemId) || "minecraft:smoker".equals(itemId) || "minecraft:campfire".equals(itemId)
            || "minecraft:ladder".equals(itemId) || "minecraft:bed".equals(itemId) || itemId.endsWith("_bed")
            || "minecraft:shield".equals(itemId) || "minecraft:bow".equals(itemId) || "minecraft:fishing_rod".equals(itemId)
            || "minecraft:bucket".equals(itemId) || "minecraft:compass".equals(itemId) || "minecraft:clock".equals(itemId)
            || "minecraft:bookshelf".equals(itemId) || "minecraft:enchanting_table".equals(itemId) || "minecraft:anvil".equals(itemId)
            || "minecraft:repeater".equals(itemId);
    }

    private String materialForTier(String tier) {
        return switch (tier) {
            case "wooden" -> "any_planks";
            case "stone" -> "minecraft:cobblestone";
            case "iron" -> "minecraft:iron_ingot";
            case "golden" -> "minecraft:gold_ingot";
            case "diamond" -> "minecraft:diamond";
            case "netherite" -> "minecraft:netherite_ingot";
            default -> "any_planks";
        };
    }

    private String armorMaterialForItem(String itemId) {
        String id = normalizeItemId(itemId);
        if (id.startsWith("minecraft:leather_")) {
            return "minecraft:leather";
        }
        if (id.startsWith("minecraft:chainmail_")) {
            return "minecraft:iron_ingot";
        }
        if (id.startsWith("minecraft:iron_")) {
            return "minecraft:iron_ingot";
        }
        if (id.startsWith("minecraft:golden_")) {
            return "minecraft:gold_ingot";
        }
        if (id.startsWith("minecraft:diamond_")) {
            return "minecraft:diamond";
        }
        if (id.startsWith("minecraft:netherite_")) {
            return "minecraft:netherite_ingot";
        }
        return "minecraft:leather";
    }

    private int armorPieceCount(String itemId) {
        String id = normalizeItemId(itemId);
        if (id.endsWith("_helmet")) {
            return 5;
        }
        if (id.endsWith("_chestplate")) {
            return 8;
        }
        if (id.endsWith("_leggings")) {
            return 7;
        }
        if (id.endsWith("_boots")) {
            return 4;
        }
        return 1;
    }

    private String preferredPlanksForInventory(Inventory inventory) {
        for (String plank : plankItemIds()) {
            if (inventoryCount(inventory, plank) > 0) {
                return plank;
            }
        }
        for (String log : logBlockIds()) {
            if (inventoryCount(inventory, log) > 0) {
                return planksForLogId(log);
            }
        }
        return "minecraft:oak_planks";
    }

    private String planksForLogId(String logId) {
        String id = normalizeItemId(logId);
        if ("minecraft:crimson_stem".equals(id)) {
            return "minecraft:crimson_planks";
        }
        if ("minecraft:warped_stem".equals(id)) {
            return "minecraft:warped_planks";
        }
        int suffix = id.indexOf("_log");
        if (suffix > "minecraft:".length()) {
            return id.substring(0, suffix) + "_planks";
        }
        return "minecraft:oak_planks";
    }

    private boolean hasCraftingTableAccess(ClientLevel level, LocalPlayer player) {
        return inventoryCount(player.getInventory(), "minecraft:crafting_table") > 0
            || findNearestBlock(level, player.blockPosition(), "minecraft:crafting_table", 16).isPresent();
    }

    private int inventoryCount(Inventory inventory, String itemId) {
        return inventoryItemCounts(inventory).getOrDefault(normalizeItemId(itemId), 0);
    }

    private int inventoryCountAny(Inventory inventory, List<String> itemIds) {
        Map<String, Integer> counts = inventoryItemCounts(inventory);
        int total = 0;
        for (String itemId : itemIds) {
            total += counts.getOrDefault(normalizeItemId(itemId), 0);
        }
        return total;
    }

    private List<String> logBlockIds() {
        return List.of(
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log",
            "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
            "minecraft:pale_oak_log", "minecraft:crimson_stem", "minecraft:warped_stem"
        );
    }

    private List<String> plankItemIds() {
        return List.of(
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
            "minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
            "minecraft:pale_oak_planks", "minecraft:crimson_planks", "minecraft:warped_planks"
        );
    }

    private List<String> woodenSlabItemIds() {
        return List.of(
            "minecraft:oak_slab", "minecraft:spruce_slab", "minecraft:birch_slab", "minecraft:jungle_slab",
            "minecraft:acacia_slab", "minecraft:dark_oak_slab", "minecraft:mangrove_slab", "minecraft:cherry_slab",
            "minecraft:pale_oak_slab", "minecraft:crimson_slab", "minecraft:warped_slab"
        );
    }

    private List<String> woolItemIds() {
        return List.of(
            "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool", "minecraft:light_blue_wool",
            "minecraft:yellow_wool", "minecraft:lime_wool", "minecraft:pink_wool", "minecraft:gray_wool",
            "minecraft:light_gray_wool", "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
            "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool", "minecraft:black_wool"
        );
    }

    private Optional<BlockPos> findNearestAnyBlock(ClientLevel level, BlockPos center, List<String> ids, int radius) {
        Set<String> normalized = new HashSet<>();
        for (String id : ids) {
            normalized.add(normalizeBlockId(id));
        }
        List<BlockPos> matches = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockPos immutable = pos.immutable();
            if (normalized.contains(blockIdAt(level, immutable))) {
                matches.add(immutable);
            }
        }
        matches.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
        return matches.stream().findFirst();
    }

    private JsonArray findAnyBlocksJson(ClientLevel level, BlockPos center, List<String> ids, int radius, int limit) {
        JsonArray results = new JsonArray();
        Set<String> normalized = new HashSet<>();
        for (String id : ids) {
            normalized.add(normalizeBlockId(id));
        }
        List<BlockPos> matches = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockPos immutable = pos.immutable();
            if (normalized.contains(blockIdAt(level, immutable))) {
                matches.add(immutable);
            }
        }
        matches.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
        for (BlockPos pos : matches) {
            if (results.size() >= limit) {
                break;
            }
            JsonObject entry = blockJson(level, pos, null);
            entry.addProperty("distance", Math.sqrt(pos.distToCenterSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D)));
            results.add(entry);
        }
        return results;
    }

    private List<BlockPos> collectVerticalLogs(ClientLevel level, BlockPos base, int maxLogs) {
        List<BlockPos> logs = new ArrayList<>();
        String baseId = blockIdAt(level, base);
        BlockPos cursor = base;
        while (logs.size() < maxLogs && baseId.equals(blockIdAt(level, cursor))) {
            logs.add(cursor);
            cursor = cursor.above();
        }
        return logs;
    }

    private String blockIdAt(ClientLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    private JsonObject taskStep(String action) {
        JsonObject step = new JsonObject();
        step.addProperty("action", action);
        return step;
    }

    private JsonObject waitForActionIdleStep(int timeoutMs) {
        JsonObject step = taskStep("waitForActionIdle");
        step.addProperty("timeoutMs", timeoutMs);
        return step;
    }

    private JsonObject waitForScreenStep(String screen, int timeoutMs) {
        JsonObject step = taskStep("waitForScreen");
        step.addProperty("screen", screen);
        step.addProperty("timeoutMs", timeoutMs);
        return step;
    }

    private JsonArray taskStepsJson(List<JsonObject> steps) {
        JsonArray array = new JsonArray();
        for (JsonObject step : steps) {
            array.add(step.deepCopy());
        }
        return array;
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
        taskState = TaskState.running(
            taskId,
            steps,
            getString(request, "name", "task"),
            getBoolean(request, "autoRecoverStuck", true),
            clamp(getInt(request, "maxAutoRecoveries", 1), 0, 8)
        );
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
            case "containerQuickMoveItem" -> runContainerQuickMoveItemTask(client, stepRequest);
            case "containerClickRole" -> runContainerClickRoleTask(client, stepRequest);
            case "containerButton" -> runContainerButtonTask(client, stepRequest);
            case "refillHotbar" -> runRefillHotbarTask(client, stepRequest);
            case "openNearbyCraftingTable" -> runOpenNearbyCraftingTableTask(client, stepRequest);
            case "openNearbyContainer" -> runOpenNearbyContainerTask(client, stepRequest);
            case "containerTransferProcess" -> runContainerTransferProcessTask(client, stepRequest);
            case "containerTransferProcessAutoRepair" -> runContainerTransferProcessAutoRepairTask(client, stepRequest);
            case "craftInventoryProcess" -> runCraftInventoryProcessTask(client, stepRequest);
            case "craftInventoryProcessAutoRepair" -> runCraftInventoryProcessAutoRepairTask(client, stepRequest);
            case "craftTableProcess" -> runCraftTableProcessTask(client, stepRequest);
            case "craftTableProcessAutoRepair" -> runCraftTableProcessAutoRepairTask(client, stepRequest);
            case "advancedPathProcess" -> runGeneratedSurvivalProcessTask(client, "advancedPathProcess", survivalAdvancedPathSteps(client, stepRequest));
            case "recoverProcess" -> runGeneratedSurvivalProcessTask(client, "recoverProcess", survivalRecoverSteps(client, stepRequest));
            case "smeltProcess" -> runGeneratedSurvivalProcessTask(client, "smeltProcess", survivalSmeltSteps(client, stepRequest));
            case "storageOrganizeProcess" -> runGeneratedSurvivalProcessTask(client, "storageOrganizeProcess", storageOrganizeSteps(client, stepRequest));
            case "buildTemplateProcess" -> runGeneratedSurvivalProcessTask(client, "buildTemplateProcess", buildTemplateSteps(client, stepRequest).steps());
            case "experienceProcess" -> runGeneratedSurvivalProcessTask(client, "experienceProcess", survivalExperienceSteps(client, stepRequest));
            case "combatProcess" -> runGeneratedSurvivalProcessTask(client, "combatProcess", survivalCombatSteps(client, stepRequest));
            case "farmProcess" -> runGeneratedSurvivalProcessTask(client, "farmProcess", survivalFarmSteps(client, stepRequest));
            case "mineProcess" -> runGeneratedSurvivalProcessTask(client, "mineProcess", survivalMineSteps(client, stepRequest));
            case "lightProcess" -> runGeneratedSurvivalProcessTask(client, "lightProcess", survivalLightSteps(client, stepRequest));
            case "sleepProcess" -> runGeneratedSurvivalProcessTask(client, "sleepProcess", survivalSleepSteps(client, stepRequest));
            case "placeWorkstationProcess" -> runGeneratedSurvivalProcessTask(client, "placeWorkstationProcess", survivalPlaceWorkstationSteps(client, stepRequest));
            case "dimensionProcess" -> runGeneratedSurvivalProcessTask(client, "dimensionProcess", survivalDimensionSteps(client, stepRequest));
            case "redstoneProcess" -> runGeneratedSurvivalProcessTask(client, "redstoneProcess", survivalRedstoneSteps(client, stepRequest));
            case "tradeProcess" -> runGeneratedSurvivalProcessTask(client, "tradeProcess", survivalTradeSteps(client, stepRequest));
            case "tradeSelectProcess" -> runGeneratedSurvivalProcessTask(client, "tradeSelectProcess", survivalTradeSelectSteps(client, stepRequest));
            case "fishProcess" -> runGeneratedSurvivalProcessTask(client, "fishProcess", survivalFishSteps(client, stepRequest));
            case "brewProcess" -> runGeneratedSurvivalProcessTask(client, "brewProcess", survivalBrewSteps(client, stepRequest));
            case "anvilProcess" -> runGeneratedSurvivalProcessTask(client, "anvilProcess", survivalAnvilSteps(client, stepRequest));
            case "anvilApplyProcess" -> runGeneratedSurvivalProcessTask(client, "anvilApplyProcess", survivalAnvilApplySteps(client, stepRequest));
            case "exploreProcess" -> runGeneratedSurvivalProcessTask(client, "exploreProcess", survivalExploreSteps(client, stepRequest));
            case "craftToolProcess" -> runGeneratedSurvivalProcessTask(client, "craftToolProcess", survivalCraftToolSteps(client, stepRequest));
            case "craftMaterialProcess" -> runGeneratedSurvivalProcessTask(client, "craftMaterialProcess", survivalCraftMaterialSteps(client, stepRequest));
            case "chopTreeProcess" -> runGeneratedSurvivalProcessTask(client, "chopTreeProcess", survivalChopTreeSteps(client, stepRequest));
            case "digProcess" -> runGeneratedSurvivalProcessTask(client, "digProcess", survivalDigSteps(client, stepRequest));
            case "buildProcess" -> runGeneratedSurvivalProcessTask(client, "buildProcess", survivalBuildSteps(client, stepRequest).steps());
            case "enchantPrepareProcess" -> runGeneratedSurvivalProcessTask(client, "enchantPrepareProcess", survivalEnchantSteps(client, stepRequest));
            case "enchantApplyProcess" -> runGeneratedSurvivalProcessTask(client, "enchantApplyProcess", survivalEnchantApplySteps(client, stepRequest));
            case "lookAt" -> runActionLookAtTask(client, stepRequest);
            case "move" -> runActionMoveTask(stepRequest);
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

    private JsonObject runContainerQuickMoveItemTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", ""));
        int maxMoves = clamp(getInt(request, "count", 1), 1, 64);
        boolean containerOnly = getBoolean(request, "containerOnly", false);
        boolean inventoryOnly = getBoolean(request, "inventoryOnly", false);
        AbstractContainerMenu menu = player.containerMenu;
        JsonArray moves = new JsonArray();

        for (int moveIndex = 0; moveIndex < maxMoves; moveIndex++) {
            int slotIndex = findContainerSlotByItem(player, menu, itemId, containerOnly, inventoryOnly);
            if (slotIndex < 0) {
                break;
            }
            Slot slot = menu.getSlot(slotIndex);
            ItemStack before = slot.getItem().copy();
            gameMode.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, player);
            menu.broadcastChanges();
            JsonObject move = new JsonObject();
            move.addProperty("slot", slotIndex);
            move.add("before", itemJson(before));
            move.add("after", itemJson(slot.getItem()));
            moves.add(move);
        }

        JsonObject root = new JsonObject();
        root.addProperty("ok", moves.size() > 0);
        root.addProperty("itemId", itemId);
        root.addProperty("requestedMoves", maxMoves);
        root.add("moves", moves);
        root.add("container", currentContainerJson(player));
        if (moves.size() == 0) {
            root.addProperty("message", "No matching item slot was found for quick move.");
        }
        return root;
    }

    private JsonObject runContainerClickRoleTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String role = getString(request, "role", "").trim();
        int slot = semanticSlotIndex(player.containerMenu, role, getInt(request, "index", 0));
        if (slot < 0 || slot >= player.containerMenu.slots.size()) {
            JsonObject root = errorJson("RoleNotFound", "No slot role '" + role + "' was mapped for the current container.");
            root.add("semantic", currentContainerSemanticJson(player));
            return root;
        }

        int button = clamp(getInt(request, "button", 0), 0, 8);
        ContainerInput input = parseContainerInput(getString(request, "mode", "pickup"));
        gameMode.handleContainerInput(player.containerMenu.containerId, slot, button, input, player);
        player.containerMenu.broadcastChanges();

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("role", role);
        root.addProperty("slot", slot);
        root.addProperty("button", button);
        root.addProperty("mode", input.name().toLowerCase(Locale.ROOT));
        root.add("semantic", currentContainerSemanticJson(player));
        root.add("container", currentContainerJson(player));
        return root;
    }

    private JsonObject runContainerButtonTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        int buttonId = clamp(getInt(request, "buttonId", getInt(request, "button", 0)), 0, 128);
        gameMode.handleInventoryButtonClick(player.containerMenu.containerId, buttonId);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("buttonId", buttonId);
        root.addProperty("containerId", player.containerMenu.containerId);
        root.add("semantic", currentContainerSemanticJson(player));
        root.add("container", currentContainerJson(player));
        return root;
    }

    private JsonObject runRefillHotbarTask(Minecraft client, JsonObject request) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return errorJson("PlayerNotReady", "Enter a world first.");
        }

        String itemId = normalizeItemId(getString(request, "itemId", getString(request, "blockId", "")));
        int hotbarSlot = clamp(getInt(request, "hotbarSlot", player.getInventory().getSelectedSlot()), 0, 8);
        if (itemId.isBlank()) {
            ItemStack selected = player.getInventory().getItem(hotbarSlot);
            if (!selected.isEmpty()) {
                itemId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
            }
        }
        if (itemId.isBlank()) {
            return errorJson("InvalidItem", "itemId/blockId or a non-empty target hotbar slot is required.");
        }

        AbstractContainerMenu menu = player.inventoryMenu;
        int sourceMenuSlot = findInventoryMenuSlotByItem(player, menu, itemId, 9, player.getInventory().getContainerSize());
        if (sourceMenuSlot < 0) {
            return errorJson("ItemNotFound", "No matching item found outside the target hotbar.");
        }

        ItemStack before = menu.getSlot(sourceMenuSlot).getItem().copy();
        gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, hotbarSlot, ContainerInput.SWAP, player);
        menu.broadcastChanges();
        player.getInventory().setSelectedSlot(hotbarSlot);

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("itemId", itemId);
        root.addProperty("sourceMenuSlot", sourceMenuSlot);
        root.addProperty("hotbarSlot", hotbarSlot);
        root.add("moved", itemJson(before));
        root.add("selected", itemJson(player.getInventory().getSelectedItem()));
        root.add("inventory", inventoryJson(player));
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

    private JsonObject runActionMoveTask(JsonObject request) {
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
        root.addProperty("processMode", "player_like");
        root.addProperty("durationMs", durationMs);
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

    private JsonObject currentContainerSemanticJson(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        JsonObject root = new JsonObject();
        String type = menuTypeName(menu);
        root.addProperty("type", type);
        root.addProperty("menuClass", menu.getClass().getName());
        root.addProperty("containerId", menu.containerId);
        root.addProperty("slotCount", menu.slots.size());
        root.add("screen", screenMetadataJson(Minecraft.getInstance().screen));
        JsonObject roles = semanticRolesJson(menu);
        root.add("roles", roles);
        root.add("roleSlots", semanticRoleSlotsJson(player, menu, roles));
        root.add("playerInventory", playerInventorySlotRangesJson(player, menu));
        root.addProperty("notes", "Use /container/clickRole with a role name, or /container/button for menu buttons such as enchant options.");
        return root;
    }

    private JsonObject screenMetadataJson(Screen screen) {
        JsonObject root = new JsonObject();
        root.addProperty("name", screenName(screen));
        root.addProperty("open", screen != null);
        if (screen != null) {
            root.addProperty("class", screen.getClass().getName());
            root.addProperty("title", screen.getTitle().getString());
            root.addProperty("isContainerScreen", screen instanceof AbstractContainerScreen<?>);
        }
        return root;
    }

    private JsonObject semanticRolesJson(AbstractContainerMenu menu) {
        JsonObject roles = new JsonObject();
        String type = menuTypeName(menu).toLowerCase(Locale.ROOT);
        int size = menu.slots.size();

        if (type.contains("furnace") || type.contains("smoker") || type.contains("blast")) {
            addRole(roles, "input", 0, size);
            addRole(roles, "fuel", 1, size);
            addRole(roles, "result", 2, size);
        } else if (type.contains("brewing")) {
            addRole(roles, "bottle_0", 0, size);
            addRole(roles, "bottle_1", 1, size);
            addRole(roles, "bottle_2", 2, size);
            addRole(roles, "ingredient", 3, size);
            addRole(roles, "fuel", 4, size);
        } else if (type.contains("anvil")) {
            addRole(roles, "left", 0, size);
            addRole(roles, "right", 1, size);
            addRole(roles, "result", 2, size);
        } else if (type.contains("enchant")) {
            addRole(roles, "item", 0, size);
            addRole(roles, "lapis", 1, size);
            JsonArray buttons = new JsonArray();
            buttons.add(buttonRoleJson("option_0", 0));
            buttons.add(buttonRoleJson("option_1", 1));
            buttons.add(buttonRoleJson("option_2", 2));
            roles.add("buttons", buttons);
        } else if (type.contains("merchant") || type.contains("trade")) {
            addRole(roles, "buy_a", 0, size);
            addRole(roles, "buy_b", 1, size);
            addRole(roles, "result", 2, size);
            JsonArray buttons = new JsonArray();
            for (int i = 0; i < 16; i++) {
                buttons.add(buttonRoleJson("trade_" + i, i));
            }
            roles.add("buttons", buttons);
        } else if (type.contains("crafting")) {
            addRole(roles, "result", 0, size);
            for (int i = 1; i <= 9; i++) {
                addRole(roles, "grid_" + i, i, size);
            }
        } else if (type.contains("inventory")) {
            addRole(roles, "craft_result", 0, size);
            addRole(roles, "craft_1", 1, size);
            addRole(roles, "craft_2", 2, size);
            addRole(roles, "craft_3", 3, size);
            addRole(roles, "craft_4", 4, size);
        }

        return roles;
    }

    private JsonArray semanticRoleSlotsJson(LocalPlayer player, AbstractContainerMenu menu, JsonObject roles) {
        JsonArray slots = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : roles.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            int slotIndex = entry.getValue().getAsInt();
            if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
                continue;
            }
            Slot slot = menu.getSlot(slotIndex);
            JsonObject item = new JsonObject();
            item.addProperty("role", entry.getKey());
            item.addProperty("slot", slotIndex);
            item.addProperty("inventoryIndex", slot.index);
            item.addProperty("isPlayerInventory", slot.container == player.getInventory());
            item.addProperty("hasItem", slot.hasItem());
            if (slot.hasItem()) {
                item.add("item", itemJson(slot.getItem()));
            }
            slots.add(item);
        }
        return slots;
    }

    private JsonObject playerInventorySlotRangesJson(LocalPlayer player, AbstractContainerMenu menu) {
        JsonObject root = new JsonObject();
        JsonArray hotbar = new JsonArray();
        JsonArray main = new JsonArray();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("menuSlot", i);
            entry.addProperty("inventoryIndex", slot.index);
            if (slot.index >= 0 && slot.index < 9) {
                hotbar.add(entry);
            } else {
                main.add(entry);
            }
        }
        root.add("hotbar", hotbar);
        root.add("main", main);
        return root;
    }

    private void addRole(JsonObject roles, String role, int slot, int size) {
        if (slot >= 0 && slot < size) {
            roles.addProperty(role, slot);
        }
    }

    private JsonObject buttonRoleJson(String role, int buttonId) {
        JsonObject root = new JsonObject();
        root.addProperty("role", role);
        root.addProperty("buttonId", buttonId);
        return root;
    }

    private int semanticSlotIndex(AbstractContainerMenu menu, String role, int index) {
        JsonObject roles = semanticRolesJson(menu);
        String key = role == null ? "" : role.trim();
        if (roles.has(key) && roles.get(key).isJsonPrimitive()) {
            return roles.get(key).getAsInt();
        }
        if ("bottle".equals(key)) {
            String bottleKey = "bottle_" + clamp(index, 0, 2);
            return roles.has(bottleKey) ? roles.get(bottleKey).getAsInt() : -1;
        }
        if ("grid".equals(key)) {
            String gridKey = "grid_" + clamp(index, 1, 9);
            return roles.has(gridKey) ? roles.get(gridKey).getAsInt() : -1;
        }
        return -1;
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

    private int findContainerSlotByItem(LocalPlayer player, AbstractContainerMenu menu, String itemId, boolean containerOnly, boolean inventoryOnly) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.hasItem()) {
                continue;
            }
            boolean playerSlot = slot.container == player.getInventory();
            if (containerOnly && playerSlot) {
                continue;
            }
            if (inventoryOnly && !playerSlot) {
                continue;
            }
            String currentId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
            if (itemId.isBlank() || currentId.equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    private int findInventoryMenuSlotByItem(LocalPlayer player, AbstractContainerMenu menu, String itemId, int inventoryStartInclusive, int inventoryEndExclusive) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory() || !slot.hasItem()) {
                continue;
            }
            int inventoryIndex = slot.index;
            if (inventoryIndex < inventoryStartInclusive || inventoryIndex >= inventoryEndExclusive) {
                continue;
            }
            String currentId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
            if (currentId.equals(itemId)) {
                return i;
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
        private final boolean autoRecoverStuck;
        private final int maxAutoRecoveries;
        private final String message;
        private int currentStepIndex;
        private int autoRecoveriesUsed;
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

        private TaskState(
            String taskId,
            String name,
            List<JsonObject> steps,
            boolean running,
            boolean completed,
            boolean cancelled,
            boolean autoRecoverStuck,
            int maxAutoRecoveries,
            String message
        ) {
            this.taskId = taskId;
            this.name = name;
            this.startedAtMs = System.currentTimeMillis();
            this.steps = steps == null ? List.of() : new ArrayList<>(steps);
            this.results = new JsonArray();
            this.running = running;
            this.completed = completed;
            this.cancelled = cancelled;
            this.autoRecoverStuck = autoRecoverStuck;
            this.maxAutoRecoveries = Math.max(0, maxAutoRecoveries);
            this.message = message;
            this.currentStepIndex = 0;
            this.autoRecoveriesUsed = 0;
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
            return new TaskState("", "idle", List.of(), false, false, false, false, 0, "idle");
        }

        static TaskState running(String taskId, List<JsonObject> steps, String name) {
            return running(taskId, steps, name, true, 1);
        }

        static TaskState running(String taskId, List<JsonObject> steps, String name, boolean autoRecoverStuck, int maxAutoRecoveries) {
            return new TaskState(taskId, name, steps, true, false, false, autoRecoverStuck, maxAutoRecoveries, "running");
        }

        TaskState cancelled(String message) {
            TaskState next = copyWith(false, false, true, message);
            next.currentStepIndex = this.currentStepIndex;
            next.autoRecoveriesUsed = this.autoRecoveriesUsed;
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
            next.autoRecoveriesUsed = this.autoRecoveriesUsed;
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
            next.autoRecoveriesUsed = this.autoRecoveriesUsed;
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
            return new TaskState(taskId, name, steps, running, completed, cancelled, autoRecoverStuck, maxAutoRecoveries, message);
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

        boolean autoRecoverStuck() {
            return autoRecoverStuck;
        }

        boolean canAutoRecover() {
            return autoRecoveriesUsed < maxAutoRecoveries;
        }

        int noteAutoRecovery() {
            autoRecoveriesUsed++;
            return autoRecoveriesUsed;
        }

        int maxAutoRecoveries() {
            return maxAutoRecoveries;
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
            root.addProperty("autoRecoverStuck", autoRecoverStuck);
            root.addProperty("autoRecoveriesUsed", autoRecoveriesUsed);
            root.addProperty("maxAutoRecoveries", maxAutoRecoveries);
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
        private double bestProgressDistance;
        private long lastProgressAtMs;
        private boolean stuck;
        private String stuckReason;

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
            this.bestProgressDistance = Double.MAX_VALUE;
            this.lastProgressAtMs = this.startedAtMs;
            this.stuck = false;
            this.stuckReason = "";
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
            bestProgressDistance = Double.MAX_VALUE;
            lastProgressAtMs = System.currentTimeMillis();
            stuck = false;
            stuckReason = "";
        }

        long startedAtMs() {
            return startedAtMs;
        }

        void observeProgress(double distance, String reason, long timeoutMs) {
            long now = System.currentTimeMillis();
            if (distance + 0.05D < bestProgressDistance) {
                bestProgressDistance = distance;
                lastProgressAtMs = now;
                stuck = false;
                stuckReason = "";
                return;
            }
            if (now - lastProgressAtMs > timeoutMs) {
                markStuck(reason);
            }
        }

        void markStuck(String reason) {
            this.stuck = true;
            this.stuckReason = reason == null ? "stuck" : reason;
        }

        boolean stuck() {
            return stuck;
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
            root.addProperty("stuck", stuck);
            root.addProperty("stuckReason", stuckReason);
            if (stuck) {
                root.addProperty("recoveryHint", "Call /survival/recover or /survival/advancedPath with start:true.");
            }

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

    private record SurvivalBuildPlan(List<JsonObject> steps, BlockPos origin, int blockCount, JsonArray placements, JsonObject missing) {
    }

    private record BuildPlacement(BlockPos pos, String blockId) {
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
