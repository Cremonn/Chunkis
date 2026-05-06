<img src="core/src/main/resources/assets/logo.png" width="150" alt="Chunkis Logo" style="border-radius: 50%;">

# Chunkis

Chunkis replaces Minecraft's Anvil (`.mca`) chunk storage with a sparse delta format called CIS. Rather than writing entire chunks to disk, Chunkis records only the blocks that players actually changed relative to what the world generator originally produced.

This is a disk I/O optimization. It has no effect on FPS, TPS, or gameplay.

## At a Glance

|                     |                                                           |
|:--------------------|:----------------------------------------------------------|
| File size reduction | 90–95% typical for modpacks with player construction      |
| Performance impact  | Negligible CPU cost during save/load; no rendering impact |
| Compatibility       | Fabric only                                               |
| Reversibility       | **Not reversible** — always back up before installing     |
| Maturity            | Beta; tested on single-player and multiplayer servers     |

---

## Is This For You?

**Good fit if:**
- You run a modpack server where world backups or transfers are slow
- Your modpack doesn't include mods that read `.mca` region files directly
- You're able to test on a backup world before committing

**Not a good fit if:**
- You're hoping for FPS or TPS improvements
- Your modpack includes mods listed under Known Incompatibilities
- You don't have a backup strategy in place

---

## How It Works

Vanilla Minecraft writes entire chunks to disk on every save — including thousands of unmodified blocks from world generation. For a typical modpack world, the vast majority of that data never changes.

Chunkis hooks into the chunk save and load pipeline via Fabric mixins. On save, it computes a delta between the current chunk state and the generated baseline, then writes only the changed blocks. On load, it reconstructs the full chunk by applying those deltas back onto the generator output.

Each `.cis` file contains a block state palette, a bit-packed delta instruction stream (with jump instructions to skip unmodified sections), and NBT data for block entities and global entities.

**On-disk layout (overworld example):**
```
world/
└── chunkis/
    ├── global_ids.json
    └── regions/
        ├── r.0.0.cis
        ├── r.0.1.cis
        └── ...
```

For non-overworld dimensions, Chunkis stores data under
`world/dimensions/<namespace>/<path>/chunkis/`, including a dimension-local
`global_ids.json` alongside that dimension's `regions/` directory.

---

## Known Incompatibilities

The following are incompatible with Chunkis:

- **WorldEdit** — reads and writes `.mca` region files directly
- Any mod that directly manipulates region files
- Mods that apply chunk post-processing after world generation
- Custom world managers with their own chunk serialization

If you're unsure about a mod in your list, test on a backup world. Look for mods that mention "region files," "world editing," or "chunk serialization."

---

## FAQ

**Will this improve my FPS?**
No. Chunkis only affects what gets written to disk. Rendering, ticking, and gameplay are entirely unaffected.

**Does this work on servers?**
Yes. Chunkis is tested on both single-player and multiplayer. Delta encoding is per-chunk and scales linearly.

**Why hasn't my world converted to CIS yet?**
Chunks convert when they save. Unvisited or unmodified chunks stay in Anvil format until a player loads and modifies them.

**Can I move a CIS world to a different server?**
Yes, as long as both servers have Chunkis installed. The `global_ids.json` file ensures portability. Without Chunkis, the world cannot be read.

**What happens if Chunkis development stops?**
Your world remains in CIS format. You'll need to keep Chunkis installed, or restore from a pre-Chunkis backup.

**Can I use this with [mod]?**
Check Known Incompatibilities above. If the mod reads `.mca` files or modifies chunks post-generation, it's likely incompatible. When unsure, test on a backup.

---

## Reporting Issues

Before opening an issue:
- Test on a backup world, not your main save
- Confirm the issue isn't caused by a mod in the Known Incompatibilities list
- Make sure you're on the latest Chunkis version

When opening an issue, include:
- Minecraft version, Fabric Loader version, and full modlist
- `latest.log` or crash report
- Steps to reproduce

Issues missing this information may be closed without response.

---

## Contributing

Contributions are welcome. The most valuable contributions are:

- Bug fixes
- Compatibility fixes or testing against popular modpacks
- Performance improvements
- Backports to older Minecraft versions
- Documentation improvements

**Process:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit changes with clear messages
4. Open a pull request with a description of what changed and why

---

## Building

```bash
git clone <repository>
cd chunkis
./gradlew build
# Output: fabric/build/libs/chunkis-<version>.jar
```
