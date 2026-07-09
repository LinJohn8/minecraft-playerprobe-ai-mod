# PlayerProbe Wiki

PlayerProbe v0.3 is a client-only Fabric mod for Minecraft `26.1.2-Fabric`.
It exposes a local HTTP interface at `http://127.0.0.1:8765` so an LLM or
external agent can observe the player, reason about state, and execute
player-like process chains.

## Quick Links

- [API Overview](API-Overview)
- [Survival Automation](Survival-Automation)
- [Task Steps](Task-Steps)
- [Known Limits And Roadmap](Known-Limits-And-Roadmap)

## Current Artifact

The generated mod jar is:

```text
finalMod/playerprobe-1.0.0-v0.3.jar
```

Install it into:

```text
/Users/hwslanddftx8/Library/Application Support/minecraft/versions/26.1.2-Fabric/mods
```

## Design Rule

PlayerProbe prefers real player-like progression over direct world mutation.
Most high-level endpoints return executable `steps` first. Pass
`{"start":true}` to start the generated task and then poll `/task/status`.
