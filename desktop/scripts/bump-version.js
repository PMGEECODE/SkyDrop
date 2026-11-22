const fs = require('fs');
const path = require('path');

const pkgPath = path.join(__dirname, '../package.json');
const pkgLockPath = path.join(__dirname, '../package-lock.json');
const stampPath = path.join(__dirname, '../.bump-stamp');

// Check environment variables to skip bumping if requested
if (process.env.NO_BUMP === '1' || process.env.SKIP_BUMP === '1') {
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    console.log(`[SkyDrop Build] Version bump skipped (NO_BUMP/SKIP_BUMP is set). Current version: v${pkg.version}`);
  } catch (_) {}
  process.exit(0);
}

// Argument could be explicit version (e.g., '1.0.30') or bump type ('patch', 'minor', 'major')
const targetArg = process.argv[2] ? process.argv[2].trim() : null;

// Debounce / duplicate bump guard (within 15 seconds) unless an explicit version argument is given
if (!targetArg && fs.existsSync(stampPath)) {
  try {
    const stamp = JSON.parse(fs.readFileSync(stampPath, 'utf8'));
    const elapsed = Date.now() - (stamp.timestamp || 0);
    if (elapsed < 15000) {
      console.log(`[SkyDrop Build] Version already bumped to v${stamp.version} (${Math.round(elapsed / 1000)}s ago in this build run); skipping duplicate bump.`);
      process.exit(0);
    }
  } catch (_) {}
}

try {
  const pkgData = fs.readFileSync(pkgPath, 'utf8');
  const pkg = JSON.parse(pkgData);
  const oldVersion = pkg.version;
  let newVersion = null;

  if (targetArg && /^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$/.test(targetArg.replace(/^v/i, ''))) {
    // Explicit semantic version provided
    newVersion = targetArg.replace(/^v/i, '');
  } else {
    const versionParts = oldVersion.split('-')[0].split('.').map(Number);
    if (versionParts.length === 3 && !versionParts.some(isNaN)) {
      if (targetArg === 'major') {
        versionParts[0] += 1;
        versionParts[1] = 0;
        versionParts[2] = 0;
      } else if (targetArg === 'minor') {
        versionParts[1] += 1;
        versionParts[2] = 0;
      } else {
        // Default: patch increment
        versionParts[2] += 1;
      }
      newVersion = versionParts.join('.');
    } else {
      console.warn(`[SkyDrop Build] Unable to parse current version string: ${oldVersion}`);
      process.exit(1);
    }
  }

  if (newVersion && newVersion !== oldVersion) {
    pkg.version = newVersion;
    fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
    console.log(`[SkyDrop Build] Automatically bumped version from v${oldVersion} to v${newVersion}`);

    // Synchronize package-lock.json if present
    if (fs.existsSync(pkgLockPath)) {
      try {
        const pkgLock = JSON.parse(fs.readFileSync(pkgLockPath, 'utf8'));
        pkgLock.version = newVersion;
        if (pkgLock.packages && pkgLock.packages['']) {
          pkgLock.packages[''].version = newVersion;
        }
        fs.writeFileSync(pkgLockPath, JSON.stringify(pkgLock, null, 2) + '\n');
        console.log(`[SkyDrop Build] Synchronized package-lock.json to v${newVersion}`);
      } catch (lockErr) {
        console.warn(`[SkyDrop Build] Could not update package-lock.json:`, lockErr.message);
      }
    }

    // Record stamp to prevent duplicate bumps in chained build scripts
    try {
      fs.writeFileSync(stampPath, JSON.stringify({ version: newVersion, timestamp: Date.now() }, null, 2));
    } catch (_) {}
  } else {
    console.log(`[SkyDrop Build] Version unchanged: v${oldVersion}`);
  }
} catch (err) {
  console.error('[SkyDrop Build] Error bumping version in package.json:', err.message);
  process.exit(1);
}
