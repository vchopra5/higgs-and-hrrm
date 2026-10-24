import { fileURLToPath } from 'node:url';
import { createBridge } from './lib/bridge.js';

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
    apiKey: process.env.BOSON_API_KEY,
    token: process.env.HIGGS_BRIDGE_TOKEN,
    upstreamUrl: process.env.HIGGS_REALTIME_URL,
  });
  const host = process.env.HIGGS_HOST || '127.0.0.1';
  const port = Number(process.env.HIGGS_PORT || 8787);
  bridge.server.listen(port, host, () => {
    console.log(`Higgs bridge listening on http://${host}:${port}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, async () => {
      await bridge.close();
      process.exit(0);
    });
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) start();
