# Noxs Plugin SDK (app side)

How the Noxs Android app hosts the versioned Noxs Plugin SDK. The
ecosystem-side documentation (SDK releases, publishing, examples) lives in
the [noxs-plugins repository](https://github.com/web12-app/noxs-plugins)
— see `docs/SDK.md` there.

## Capability table

`runtime/plugins/PluginSdk.kt` is the single explicit compatibility matrix.
Each row pins one SDK release: version, API generation, provided features
and the minimum Noxs version that implements them host-side.

| SDK | apiVersion | Features | Minimum Noxs |
|---|---|---|---|
| 0.0.1 | 1 | logging, ui, terminal | 0.11.0 |
| 0.0.2 | 1 | logging, ui, terminal, storage | 0.13.0 |

New SDK releases ship with a Noxs app update that extends this table —
nothing else in the app hardcodes SDK versions.

## On-device layout (inside the active rootfs home)

```
~/.noxs/
├── plugins/<id>/plugin.json|plugin.js   # existing plugin installs
├── plugins/<id>/.storage.json           # plugin-scoped KV (SDK "storage")
├── sdk/<version>/sdk.json|index.js|types.d.ts
└── plugin-state.json                    # per-plugin SDK version + digests
```

## Runtime flow

1. `NoxsPluginManager` resolves the plugin's SDK requirements through
   `PluginSdkCatalog.resolve` (same API generation, `[min, max)` range,
   feature coverage, newest-installed-first preference).
2. A missing but supported release is downloaded from the published
   `sdk/registry.json`, SHA-256-verified and extracted with the same safe
   tar handling as plugin artifacts. Published versions are immutable and
   never re-downloaded.
3. `NoxsPluginRuntime.activate` injects the verified SDK `index.js` after
   the host shim and before `plugin.js`, then calls
   `__NOXS_PLUGIN__.activate(noxs)`. A plugin declared against an SDK
   newer than 0.0.1 is never executed without its verified SDK.
4. The SDK attaches `noxs.sdk` (documented, permission-checked calls)
   onto the existing `noxs` global — there is no second API surface.

## NXMirror cloud publishing

Create a plugin scaffold and publish its versioned archive from a Noxs terminal:

```bash
nxc register
nxc plugin init hello-plugin
cd hello-plugin
# edit plugin.js and plugin.json
nxc plugin deploy
nxc plugins
```

The cloud library stores the immutable plugin artifact URL and SHA-256 digest
under the signed-in account. Direct CLI artifact uploads are currently capped
at 4 MiB. Cloud publishing does not automatically add a private plugin to the
public Plugin Store registry; public-store distribution still uses the
noxs-plugins registry workflow.

## Store integration

Every store card and the `nx plug` CLI snapshots carry a compatibility
state: `compatible`, `sdk_missing`, `sdk_incompatible`,
`app_update_required`, `plugin_update_available`, `blocked`, `error`.
The store filters (Compatible / App update required / Incompatible /
Blocked), badges (New / Update) and details page all render from
`PluginCompatInfo`; plugins that cannot run on this release hide their
install/open action and explain why.

The registry refreshes when the store opens (throttled to one fetch per
5 minutes, configurable) and via the Refresh button; offline the cached
registry is shown with an offline notice.
