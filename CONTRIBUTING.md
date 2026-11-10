# Commit & Release Guide

How commits, versions and releases are written in Kiwami. Short version: **every commit says what kind of change it is, and the kind decides the next version number.**

## Commit messages

Format:

```
type(scope): short description
```

The scope is optional. Example: `fix(player): fixed crash when resuming playback`.

### Types

| Type | Use it for | Version bump |
|---|---|---|
| `feat` | a new feature or visible improvement | minor (0.1.0 → 0.2.0) |
| `fix` | a bug fix | patch (0.1.0 → 0.1.1) |
| `perf` | making something faster or lighter | patch |
| `refactor` | cleaning up code without changing behavior | none |
| `style` | formatting, whitespace, no code change | none |
| `docs` | README, this guide, privacy policy, comments | none |
| `build` | Gradle, dependencies, signing, versions | none |
| `ci` | GitHub workflows | none |
| `chore` | everything else (renames, cleanup, assets) | none |

"Minor fix" and "major fix" are not commit types. The **type** says what the change is, and the **size** shows up in the version number. A small bug fix and a big one are both `fix`.

### Scopes (optional)

Name the area when it helps. Suggested scopes: `player`, `reader`, `anilist`, `discord`, `comments`, `downloads`, `extensions`, `notifications`, `widgets`, `settings`, `ui`, `updater`, `onboarding`.

### Writing the description

- Write it in the past tense, describing what the commit did: "added", "fixed", "removed", not "add" or "adds". Use the same tense in every commit.
- Lowercase, no full stop at the end, aim for 72 characters or fewer.
- Say what changed, not how. "fixed crash when opening an empty list" beats "fixed null check".
- One logical change per commit. If you need "and" in the description, it may be two commits.

### Body (optional)

Add a blank line, then explain **why** the change was needed or anything non-obvious. Keep lines under about 72 characters.

```
fix(updater): included pre-releases when checking for updates

The "latest release" endpoint skips pre-releases, so testers never saw
0.x updates. The updater now uses the releases list and takes the
newest entry instead.
```

### Breaking changes

If a change breaks something for existing users (old backups stop restoring, settings are reset, the app id changes), add `!` after the type and explain in the body:

```
feat(settings)!: moved preferences to a new file

BREAKING CHANGE: backups made before 0.3.0 can no longer be restored.
```

### Examples

```
feat(widgets): added a continue-watching widget
fix(anilist): fixed login token not refreshing before it expires
fix(reader): fixed page position resetting after rotating the screen
perf(downloads): sped up queue loading
refactor(player): split the controls into separate classes
docs: updated the privacy policy for the comments server
build: bumped version to 0.2.0
build: removed firebase and crashlytics
ci: changed the build to upload only the arm64 apk
chore: renamed app id to nox.kiwami
```

## Versions

Kiwami uses `MAJOR.MINOR.PATCH`:

- **PATCH** (0.1.**1**): bug fixes only
- **MINOR** (0.**2**.0): new features, nothing broken. Patch resets to 0
- **MAJOR** (**1**.0.0): big overhauls or breaking changes. Minor and patch reset to 0

While the app is at `0.x`, it is still in early development. The jump to `1.0.0` means "I consider this stable".

Test builds: `0.2.0-alpha+<commit>` and `0.2.0-beta+<commit>` are produced automatically. Only release builds have the clean version number.

### Changing the version

Edit the one line near the top of `app/build.gradle.kts`:

```kotlin
val baseVersion = "0.1.0"
```

Rules: three plain numbers, each below 1000, no text like `-beta` in it. `versionName` and `versionCode` are calculated from this line. Never lower the version after a release has shipped, because Android refuses to update to a lower version code.

## Making a release

1. Make sure everything for the release is committed and the build passes.
2. Bump `baseVersion` and commit it: `build: bumped version to 0.2.0`
3. Tag it: `git tag v0.2.0`, then `git push --tags`
4. On GitHub, create a release from that tag titled `Kiwami v0.2.0`. For a test release, tick **Set as a pre-release**.
5. Attach the APK, ideally named `Kiwami-0.2.0.apk`.
6. Write the release notes: group the commits since the last tag by type (features, fixes, other).

## Quick checklist before committing

- [ ] Does the type match what the change really is?
- [ ] Is the description in the past tense, short, lowercase, no full stop?
- [ ] Is it one change?
- [ ] Did I mark it with `!` if it breaks existing users' data or settings?
