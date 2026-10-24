# Villager expressions from Fresh Animations

Visuals use **Fresh Animations 1.11.1 by FreshLX**, with **Entity Model Features 3.3.12** and **Entity Texture Features 7.2.6 by Traben**, for Fabric / Minecraft 26.1.2.

- Original pack: https://modrinth.com/resourcepack/fresh-animations
- EMF: https://modrinth.com/mod/entity-model-features
- ETF: https://modrinth.com/mod/entitytexturefeatures

The original pack supplies animated eyes, brows, mouth geometry and hurt reactions. Higgs exposes a player-specific reputation variable to EMF. A small local overlay adjusts those existing facial parts for friendly/annoyed reputation; it does not repaint textures. Hurt animation remains controlled by Fresh Animations. The previous dynamic pixel-face renderer is removed.

Close Minecraft, then run:

```sh
python3 scripts/install-villager-visuals.py "$HOME/Library/Application Support/PrismLauncher/instances/higgs-and-hrrm/.minecraft"
```

The script downloads pinned releases, verifies SHA-512 hashes, installs client-only dependencies, preserves other selected resource packs, and enables Fresh Animations with the Higgs reputation overlay above it. If Minecraft was running during installation, it may overwrite `options.txt` on exit: select both packs in Options → Resource Packs after restarting, keeping the Higgs overlay on top. Do not install EMF/ETF on the dedicated server.

Fresh Animations' official terms allow local modification and credited adaptations; original assets are not vendored in this repository. The installer retains the creator's credit in the adapted animation and writes a CREDITS file. Consult the original project terms before redistributing any generated pack. EMF and ETF are separate third-party dependencies.

Verify after restart: eyes blink; a hit triggers the pack's animated hurt pose; polite treatment or hostility changes eyebrow/mouth posture within about one second. Check client logs for `Fresh Animations reputation variable registered` and absence of unknown-variable or animation parse errors. Actual in-game visual appearance still needs a live check on this machine.

## Clear reputation expressions

The adult overlay now splits the original visible unibrow into two UV-mapped halves. Previously the left/right brow nodes were empty, so rotating them did not change the visible brow. The original mouth is also split into two hinged halves for a visible smile/frown. Friendly villagers have raised relaxed brows, wider smiling mouths and open eyes; annoyed villagers have sharply lowered brows, narrowed eyes and a downturned mouth. Neutral uses the original idle expression. Hurt/sleep overrides and blinking remain intact. The expression follows the actual trade band, including temporary conversation mood and native Minecraft reputation.

To update an already installed pack while Minecraft is running:

```sh
python3 scripts/install-villager-visuals.py --overlay-only "$HOME/Library/Application Support/PrismLauncher/instances/higgs-and-hrrm/.minecraft"
```

Press F3+T to reload resources (Fn+F3+T if your keyboard requires Fn). This mode leaves game options and dependency jars untouched. Only the adult geometry gets the mouth hinges; shared animations do not reference those parts, preserving baby-model compatibility. Check friendly, neutral and annoyed villagers from 3–5 blocks away after reload; live visual verification is still required.
