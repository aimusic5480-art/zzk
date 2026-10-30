#!/usr/bin/env python3
"""
Installs the Aletwende call kit into a Capacitor Android project.

Usage:
  python3 install.py <app-folder> <android-package> [--battery]

Example:
  python3 install.py ~/aletwende/driver com.twende.driver --battery

Run it AFTER `npx cap add android`. Safe to run again (it skips what is already done).
"""
import os, re, shutil, sys

MARK = "AletwendeCalls kit"

def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)

def main():
    sounds = [a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--sound=")]
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    battery = "--battery" in sys.argv
    if len(args) != 2:
        fail("usage: python3 install.py <app-folder> <android-package> [--battery] [--sound=name=file ...]")
    app, pkg = os.path.abspath(args[0]), args[1]
    if not re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$", pkg):
        fail("the package must look like com.twende.driver")

    android = os.path.join(app, "android")
    if not os.path.isdir(android):
        fail("no android folder found in %s. Run `npx cap add android` first." % app)

    kit = os.path.dirname(os.path.abspath(__file__))
    java_src = os.path.join(kit, "java")
    java_dst = os.path.join(android, "app", "src", "main", "java", *pkg.split("."))
    os.makedirs(java_dst, exist_ok=True)

    # 1) Java files
    for name in sorted(os.listdir(java_src)):
        if not name.endswith(".java"):
            continue
        with open(os.path.join(java_src, name), encoding="utf-8") as f:
            text = f.read().replace("__PKG__", pkg)
        with open(os.path.join(java_dst, name), "w", encoding="utf-8") as f:
            f.write(text)
        print("  wrote " + name)

    # 1b) app tones -> res/raw  (--sound=request_tone=public/sounds/request.mp3)
    raw_dir = os.path.join(android, "app", "src", "main", "res", "raw")
    for spec in sounds:
        if "=" not in spec:
            fail("--sound must look like --sound=request_tone=path/to/request.mp3")
        rname, rpath = spec.split("=", 1)
        rpath = os.path.abspath(rpath)
        if not re.match(r"^[a-z][a-z0-9_]*$", rname):
            fail("sound name must be lowercase letters, numbers, underscore: " + rname)
        if not os.path.isfile(rpath):
            fail("sound file not found: " + rpath)
        os.makedirs(raw_dir, exist_ok=True)
        ext = os.path.splitext(rpath)[1].lower() or ".mp3"
        import shutil
        shutil.copyfile(rpath, os.path.join(raw_dir, rname + ext))
        print("  tone " + rname + " <- " + rpath)

    # 2) AndroidManifest.xml
    mpath = os.path.join(android, "app", "src", "main", "AndroidManifest.xml")
    with open(mpath, encoding="utf-8") as f:
        m = f.read()
    if MARK in m:
        print("  manifest already patched")
    else:
        perms = [
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.VIBRATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        ]
        if battery:
            perms.append("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        missing = [p for p in perms if p not in m]
        block = "    <!-- %s: permissions -->\n" % MARK
        block += "".join('    <uses-permission android:name="%s" />\n' % p for p in missing)
        block += "\n"
        idx = m.find("<application")
        if idx < 0:
            fail("could not find <application> in AndroidManifest.xml")
        m = m[:idx] + block + "    " + m[idx:].lstrip(" ")

        # show over the lock screen + wake the screen
        am = re.search(r"<activity\b[^>]*android:name=\"\.MainActivity\"", m, re.S)
        if not am:
            am = re.search(r"<activity\b(?=[^>]*\.MainActivity)", m, re.S)
        if not am:
            fail("could not find MainActivity in AndroidManifest.xml")
        start = m.find("<activity", am.start())
        end = start + len("<activity")
        m = m[:end] + '\n            android:showWhenLocked="true"\n            android:turnScreenOn="true"' + m[end:]

        comps = """
        <!-- %s: components -->
        <service
            android:name=".CallMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
        <service
            android:name=".CallService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
        <receiver
            android:name=".CallActionReceiver"
            android:exported="false" />
        <activity
            android:name=".IncomingCallActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleInstance"
            android:showWhenLocked="true"
            android:taskAffinity=""
            android:turnScreenOn="true"
            android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />
""" % MARK
        end_app = m.rfind("</application>")
        if end_app < 0:
            fail("could not find </application> in AndroidManifest.xml")
        m = m[:end_app] + comps + "    " + m[end_app:]
        with open(mpath, "w", encoding="utf-8") as f:
            f.write(m)
        print("  patched AndroidManifest.xml")

    # 3) app/build.gradle
    gpath = os.path.join(android, "app", "build.gradle")
    with open(gpath, encoding="utf-8") as f:
        g = f.read()
    if "firebase-messaging" in g:
        print("  build.gradle already patched")
    else:
        i = g.find("dependencies {")
        if i < 0:
            fail("could not find `dependencies {` in app/build.gradle")
        i += len("dependencies {")
        add = ("\n    // %s\n"
               "    implementation platform('com.google.firebase:firebase-bom:33.7.0')\n"
               "    implementation 'com.google.firebase:firebase-messaging'\n") % MARK
        g = g[:i] + add + g[i:]
        with open(gpath, "w", encoding="utf-8") as f:
            f.write(g)
        print("  patched app/build.gradle")

    gs = os.path.join(android, "app", "google-services.json")
    if not os.path.isfile(gs):
        print("\nSTILL TO DO: copy your google-services.json into android/app/ (see the guide, step 2).")
    print("\nDone. Next: npx cap sync android")

if __name__ == "__main__":
    main()
