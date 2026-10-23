"""Package built VAP release APKs, pinned native sources and SHA-256 checksums.

Run after assembleFossRelease and assembleCastRelease. APK signatures must be
verified separately with Android SDK apksigner before publishing.
"""
import ast
import configparser
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def literal_assignment(path, name):
    for node in ast.parse(path.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == name for target in node.targets
        ):
            return ast.literal_eval(node.value)
    raise ValueError(f"Missing {name}: {path}")


def main():
    config = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName = "([A-Za-z0-9._-]+)"', config).group(1)
    code = int(re.search(r"versionCode = (\d+)", config).group(1))
    output = ROOT / "dist" / version
    output.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for flavor in ("foss", "cast"):
        folder = ROOT / f"app/build/outputs/apk/{flavor}/release"
        metadata = json.loads((folder / "output-metadata.json").read_text(encoding="utf-8"))
        if metadata["applicationId"] != "com.phnem.vap" or len(metadata["elements"]) != 1:
            raise ValueError(f"Unexpected APK metadata: {flavor}")
        element = metadata["elements"][0]
        if element["versionName"] != version or element["versionCode"] != code:
            raise ValueError(f"Stale APK: {flavor}")
        source = folder / element["outputFile"]
        with zipfile.ZipFile(source) as apk:
            if apk.testzip() is not None:
                raise ValueError(f"Corrupt APK: {flavor}")
            abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/")}
            if abis != {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}:
                raise ValueError(f"Missing APK ABIs: {flavor}: {abis}")
        target = output / f"VAP-{version}-{flavor}.apk"
        shutil.copyfile(source, target)
        artifacts.append(target)

    native = ROOT / "app/build/native-src"
    subtitle_script = ROOT / "tools/build-subtitle-native.py"
    subtitle_version = literal_assignment(subtitle_script, "VERSION")
    archives = [native / f"libass-{subtitle_version}.tar.gz"]
    whisper_revision = literal_assignment(ROOT / "tools/prepare-whisper-source.py", "REV")
    archives.append(native / f"whisper-{whisper_revision}.tar.gz")
    sources = literal_assignment(ROOT / "tools/prepare-translation-source.py", "SOURCES")
    archives.extend(native / f"{relative.split('/')[-1]}-{rev}.tar.gz" for relative, _, rev, _ in sources)
    for wrap in sorted((ROOT / "tools/native/wraps").glob("*.wrap")):
        parsed = configparser.ConfigParser(interpolation=None)
        parsed.read(wrap, encoding="utf-8")
        archives.append(native / f"libass-{subtitle_version}/subprojects/packagecache" / parsed["wrap-file"]["source_filename"])

    files = archives + [
        ROOT / "README.md", ROOT / "LICENSE", ROOT / "THIRD_PARTY_NOTICES.md",
        ROOT / "docs/subtitle-native-build.md", subtitle_script,
        ROOT / "tools/prepare-whisper-source.py", ROOT / "tools/prepare-translation-source.py",
        ROOT / "tools/package-release.py",
    ]
    files += list((ROOT / "tools/native/wraps").glob("*.wrap"))
    files += list((ROOT / "app/src/main/cpp").glob("*"))
    files += list((ROOT / "app/src/main/assets/licenses").glob("*"))
    if any(not path.is_file() for path in files):
        raise FileNotFoundError("Missing native source archives: complete an APK build first")
    archive = output / f"VAP-{version}-native-sources.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
        for path in sorted(set(files)):
            package.write(path, path.relative_to(ROOT).as_posix())
    artifacts.append(archive)
    checksums = []
    for path in artifacts:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        checksums.append(f"{digest}  {path.name}\n")
        print(f"{path.name}: {path.stat().st_size:,} bytes")
    (output / "SHA256SUMS.txt").write_text("".join(checksums), encoding="ascii")


if __name__ == "__main__":
    main()
