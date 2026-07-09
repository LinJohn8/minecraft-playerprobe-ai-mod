# Known Limits And Roadmap

The current v0.2 build exposes broad interfaces and base process chains, but it
is still intentionally conservative. It should be treated as an LLM-controllable
Minecraft player MVP, not a perfect fully autonomous Minecraft bot.

## Current Limits

| Area | Current Limit |
|---|---|
| Pathfinding | Walks conservatively; advanced path emits bounded repair steps but does not fully solve caves, ravines, swimming, ladders, or arbitrary vertical shafts. |
| Stuck recovery | Recovery process exists, but automatic stuck detection is still basic. |
| Crafting | Recipe tree exists, but special/shapeless recipes and complete dependency execution still need stronger modeling. |
| Containers | Quick-move and hotbar refill exist; exact UI slot semantics for anvil/enchanting/trading need more maps. |
| Enchanting | Opens/prepares enchanting UI; final option selection is still left to LLM/container clicks. |
| Villager trading | Opens villager UI; exact trade selection still needs dedicated trade slot handling. |
| Anvil | Places/opens anvil; repair/name operation still needs dedicated UI handling. |
| Brewing | Opens/fills brewing stand using quick-move; exact slot validation can be improved. |
| Building | Templates exist, but large builds need better material staging and obstacle handling. |
| Combat | Basic target/equip/attack/retreat exists; no advanced kiting/shield/tactical positioning yet. |

## Roadmap

| Priority | Work |
|---|---|
| High | Stronger stuck detection in `action/status` and task loop. |
| High | UI-specific slot maps for enchanting, trading, anvil, brewing, furnace variants. |
| High | Full recursive craft execution for common vanilla survival recipes. |
| High | Better bridge/mine path repair for water, caves, ravines, ladders, and vertical shafts. |
| Medium | More deterministic build material staging and hotbar refill during large builds. |
| Medium | More templates: starter base, animal pen, crop farm, mine entrance, storage room, nether portal room. |
| Medium | More explicit verification payloads for every write operation. |
| Nice | Better exploration memory, markers, map item support, and route history. |

