// Run after `npx cap add android`. Idempotent.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const appId = require(path.join(root, 'capacitor.config.json')).appId;
const android = path.join(root, 'android');
if (!fs.existsSync(android)) throw new Error('android/ missing - run `npx cap add android` first');

// 1. Java sources (package line is rewritten to the appId)
const srcDir = path.join(root, 'native', 'java');
const dstDir = path.join(android, 'app/src/main/java', ...appId.split('.'));
fs.mkdirSync(dstDir, { recursive: true });
for (const f of fs.readdirSync(srcDir)) {
  const code = fs.readFileSync(path.join(srcDir, f), 'utf8').replace(/^package .*;/m, `package ${appId};`);
  fs.writeFileSync(path.join(dstDir, f), code);
}

// 2. Manifest
const mPath = path.join(android, 'app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(mPath, 'utf8');
if (!m.includes('.PopupActivity')) {
  const perms = fs.readFileSync(path.join(root, 'native/manifest-permissions.xml'), 'utf8');
  const comps = fs.readFileSync(path.join(root, 'native/manifest-components.xml'), 'utf8');
  m = m.replace('<application', () => perms + '\n    <application android:extractNativeLibs="true"');
  m = m.replace('</application>', () => comps + '\n    </application>');
  fs.writeFileSync(mPath, m);
}

// 2b. Accessibility service config + description (generated here so the repo stays flat)
const res = path.join(android, 'app/src/main/res');
fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
fs.mkdirSync(path.join(res, 'values'), { recursive: true });
fs.mkdirSync(path.join(res, 'drawable'), { recursive: true });
fs.mkdirSync(path.join(res, 'mipmap-anydpi-v26'), { recursive: true });
// Icon for the "Download video" launcher entry: white download arrow on teal.
fs.writeFileSync(path.join(res, 'drawable/lr_grab_fg.xml'), `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M48,30h12v20h10L54,68L38,50h10z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M37,74h34v6H37z"/>
</vector>
`);
fs.writeFileSync(path.join(res, 'mipmap-anydpi-v26/lr_grab.xml'), `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/lr_grab_bg"/>
    <foreground android:drawable="@drawable/lr_grab_fg"/>
</adaptive-icon>
`);
fs.writeFileSync(path.join(res, 'xml/lr_accessibility.xml'), `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100"
    android:description="@string/lr_a11y_desc"
    android:settingsActivity="${appId}.MainActivity" />
`);
fs.writeFileSync(path.join(res, 'values/lr_strings.xml'), `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="lr_a11y_desc">Shows a download button over YouTube, TikTok, Instagram and Facebook. When you tap it, it presses Share and Copy link for you, then downloads the video. It only looks at the screen after you tap the button.</string>
    <color name="lr_grab_bg">#0E8F84</color>
</resources>
`);

// 3. Shizuku + yt-dlp engine dependencies, native-lib packaging, minSdk
const gPath = path.join(android, 'app/build.gradle');
let g = fs.readFileSync(gPath, 'utf8');
if (!g.includes('dev.rikka.shizuku')) {
  g = g.replace(/dependencies\s*\{/, (x) =>
    x + '\n    implementation "dev.rikka.shizuku:api:13.1.5"\n    implementation "dev.rikka.shizuku:provider:13.1.5"'
      + '\n    implementation "io.github.junkfood02.youtubedl-android:library:0.18.1"'
      + '\n    implementation "io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1"'
      + '\n    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.9.24"');
  // yt-dlp's python/ffmpeg ship as native libs that must be extracted to disk; arm64 only (modern phones) keeps the APK smaller.
  g = g.replace(/defaultConfig\s*\{/, (x) => x + "\n        ndk { abiFilters 'arm64-v8a' }");
  g = g.replace(/android\s*\{/, (x) => x + '\n    packaging { jniLibs { useLegacyPackaging = true } }');
  fs.writeFileSync(gPath, g);
}
const vPath = path.join(android, 'variables.gradle');
if (fs.existsSync(vPath)) {
  let v = fs.readFileSync(vPath, 'utf8');
  v = v.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 30');
  fs.writeFileSync(vPath, v);
}
console.log('Android project patched for', appId);
