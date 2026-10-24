#!/usr/bin/env python3
"""Refuse to launch the client if a mod or the Higgs overlay differs from scripts/client.lock.
Set as the Prism instance's pre-launch command, which provides $INST_MC_DIR:
  python3 -I /path/to/repo/scripts/verify-client.py
Listed files that are present must match the lock; visuals are optional. No unlisted
jar may be in mods/, Higgs Villagers must match the local build, and the reputation
overlay must match a fresh regeneration from the verified Fresh Animations pack.
"""
import hashlib, importlib.util, os, pathlib, sys, zipfile

SCRIPTS = pathlib.Path(__file__).resolve().parent
MOD_JAR = 'mods/higgs-villagers-0.1.0.jar'
MOD_BUILD = SCRIPTS.parent / 'mod/build/libs/higgs-villagers-0.1.0.jar'

def load_installer():
    sys.dont_write_bytecode = True  # no __pycache__ in the repository
    spec = importlib.util.spec_from_file_location('install_villager_visuals', SCRIPTS / 'install-villager-visuals.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def problems(profile):
    lock = {}
    for line in (SCRIPTS / 'client.lock').read_text().splitlines():
        if line and not line.startswith('#'):
            path, sha512, _source = line.split('\t')
            lock[path] = sha512
    for path, sha512 in lock.items():
        file = profile / path
        if file.is_file() and hashlib.sha512(file.read_bytes()).hexdigest() != sha512:
            yield f'{path} differs from scripts/client.lock'
    for jar in sorted((profile / 'mods').glob('*.jar')):
        path = jar.relative_to(profile).as_posix()
        if path not in lock and path != MOD_JAR:
            yield f'{path} is not in scripts/client.lock; verify it upstream and add it, or remove it'
    mod = profile / MOD_JAR
    if mod.is_file():
        if not MOD_BUILD.is_file():
            yield f'no build to compare {MOD_JAR} with; run ./scripts/build-mod.sh'
        elif mod.read_bytes() != MOD_BUILD.read_bytes():
            yield f'{MOD_JAR} differs from {MOD_BUILD}; copy the current build in'
    installer = load_installer()
    overlay = profile / installer.OVERLAY
    if overlay.is_file():
        fresh = profile / 'resourcepacks' / installer.FILES[0]['name']
        if not fresh.is_file():
            yield f'{installer.OVERLAY} is installed without Fresh Animations to verify it against'
        else:
            with zipfile.ZipFile(overlay) as pack:
                installed = {name: pack.read(name) for name in pack.namelist()}
            if installed != installer.overlay_entries(fresh):
                yield f'{installer.OVERLAY} differs from a fresh regeneration; rerun install-villager-visuals.py'

def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('INST_MC_DIR')
    if not directory: raise SystemExit('Usage: verify-client.py MINECRAFT_DIR (or set INST_MC_DIR)')
    profile = pathlib.Path(directory).expanduser()
    if not (profile / 'mods').is_dir(): raise SystemExit(f'No mods/ directory in {profile}')
    found = list(problems(profile))
    if found:
        print('Refusing to launch:', *found, sep='\n  ', file=sys.stderr)
        raise SystemExit(1)
    print('Verified client mods and resource packs against scripts/client.lock.')

if __name__ == '__main__':
    main()
