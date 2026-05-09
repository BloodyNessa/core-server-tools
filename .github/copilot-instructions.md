Copilot instructions for bleeding-edge-minecraft

Purpose
- Repository-specific guidance for Copilot CLI and other AI assistants working on this Fabric mod template.

Mandatory reference (READ FIRST)
- Always consult the Fabric developer documentation before making changes that touch Fabric, Loom, the Fabric API, or Minecraft internals:
  https://docs.fabricmc.net/develop/
  This documentation is the authoritative source for API locations, signatures, lifecycle, and recommended patterns. The agent MUST check it before proposing or implementing Fabric-related code.

Build, test, and run (use these commands in the project root)
- ./gradlew build           # build project and produce remapped JARs/sources
- ./gradlew runClient       # launch development Minecraft client (Loom)
- ./gradlew runServer       # launch development server (Loom)
- ./gradlew test            # run the test suite
- Run a single test: ./gradlew test --tests "fully.qualified.ClassName"  # filter tests by FQCN or pattern
- ./gradlew check           # run verification tasks
- ./gradlew clean
- ./gradlew publishToMavenLocal  # publish locally for testing

High-level architecture
- Fabric mod template using Fabric Loom (configured in build.gradle / gradle.properties).
- Single Gradle project named 'bleeding-edge-minecraft'. Java compatibility is configured in build.gradle/gradle.properties.
- Two source sets:
  - src/main — common and server-side code (non-client-specific). Use this for code that must run on dedicated servers.
  - src/client — client-only code (renderers, client events, anything that references client-only Minecraft classes).
- Resources: fabric.mod.json is processed during processResources and version is injected from project.version/gradle.properties.

Key conventions (repository-specific)
- ALWAYS place client-only classes under src/client. Do NOT add client-only imports to src/main; doing so will cause runtime crashes on dedicated servers.
- Keep versions and mod metadata in gradle.properties (minecraft_version, loader_version, fabric_api_version, mod_version, maven_group).
- Use CommandRegistrationCallback (fabric-api) for command registration and Brigadier-style command patterns.
- The jar task includes LICENSE renaming — preserve that behavior when modifying packaging.

Agent behavior rules (MANDATORY)
1. ALWAYS consult the Fabric docs: https://docs.fabricmc.net/develop/ before making changes touching Fabric, Loom, Fabric API, Minecraft server/client classes, rendering, networking, data generation, or persistence.
2. ALWAYS respect the source-set separation: src/main for non-client code; src/client for client-only code. If uncertain whether a class is client-only, consult the Fabric docs or ask the user.
3. Run ./gradlew build locally after changes that affect compilation. If the build fails, report errors and propose a minimal fix before committing.
4. For persistent data (e.g., player homes), prefer Minecraft/Fabric persistence patterns (PersistentState/WorldSavedData) and confirm the correct API and lifecycle via Fabric docs.
5. Make surgical changes; avoid sweeping refactors without explicit user approval.

Repository-level files to consult (if present)
- README.md (project overview)
- build.gradle, gradle.properties, settings.gradle (build configuration)

MCP servers / additional configuration
- (Ask the repository owner whether to configure any MCP servers relevant to this project.)

If unsure about a change, ask a clarifying question instead of guessing.
