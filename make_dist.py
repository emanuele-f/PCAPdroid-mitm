#!/usr/bin/env python3

import os
import re
import json
import glob
import shutil
import subprocess

ABI_FLAVORS = {
  "arm64":  "arm64-v8a",
  "x86_64": "x86_64",
}

SIGNATURE_SHA1 = "EE953D4F988C8AC17575DFFAA1E3BBCE2E29E81D"

def findApksigner():
  apksigner = shutil.which("apksigner")
  if apksigner:
    return apksigner

  sdk_dirs = [
    os.environ.get("ANDROID_HOME"),
    os.environ.get("ANDROID_SDK_ROOT"),
    os.environ.get("ANDROID_SDK_HOME"),
    os.path.expanduser("~/Android/Sdk"),
    os.path.expanduser("~/Library/Android/sdk"),
    "/opt/android-sdk",
    "/usr/lib/android-sdk",
  ]

  for sdk in sdk_dirs:
    if not sdk:
      continue

    tools = glob.glob(f"{sdk}/build-tools/*/apksigner")
    if tools:
      return max(tools, key=lambda t: [int(x) for x in
        re.findall(r"\d+", os.path.basename(os.path.dirname(t)))])

  print("apksigner not found, set $ANDROID_HOME to the Android SDK path")
  exit(1)

def getFingerprints(apksigner, apk):
  proc = subprocess.run([apksigner, "verify", "--print-certs", apk],
    capture_output=True, text=True)

  if proc.returncode != 0:
    print(f"could not verify {apk}: {proc.stderr.strip()}")
    exit(1)

  return re.findall(r"^Signer .* certificate SHA-1 digest: ([0-9a-fA-F]+)$",
    proc.stdout, re.MULTILINE)

def verifySignature(apksigner, apk):
  fingerprints = [f.upper() for f in getFingerprints(apksigner, apk)]

  if not fingerprints:
    print(f"could not extract the signing certificate of {apk} (unsigned APK?)")
    exit(1)

  if SIGNATURE_SHA1 not in fingerprints:
    print(f"bad signature for {apk}: expected {SIGNATURE_SHA1}, got {','.join(fingerprints)}")
    exit(1)

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
  apksigner = findApksigner()

  shutil.rmtree("dist", ignore_errors=True)
  os.mkdir("dist")

  for flavor, abi in ABI_FLAVORS.items():
    release_dir = f"app/{flavor}/release"

    if not os.path.exists(release_dir):
      print(f"{release_dir} does not exist, have you built the signed APKs?")
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

    verifySignature(apksigner, f"{release_dir}/app-{flavor}-release.apk")

    # move to dist
    shutil.move(f"{release_dir}/app-{flavor}-release.apk", f"dist/{apkname}")
    shutil.rmtree(f"app/{flavor}")

if __name__ == "__main__":
  main()
