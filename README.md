# Player Probe Fabric Mod

Client-only Fabric mod for Minecraft `26.1.2-Fabric`.

The mod starts a local HTTP server when the Minecraft client starts:

- `GET http://127.0.0.1:8765/health`
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
- `GET/POST http://127.0.0.1:8765/craft/check`
- `GET http://127.0.0.1:8765/screen/status`
- `POST http://127.0.0.1:8765/screen/close`
- `GET http://127.0.0.1:8765/events?since=0`
- `POST http://127.0.0.1:8765/chat/send`
- `GET/POST http://127.0.0.1:8765/action/stop`

The final jar is in:

```text
finalMod/playerprobe-1.0.0-v0.2.jar
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

curl -X POST http://127.0.0.1:8765/inventory/click \
  -H 'Content-Type: application/json' \
  -d '{"slot":10,"button":0,"mode":"pickup"}'

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

- `craft` now follows a player-like process instead of directly mutating inventory.
- Inventory crafting expects the player inventory screen to be opened and uses the real crafting grid/result slot flow.
- Crafting-table recipes expect a reachable nearby `minecraft:crafting_table`; the mod will only continue when the player is actually at the usable position and the table UI is opened.
- Craft responses include `steps` so an LLM can inspect each stage such as menu preparation, recipe placement, and result pickup.
- Key single-action write endpoints now also return `steps` and `verify` fields so the caller can reason about process and result instead of only success/failure.
- `task/start` now creates a tracked task with `taskId`, `currentStepIndex`, `results`, and final `snapshot` state.
- `task/status` can be polled while a longer player-like process is running, and now reports whether the current step has started, whether it is waiting for an in-progress action, and the live `currentActionState`.
- `task/start` also supports process-oriented helper steps such as `retry`, `if`, `repeat`, `breakIf`, `wait`, `waitForScreen`, `waitForActionIdle`, `verifyBlock`, `verifyInventoryItem`, `verifyScreen`, `verifyContainer`, `selectHotbar`, `equipBest`, `openInventory`, `inventoryClick`, `containerTransfer`, `openNearbyCraftingTable`, `openNearbyContainer`, `containerTransferProcess`, `containerTransferProcessAutoRepair`, `craftInventoryProcess`, `craftInventoryProcessAutoRepair`, `craftTableProcess`, and `craftTableProcessAutoRepair`.
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
