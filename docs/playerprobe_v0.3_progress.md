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
  - `repeat`
  - `breakIf`
  - `verifyBlock`
  - `verifyInventoryItem`
  - `verifyScreen`
  - `verifyContainer`
  - `selectHotbar`
  - `equipBest`
  - `openInventory`
  - `inventoryClick`
  - `containerTransfer`
  - `containerQuickMoveItem`
  - `containerClickRole`
  - `containerButton`
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
  - `/survival/enchantApply`, `/survival/tradeSelect`, and
    `/survival/anvilApply` add common final UI action chains.
  - `action/status` now exposes `stuck`, `stuckReason`, and `recoveryHint`.
- Build artifact generated successfully:
  - `finalMod/playerprobe-1.0.0-v0.3.jar`

## Partially Completed

- Task system:
  - Supports sequential steps, timed waits, screen waits, action-idle waits,
    waiting for long-running action completion, retry, repeat, and conditional
    step insertion, plus break-style early task exit.
  - Still lacks richer branch semantics such as multi-branch and stateful loop
    conditions.
- Crafting process:
  - Inventory crafting and crafting-table crafting are implemented.
  - Basic higher-level process wrappers now exist.
  - Auto-repair wrappers for inventory crafting and table crafting now exist.
  - Tool/material survival wrappers now add common prerequisite chains for
    planks, sticks, crafting tables, and crafting-table access.
  - `/craft/tree` now exposes a recursive recipe tree surface.
  - Still needs stronger shapeless/special-recipe modeling and complete
    dependency execution for every vanilla recipe.
- Inventory/container control:
  - Read, click, transfer, drop, select, equip are implemented.
  - High-level process wrappers now exist for nearby open + transfer.
  - Quick-move by item, hotbar refill, semantic role maps, role clicks, and
    menu buttons now exist.
  - Still needs richer UI text/cost parsing for complex anvil/enchanting/trading
    edge cases.

## Remaining Work

### High Priority

- Add general task DSL steps:
  - richer branch semantics
  - continue-style semantics
  - stateful loop conditions
- Harden survival process chains:
  - convert stuck diagnostics into automatic recovery insertion while chopping,
    digging, and building
  - stronger bridge/mine path repair for caves, water, ladders, ravines, and
    vertical shafts
  - parse final enchantment/trading/anvil labels/costs after the screen opens

### Medium Priority

- Improve action/task status with more detailed stuck and obstacle information.
- Add richer block/entity filtering and deterministic target selection helpers.
- Add more explicit verification payloads for all write operations.
- Add recipe-depth explanation for more vanilla blocks/items beyond the current
  common survival tools/materials.
- Add richer UI metadata extraction for brewing, trading, anvil, enchanting,
  and villager professions.

### Nice To Have

- More building templates beyond house/farm/mine-stairs/portal/redstone-line.
- Better recipe depth and dependency explanation.
- More menu/process helpers for create-world screen state introspection.

## Current Rule For Ongoing Work

- Prefer exposing interface primitives and process-chain endpoints instead of
  backend shortcuts.
- Prefer real player-like progression over direct world mutation.
- Keep this file updated whenever a significant missing feature is completed or
  newly identified.
