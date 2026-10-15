# WorldPainter+ native terrain assistant

This feature is developed on `feature/ai-terrain-assistant` in draft PR #1. It edits existing WorldPainter dimensions through a closed Java command engine. Model output is data; it cannot execute scripts, shell commands, Java, or arbitrary plugin methods.

## Use

1. Build and run WorldPainter using [BUILDING.md](BUILDING.md), including the licensed JIDE dependencies and Java 17 toolchain.
2. Open or create a world, then choose **AI > Terrain Assistant**. The panel uses WorldPainter's existing docking framework and can float or dock like other panels.
3. Choose **Offline grammar / JSON**, **OpenAI-compatible API**, or **Ollama (local)**. For a remote API, enter its base URL including `/v1`, an available model name, and an API key if required. Ollama defaults to `http://localhost:11434/v1`; enter a model already installed in your Ollama server. The model must support JSON output through Chat Completions.
4. Enter a request and select **Generate plan**. Inspect **Plan JSON**, select **Preview**, review the colored changes on the map, then select **Apply** and confirm. JSON can also be pasted or edited directly before previewing.
5. **Edit > Undo / Redo** treats the entire plan as one operation. Undo must be enabled in Preferences. Save the world using WorldPainter's normal save action.

Offline examples:

```text
Create a mountain at 0, 0 radius 64 height 40 seed 42
Create a valley at 0, 0 radius 64 height 20
Create a river at 0, 0 radius 64 height 12 seed 42 water 62
Create a island at 0, 0 radius 96 height 60 seed 42 water 62
```

The offline parser deliberately recognizes a small fixed grammar, including `Create a hill` from the original prototype. Natural-language interpretation requires a configured model. X/Y are WorldPainter map coordinates, corresponding to Minecraft X/Z; they are not vertical height.

API keys and conversation history stay in memory for the session. Provider requests send the prompt, bounded recent conversation, dimension height/tile bounds, view center, biome names/IDs, and custom-layer names/IDs to the chosen endpoint. No terrain samples or complete world files are uploaded. Offline mode makes no provider requests. HTTPS is required except for loopback HTTP used by local models. Redirects are refused, and errors omit server bodies and credentials.

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

## Validation and remaining work

Core tests run without the licensed GUI dependencies:

```sh
mvn -B -f WorldPainter/pom.xml -pl WPCore -am -Djava.awt.headless=true verify
```

The `AI terrain core` GitHub Actions workflow runs this command with JDK 17. The WPGUI module also contains focused raster overlay tests, which pass against the actual core classes without JIDE. The panel has also passed a limited Java type check against temporary editor API stubs; this does not validate JIDE integration or desktop behavior.

Workspace results: WPCore verification passed with 60 test cases (59 passed and one pre-existing skipped). WPDynmapPreviewer also compiled and packaged successfully in the combined reactor. Two additional overlay tests passed independently. Tests cover strict parsing and resource limits, deterministic generation, preview accuracy, protected areas, unsupported formats, native undo/redo, failure rollback, and HTTP request/response/cancellation behavior.

The clean starting branch compiles WPCore and WPDynmapPreviewer. Full application compilation and an interactive desktop smoke test are blocked in this workspace by absent `com.jidesoft:jide-common`, `jide-dock`, and `jide-plaf-jdk7` version `3.8.1`, as described in BUILDING.md. No replacement or fake JIDE jars are included. Provider transport is exercised against a real local mock HTTP server; live hosted-model/Ollama behavior remains unverified.

This milestone supplies bounded landform primitives, biome/layer painting, and preview/undo infrastructure. Complete world creation, tile allocation, watershed-aware rivers, cross-region coastline blending, native selection-mask integration, model discovery, persistent nonsecret provider settings, and Minecraft export automation remain future milestones. A `continent` command currently generates a bounded landmass patch; it does not construct an entire Minecraft world.
