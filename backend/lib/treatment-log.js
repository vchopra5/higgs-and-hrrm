import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export function treatmentLogger(path, output = console.info) {
  try {
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    appendFileSync(path, '', { mode: 0o600 });
  } catch (error) {
    output(JSON.stringify({ event: 'treatment_log_error', code: error.code }));
  }
  return entry => {
    const line = JSON.stringify(entry);
    output(line);
    if (!['treatment_classified', 'treatment_result'].includes(entry.event)) return;
    try {
      appendFileSync(path, line + '\n', { mode: 0o600 });
    } catch (error) {
      output(JSON.stringify({ event: 'treatment_log_error', code: error.code }));
    }
  };
}
