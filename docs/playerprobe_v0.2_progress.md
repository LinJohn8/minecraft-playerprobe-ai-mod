# PlayerProbe v0.2 Progress

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
- Survival task helpers added:
  - `chopTreeProcess`
  - `digProcess`
  - `buildProcess`
  - `craftToolProcess`
  - `craftMaterialProcess`
  - `enchantPrepareProcess`
- Survival endpoints support preview-first planning and `{"start":true}` task
  execution, with generated `steps`, inventory summaries, and missing-material
  analysis where applicable.
- Build artifact generated successfully:
  - `finalMod/playerprobe-1.0.0-v0.2.jar`

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
  - Still needs richer recursive recipe planning for every vanilla recipe.
- Inventory/container control:
  - Read, click, transfer, drop, select, equip are implemented.
  - High-level process wrappers now exist for nearby open + transfer.
  - Still needs stronger deterministic move chains and slot-targeting
    convenience steps.

## Remaining Work

### High Priority

- Add general task DSL steps:
  - richer branch semantics
  - continue-style semantics
  - stateful loop conditions
- Harden survival process chains:
  - stronger stuck/obstacle recovery while chopping, digging, and building
  - deterministic hotbar refill before large build placement chains
  - final enchantment option selection after the enchanting table screen opens

### Medium Priority

- Improve action/task status with more detailed stuck and obstacle information.
- Add richer block/entity filtering and deterministic target selection helpers.
- Add more explicit verification payloads for all write operations.
- Add recipe-depth explanation for more vanilla blocks/items beyond the current
  common survival tools/materials.

### Nice To Have

- More building templates beyond the basic house generator.
- Better recipe depth and dependency explanation.
- More menu/process helpers for create-world screen state introspection.

## Current Rule For Ongoing Work

- Prefer exposing interface primitives and process-chain endpoints instead of
  backend shortcuts.
- Prefer real player-like progression over direct world mutation.
- Keep this file updated whenever a significant missing feature is completed or
  newly identified.
