# WorldPainter+ workspace framework

The first framework milestone replaces the commercial JIDE Docking Framework with a WorldPainter-owned API and an open-source backend. Application code uses `DockablePanel`, `DockSide`, `WorkspacePreset`, and `WorkspaceDocking` from `org.pepsoft.worldpainter.docking`. Third-party docking types are confined to `ModernDockingBackend`; panels and application callers do not implement or import ModernDocking interfaces.

This is a working framework foundation using ModernDocking 1.4.8 for drag handles, floating windows, split/tab groups and auto-hide. A replacement drag/layout engine has not been written. `DockingBackend` is the internal boundary for adding another implementation without changing editor callers.

## Features

- Stable panel IDs, duplicate-registration rejection, dynamic panel addition/removal, title/icon updates, and palette renaming.
- Default west/east stacks, tab groups, floating windows, auto-hide, programmatic activation, and restoration of floated layouts.
- A central world editor that cannot float, close or auto-hide. Tool panels cannot be closed through the backend header; they remain reachable through **View > Workspace layout > Show panel**.
- Task presets for terrain editing, biome painting and AI planning. Presets restore default grouping and select relevant tabs while keeping all custom panels available.
- Up to 16 named workspace snapshots, saved, loaded and deleted through **View > Workspace layout**. The default workspace and per-dimension layouts continue to be saved independently.
- Invalid, unsupported or stale layouts fall back to a usable default workspace. A layout saved before a newly added panel still exposes that panel.

## Lifecycle and persistence

All mutations and layout capture/restore run on Swing's event thread. Each workspace owns its backend instance; closing its main window disposes backend-created floating windows and unregisters the backend. Panels can be removed and re-added without duplicate registrations. Renaming a palette re-registers its new persistent ID and returns that palette to its default group.

Layout snapshots have a `WorldPainter-ModernDocking:1` header followed by native backend XML. Input is bounded to 1 MiB, 128 levels and 8,192 XML elements. DTDs and external entities are refused before the backend parser runs. Missing panels trigger recovery rather than reflective construction of classes named in layout metadata.

`Configuration` keeps new default, per-dimension and named workspace data separate from legacy JIDE layout fields. Existing world files are not converted. Old JIDE workspace positions are not imported; the first launch uses the new defaults. Legacy configuration fields remain readable, preserving their data for older versions.

Named-layout accessors return independent byte-array snapshots. Saving an existing name replaces that named workspace. Resetting current/default or per-world workspace positions does not delete named snapshots.

## Tests

`WorkspaceDockingTest` runs real JFrame windows and the production backend under a desktop display or Xvfb. It exercises tab groups, activation, floating/save/restore/reset, dynamic palettes, duplicate IDs, renamed panels, invalid/legacy/DTD layouts, missing/new panels, named snapshots, task presets, and configuration separation. The existing native AI panel/overlay tests run against the complete desktop module as well. `WorkspaceLayoutCodecTest` validates the layout envelope without a display. `DesktopWorkspaceTest` starts the real App, opens a deterministic island world, previews an offline AI edit, and exercises named/floating layouts.

```sh
xvfb-run -a mvn -B -f WorldPainter/pom.xml -pl WPGUI -am -Djava.awt.headless=false verify
```

CI enables `-Dworldpainter.captureScreenshots=true` and uploads three real editor screenshots as the `workspace-screenshots` artifact. Capture requires a display; it does not create a replacement UI or alter the screenshot contents.

Local full-reactor headless verification passes: 75 core tests (one existing skip) and 18 desktop-module tests (eight display-dependent skips). This workspace cannot start Xvfb listening sockets, so native window execution and screenshots run in GitHub Actions.

Plugins that directly use JIDE docking types must migrate to the new workspace API. Existing terrain/layer/export APIs continue to use their native WorldPainter types.

## Further milestones

A custom drag/layout engine remains future work. Other candidates are keyboard docking commands, richer focus restoration, monitor-aware workspace recovery, undoable workspace arrangement, and AI comparison layouts. Dragging between real monitors, DPI changes, and native Windows/macOS behavior require manual platform testing.

## Dependencies

- ModernDocking: `io.github.andrewauclair:modern-docking-api:1.4.8`, MIT. [Project](https://github.com/andrewauclair/ModernDocking).
- Open-source Swing widgets: `com.formdev:jide-oss:3.7.15`, published under GPL with the classpath exception and a free commercial licence option. [Project](https://github.com/JFormDesigner/jide-oss).

The commercial `jide-common`, `jide-dock`, `jide-plaf-jdk7` artifacts and runtime licence-loading code have been removed. JIDE Common Layer is retained only for existing Swing widgets and their look-and-feel integration.
