# Player Probe Fabric Mod

<p align="center">
  <img src="https://count.getloli.com/@minecraft-playerprobe-ai-mod?theme=minecraft&padding=7&offset=0&align=top&scale=1&pixelated=1&darkmode=auto" alt="Counter">
</p>

Client-only Fabric mod for Minecraft `26.1.2-Fabric`.

Project wiki source pages are kept locally in `docs/wiki/` and are intentionally
not tracked or pushed to GitHub.

## Support

If this project helps you, you can support it here:

- PayPal: https://www.paypal.com/paypalme/HWSLandDFTX8

Payment QR codes:

<p>
  <img src="docs/assets/payment-qr-1.png" alt="Payment QR code 1" width="240">
  <img src="docs/assets/payment-qr-2.png" alt="Payment QR code 2" width="240">
</p>

The mod starts a local HTTP server when the Minecraft client starts:

- `GET http://127.0.0.1:8765/health`
- `GET http://127.0.0.1:8765/status`
- `GET http://127.0.0.1:8765/player`
- `GET http://127.0.0.1:8765/blocks?radius=2`
- `GET http://127.0.0.1:8765/raycast?distance=6`
- `GET http://127.0.0.1:8765/entities?radius=12&limit=32`
- `GET http://127.0.0.1:8765/inventory`
- `GET http://127.0.0.1:8765/recipes?item=minecraft:crafting_table&limit=16`
- `GET http://127.0.0.1:8765/snapshot?radius=2`
- `GET http://127.0.0.1:8765/watch?radius=2&intervalMs=1000`
- `GET http://127.0.0.1:8765/menu/status`
- `GET/POST http://127.0.0.1:8765/menu/back`
- `GET/POST http://127.0.0.1:8765/menu/open/title`
- `GET/POST http://127.0.0.1:8765/menu/open/singleplayer`
- `GET/POST http://127.0.0.1:8765/menu/open/createWorld`
- `GET http://127.0.0.1:8765/menu/worlds`
- `GET/POST http://127.0.0.1:8765/menu/worlds/detail`
- `POST http://127.0.0.1:8765/menu/worlds/create`
- `POST http://127.0.0.1:8765/menu/worlds/delete`
- `POST http://127.0.0.1:8765/menu/worlds/rename`
- `POST http://127.0.0.1:8765/menu/worlds/backup`
- `POST http://127.0.0.1:8765/menu/worlds/leave`
- `POST http://127.0.0.1:8765/menu/worlds/load`
- `POST http://127.0.0.1:8765/menu/worlds/switch`
- `GET http://127.0.0.1:8765/action/status`
- `POST http://127.0.0.1:8765/action/look`
- `POST http://127.0.0.1:8765/action/lookAt`
- `POST http://127.0.0.1:8765/action/move`
- `GET/POST http://127.0.0.1:8765/action/planPath`
- `POST http://127.0.0.1:8765/action/goto`
- `GET/POST http://127.0.0.1:8765/action/findBlock`
- `GET/POST http://127.0.0.1:8765/action/findBlocks`
- `POST http://127.0.0.1:8765/action/gotoBlock`
- `POST http://127.0.0.1:8765/action/mineBlock`
- `POST http://127.0.0.1:8765/action/attackEntity`
- `POST http://127.0.0.1:8765/action/interact`
- `POST http://127.0.0.1:8765/action/useItem`
- `POST http://127.0.0.1:8765/action/placeBlock`
- `POST http://127.0.0.1:8765/action/pickupItems`
- `POST http://127.0.0.1:8765/craft`
- `POST http://127.0.0.1:8765/task/start`
- `GET http://127.0.0.1:8765/task/status`
- `GET/POST http://127.0.0.1:8765/task/cancel`
- `GET/POST http://127.0.0.1:8765/inventory/find`
- `GET http://127.0.0.1:8765/inventory/knowledge`
- `POST http://127.0.0.1:8765/inventory/selectHotbar`
- `POST http://127.0.0.1:8765/inventory/equipBest`
- `POST http://127.0.0.1:8765/inventory/open`
- `POST http://127.0.0.1:8765/inventory/click`
- `POST http://127.0.0.1:8765/inventory/drop`
- `GET http://127.0.0.1:8765/container`
- `POST http://127.0.0.1:8765/container/transfer`
- `POST http://127.0.0.1:8765/container/moveToSlot`
- `GET http://127.0.0.1:8765/container/semantic`
- `POST http://127.0.0.1:8765/container/clickRole`
- `POST http://127.0.0.1:8765/container/button`
- `POST http://127.0.0.1:8765/container/text`
- `GET/POST http://127.0.0.1:8765/craft/check`
- `GET http://127.0.0.1:8765/survival/status`
- `GET/POST http://127.0.0.1:8765/survival/missing`
- `POST http://127.0.0.1:8765/survival/craftTool`
- `POST http://127.0.0.1:8765/survival/craftMaterial`
- `POST http://127.0.0.1:8765/survival/chopTree`
- `POST http://127.0.0.1:8765/survival/dig`
- `POST http://127.0.0.1:8765/survival/build`
- `POST http://127.0.0.1:8765/survival/enchant`
- `POST http://127.0.0.1:8765/survival/enchantApply`
- `POST http://127.0.0.1:8765/survival/advancedPath`
- `POST http://127.0.0.1:8765/survival/recover`
- `POST http://127.0.0.1:8765/survival/smelt`
- `POST http://127.0.0.1:8765/survival/experience`
- `POST http://127.0.0.1:8765/survival/combat`
- `POST http://127.0.0.1:8765/survival/decision`
- `POST http://127.0.0.1:8765/survival/farm`
- `POST http://127.0.0.1:8765/survival/mine`
- `POST http://127.0.0.1:8765/survival/light`
- `POST http://127.0.0.1:8765/survival/sleep`
- `POST http://127.0.0.1:8765/survival/placeWorkstation`
- `POST http://127.0.0.1:8765/survival/dimension`
- `POST http://127.0.0.1:8765/survival/redstone`
- `POST http://127.0.0.1:8765/survival/trade`
- `POST http://127.0.0.1:8765/survival/tradeSelect`
- `POST http://127.0.0.1:8765/survival/fish`
- `POST http://127.0.0.1:8765/survival/brew`
- `POST http://127.0.0.1:8765/survival/anvil`
- `POST http://127.0.0.1:8765/survival/anvilApply`
- `POST http://127.0.0.1:8765/survival/explore`
- `GET/POST http://127.0.0.1:8765/craft/tree`
- `POST http://127.0.0.1:8765/storage/organize`
- `POST http://127.0.0.1:8765/build/template`
- `POST http://127.0.0.1:8765/build/refillHotbar`
- `GET/POST http://127.0.0.1:8765/explore/markers`
- `GET http://127.0.0.1:8765/screen/status`
- `POST http://127.0.0.1:8765/screen/close`
- `GET http://127.0.0.1:8765/events?since=0`
- `POST http://127.0.0.1:8765/chat/send`
- `GET/POST http://127.0.0.1:8765/action/stop`

The final jar is in:

```text
finalMod/playerprobe-1.0.0-v0.3.jar
```

Put that jar into:

```text
/Users/hwslanddftx8/Library/Application Support/minecraft/versions/26.1.2-Fabric/mods
```

After entering a world, watch live data from Terminal:

```bash
./scripts/watch-playerprobe.sh 2 1000
```

Action examples:

```bash
curl http://127.0.0.1:8765/menu/status

curl -X POST http://127.0.0.1:8765/menu/open/singleplayer

curl http://127.0.0.1:8765/menu/worlds

curl 'http://127.0.0.1:8765/menu/worlds/detail?id=ai_test_world'

curl -X POST http://127.0.0.1:8765/menu/worlds/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"AI Test World","id":"ai_test_world","gameMode":"survival","difficulty":"normal","allowCommands":true,"bonusChest":false,"generateStructures":true,"preset":"normal","seed":"123456"}'

curl -X POST http://127.0.0.1:8765/menu/worlds/rename \
  -H 'Content-Type: application/json' \
  -d '{"id":"ai_test_world","newName":"AI Test World v2"}'

curl -X POST http://127.0.0.1:8765/menu/worlds/backup \
  -H 'Content-Type: application/json' \
  -d '{"id":"ai_test_world"}'

curl -X POST http://127.0.0.1:8765/menu/worlds/load \
  -H 'Content-Type: application/json' \
  -d '{"id":"ai_test_world"}'

curl -X POST http://127.0.0.1:8765/menu/worlds/leave \
  -H 'Content-Type: application/json' \
  -d '{"toTitle":false}'

curl -X POST http://127.0.0.1:8765/menu/worlds/switch \
  -H 'Content-Type: application/json' \
  -d '{"id":"ai_test_world"}'

curl -X POST http://127.0.0.1:8765/action/look \
  -H 'Content-Type: application/json' \
  -d '{"yaw":90,"pitch":0}'

curl -X POST http://127.0.0.1:8765/action/lookAt \
  -H 'Content-Type: application/json' \
  -d '{"blockPos":{"x":10,"y":64,"z":10}}'

curl -X POST http://127.0.0.1:8765/action/move \
  -H 'Content-Type: application/json' \
  -d '{"forward":true,"sprint":true,"durationMs":1200}'

curl -X POST http://127.0.0.1:8765/action/planPath \
  -H 'Content-Type: application/json' \
  -d '{"x":10,"y":64,"z":10,"radius":24}'

curl 'http://127.0.0.1:8765/action/findBlock?id=minecraft:stone&radius=16'

curl 'http://127.0.0.1:8765/action/findBlocks?id=minecraft:crafting_table&radius=24&limit=5'

curl -X POST http://127.0.0.1:8765/action/gotoBlock \
  -H 'Content-Type: application/json' \
  -d '{"id":"minecraft:stone","radius":20,"standRange":2}'

curl -X POST http://127.0.0.1:8765/action/goto \
  -H 'Content-Type: application/json' \
  -d '{"x":10,"y":64,"z":10,"radius":24}'

curl http://127.0.0.1:8765/inventory

curl http://127.0.0.1:8765/inventory/knowledge

curl 'http://127.0.0.1:8765/inventory/find?itemId=minecraft:oak_log&limit=8'

curl 'http://127.0.0.1:8765/recipes?item=minecraft:crafting_table&limit=8'

curl 'http://127.0.0.1:8765/craft/check?itemId=minecraft:oak_planks&useTable=false'

curl -X POST http://127.0.0.1:8765/inventory/selectHotbar \
  -H 'Content-Type: application/json' \
  -d '{"slot":0}'

curl -X POST http://127.0.0.1:8765/inventory/equipBest \
  -H 'Content-Type: application/json' \
  -d '{"purpose":"mine","blockPos":{"x":10,"y":64,"z":10}}'

curl -X POST http://127.0.0.1:8765/action/mineBlock \
  -H 'Content-Type: application/json' \
  -d '{"x":10,"y":64,"z":10}'

curl -X POST http://127.0.0.1:8765/action/attackEntity \
  -H 'Content-Type: application/json' \
  -d '{"entityId":123,"count":1}'

curl -X POST http://127.0.0.1:8765/action/interact \
  -H 'Content-Type: application/json' \
  -d '{"blockPos":{"x":10,"y":64,"z":10},"face":"up","hand":"main"}'

curl -X POST http://127.0.0.1:8765/action/useItem \
  -H 'Content-Type: application/json' \
  -d '{"slot":0,"hand":"main"}'

curl -X POST http://127.0.0.1:8765/action/placeBlock \
  -H 'Content-Type: application/json' \
  -d '{"blockId":"minecraft:cobblestone","x":10,"y":64,"z":10,"face":"up","lookAtFirst":true}'

curl -X POST http://127.0.0.1:8765/action/pickupItems \
  -H 'Content-Type: application/json' \
  -d '{"radius":6,"timeoutMs":5000}'

curl -X POST http://127.0.0.1:8765/craft \
  -H 'Content-Type: application/json' \
  -d '{"itemId":"minecraft:oak_planks","count":4,"useTable":false}'

curl -X POST http://127.0.0.1:8765/inventory/open

curl http://127.0.0.1:8765/container

curl http://127.0.0.1:8765/container/semantic

curl -X POST http://127.0.0.1:8765/inventory/click \
  -H 'Content-Type: application/json' \
  -d '{"slot":10,"button":0,"mode":"pickup"}'

curl -X POST http://127.0.0.1:8765/container/clickRole \
  -H 'Content-Type: application/json' \
  -d '{"role":"result","mode":"quick_move"}'

curl -X POST http://127.0.0.1:8765/container/button \
  -H 'Content-Type: application/json' \
  -d '{"buttonId":0}'

curl -X POST http://127.0.0.1:8765/container/transfer \
  -H 'Content-Type: application/json' \
  -d '{"from":0,"count":1}'

curl -X POST http://127.0.0.1:8765/inventory/drop \
  -H 'Content-Type: application/json' \
  -d '{"slot":0,"count":1}'

curl http://127.0.0.1:8765/screen/status

curl -X POST http://127.0.0.1:8765/screen/close

curl 'http://127.0.0.1:8765/events?since=0'

curl -X POST http://127.0.0.1:8765/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"text":"hello from playerprobe"}'

curl http://127.0.0.1:8765/survival/status

curl -X POST http://127.0.0.1:8765/survival/missing \
  -H 'Content-Type: application/json' \
  -d '{"goal":"build","blockId":"minecraft:oak_planks","width":5,"depth":5,"height":3,"roof":true}'

curl -X POST http://127.0.0.1:8765/survival/chopTree \
  -H 'Content-Type: application/json' \
  -d '{"radius":24,"maxLogs":8,"start":true}'

curl -X POST http://127.0.0.1:8765/survival/craftTool \
  -H 'Content-Type: application/json' \
  -d '{"tier":"wooden","type":"pickaxe","count":1,"start":true}'

curl -X POST http://127.0.0.1:8765/survival/craftMaterial \
  -H 'Content-Type: application/json' \
  -d '{"itemId":"minecraft:stick","count":1,"start":true}'

curl -X POST http://127.0.0.1:8765/survival/dig \
  -H 'Content-Type: application/json' \
  -d '{"width":3,"length":3,"depth":2,"start":true}'

curl -X POST http://127.0.0.1:8765/survival/build \
  -H 'Content-Type: application/json' \
  -d '{"blockId":"minecraft:oak_planks","width":5,"depth":5,"height":3,"roof":true,"start":false}'

curl -X POST http://127.0.0.1:8765/survival/enchant \
  -H 'Content-Type: application/json' \
  -d '{"requiredLevel":1,"radius":16,"start":false}'

curl -X POST http://127.0.0.1:8765/survival/enchantApply \
  -H 'Content-Type: application/json' \
  -d '{"itemId":"minecraft:iron_pickaxe","option":0,"start":false}'

curl -X POST http://127.0.0.1:8765/survival/smelt \
  -H 'Content-Type: application/json' \
  -d '{"inputItemId":"minecraft:raw_iron","fuelItemId":"minecraft:coal","count":1,"start":true}'

curl -X POST http://127.0.0.1:8765/build/template \
  -H 'Content-Type: application/json' \
  -d '{"template":"farm","start":false}'

curl -X POST http://127.0.0.1:8765/survival/light \
  -H 'Content-Type: application/json' \
  -d '{"torchItemId":"minecraft:torch","radius":8,"limit":6,"start":true}'

curl -X POST http://127.0.0.1:8765/survival/combat \
  -H 'Content-Type: application/json' \
  -d '{"entityType":"minecraft:zombie","radius":12,"hits":3,"start":false}'

curl 'http://127.0.0.1:8765/craft/tree?itemId=minecraft:iron_pickaxe&depth=4'

curl -X POST http://127.0.0.1:8765/task/start \
  -H 'Content-Type: application/json' \
  -d '{
    "steps":[
      {"action":"gotoBlock","id":"minecraft:crafting_table","radius":16,"standRange":2},
      {"action":"interact","blockPos":{"x":10,"y":64,"z":10},"face":"up","hand":"main"},
      {"action":"craft","itemId":"minecraft:oak_planks","count":1,"useTable":true}
    ]
  }'

curl http://127.0.0.1:8765/task/status
```

Process notes:

- `GET /status` is the unified "what is the player/mod doing now?" endpoint. It returns `activity.kind`, a readable `activity.summary`, and the current menu/task/action/player/world/container state for LLM polling.
- `craft` now follows a player-like process instead of directly mutating inventory.
- Camera requests rotate over multiple client ticks. Path movement turns toward
  each waypoint before pressing forward, so look and travel remain visible.
- Inventory crafting expects the player inventory screen to be opened and uses the real crafting grid/result slot flow.
- Crafting-table recipes expect a reachable nearby `minecraft:crafting_table`; the mod will only continue when the player is actually at the usable position and the table UI is opened.
- Survival crafting expands into visible `openInventory` / `waitForScreen` /
  `placeCraftRecipe` / `takeCraftResult` / `closeScreen` steps with short grid
  and result pauses instead of completing the whole recipe in one frame.
- Mining is limited to five-block reach and requires the requested block to be
  the raycast's first hit. Tree chopping finds a reachable clear-view standing
  position before every log and never mines through leaves or other blocks.
- Craft responses include `steps` so an LLM can inspect each stage such as menu preparation, recipe placement, and result pickup.
- Key single-action write endpoints now also return `steps` and `verify` fields so the caller can reason about process and result instead of only success/failure.
- `task/start` now creates a tracked task with `taskId`, `currentStepIndex`, `results`, and final `snapshot` state.
- `task/status` can be polled while a longer player-like process is running, and now reports whether the current step has started, whether it is waiting for an in-progress action, and the live `currentActionState`.
- `task/start` supports stuck auto-recovery with `autoRecoverStuck` and `maxAutoRecoveries`; it records inserted recovery/retry steps and the movement diagnostics used to choose them instead of hiding them from the LLM.
- `task/start` also supports process-oriented helper steps such as `retry`, `if`, `switch`, `repeat`, `repeatUntil`, `breakIf`, `continueIf`, `label`, `gotoLabel`, `setVar`, `incVar`, `verifyVar`, `wait`, `waitForScreen`, `waitForActionIdle`, `verifyBlock`, `verifyInventoryItem`, `verifyScreen`, `verifyContainer`, `verifyStatus`, `selectHotbar`, `equipBest`, `openInventory`, `inventoryClick`, `containerTransfer`, `containerQuickMoveItem`, `containerMoveToSlot`, `containerClickRole`, `containerButton`, `containerText`, `recordExploreMarker`, `refillHotbar`, `openNearbyCraftingTable`, `gotoVisibleBlock`, `placeCraftRecipe`, `takeCraftResult`, `openNearbyContainer`, `containerTransferProcess`, `containerTransferProcessAutoRepair`, `craftInventoryProcess`, `craftInventoryProcessAutoRepair`, `craftTableProcess`, `craftTableProcessAutoRepair`, `advancedPathProcess`, `recoverProcess`, `smeltProcess`, `storageOrganizeProcess`, `buildTemplateProcess`, `experienceProcess`, `combatProcess`, `farmProcess`, `mineProcess`, `lightProcess`, `sleepProcess`, `placeWorkstationProcess`, `dimensionProcess`, `redstoneProcess`, `tradeProcess`, `tradeSelectProcess`, `fishProcess`, `brewProcess`, `anvilProcess`, `anvilApplyProcess`, `exploreProcess`, `craftToolProcess`, `craftMaterialProcess`, `chopTreeProcess`, `digProcess`, `buildProcess`, `enchantPrepareProcess`, and `enchantApplyProcess`.
- Task steps can use `${variableName}` placeholders after `setVar`/`incVar`; `/task/status` exposes live `variables`, `labels`, and pending jump state so an LLM can debug loops and counters.
- `/survival/*` endpoints are higher-level survival planners. They return material checks plus executable task steps by default; pass `{"start":true}` to execute the generated player-like task immediately.
- `/survival/chopTree` finds a nearby vanilla log/stem, walks to it, equips the best mining tool, mines vertical logs one by one, waits for each break, verifies air, and picks up drops.
- `/survival/craftTool` and `/survival/craftMaterial` generate inventory/workbench crafting chains, including basic prerequisite steps such as planks, sticks, crafting table crafting, and crafting table placement when needed.
- `/survival/dig` generates a bounded block-by-block mining process for pits/holes.
- `/survival/build` generates a basic house placement plan with floor, walls, doorway, optional roof, missing-block analysis, optional nearby-storage material staging, optional build-area clearing, and post-placement verification.
- `/survival/enchant` exposes experience/lapis/table checks and a preparation chain for opening a nearby or placed enchanting table. Final option selection can be completed with the lower-level container interfaces after the screen is open.
- `/survival/smelt`, `/survival/brew`, `/survival/anvil`, `/survival/trade`, and `/storage/organize` use the real container UI plus quick-move/click primitives. Exact slot choice remains visible to the LLM through `/container`.
- `/container/semantic`, `/container/clickRole`, `/container/button`, `/container/text`, and `/container/moveToSlot` expose UI-specific role maps, precise slot moves, role slot item snapshots, screen/menu metadata, anvil rename text, enchant costs/clues, merchant trade metadata, furnace/brewing progress metadata, and menu-button operations for furnace, brewing, anvil, enchanting, merchant/trading, crafting, and inventory menus.
- `/survival/enchantApply`, `/survival/tradeSelect`, and `/survival/anvilApply` use those semantic UI controls to perform the most common final UI actions.
- `action/status` now includes `stuck`, `stuckReason`, `recoveryHint`, and movement diagnostics for front obstruction, gaps, liquids, head space, raycast, and recovery hints.
- `/survival/missing` and `/craft/tree` now include broader common survival/building requirement guesses, including beds, doors, ladders, storage, furnaces, enchanting table/bookshelves, armor, bows, fishing rods, buckets, shield, and basic redstone parts.
- `/build/template` adds house, starter-base, storage-room, animal-pen, crop-farm, mine-entrance, mine-stairs, portal, portal-room, watchtower, bridge, and redstone templates; templates now support optional nearby-storage material staging, build-area clearing, batched hotbar refill, and placement verification.
- `/survival/advancedPath` adds recovery/mining/bridging/liquid-fill/vertical-assist fallback planning around the conservative pathfinder. Task auto-recovery now chooses bounded repair actions from movement diagnostics. It still avoids teleporting or direct world mutation.
- `/entities` and snapshots include villager profession/type/level/xp/restock metadata when visible.
- `/craft/tree` exposes recipe id/type/category, special recipe flags, ingredient source, and explanation status so LLMs can tell whether a vanilla recipe can be expanded normally.
- `/explore/markers` exposes local exploration memory for waypoints and manually recorded positions, persisted locally under `~/.playerprobe/explore-markers.json`; exploration tasks also record route start/end and route waypoint summaries.
- `/survival/decision` is a decision-only endpoint that ranks immediate survival priorities for the LLM.
- `action/planPath` and `action/findBlocks` expose richer path/block planning surfaces for LLM decision making without immediately mutating the world.
- `inventory/knowledge`, `inventory/find`, and `craft/check` expose more decision-friendly summaries before taking action.
- `menu/worlds/detail`, `menu/worlds/rename`, `menu/worlds/delete`, and `menu/worlds/backup` extend singleplayer world CRUD/management.

The pathfinder is intentionally conservative: it walks to reachable standing positions, handles one-block step up/down movement, rotates the view toward the next waypoint, and does not mine, place blocks, bridge gaps, or teleport.

Menu automation notes:

- `menu/status` returns the current screen name, title, whether Minecraft is already in a world, and the last menu action handled by the mod.
- `menu/worlds` lists local singleplayer worlds with directory id, display name, game mode, compatibility flags, and local path.
- `menu/worlds/create` lets AI create and enter a singleplayer world without clicking the vanilla UI.
- `menu/worlds/leave` saves and exits the current world, then goes to singleplayer world selection by default. Pass `{"toTitle":true}` to return to the title screen instead.
- `menu/worlds/load` lets AI enter an existing singleplayer world by world id or name.
- `menu/worlds/switch` saves/exits the current world if needed, then enters another local singleplayer world in one step.
- `preset` currently supports `normal` and `flat`.

Rebuild without Gradle:

```bash
./scripts/build-finalmod.sh
```

### Player-like safety behavior

- `POST /action/respawn` cancels the dead player's previous task/action and requests the vanilla respawn flow.
- Mining keeps the vanilla destroy process active and visibly swings the main hand; it still refuses an obstructed first hit.
- Combat walks into melee range, turns smoothly, requires a clear entity raycast, then attacks with normal hand swings.
- World actions automatically close an open inventory/container before movement, looking, mining, combat, interaction, use, placement, or pickup.
- Set `pauseOnLostFocus:false` in the instance `options.txt` so autonomous movement continues while Live2D/OBS/HanwenOS has focus.
- Ordinary movement never teleports. A three-block emergency relocation is internal-only and requires the same-position stuck state to recur at least three times for at least ten seconds after normal recovery is exhausted.
