import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createBridge } from './lib/bridge.js';
import { treatmentLogger } from './lib/treatment-log.js';

export { createBridge };
export { sessionConfig } from './lib/protocol.js';

function start() {
  const envPath = fileURLToPath(new URL('../.env', import.meta.url));
  try {
    process.loadEnvFile(envPath);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }

  const bridge = createBridge({
    log: treatmentLogger(fileURLToPath(new URL('./logs/treatment.jsonl', import.meta.url))),
    apiKey: process.env.BOSON_API_KEY,
    token: process.env.HIGGS_BRIDGE_TOKEN,
    upstreamUrl: process.env.HIGGS_REALTIME_URL,
    consolidation: process.env.HIGGS_CONSOLIDATION_ENABLED !== 'false',
    voiceOverrides: { warm: process.env.HIGGS_VOICE_WARM, thoughtful: process.env.HIGGS_VOICE_THOUGHTFUL, steady: process.env.HIGGS_VOICE_STEADY },
    rapVoice: process.env.HIGGS_VOICE_RAP || undefined,
    dashboardToken: process.env.HIGGS_DASHBOARD_TOKEN,
  });
  const host = process.env.HIGGS_HOST || '127.0.0.1';
  const port = Number(process.env.HIGGS_PORT || 8787);
  bridge.server.listen(port, host, () => {
    console.log(`Higgs bridge listening on http://${host}:${port}`);
    if (!bridge.dashboard.enabled) {
      console.log('Village dashboard disabled: set HIGGS_DASHBOARD_TOKEN (16+ characters) to enable it.');
      return;
    }
    console.log(`Village dashboard: http://localhost:${port}/dashboard?token=<HIGGS_DASHBOARD_TOKEN>`);
    if (process.env.HIGGS_DASHBOARD_OPEN !== 'false') openBrowser(`http://localhost:${port}/dashboard?token=${encodeURIComponent(process.env.HIGGS_DASHBOARD_TOKEN)}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, async () => {
      await bridge.close();
      process.exit(0);
    });
  }
}

/** Opens the dashboard in the default browser; best-effort, a missing opener just means opening it by hand. */
function openBrowser(url) {
  const [command, args] = process.platform === 'darwin' ? ['open', [url]]
    : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', url]] : ['xdg-open', [url]];
  try {
    const opener = spawn(command, args, { detached: true, stdio: 'ignore' });
    opener.on('error', () => console.log('Could not open a browser; open the dashboard URL above by hand.'));
    opener.unref();
  } catch {
    console.log('Could not open a browser; open the dashboard URL above by hand.');
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) start();
