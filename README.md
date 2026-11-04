# numen-gto-ae2-plugin

A [Numen](https://github.com/Dwinovo/minecraft-numen) plugin (server-side companion mod) that
exposes an `ae2.*` Lua function group, so a Numen companion can read and work an
**Applied Energistics 2 ME network** straight from the server side - no screen, no virtual
slot handling, no GUI automation.

Built and tested against the **GTOCore (GregTech Odyssey) modpack**, whose AE2 is an
**unofficial modified build** (`appliedenergistics2-forge-1.20.1-15.267.4.jar`, embedded via
jarjar inside `gtocore-forge-*.jar`). Vanilla AE2 15.x shares the same API surface and should
work, but is untested here.

## Functions

| Lua call | What it does |
|---|---|
| `ae2.drives()` | List AE2 machines (drive / pattern provider / controller / terminal...) with grids in loaded chunks around the companion |
| `ae2.net.here({pos = "x,y,z"})` | Network overview: stored kinds, total items, node count |
| `ae2.net.count({id = "ns:name", pos = "..."})` | How many of one item (or fluid) the network holds |
| `ae2.net.find({query = "steel", limit = 20, pos = "..."})` | Search the whole network by id/name fragment |
| `ae2.net.take({id = "ns:name", count = 32, pos = "..."})` | Extract into the companion's inventory - gated by the owner consent popup |
| `ae2.net.put({id = "ns:name", count = 64, pos = "..."})` | Insert from the companion's inventory - consent-gated as well |

Reads answer from `IStorageService#getInventory()` (the grid's own ME storage), so virtual
terminal slots are irrelevant. Writes go through `ServerCall#authorize` - the same consent
flow as breaking a block.

## Layout

```
plugin-ae2/
  src/dev/local/numenae2/Ae2Mod.java   entry: registers the "ae2" group when numen + ae2 are loaded
  src/dev/local/numenae2/Ae2Api.java   the Lua functions themselves
  src/main/resources/                  mods.toml (modId numen_gto_ae2), pack.mcmeta
build_ae2.py                           build script (JDK 25 javac --release 17)
```

## Building

The modpack's modified AE2 jar carries class files of version 65 (Java 21), which a JDK 17
`javac` cannot read, while the Forge 1.20.1 runtime needs `--release 17` output. So the build
uses **JDK 25 with `--release 17`**.

The script reuses the classpath layout of Haruka0000000/numen-compat's `build_plugin.py`:
a Forge 1.20.1 server tree linked (junction) as `Server/` next to the script, plus two
third-party jars you extract yourself (never committed):

1. **Numen API**: open the installed `numen-forge-...-all.jar` and copy
   `META-INF/jarjar/numen_api-forge-1.20.1-<v>.jar` to `_patch/nested_numen_api-...jar`
2. **AE2**: open the modpack's `gtocore-forge-*.jar` and copy
   `META-INF/jarjar/appliedenergistics2-forge-1.20.1-*.jar` to `_patch/_libs/`

Then (paths in the script are machine-specific, adjust `JDK25` and the `Server/` junction):

```
python build_ae2.py
```

Output: `plugin-ae2/build/numen_gto_ae2-1.0.0.jar`. Drop it into **both** the server's and the
client's `mods/` folders and restart - Forge 1.20.1 has no safe mod hot-reload.

## Notes

- The Lua engine runs server-side (`NumenPlayer extends ServerPlayer`), so the server jar is
  the one that matters; the client copy keeps the MCP-side tool list happy.
- Third-party jars are not distributed here; build against your own copies, per their licenses.

## License

MIT for the code in this repository. Applied Energistics 2, Numen, GTOCore and their assets
belong to their respective authors.
