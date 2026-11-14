const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const pkg = require('../package.json');

const version = pkg.version;
const daemonDir = path.resolve(__dirname, '../../daemon');
const outBin = path.resolve(__dirname, '../bin/skydrop-daemon');

// Ensure output directory exists
fs.mkdirSync(path.dirname(outBin), { recursive: true });

console.log(`[SkyDrop Build] Compiling Go daemon with version v${version}...`);
const ldflags = `-X skydrop/daemon/internal/presence.AppVersion=${version}`;

try {
  execSync(`go build -ldflags "${ldflags}" -o "${outBin}" ./cmd/daemon`, {
    cwd: daemonDir,
    stdio: 'inherit'
  });
  console.log(`[SkyDrop Build] Daemon successfully built at ${outBin}`);
} catch (err) {
  console.error(`[SkyDrop Build] Failed to build daemon:`, err.message);
  process.exit(1);
}
