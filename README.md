   in the whole folder (browsers on mobile support folder upload in most cases),
   or use a Git client app like **Working Copy** (iOS) / **Termux + git** (Android).
3. Once the files are pushed to the `main` branch, go to the repo's **Actions** tab
   in the GitHub app or mobile browser.
4. You'll see the **Build APK** workflow. Tap it, then tap **Run workflow** (this is
   the `workflow_dispatch` trigger) — or just pushing to `main` will trigger it
   automatically.
5. Wait for the green checkmark (a couple of minutes). Open the completed run,
   scroll to **Artifacts**, and download `BatteryRestrict-debug` — it's a zip
   containing the `.apk`.
6. On your phone, unzip it (any file manager / zip app can do this), then tap the
   `.apk` to install. You'll need "Install unknown apps" enabled for whichever
   app you use to open it.
7. Grant root access to the app when Magisk (or your root manager) prompts you.

## Before you build

- Change `applicationId` / `namespace` in `app/build.gradle.kts` from
  `com.example.batteryrestrict` to something unique to you, especially if you
  plan to keep updating it later.
- The debug build is self-signed automatically by Android's build tools with a
  debug key — that's fine for personal use and lets you install directly.

## Notes / limits

- This app has **no effect on non-rooted devices** and no effect if your kernel
  doesn't expose these two sysfs paths — it will detect and tell you.
- Because it operates via raw sysfs writes, values you set are **not persisted
  across reboots** unless you separately add a boot script (e.g. a Magisk
  `service.sh`) — this app only does live toggling while running.
