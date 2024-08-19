#!/usr/bin/env python3

import os
import re
import json
import shutil

ABI_FLAVORS = {
  "arm32":  "armeabi-v7a",
  "arm64":  "arm64-v8a",
  "x86":    "x86",
  "x86_64": "x86_64",
}

def getAppVersion():
  appver = None

  with open("app/build.gradle") as gradle:
    for line in gradle:
      if "versionName" in line:
          m = re.findall(r"versionName\s*\"([^\"]+)\"", line)
          if m:
            appver = m[0]

  if not appver:
    print("Could not determine app version")
    exit(1)

  return appver

def main():
  appver = getAppVersion()

  shutil.rmtree("dist", ignore_errors=True)
  os.mkdir("dist")

  for flavor, abi in ABI_FLAVORS.items():
    release_dir = f"app/{flavor}/release"

    if not os.path.exists(release_dir):
      print(f"{release_dir} does not exist, have you built the app?")
      exit(1)

    # verify version name
    with open(f"{release_dir}/output-metadata.json", "r") as metadata:
      meta = json.load(metadata)
      vername = meta["elements"][0]["versionName"]

      if vername != appver:
        print(f"app version mismatch: build.gradle says {appver} but {flavor} says {vername}")
        exit(1)

    apkname = f"PCAPdroid-mitm_v{appver}_{abi}.apk"
    print(f"[+] {apkname}")

    # move to dist
    shutil.move(f"{release_dir}/app-{flavor}-release.apk", f"dist/{apkname}")
    shutil.rmtree(f"app/{flavor}")

if __name__ == "__main__":
  main()
