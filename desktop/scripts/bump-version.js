const fs = require('fs');
const path = require('path');

const pkgPath = path.join(__dirname, '../package.json');
try {
  const pkgData = fs.readFileSync(pkgPath, 'utf8');
  const pkg = JSON.parse(pkgData);

  const versionParts = pkg.version.split('.').map(Number);
  if (versionParts.length === 3 && !versionParts.some(isNaN)) {
    versionParts[2] += 1; // Increment patch version
    const newVersion = versionParts.join('.');
    pkg.version = newVersion;
    fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
    console.log(`[SkyDrop Build] Automatically bumped version to v${newVersion}`);
  } else {
    console.warn(`[SkyDrop Build] Unable to parse version string: ${pkg.version}`);
  }
} catch (err) {
  console.error('[SkyDrop Build] Error bumping version in package.json:', err.message);
}
