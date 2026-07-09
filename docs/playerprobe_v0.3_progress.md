# PlayerProbe v0.3 Progress

This file records what is already completed, what is partially completed, and
what still needs to be finished for the Minecraft AI player mod.

## Completed

- HTTP interface for menu, world load/create/switch/leave, snapshot, raycast,
  entities, inventory, recipes, container, chat, and action basics.
- World management extensions:
  - `/menu/worlds/detail`
  - `/menu/worlds/delete`
  - `/menu/worlds/rename`
  - `/menu/worlds/backup`
- Planning/read interfaces for LLM decision making:
  - `/status`
  - `/action/planPath`
  - `/action/findBlocks`
  - `/inventory/find`
  - `/inventory/knowledge`
  - `/craft/check`
  - `/screen/status`
  - `/survival/status`
  - `/survival/missing`
  - `/craft/tree`
  - `/survival/decision`
  - `/container/semantic`
- Player-like bounded actions:
  - `goto`
  - `gotoBlock`
  - `mineBlock`
  - `attackEntity`
  - `interact`
  - `useItem`
  - `placeBlock`
  - `pickupItems`
  - `craft`
- Task execution no longer uses the old immediate path-follow shortcut inside
  `task` mode. Movement tasks now start real action-state movement and wait for
  completion.
- Task primitives extended:
  - `wait`
  - `waitForScreen`
  - `waitForActionIdle`
  - `retry`
  - `if`
  - `switch`
  - `repeat`
  - `repeatUntil`
  - `breakIf`
  - `continueIf`
  - `label`
  - `gotoLabel`
  - `setVar`
  - `incVar`
  - `verifyBlock`
  - `verifyInventoryItem`
  - `verifyScreen`
  - `verifyContainer`
  - `verifyStatus`
  - `verifyVar`
  - `selectHotbar`
  - `equipBest`
  - `openInventory`
  - `inventoryClick`
  - `containerTransfer`
  - `containerQuickMoveItem`
  - `containerMoveToSlot`
  - `containerClickRole`
  - `containerButton`
  - `containerText`
  - `recordExploreMarker`
  - `refillHotbar`
- Higher-level process helpers added:
  - `openNearbyCraftingTable`
  - `openNearbyContainer`
  - `containerTransferProcess`
  - `containerTransferProcessAutoRepair`
  - `craftInventoryProcess`
  - `craftInventoryProcessAutoRepair`
  - `craftTableProcess`
  - `craftTableProcessAutoRepair`
- Survival process endpoints added:
  - `/survival/chopTree`
  - `/survival/dig`
  - `/survival/build`
  - `/survival/craftTool`
  - `/survival/craftMaterial`
  - `/survival/enchant`
  - `/survival/enchantApply`
  - `/survival/advancedPath`
  - `/survival/recover`
  - `/survival/smelt`
  - `/survival/experience`
  - `/survival/combat`
  - `/survival/farm`
  - `/survival/mine`
  - `/survival/light`
  - `/survival/sleep`
  - `/survival/placeWorkstation`
  - `/survival/dimension`
  - `/survival/redstone`
  - `/survival/trade`
  - `/survival/tradeSelect`
  - `/survival/fish`
  - `/survival/brew`
  - `/survival/anvil`
  - `/survival/anvilApply`
  - `/survival/explore`
  - `/storage/organize`
  - `/build/template`
  - `/build/refillHotbar`
  - `/explore/markers`
- Survival task helpers added:
  - `chopTreeProcess`
  - `digProcess`
  - `buildProcess`
  - `craftToolProcess`
  - `craftMaterialProcess`
  - `enchantPrepareProcess`
  - `enchantApplyProcess`
  - `advancedPathProcess`
  - `recoverProcess`
  - `smeltProcess`
  - `storageOrganizeProcess`
  - `buildTemplateProcess`
  - `experienceProcess`
  - `combatProcess`
  - `farmProcess`
  - `mineProcess`
  - `lightProcess`
  - `sleepProcess`
  - `placeWorkstationProcess`
  - `dimensionProcess`
  - `redstoneProcess`
  - `tradeProcess`
  - `tradeSelectProcess`
  - `fishProcess`
  - `brewProcess`
  - `anvilProcess`
  - `anvilApplyProcess`
  - `exploreProcess`
- Survival endpoints support preview-first planning and `{"start":true}` task
  execution, with generated `steps`, inventory summaries, and missing-material
  analysis where applicable.
- Complex survival coverage now has interface and base process-chain support for
  conservative advanced path fallback, stuck recovery, recursive craft tree
  inspection, furnace smelting, storage organization, build templates, hotbar
  refill, experience planning, combat planning, farming, mining, lighting,
  sleeping, workstation placement, nether portal planning, redstone templates,
  villager interaction opening, fishing, brewing stand opening/filling, anvil
  opening, and waypoint exploration.
- v0.3 UI/control hardening:
  - `/container/semantic` exposes role-to-slot/button mappings for furnace,
    brewing, anvil, enchanting, merchant/trading, crafting, and inventory menus.
  - `/container/clickRole` and `/container/button` let LLMs operate UI roles
    without guessing raw slot ids.
  - `/container/semantic` now includes screen/menu metadata and role slot item
    snapshots.
  - `/container/moveToSlot` supports precise source-slot to target-slot movement,
    with optional explicit swap and post-click verification.
  - `/survival/enchantApply`, `/survival/tradeSelect`, and
    `/survival/anvilApply` add common final UI action chains.
  - `action/status` now exposes `stuck`, `stuckReason`, and `recoveryHint`.
  - `/container/semantic` now exposes enchant option costs/clues, merchant
    trade offers, and anvil cost/rename readiness where available.
  - Furnace and brewing menus now expose progress/fuel-style metadata where the
    client menu provides it.
  - Visible villager entities now expose profession, type, level, XP, and restock
    metadata.
  - `/container/text` supports vanilla anvil rename text.
- v0.3 task hardening:
  - `/task/start` and survival `start:true` tasks support `autoRecoverStuck`
    and `maxAutoRecoveries`.
  - The task runner can insert bounded recovery/retry steps when movement or
    mining reports `stuck:true`, and now records the movement diagnostics used
    to select the recovery plan.
  - Task DSL now supports multi-branch `switch`, long-action-friendly
    `repeatUntil`, `continueIf`, and status-based conditions through
    `verifyStatus`.
  - Task DSL now supports task-local variables through `setVar`, `incVar`,
    `${name}` substitution, and `verifyVar`.
  - Task DSL now supports named `label` and bounded `gotoLabel` jumps for
    process chains that need loops or recovery branches.
- v0.3 crafting/material knowledge:
  - Requirement guesses now cover more common survival/building items such as
    beds, doors, ladders, storage, furnaces, enchanting table/bookshelves,
    armor, bows, fishing rods, buckets, shields, and basic redstone parts.
  - Recipe ingredient extraction now uses vanilla placement info, improving
    shapeless recipe trees.
  - `/craft/tree` now exposes recipe id/type/category, special flags,
    ingredient source, and explanation status for recipes that do not expose
    normal ingredients.
- v0.3 planning expansions:
  - `/status` provides a unified activity/status surface with `activity.kind`,
    `activity.summary`, task/action/menu/player/world/container state, and
    recent events.
  - `/action/status` now exposes movement diagnostics for front obstruction,
    gaps, liquids, head space, raycast, and recovery hints.
  - `/survival/advancedPath` now emits bounded terrain repair steps for gaps,
    liquids, breakable obstruction, and vertical assist before retrying.
  - Task auto-recovery now chooses bounded repair actions from movement
    diagnostics, including head-space mining, front obstruction mining, gap
    bridging, and liquid fill/jump recovery.
  - `/build/template` includes starter base, storage room, animal pen, crop
    farm, mine entrance, portal room, watchtower, bridge, repeater line,
    redstone torch tower, and simple redstone clock templates.
  - `/survival/build` and `/build/template` can optionally pull missing
    materials from nearby storage, clear occupied build positions, batch hotbar
    refills, and verify placements.
  - `/storage/organize` supports category-based transfer groups such as wood,
    blocks, ores, food, tools, combat, and farming.
  - `/survival/combat` supports optional shield bracing and kite/strafe steps.
  - `/explore/markers` and `recordExploreMarker` provide local exploration
    memory for waypoints, route start/end, route summaries, and persist them
    under `~/.playerprobe/`.
- Build artifact generated successfully:
  - `finalMod/playerprobe-1.0.0-v0.3.jar`

## Partially Completed

- Task system:
  - Supports sequential steps, timed waits, screen waits, action-idle waits,
    waiting for long-running action completion, retry, repeat, conditional step
    insertion, switch, repeat-until, continue-if, status conditions, variable
    conditions, labels/goto jumps, counters, and break-style early task exit.
  - Still lacks a full expression language beyond simple variables,
    placeholders, counters, and comparisons.
- Crafting process:
  - Inventory crafting and crafting-table crafting are implemented.
  - Basic higher-level process wrappers now exist.
  - Auto-repair wrappers for inventory crafting and table crafting now exist.
  - Tool/material survival wrappers now add common prerequisite chains for
    planks, sticks, crafting tables, and crafting-table access.
  - `/craft/tree` now exposes a recursive recipe tree surface.
  - Common survival/building requirement guesses are broader, which improves
    `/survival/missing` and `/craft/tree` planning.
  - Shapeless ingredient extraction is improved through vanilla placement info.
  - Recipe tree explanation now labels special/no-standard-ingredient recipes
    instead of silently returning an empty tree.
  - Still needs complete dependency execution for every vanilla recipe and
    custom handling for special recipes that vanilla exposes without normal
    ingredients.
- Inventory/container control:
  - Read, click, transfer, drop, select, equip are implemented.
  - High-level process wrappers now exist for nearby open + transfer.
  - Quick-move by item, hotbar refill, semantic role maps, role clicks, and
    menu buttons now exist.
  - Precise `containerMoveToSlot` source/target movement now exists for empty
    target slots, plus explicit swap mode.
  - Semantic container output now includes screen/menu metadata and current
    role slot item snapshots.
  - Enchant costs/clues, merchant offers, villager metadata, brewing/furnace
    progress metadata, and anvil cost/rename readiness are exposed. Complex
    rendered text and some UI edge cases can still improve.

## Remaining Work

### High Priority

- Harden survival process chains:
  - stronger bridge/mine path repair for caves, water, ladders, ravines, and
    vertical shafts
  - parse final rendered enchantment/trading/anvil labels where vanilla only
    exposes clues or screen text

### Medium Priority

- Improve advanced pathfinding beyond bounded local repair for caves, ravines,
  water, ladders, and vertical shafts.
- Add richer block/entity filtering and deterministic target selection helpers.
- Add more explicit verification payloads for remaining write operations.
- Add recipe-depth explanation for more vanilla blocks/items beyond the current
  common survival tools/materials.
- Add rendered UI text extraction where vanilla exposes only clues or widgets.

### Nice To Have

- Better recipe depth and dependency explanation for special recipes.
- More menu/process helpers for create-world screen state introspection.

## Current Rule For Ongoing Work

- Prefer exposing interface primitives and process-chain endpoints instead of
  backend shortcuts.
- Prefer real player-like progression over direct world mutation.
- Keep this file updated whenever a significant missing feature is completed or
  newly identified.
