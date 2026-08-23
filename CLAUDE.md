# Permissions

Do not ask for permission before doing anything. Just go ahead — this applies to code changes, file edits, git commands, builds, searches, curl commands, or any other action required to complete the task.

# Git Workflow

Follow steps 1 and 2 only if you are already on the master branch. If you are not, then skip step 1 and 2.

1. Checkout master and pull the latest code:
   ```
   git checkout master
   git pull
   ```

2. Checkout a new branch named something short and relevant to the task (one or two words):
   ```
   git checkout -b <branch-name>
   ```

The below should always be run.

After a successful build and install (see Installing Builds), finish with:

1. Update the release notes (see Release Notes below).

2. Stage and commit the changes:
   ```
   git add <changed files>
   git commit -m "<message>"
   ```

3. Rebase with the latest master:
   ```
   git fetch origin
   git rebase origin/master
   ```

4. Do NOT push. Stop here and wait for the user to explicitly ask you to push, even if a remote branch already exists — this gives them a chance to test the installed build first. Once they say to push:
   ```
   git push -u origin <branch-name>
   ```

# Build Instructions

After completing any code changes, always run the following commands from this directory in order:

```
./gradlew clean
./gradlew assembleDebug
```

# Installing Builds

After a successful `assembleDebug`, if a device/emulator is reachable via `adb devices`, install the build and relaunch the app automatically so the user can test it immediately — don't wait to be asked:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop <applicationId>
adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1
```

`adb install -r` alone does not restart an already-running app, so the force-stop + relaunch step is required or the old code stays loaded in memory. If no device is reachable, just report the build succeeded and let the user install it themselves.

Do not interact with the UI to test changes yourself — no `adb shell input tap`/`swipe`/`keyevent` and no exploratory screenshots to self-verify a feature works. Install and relaunch so the build is ready on the device, then stop; the user tests it themselves. Screenshots and logcat are still fine when the user asks you to diagnose something specific they've already hit (see Logging below) — the thing to avoid is driving the app's UI on your own to poke around or confirm a change "looks right."

# Tests

Before running the tests, review the changes made and check whether any new tests are needed. If there are gaps, add them first.

Then ensure all tests pass:

```
./gradlew testDebugUnitTest
```

If they fail, investigate the reason and make the relevant changes. Then re-run the tests. Follow this process until all tests pass.

# Release Notes

After installing a successful build (not just after the build itself), update the `RELEASE_NOTES` value in `.github/workflows/build-release-apk.yml` with a summary of the changes made.

Format it as a bullet list — one line per distinct feature, bug fix, or improvement, each starting with `- ` — rather than one long paragraph, so the release reads clearly. Keep each bullet brief but informative, and write from the user-facing outcome, not the debugging path it took to get there: if a fix went through several iterations before it actually worked, that's one bullet describing the end result, not one bullet per attempt.

`RELEASE_NOTES` is a YAML literal block scalar (`|-`), so each bullet must be its own line at the same indentation — do not collapse it back to a folded (`>-`) paragraph.

`RELEASE_NOTES` should only ever reflect the current branch's changes:
- Starting a new branch for a new task: remove all existing bullets first, then add only the bullet(s) for this branch's work.
- Adding more commits to the same branch/task: keep bullets already added earlier in this branch, and append the new bullet(s) alongside them (don't replace them).

# Logging

When a build adds logging that the user needs to check, always tell the user the exact Android Studio Logcat filter string and the specific keyword to search for within the results.

When diagnosing an issue that requires checking logs, check them yourself by default instead of just asking the user to — via `adb` (e.g. `adb shell pidof <applicationId>` to find the running app's pid, then `adb logcat -d --pid=<pid>` to dump the current buffer, or `adb logcat -c` to clear it first when a clean capture around a specific repro step is needed). Only fall back to asking the user to pull logs manually if no device/emulator is reachable via `adb devices`.

Always run `adb logcat -c` to clear the log buffer before asking the user to reproduce something (or before capturing yourself), then pull fresh logs with `adb logcat -d`. Old, unrelated log lines can pile up in the buffer and get mistaken for evidence about the current issue — a fresh capture avoids that.
