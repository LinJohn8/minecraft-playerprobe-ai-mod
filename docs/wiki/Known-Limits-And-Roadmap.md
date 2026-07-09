# Known Limits And Roadmap

The current v0.3 build exposes broad interfaces and base process chains, but it
is still intentionally conservative. It should be treated as an LLM-controllable
Minecraft player MVP, not a perfect fully autonomous Minecraft bot.

## Current Limits

| Area | Current Limit |
|---|---|
| Pathfinding | Walks conservatively; advanced path emits bounded repair steps but does not fully solve caves, ravines, swimming, ladders, or arbitrary vertical shafts. |
| Stuck recovery | Recovery process exists and `action/status` reports stuck diagnostics; automatic recovery insertion is still future work. |
| Crafting | Recipe tree exists, but special/shapeless recipes and complete dependency execution still need stronger modeling. |
| Containers | Quick-move, hotbar refill, semantic role maps, role clicks, and menu buttons exist; complex UI edge cases still need more validation. |
| Enchanting | v0.3 can put item/lapis, press an option, and take the item back; option quality still needs client-side display parsing. |
| Villager trading | v0.3 can open villager UI, select a trade button, move payment, and take result; profession/trade metadata parsing remains future work. |
| Anvil | v0.3 can place/open anvil, put left/right items, and take result; rename text input remains future work. |
| Brewing | Opens/fills brewing stand using quick-move; exact slot validation can be improved. |
| Building | Templates exist, but large builds need better material staging and obstacle handling. |
| Combat | Basic target/equip/attack/retreat exists; no advanced kiting/shield/tactical positioning yet. |

## Roadmap

| Priority | Work |
|---|---|
| High | Convert stuck diagnostics into automatic recovery insertion for long tasks. |
| High | Parse displayed enchant/trade/anvil costs and names from UI widgets where available. |
| High | Full recursive craft execution for common vanilla survival recipes. |
| High | Better bridge/mine path repair for water, caves, ravines, ladders, and vertical shafts. |
| Medium | More deterministic build material staging and hotbar refill during large builds. |
| Medium | More templates: starter base, animal pen, crop farm, mine entrance, storage room, nether portal room. |
| Medium | More explicit verification payloads for every write operation. |
| Nice | Better exploration memory, markers, map item support, and route history. |
