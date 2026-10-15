# WorldPainter+ native terrain assistant

This feature is developed on `feature/ai-terrain-assistant` in draft PR #1. It edits existing dimensions and creates separate, finite Java worlds through closed Java command engines. Model output is data; it cannot execute scripts, shell commands, Java, or arbitrary plugin methods.

## Use

1. Build and run WorldPainter using [BUILDING.md](BUILDING.md), using the Java 17 toolchain and public open-source docking dependencies. No JIDE evaluation account is required.
2. Choose **AI > Terrain Assistant**, then select the Task: **Edit current dimension** or **Create a new world**. Creation works even when no world is open. The panel uses WorldPainter+'s workspace framework and can float or dock like other panels.
3. Choose **Offline grammar / JSON**, **OpenAI-compatible API**, or **Ollama (local)**. Enter the base URL including `/v1`, model, and API key if needed. **Find** lists models from the provider without downloading them. Ollama defaults to `http://localhost:11434/v1`; use an installed model supporting JSON output through Chat Completions. Provider, endpoint, and model are remembered when used; API keys are never saved.
4. Enter a request and select **Generate plan**. Inspect **Plan JSON**, select **Preview**, review the colored changes on the map, then select **Apply** and confirm. JSON can also be pasted or edited directly before previewing.
5. For edits, **Edit > Undo / Redo** treats the entire plan as one operation. Undo must be enabled in Preferences. **Limit edits to current selection** intersects every command with native block/chunk selections; an empty selection edits nothing. For creation, review **New world preview**, then **Open new world**. The current world goes through the normal save prompt; replacing a world does not create a cross-world undo step. Save through the normal save action.

Offline examples:

```text
Create a mountain at 0, 0 radius 64 height 40 seed 42
Create a valley at 0, 0 radius 64 height 20
Create a river at 0, 0 radius 64 height 12 seed 42 water 62
Create a island at 0, 0 radius 96 height 60 seed 42 water 62
```

The offline parser deliberately recognizes a small fixed grammar, including `Create a hill` from the original prototype. Natural-language interpretation requires a configured model. X/Y are WorldPainter map coordinates, corresponding to Minecraft X/Z; they are not vertical height.

API keys and conversation history stay in memory for the session; nonsecret provider settings use Java Preferences. Edit requests send the prompt, bounded recent conversation, dimension height/tile bounds, view center, biome names/IDs, and custom-layer names/IDs. Creation requests send the creation contract and built-in biome catalog, with no existing-world data. No terrain samples or complete world files are uploaded. Offline mode makes no provider requests. HTTPS is required except for loopback HTTP used by local models. Redirects are refused, and errors omit server bodies and credentials.

## Command protocol, version 1

Every root, command, and region requires exactly the documented fields. Duplicate keys, malformed JSON, extra fields, decimal/exponent numbers, unsupported operations, and out-of-range values are rejected. Plans contain 1–8 commands and at most 262,144 region columns in total, counting overlaps. Region sides are 1–1,024 blocks; coordinate endpoints must fit signed 32-bit integers. Seeds are signed 64-bit integers. Parsing also bounds document size, nesting, strings, and numeric tokens.

```json
{
  "version": 1,
  "commands": [
    {
      "type": "mountain",
      "region": {"x": 0, "y": 0, "width": 128, "height": 128},
      "magnitude": 80,
      "seed": 42
    },
    {
      "type": "biome",
      "region": {"x": 0, "y": 0, "width": 128, "height": 128},
      "biomeId": 1
    }
  ]
}
```

| Type | Fields in addition to `type` and `region` | Behavior |
| --- | --- | --- |
| `hill` | `magnitude`, `seed` | Raises a smooth elliptical hill. |
| `mountain` | `magnitude`, `seed` | Raises a peaked mountain with seeded smooth variation. |
| `valley` | `magnitude`, `seed` | Lowers a smooth elliptical basin. |
| `river` | `magnitude`, `seed`, `waterLevel` | Carves a seeded meandering channel along the region's longer axis. |
| `island` | `magnitude`, `seed`, `waterLevel` | Replaces the rectangle with an island and surrounding submerged ground. |
| `continent` | `magnitude`, `seed`, `waterLevel` | Replaces the rectangle with a broader landmass and seeded coastline variation. |
| `biome` | `biomeId` | Paints a valid built-in/custom biome ID, or automatic biome `255`. |
| `layer` | `layerId`, `value` | Paints an existing custom layer by exact ID; `0` clears its values. |

`magnitude` is 1–256 blocks. Heights clamp to dimension bounds and match WorldPainter's 1/256-block precision. Water levels must lie within the dimension's height range. River magnitude is maximum depth below its water plane. Island/continent magnitude controls land elevation; these operations overwrite the rectangle's heights and water plane, so review its borders in the preview. Water commands clear lava in the affected columns. Hill/valley seeds are accepted for a uniform protocol but do not affect their smooth shape.

Biome IDs are checked against the current platform and registered custom biomes. Custom layers support per-column BIT (0/1), NIBBLE (0–15), and BYTE (0–255) values. Chunk-wide and data-free layers are refused because their writes could extend beyond the requested region. The assistant does not create, rename, delete, or reconfigure custom layers.

## Preview and undo contract

The editor thread captures an immutable snapshot. A background worker computes the final changes in command order; overlapping commands are composed into one final value per column. The transient map overlay marks only columns that actually change: orange for raised terrain, blue for lowered terrain, cyan for water/lava changes, and purple for biome/layer changes. Height color takes precedence when several properties change. The overlay is never saved into the world.

Apply uses the exact preview, rather than generating terrain again. It checks dimension identity, change number, tile identity, relevant column values, available layers, and biome validity before any write. Changing the world/dimension, input, provider settings, or map invalidates a pending preview. Missing tiles and Read Only, Not Present, and Void areas are skipped. No tiles are created.

Writes run on the editor thread with native batched tile notifications and WorldPainter's undo savepoints. An exception during writing rolls the operation back through the undo manager. The previous editor operation stays separate from the plan. Capturing and applying are bounded but synchronous; larger regions may briefly pause the editor.

## New world creation

Creation uses a separate root schema; an edit response cannot create a world. Select **Create a new world** before generating or previewing it. Offline mode supplies an editable JSON example; with a configured model, describe the world in natural language.

```json
{
  "version": 1,
  "world": {
    "name": "Emerald Isles",
    "seed": 42,
    "width": 256,
    "height": 256,
    "landscape": "island",
    "baseHeight": 80,
    "waterLevel": 62,
    "relief": 60,
    "biomeId": 255
  },
  "commands": []
}
```

The root and `world` fields are exact. Width and height are multiples of 128, from 128 to 1024 blocks, at most 64 native tiles. Vertical limits are fixed at −64 through 319 in the native Minecraft Java 1.19–1.20.4 format. World bounds start at `-(width / 128 / 2) * 128` and `-(height / 128 / 2) * 128`, with integer division, so tiles align around the origin. A 256×256 world starts at −128,−128; a single 128×128 tile starts at 0,0.

Landscapes are `plains`, `mountains`, `island`, `continent`, and `archipelago`. `baseHeight` and `waterLevel` are −63–319; `relief` is 1–192. `biomeId` must be built-in or automatic `255`; no existing custom biomes/layers are copied. The optional local-detail command list may be empty; otherwise it uses the editing vocabulary/budget above, must fit wholly inside the new world, and cannot paint custom layers. See [island.json](examples/ai-worlds/island.json) and [mountain-valley.json](examples/ai-worlds/mountain-valley.json).

Generation works on an isolated world in a cancellable background worker, using seeded noise in world coordinates across tile boundaries. Native themes supply terrain materials, and a dry spawn is chosen close to the center. Plans with no dry spawn are rejected. The full-resolution preview shows height and water; the generated world is opened only after explicit approval. A declined save prompt leaves the current world and generated preview available.

Only native WorldPainter data/classes are serialized into `.world` files; the generated terrain does not require an AI provider to reopen. Later manual tile expansion uses the stored flat base tile factory; extending the AI landscape into existing worlds is a separate future feature. Terrain and biome values are deterministic for the same plan; file metadata such as save timestamps can differ.

## Generate and export without JIDE

Build WPCore and collect its runtime dependencies (Java 17 and Maven required):

```sh
mvn -B -f WorldPainter/pom.xml -pl WPCore -am -DskipTests package
mvn -B -f WorldPainter/WPCore/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies -DincludeScope=runtime
scripts/generate-world.sh examples/ai-worlds/island.json /path/to/new-output --minecraft
```

On Windows, use `scripts\generate-world.cmd` with the same arguments. The output directory must not exist and its parent must exist. The command stages all output, then publishes a new directory containing `world.world`, `preview.png`, and the normalized `plan.json`. `--minecraft` additionally runs WorldPainter's native exporter into `minecraft/`, including `level.dat` and Anvil `.mca` files. Omit the flag to generate an editable WorldPainter world and preview only. Existing paths, including symlinks, are refused; failed/cancelled work is not published.

## Validation and remaining work

Core and actual Swing assistant tests run without licensed docking dependencies:

```sh
mvn -B -f WorldPainter/pom.xml -Pai-tests -pl WPAIHeadlessTests -am -Djava.awt.headless=true verify
```

The `AI terrain core` GitHub Actions workflow now builds/tests the full WPGUI reactor under Xvfb with JDK 17; the smaller headless profile remains available locally. `WPAIHeadlessTests` compiles the actual WPGUI assistant/preview sources and tests through an editor-host interface, with no App/WorldPainter/JIDE stubs. Tests exercise SwingWorker/EDT behavior, confirmation races, cancellation, generated-world opening, save-guard refusal, and overlay pixels. The actual App save-dialog handoff and cross-platform pointer/monitor behavior still need manual desktop validation.

Workspace verification: the full WPGUI reactor packages successfully in headless mode. WPCore runs 75 tests with zero failures/errors and one pre-existing skip. WPGUI runs 18 tests: 10 pass and 8 native-window checks skip without a display. Core tests cover strict parsing/resource limits, deterministic generation, tile seams, protected/selected areas, native undo/redo, rollback, nonsecret settings, and bounded HTTP behavior. Generation tests reload actual `.world` files and read ground/air from a native Minecraft Anvil export. Minecraft itself has not been launched here.

The desktop now uses the WorldPainter-owned workspace API backed by ModernDocking and public JIDE OSS widgets. Commercial JIDE dependencies and licence loading have been removed; see [DOCKING_FRAMEWORK.md](DOCKING_FRAMEWORK.md). The complete desktop source compiles locally. Native window tests require a display or Xvfb; live hosted-model/Ollama behavior remains unverified. Platform-specific manual docking tests remain necessary.

Implemented milestones now include separate finite-world creation and tile allocation, native Minecraft export, selection masks, model discovery, and nonsecret provider persistence. Worlds larger than 1024×1024, AI expansion of existing maps, watershed-aware river systems, advanced coast blending, structures/decoration planning, and multi-dimension generation remain future work. Natural-language quality still depends on the selected model; live hosted-model/Ollama testing remains unverified.
