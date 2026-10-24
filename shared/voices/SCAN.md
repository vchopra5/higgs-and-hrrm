# Villager voice scan

Scanned on 2026-10-24 with ClamAV 1.5.4 using freshly downloaded, validated
signature databases: daily 28163, main 63, bytecode 339.

Command: `clamscan --database=/tmp/higgs-clamav-db --recursive shared/voices`

Result: 3 files scanned (`villager.wav`, `villager.txt`, `README.md`),
0 infected files; scanner exit code 0.

## WAV integrity

- File: `villager.wav`
- SHA-256: `0b7f354621febceed9769f483e4a323ef6f05c8c292e64a2befceca677926c5b`
- Size: 639496 bytes
- Format: RIFF/WAVE, uncompressed PCM, 16-bit mono, 44100 Hz
- Duration: approximately 7.25 seconds
- Chunks: `fmt ` (16 bytes), `data` (639452 bytes)
- Declared lengths match the file; no additional chunks or trailing data.

The scan found no known malware. It is a point-in-time result for this exact
hash, not a guarantee against every possible vulnerability in audio software.

Raps use this same custom villager voice on Higgs Realtime 1.5 preview.
No replacement rap recording was added.
