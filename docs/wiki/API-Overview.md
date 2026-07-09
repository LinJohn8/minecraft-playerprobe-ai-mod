# API Overview

Base URL:

```text
http://127.0.0.1:8765
```

## Observation

| Endpoint | Purpose |
|---|---|
| `GET /health` | Mod/server health and player readiness. |
| `GET /player` | Player snapshot without nearby block grid. |
| `GET /snapshot?radius=2` | Player, world, inventory, entities, action state, nearby blocks. |
| `GET /blocks?radius=2` | Nearby non-air blocks. |
| `GET /raycast?distance=6` | Current look/raycast target. |
| `GET /entities?radius=12&limit=32` | Nearby entities. |
| `GET /watch?radius=2&intervalMs=1000` | NDJSON stream of snapshots. |
| `GET /events?since=0` | Recent local observation events. |

## Menu And Worlds

| Endpoint | Purpose |
|---|---|
| `GET /menu/status` | Current menu/screen/world status. |
| `GET/POST /menu/back` | Close/go back from current screen. |
| `GET/POST /menu/open/title` | Open title screen. |
| `GET/POST /menu/open/singleplayer` | Open singleplayer selection. |
| `GET/POST /menu/open/createWorld` | Open create-world screen. |
| `GET /menu/worlds` | List local singleplayer worlds. |
| `GET/POST /menu/worlds/detail` | Read one world detail. |
| `POST /menu/worlds/create` | Create and enter a world. |
| `POST /menu/worlds/delete` | Delete a local world. |
| `POST /menu/worlds/rename` | Rename a local world. |
| `POST /menu/worlds/backup` | Create a world backup. |
| `POST /menu/worlds/leave` | Save and leave current world. |
| `POST /menu/worlds/load` | Load an existing world. |
| `POST /menu/worlds/switch` | Save/leave current world and load another. |

## Actions

| Endpoint | Purpose |
|---|---|
| `GET /action/status` | Current long-running action state. |
| `GET/POST /action/stop` | Stop current action. |
| `POST /action/look` | Set yaw/pitch or look at coordinates. |
| `POST /action/lookAt` | Look at a block/entity/position. |
| `POST /action/move` | Short manual movement. |
| `GET/POST /action/planPath` | Plan a conservative walk path. |
| `POST /action/goto` | Walk to a position. |
| `GET/POST /action/findBlock` | Find nearest block. |
| `GET/POST /action/findBlocks` | Find multiple blocks. |
| `POST /action/gotoBlock` | Walk near a matching block. |
| `POST /action/mineBlock` | Mine a target block. |
| `POST /action/attackEntity` | Attack an entity. |
| `POST /action/interact` | Interact with block/entity. |
| `POST /action/useItem` | Use selected item. |
| `POST /action/placeBlock` | Place a block using held item. |
| `POST /action/pickupItems` | Move toward nearby dropped items. |

## Inventory, Containers, Crafting

| Endpoint | Purpose |
|---|---|
| `GET /inventory` | Full inventory and current container. |
| `GET /inventory/knowledge` | Decision-friendly inventory summary. |
| `GET/POST /inventory/find` | Find matching inventory items. |
| `POST /inventory/selectHotbar` | Select a hotbar slot. |
| `POST /inventory/equipBest` | Select best hotbar item for mining/combat/food. |
| `POST /inventory/open` | Open inventory screen. |
| `POST /inventory/click` | Low-level container click. |
| `POST /inventory/drop` | Drop item(s). |
| `GET /container` | Read current container slots. |
| `POST /container/transfer` | Quick-move from a slot. |
| `GET /container/semantic` | Read UI role-to-slot/button map. |
| `POST /container/clickRole` | Click or quick-move a semantic slot role. |
| `POST /container/button` | Press a menu button such as enchant/trade option. |
| `GET /recipes` | List recipes for an item. |
| `GET/POST /craft/check` | Check craftability and menu type. |
| `POST /craft` | Execute player-like crafting. |
| `GET/POST /craft/tree` | Recursive recipe-tree inspection for LLM planning. |

## Task Control

| Endpoint | Purpose |
|---|---|
| `POST /task/start` | Start a multi-step process. |
| `GET /task/status` | Poll task progress and results. |
| `GET/POST /task/cancel` | Cancel the task. |
