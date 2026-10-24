#!/usr/bin/env python3
"""Install original Fresh Animations + dependencies, and a local reputation overlay.
Downloads are pinned and SHA-512 verified. Third-party binaries are not vendored.
Run while Minecraft is closed so it does not overwrite options.txt on exit.
"""
import argparse, copy, hashlib, json, pathlib, subprocess, tempfile, zipfile

FILES = [{'kind': 'fa', 'name': 'FreshAnimations_v1.11.1_26.1+.zip', 'url': 'https://cdn.modrinth.com/data/50dA9Sha/versions/tvGVYMD4/FreshAnimations_v1.11.1_26.1%2B.zip', 'sha512': '5357a3dbce74f5bdb688f3c4e6f7cadb6e0d81d3dde41d46017993d5bc5456a325a39a3faad0d471be51fcc73e3a47218ec64ed8e0024b092cd117b422f23356'}, {'kind': 'emf', 'name': 'entity_model_features-3.3.12-26.1-fabric.jar', 'url': 'https://cdn.modrinth.com/data/4I1XuqiY/versions/zh2AVI88/entity_model_features-3.3.12-26.1-fabric.jar', 'sha512': 'e65d4bda4f655b64e70f8c0cb9491e90181c0e3c511317ba53431db5e36dd405307fa69eb79e53c4b17d9a2d378a99c247fe8730acb90b39ecbfaa0681dd27ea'}, {'kind': 'etf', 'name': 'entity_texture_features-7.2.6-26.1-fabric.jar', 'url': 'https://cdn.modrinth.com/data/BVzZfTc1/versions/2bcqPrsv/entity_texture_features-7.2.6-26.1-fabric.jar', 'sha512': 'a09be994660228b12752ff6429a58e825b2d82f918434fec858699fdaa8cddacd300691d8b944c8741c84532fd70578c6851e453b895bce0420ab106368ada65'}]

def install(profile, overlay_only=False):
    profile = pathlib.Path(profile).expanduser()
    if not profile.is_dir(): raise SystemExit(f"Missing Minecraft profile: {profile}")
    with tempfile.TemporaryDirectory() as temp:
        downloaded = {}
        for item in FILES:
            if overlay_only and item['kind'] != 'fa': continue
            target = pathlib.Path(temp) / item['name']
            cached = profile / ('resourcepacks' if item['kind'] == 'fa' else 'mods') / item['name']
            if cached.exists() and hashlib.sha512(cached.read_bytes()).hexdigest() == item['sha512']:
                target.write_bytes(cached.read_bytes())
            else:
                subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', item['url'], '-o', str(target)], check=True)
            data = target.read_bytes()
            if hashlib.sha512(data).hexdigest() != item['sha512']: raise SystemExit('Download checksum mismatch')
            destination = profile / ('resourcepacks' if item['kind'] == 'fa' else 'mods') / item['name']
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not overlay_only: destination.write_bytes(data)
            downloaded[item['kind']] = target
        with zipfile.ZipFile(downloaded['fa']) as original:
            metadata = original.read('pack.mcmeta')
            path = 'assets/minecraft/optifine/cem/villager_animations.jpm'
            model = json.loads(original.read(path))
            geometry_path = 'assets/minecraft/optifine/cem/villager.jem'
            geometry = json.loads(original.read(geometry_path))
        # The adult model has one visible unibrow and EMPTY left/right brow nodes.
        # Split the original UV-mapped geometry so reputation rotations are visible.
        def visit(node):
            if isinstance(node, dict):
                if node.get('id') == 'face':
                    parts = {part['id']: part for part in node['submodels']}
                    brow = parts['brow'].pop('boxes')
                    if len(brow) != 1: raise ValueError('Unexpected Fresh Animations brow geometry')
                    for name, x, uv in [('right_brow', -2, [13,19,17,21]),
                                        ('left_brow', -2, [17,19,21,21])]:
                        box = copy.deepcopy(brow[0])
                        box['coordinates'] = [x, -0.5, -0.006, 4, 2, 0]
                        box['uvNorth'] = uv
                        parts[name]['boxes'] = [box]
                    mouth = parts['mouth']
                    original_box = mouth.pop('boxes')[0]
                    mouth['submodels'] = []
                    for name, x, uv in [('higgsMouthRight', -2, [15,23,17,24]),
                                        ('higgsMouthLeft', 0, [17,23,19,24])]:
                        box = copy.deepcopy(original_box)
                        box['coordinates'] = [x, -0.5, -0.002, 2, 1, 0]
                        box['uvNorth'] = uv
                        mouth['submodels'].append({'id': name, 'invertAxis': 'xy',
                            'translate': [0, -1.5, 0], 'boxes': [box]})
                for value in list(node.values()): visit(value)
            elif isinstance(node, list):
                for value in node: visit(value)
        visit(geometry)
        # Override idle facial motion for a clear stable expression, while preserving
        # the creator's hurt/sleep animation and blinking. Neutral retains the original.
        poses = {
            'right_brow.rz': 'if(higgs_reputation>0, -0.18, 0.48)',
            'left_brow.rz': 'if(higgs_reputation>0, 0.18, -0.48)',
            'var.brows_y': 'if(higgs_reputation>0, -1.15, 0.15)',
            'mouth.sx': 'if(higgs_reputation>0, 1.35, 1.1)',
            'mouth.sy': '0.65',
            'mouth.rz': '0',
            'var.eyes_top': 'if(higgs_reputation>0, -0.15, 0.50)',
        }
        changed = set()
        for animation in model['animations']:
            for key, pose in poses.items():
                if key in animation:
                    animation[key] = 'if(is_hurt || is_sleeping || higgs_reputation==0, (' + animation[key] + '), ' + pose + ')'
                    changed.add(key)
        if changed != set(poses): raise ValueError('Fresh Animations facial animation keys changed')
        geometry['models'][0].setdefault('animations', []).append({
            'higgsMouthRight.rz': 'if(is_hurt || is_sleeping, 0, -0.32*higgs_reputation)',
            'higgsMouthLeft.rz': 'if(is_hurt || is_sleeping, 0, 0.32*higgs_reputation)',
        })
        model['credit'] += '; local reputation animation adaptation for Higgs HRRM'
        overlay = profile / 'resourcepacks/Higgs-Reputation-Fresh-Animations.zip'
        with zipfile.ZipFile(overlay, 'w', zipfile.ZIP_DEFLATED) as output:
            meta = json.loads(metadata)
            meta['pack']['description'] = 'Higgs reputation expressions | Requires Fresh Animations by FreshLX'
            output.writestr('pack.mcmeta', json.dumps(meta))
            output.writestr(path, json.dumps(model, indent=2))
            output.writestr(geometry_path, json.dumps(geometry, indent=2))
            output.writestr('CREDITS.txt', 'Fresh Animations by FreshLX: https://modrinth.com/resourcepack/fresh-animations\nLocal adaptation of villager facial animation; original models/textures remain in the original pack.\n')
        if overlay_only:
            print('Updated reputation overlay; reload resources with F3+T:', profile)
            return
        options = profile / 'options.txt'
        text = options.read_text() if options.exists() else ''
        lines = text.splitlines()
        existing = next((json.loads(line.split(':', 1)[1]) for line in lines if line.startswith('resourcePacks:')), ['vanilla'])
        names = ['file/' + FILES[0]['name'], 'file/' + overlay.name]
        packs = [name for name in existing if name not in names] + names
        if options.exists():
            backup = options.with_name('options.before-higgs-visuals.txt')
            if not backup.exists(): backup.write_text(text)
        lines = [line for line in lines if not line.startswith('resourcePacks:')]
        options.write_text('\n'.join(lines + ['resourcePacks:' + json.dumps(packs)]) + '\n')
        print('Installed and enabled Fresh Animations + reputation overlay:', profile)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', help='Minecraft profile directory containing options.txt and mods/')
    parser.add_argument('--overlay-only', action='store_true', help='Update only the overlay; preserve running game options and installed mods')
    args = parser.parse_args()
    install(args.profile, args.overlay_only)
