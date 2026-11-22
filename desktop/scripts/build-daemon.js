const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const pkg = require('../package.json');

const version = pkg.version;
const daemonDir = path.resolve(__dirname, '../../daemon');
const binDir = path.resolve(__dirname, '../bin');

// Ensure output directory exists
fs.mkdirSync(binDir, { recursive: true });

const isWinRequested = process.argv.includes('--win') || process.argv.includes('--windows') || process.env.TARGET_OS === 'windows' || process.platform === 'win32';
const isAllRequested = process.argv.includes('--all');

function buildTarget(targetOS) {
  const isWin = targetOS === 'windows';
  const binName = isWin ? 'skydrop-daemon.exe' : 'skydrop-daemon';
  const outBin = path.join(binDir, binName);

  console.log(`[SkyDrop Build] Compiling Go daemon for ${targetOS} (v${version}) -> ${binName}...`);
  const guiFlag = isWin ? ' -H windowsgui' : '';
  const ldflags = `-s -w${guiFlag} -X skydrop/daemon/internal/presence.AppVersion=${version}`;

  const buildEnv = {
    ...process.env,
    GOARCH: 'amd64'
  };
  if (isWin) {
    buildEnv.GOOS = 'windows';
  } else {
    buildEnv.GOOS = 'linux';
  }

  try {
    execSync(`go build -ldflags "${ldflags}" -o "${outBin}" ./cmd/daemon`, {
      cwd: daemonDir,
      env: buildEnv,
      stdio: 'inherit'
    });
    console.log(`[SkyDrop Build] Daemon successfully built for ${targetOS} at ${outBin}`);
  } catch (err) {
    console.error(`[SkyDrop Build] Failed to build daemon for ${targetOS}:`, err.message);
    process.exit(1);
  }
}

if (isAllRequested) {
  buildTarget('linux');
  buildTarget('windows');
} else if (isWinRequested) {
  buildTarget('windows');
} else {
  buildTarget('linux');
}
