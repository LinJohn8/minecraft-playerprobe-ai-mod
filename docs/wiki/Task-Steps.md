# Task Steps

`POST /task/start` accepts:

```json
{
  "name": "example",
  "autoRecoverStuck": true,
  "maxAutoRecoveries": 1,
  "steps": [
    {"action": "gotoBlock", "id": "minecraft:crafting_table", "radius": 16},
    {"action": "waitForActionIdle", "timeoutMs": 20000}
  ]
}
```

Poll:

```text
GET /task/status
```

When `autoRecoverStuck` is enabled, path/mining actions that report
`stuck:true` through `action/status` can cause the task runner to insert a
bounded recovery chain and retry the stuck step. Inserted recovery steps are
included in task `results` and `steps` so the LLM can inspect what happened.

## Control Flow

| Step | Purpose |
|---|---|
| `wait` | Timed wait. |
| `waitForScreen` | Wait for a named screen. |
| `waitForActionIdle` | Wait until movement/mining/attack action finishes. |
| `retry` | Retry a nested step. |
| `if` | Insert `then` or `else` steps based on a condition. |
| `repeat` | Repeat a nested step. |
| `breakIf` | Request early task exit when a condition matches. |

## Verification

| Step | Purpose |
|---|---|
| `verifyBlock` | Check block id or air state. |
| `verifyInventoryItem` | Check inventory count. |
| `verifyScreen` | Check current screen. |
| `verifyContainer` | Check current container type/slots. |

## Player Actions

| Step | Purpose |
|---|---|
| `lookAt` | Look at position/block/entity. |
| `move` | Short manual movement. |
| `goto` | Walk to position. |
| `gotoBlock` | Walk near block. |
| `interact` | Interact with block/entity. |
| `useItem` | Use current item. |
| `placeBlock` | Place block. |
| `mineBlock` | Mine block. |
| `pickupItems` | Collect drops. |
| `closeScreen` | Close current screen. |

## Inventory And Containers

| Step | Purpose |
|---|---|
| `selectHotbar` | Select hotbar slot. |
| `equipBest` | Pick best available tool/weapon/food. |
| `openInventory` | Open player inventory. |
| `inventoryClick` | Low-level click. |
| `containerTransfer` | Quick-move from a slot. |
| `containerQuickMoveItem` | Quick-move by item id. |
| `containerClickRole` | Click or quick-move a semantic UI role. |
| `containerButton` | Press a menu button. |
| `refillHotbar` | Swap inventory item into hotbar. |
| `openNearbyContainer` | Walk/open nearby container-like block. |
| `containerTransferProcess` | Open nearby container and transfer. |
| `containerTransferProcessAutoRepair` | Retry wrapper for container transfer. |

## Crafting And Survival Processes

| Step | Purpose |
|---|---|
| `craft` | Player-like craft action. |
| `craftInventoryProcess` | Open inventory and craft. |
| `craftInventoryProcessAutoRepair` | Retry wrapper for inventory crafting. |
| `craftTableProcess` | Open nearby workbench and craft. |
| `craftTableProcessAutoRepair` | Retry wrapper for workbench crafting. |
| `craftToolProcess` | Generate tool-crafting chain. |
| `craftMaterialProcess` | Generate material-crafting chain. |
| `chopTreeProcess` | Generate tree-chopping chain. |
| `digProcess` | Generate digging chain. |
| `buildProcess` | Generate basic build chain. |
| `enchantPrepareProcess` | Generate enchanting-table preparation chain. |
| `enchantApplyProcess` | Put enchanting inputs, press option button, take item. |
| `advancedPathProcess` | Generate advanced path fallback chain. |
| `recoverProcess` | Generate recovery chain. |
| `smeltProcess` | Generate furnace smelting chain. |
| `storageOrganizeProcess` | Generate container organization chain. |
| `buildTemplateProcess` | Generate build template chain. |
| `experienceProcess` | Generate XP acquisition chain. |
| `combatProcess` | Generate combat chain. |
| `farmProcess` | Generate farm chain. |
| `mineProcess` | Generate mining chain. |
| `lightProcess` | Generate torch placement chain. |
| `sleepProcess` | Generate sleep chain. |
| `placeWorkstationProcess` | Generate workstation placement/open chain. |
| `dimensionProcess` | Generate dimension/portal chain. |
| `redstoneProcess` | Generate redstone template chain. |
| `tradeProcess` | Generate villager UI opening chain. |
| `tradeSelectProcess` | Select villager trade and take result if possible. |
| `fishProcess` | Generate fishing chain. |
| `brewProcess` | Generate brewing stand chain. |
| `anvilProcess` | Generate anvil opening chain. |
| `anvilApplyProcess` | Put anvil inputs and take result if possible. |
| `exploreProcess` | Generate waypoint exploration chain. |
