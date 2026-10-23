"""Build pinned libass and its dependencies from source for Android. No prebuilt APK libraries."""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import venv

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'app/build'
VERSION = '0.17.5'
SOURCE_HASH = 'fa286fc9ee1ba3b932703a3df7b8474d01dc8abe29ec69b6fa68781dc4bf7acc'
ABIS = {
    'arm64-v8a': ('aarch64-linux-android26', 'aarch64', 'aarch64'),
    'armeabi-v7a': ('armv7a-linux-androideabi26', 'arm', 'armv7'),
    'x86_64': ('x86_64-linux-android26', 'x86_64', 'x86_64'),
    'x86': ('i686-linux-android26', 'x86', 'i686'),
}

def run(*command):
    subprocess.run([str(x) for x in command], check=True, cwd=ROOT)

def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--abis', nargs='+', choices=list(ABIS), default=list(ABIS))
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk'))
    ndk = args.ndk or Path(os.environ.get('ANDROID_NDK_HOME') or str(sdk / 'ndk/27.0.12077973'))
    platform = 'windows-x86_64' if os.name == 'nt' else 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    compiler = ndk / 'toolchains/llvm/prebuilt' / platform / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    if not (compiler / ('clang' + suffix)).is_file():
        raise RuntimeError(f'Install Android NDK 27.0.12077973 or pass --ndk: {ndk}')
    tools = BUILD / 'native-tools'
    python = tools / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    if not python.is_file():
        venv.create(tools, with_pip=True)
    if subprocess.run([str(python), '-c', 'import mesonbuild, ninja'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        run(python, '-m', 'pip', 'install', 'meson==1.6.1', 'ninja==1.11.1.3')
    os.environ['PATH'] = str(python.parent) + os.pathsep + os.environ.get('PATH', '')
    source_root = BUILD / 'native-src'
    source_root.mkdir(parents=True, exist_ok=True)
    archive = source_root / f'libass-{VERSION}.tar.gz'
    if not archive.is_file():
        urllib.request.urlretrieve(f'https://github.com/libass/libass/archive/refs/tags/{VERSION}.tar.gz', archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SOURCE_HASH:
        raise RuntimeError('libass source hash mismatch')
    source = source_root / f'libass-{VERSION}'
    if not (source / 'meson.build').is_file():
        with tarfile.open(archive) as package:
            for entry in package.getmembers():
                if not (source_root / entry.name).resolve().is_relative_to(source_root.resolve()) or entry.issym() or entry.islnk():
                    raise RuntimeError('Unsafe source archive entry')
            package.extractall(source_root, filter='data')
    subprojects = source / 'subprojects'
    subprojects.mkdir(exist_ok=True)
    for wrap in (ROOT / 'tools/native/wraps').glob('*.wrap'):
        target = subprojects / wrap.name
        if not target.is_file() or target.read_bytes() != wrap.read_bytes():
            shutil.copyfile(wrap, target)
    # Fetch pinned, SHA256-verified dependency archives before the small cross-build patch.
    run(python, '-m', 'mesonbuild.mesonmain', 'subprojects', 'download', '--sourcedir', source)
    patch = subprojects / 'fribidi-1.0.17/gen.tab/meson.build'
    old = "native_cc = meson.get_compiler('c')"
    new = "native_cc = meson.get_compiler('c', native: true)"
    text = patch.read_text()
    if old in text:
        patch.write_text(text.replace(old, new))
    elif new not in text:
        raise RuntimeError('Unexpected FriBidi generator configuration')
    for abi in args.abis:
        triple, family, cpu = ABIS[abi]
        cross = source_root / f'android-{abi}.ini'
        c = compiler.as_posix()
        content = f"""[binaries]
c = ['{c}/clang{suffix}', '--target={triple}']
cpp = ['{c}/clang++{suffix}', '--target={triple}']
ar = '{c}/llvm-ar{suffix}'
strip = '{c}/llvm-strip{suffix}'
[host_machine]
system = 'android'
cpu_family = '{family}'
cpu = '{cpu}'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-fPIC']
cpp_args = ['-fPIC']
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']
"""
        if not cross.is_file() or cross.read_text() != content:
            cross.write_text(content)
        output = BUILD / f'native-{abi}'
        setup = [python, '-m', 'mesonbuild.mesonmain', 'setup', output, source, '--cross-file', cross,
                 '--prefix', BUILD / 'native-install' / abi, '--buildtype', 'release',
                 '-Ddefault_library=shared', '-Dfontconfig=disabled', '-Ddirectwrite=disabled', '-Dcoretext=disabled',
                 '-Drequire-system-font-provider=false', '-Dtest=disabled', '-Dcompare=disabled', '-Dprofile=disabled',
                 '-Dfuzz=disabled', '-Dcheckasm=disabled', '-Dasm=disabled', '-Dlibunibreak=disabled',
                 '-Dfreetype2:zlib=disabled', '-Dfreetype2:bzip2=disabled', '-Dfreetype2:png=disabled',
                 '-Dfreetype2:brotli=disabled', '-Dharfbuzz:icu=disabled', '-Dharfbuzz:utilities=disabled',
                 '-Dharfbuzz:subset=disabled', '-Dharfbuzz:raster=disabled', '-Dharfbuzz:vector=disabled']
        if (output / 'build.ninja').is_file():
            setup.append('--reconfigure')
        run(*setup)
        run(python, '-m', 'mesonbuild.mesonmain', 'compile', '-C', output, '-j', str(max(1, args.jobs)))
        run(python, '-m', 'mesonbuild.mesonmain', 'install', '-C', output, '--no-rebuild')
        packaged = BUILD / 'generated/subtitle-jni' / abi
        packaged.mkdir(parents=True, exist_ok=True)
        for name in ['libass.so', 'libfribidi.so', 'libfreetype.so', 'libharfbuzz.so']:
            shutil.copy2(BUILD / 'native-install' / abi / 'lib' / name, packaged / name)
        runtime = ndk / 'toolchains/llvm/prebuilt' / platform / 'sysroot/usr/lib' / triple.removesuffix('26') / 'libc++_shared.so'
        # armv7's sysroot ABI directory omits the compiler target's 'v7a'.
        if abi == 'armeabi-v7a':
            runtime = runtime.parent.parent / 'arm-linux-androideabi/libc++_shared.so'
        shutil.copy2(runtime, packaged / 'libc++_shared.so')

if __name__ == '__main__':
    main()
