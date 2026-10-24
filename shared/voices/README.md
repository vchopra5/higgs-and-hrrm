# Shared voice references

Run `npm --prefix backend run setup:voices` from the repository root after setting
`BOSON_API_KEY` in `.env`. Requires Node 22+, backend dependencies, current
`yt-dlp`, and `ffmpeg`. Restart the backend afterward.

- `villager.wav` and `villager.txt`: trading clips `haggle1.ogg`, `haggle2.ogg`,
  and `haggle3.ogg` from [Annoying Villiagers](https://modrinth.com/resourcepack/annoying-villiagers),
  version `ktbuOyZ7`, listed by its author as CC0-1.0. Converted to mono PCM WAV
  with 250 ms gaps. Transcript generated with Higgs STT, not manually verified.
- Rap reference: [Lose Yourself — Vocals Only](https://www.youtube.com/watch?v=wx25hG6NmrA),
  seconds 60–75. Downloaded, trimmed, and transcribed during setup; the song audio
  and lyrics are not included in this repository. Use requires appropriate rights.
  The project owner's stated fair-use basis has not been independently verified
  and is not a license grant to other users.

The installer creates account-owned voice IDs rather than publishing credentials
or assuming another account can access the original IDs. `.env` and temporary
reference files in `.tools/shared-voices/` remain ignored by Git. The voice model
is Higgs Realtime 1.5 preview; generated villager rap lyrics are original.
