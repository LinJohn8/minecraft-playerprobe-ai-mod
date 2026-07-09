# Survival Automation

Survival endpoints are high-level planners. They return a generated `steps`
array by default and execute only when `{"start":true}` is included.

## Core Survival Endpoints

| Endpoint | Status | Purpose |
|---|---|---|
| `GET /survival/status` | Implemented | Inventory, nearby useful blocks, crafting and enchanting access. |
| `GET/POST /survival/missing` | Implemented | Missing-material analysis. |
| `POST /survival/craftTool` | Implemented | Tool crafting with prerequisite chains. |
| `POST /survival/craftMaterial` | Implemented | Common material crafting. |
| `POST /survival/chopTree` | Implemented | Find tree, walk, mine logs, verify, pick up drops. |
| `POST /survival/dig` | Implemented | Bounded block-by-block pit/hole mining. |
| `POST /survival/build` | Implemented | Basic house plan. |
| `POST /survival/enchant` | Implemented | Enchanting readiness and table-opening chain. |
| `POST /survival/enchantApply` | v0.3 | Put item/lapis, press enchant option, take item back. |

## Expanded v0.3 Interfaces

| Endpoint | Status | Purpose |
|---|---|---|
| `POST /survival/advancedPath` | Base process | Direct path plus bounded recover/mine/bridge fallback steps. |
| `POST /survival/recover` | Base process | Close screen, back up, jump/side-step, replan-friendly recovery. |
| `POST /survival/smelt` | Base process | Open/place furnace, quick-move input/fuel, wait, take result. |
| `POST /survival/experience` | Planner | Pick smelting, ore mining, or combat-style XP chain. |
| `POST /survival/combat` | Base process | Eat if hungry, equip weapon, approach target, attack, optional retreat. |
| `POST /survival/decision` | Decision-only | Rank immediate survival priorities. |
| `POST /survival/farm` | Base process | Harvest crops, plant seeds, or feed nearby animal. |
| `POST /survival/mine` | Base process | Ore search/mining or bounded mine-stair/dig chain with lighting hook. |
| `POST /survival/light` | Base process | Plan torch placement for low-light nearby floor positions. |
| `POST /survival/sleep` | Base process | Find/place bed and interact. |
| `POST /survival/placeWorkstation` | Base process | Place/open common workstation blocks. |
| `POST /survival/dimension` | Base process | Nether portal frame planning and ignition interaction. |
| `POST /survival/redstone` | Template | Simple redstone line/lever template. |
| `POST /survival/trade` | UI open | Walk to/open villager trade UI. |
| `POST /survival/tradeSelect` | v0.3 | Select trade button, quick-move payment, take result. |
| `POST /survival/fish` | Base process | Select rod, cast, wait, reel. |
| `POST /survival/brew` | UI process | Open brewing stand, quick-move fuel/bottles/ingredient, wait, take result. |
| `POST /survival/anvil` | UI open | Place/open anvil. |
| `POST /survival/anvilApply` | v0.3 | Put left/right items and take anvil result when available. |
| `POST /survival/explore` | Base process | Generate waypoint exploration route. |
| `POST /storage/organize` | Base process | Open chest/container and quick-move selected item IDs. |
| `POST /build/template` | Template | House, farm, mine-stairs, portal, redstone-line templates. |
| `POST /build/refillHotbar` | Implemented | Swap inventory material into a target hotbar slot. |

## Example

```bash
curl -X POST http://127.0.0.1:8765/survival/smelt \
  -H 'Content-Type: application/json' \
  -d '{"inputItemId":"minecraft:raw_iron","fuelItemId":"minecraft:coal","count":1,"start":true}'
```

## Important Behavior

- `start:false` or omitted means preview only.
- `start:true` starts a task; poll `/task/status`.
- UI-heavy actions such as enchanting, trading, anvil, and brewing expose the
  real container/screen. v0.3 adds `/container/semantic`,
  `/container/clickRole`, and `/container/button` so LLMs can use role names
  instead of guessing raw slot numbers.
