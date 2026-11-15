# Time Machine app: build and install

The app is the same time machine, packaged as a real Android app.
- It has **no internet permission**, so Android itself keeps it offline.
- Android's cloud backup is turned off for it.
- Backups go to a folder you choose, such as an SD card or USB stick, automatically once a week.

## 1. Build it on GitHub (no Android Studio needed)

**First time:**

1. On github.com, create a new repository, for example `time-machine`. Make it **Private**, because the app's signing key will be saved in it. Tick **Add a README file**.
2. In the new repo, click **Code → Codespaces → Create codespace on main**. A code editor opens in your browser.
3. Drag `TimeMachineApp.zip` from your computer into the file list on the left.
4. Click in the **Terminal** at the bottom, paste this line, and press Enter:

   ```
   unzip -oq TimeMachineApp.zip && cp -a TimeMachineApp/. . && rm -rf TimeMachineApp TimeMachineApp.zip && git add -A && git commit -qm "Time Machine app" && git push
   ```

5. GitHub starts building straight away. Open the repo's **Actions** tab to watch; the first build takes about 5 minutes. A green ✔ means it worked.
6. Open the repo's **Releases** (right side of the repo's main page). The newest release has **TimeMachine.apk**. Download it.

The first build also creates the app's signing key and saves it in the repo as `keystore/release.jks`. Don't delete it: every future update must be signed with the same key.

**Making changes later:** change any file and push, or open the **Actions** tab and press **Run workflow**. A new release with a new TimeMachine.apk appears a few minutes later.

**Building inside the Codespace instead:** in the terminal, run `bash scripts/build.sh`. The first time it installs the Android tools, which takes a few minutes. Then right-click `TimeMachine.apk` in the file list and choose **Download**.

**If a build fails:** on the **Actions** tab, open the run with the red ✖, click the failed step, and send a screenshot of the error.

(Android Studio also still works: **File → Open** the folder, then **Build → Build APK(s)**.)

## 2. Install it on the tablet

1. Get `TimeMachine.apk` onto the tablet: copy it over with a USB cable or SD card, or open the repo's Releases page in the tablet's browser while signed in to GitHub and download it there.
2. On the tablet, open the **Files** app and tap the file.
3. Android asks to allow installing from this source. Tap **Settings**, turn on **Allow from this source**, go back and tap **Install**.
   (Fire tablets: Settings → Security & Privacy → Apps from Unknown Sources → allow your file app.)
4. The **Time Machine** icon appears with the other apps. You can switch "Allow from this source" back off afterwards.

## 3. First run

1. Open the app and tap **⚙︎**, then fill in his name, his birthday, your name and a **PIN**, and save.
2. In ⚙︎ under **Backup**, tap **Choose backup folder…** and pick or create a folder, ideally on the SD card or a USB stick (for example "Time Machine Backups"). Tap **Use this folder → Allow**.
3. Leave **Back up automatically once a week** on. When something has changed, the app makes a backup by itself about once a week, keeps the newest 5 and deletes older ones.
4. Under **Narrator voice**, choose **Phone's real voices + character effects** (the default) or the **Retro computer voice**. Pick a voice, pick a character and tap **Test voice**.

## Moving memories in from the browser version

1. In the browser version (`time-machine-offline.html`), unlock with the PIN, open ⚙︎ and tap **Back up now**. Old `.json` backups also work.
2. Copy the backup file to the tablet.
3. In the app: unlock, ⚙︎ → **Restore from backup…** → pick the file → **Restore**.

## Backups

- Each backup is a normal **.zip**. Open it on any computer: the `memories` folder has every photo, video and recording, one folder per memory named by date and title, and `memories.json` has the stories and settings.
- **Back up now** in ⚙︎ makes one any time.
- Copy a backup off the tablet now and then. A backup on an SD card that's still inside the tablet doesn't protect against the tablet being lost.
- The PIN does not lock backup files, so keep them off cloud folders.

## Updating the app later

- Download the newest TimeMachine.apk from Releases and install it over the old one. The memories stay.
- This works because every build is signed with the same key, `keystore/release.jks`, kept in your repo. If that file is ever lost, a new build can't install over the old app: you'd have to back up, uninstall, install and restore.
- Make a backup before every update, just in case.

## Checking it's offline

On the tablet: **Settings → Apps → Time Machine → Permissions**. The only permission it can ask for is the **Microphone** (for recording). There is no internet permission to grant.

## Troubleshooting

| Problem | Fix |
|---|---|
| "App not installed" when updating | The signing key changed (see "Updating"). Back up, uninstall, install, restore. |
| Can't choose a folder on the SD card | In the folder picker, tap ☰ (top left) and choose the SD card. Some tablets need the card set up as "portable storage". |
| Automatic backup stopped | The SD card or USB stick was removed or replaced. Open ⚙︎ and choose the backup folder again. |
| No phone voices listed | Settings → Accessibility → Text-to-speech output: choose Speech Services by Google and install English voice data. The retro voice always works. |
| Microphone won't record | Settings → Apps → Time Machine → Permissions → Microphone → Allow. |
