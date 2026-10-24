# Shared villager voice

Run `npm --prefix backend run setup:voices` from the repository root after setting
`BOSON_API_KEY` in `.env`. Requires Node 22+. Restart the backend afterward.
Conversations and original jukebox raps use the same villager voice.

`villager.wav` and `villager.txt` contain trading clips `haggle1.ogg`, `haggle2.ogg`,
and `haggle3.ogg` from [Annoying Villiagers](https://modrinth.com/resourcepack/annoying-villiagers),
version `ktbuOyZ7`, listed by its author as CC0-1.0. Converted to mono PCM WAV
with 250 ms gaps. Transcript generated with Higgs STT, not manually verified.

The installer creates an account-owned voice ID. Credentials stay in the ignored
`.env` file. No YouTube downloads or audio-conversion tools are needed.
All villager voice features use Higgs Realtime 1.5 preview.

See [the malware scan and file hash](SCAN.md) for the bundled WAV verification.
