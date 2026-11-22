const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const pkgPath = path.join(__dirname, '../package.json');
const distDir = path.join(__dirname, '../dist');
const releaseDir = path.resolve(__dirname, '../../skydrop-release');

try {
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
  const version = pkg.version;
  console.log(`[SkyDrop Release] Staging release artifacts for version v${version}...`);

  if (!fs.existsSync(distDir)) {
    console.warn(`[SkyDrop Release] Dist directory does not exist at ${distDir}`);
    process.exit(0);
  }

  // Find AppImage, Deb, and Windows Exe files corresponding to current version
  const appImageName = `SkyDrop-${version}.AppImage`;
  const debName = `skydrop-desktop_${version}_amd64.deb`;
  const exeName = `SkyDrop-Setup-${version}.exe`;

  const appImagePath = path.join(distDir, appImageName);
  const debPath = path.join(distDir, debName);
  const exePath = path.join(distDir, exeName);

  const foundArtifacts = [];
  if (fs.existsSync(appImagePath)) foundArtifacts.push({ name: appImageName, path: appImagePath });
  if (fs.existsSync(debPath)) foundArtifacts.push({ name: debName, path: debPath });
  if (fs.existsSync(exePath)) {
    foundArtifacts.push({ name: exeName, path: exePath });
  } else {
    // Also check for any .exe matching version or productName in distDir
    try {
      const distFiles = fs.readdirSync(distDir);
      for (const f of distFiles) {
        if (f.endsWith('.exe') && (f.includes(version) || f.toLowerCase().includes('skydrop'))) {
          if (!foundArtifacts.some(a => a.name === f)) {
            foundArtifacts.push({ name: f, path: path.join(distDir, f) });
          }
        }
      }
    } catch (_) {}
  }

  if (foundArtifacts.length === 0) {
    console.log(`[SkyDrop Release] No matching build artifacts found for v${version} in dist/ yet.`);
    process.exit(0);
  }

  // Compute SHA-256 for artifacts
  const checksumLines = [];
  for (const artifact of foundArtifacts) {
    const fileBuffer = fs.readFileSync(artifact.path);
    const hash = crypto.createHash('sha256').update(fileBuffer).digest('hex');
    checksumLines.push(`${hash}  ${artifact.name}`);
    console.log(`  ✓ ${artifact.name}: ${hash}`);
  }

  const checksumContent = checksumLines.join('\n') + '\n';
  const distChecksumPath = path.join(distDir, 'SHA256SUMS.txt');
  fs.writeFileSync(distChecksumPath, checksumContent, 'utf8');
  console.log(`[SkyDrop Release] Checksums generated at ${distChecksumPath}`);

  // Stage into skydrop-release directory if present
  if (fs.existsSync(releaseDir)) {
    // Copy new artifacts and checksums
    for (const artifact of foundArtifacts) {
      const destPath = path.join(releaseDir, artifact.name);
      fs.copyFileSync(artifact.path, destPath);
      // Ensure execute permission for AppImage
      if (artifact.name.endsWith('.AppImage')) {
        try { fs.chmodSync(destPath, 0o755); } catch (_) {}
      }
      console.log(`  ✓ Copied ${artifact.name} to skydrop-release/`);
    }

    const hasNewAppImage = foundArtifacts.some(a => a.name.endsWith('.AppImage'));
    const hasNewDeb = foundArtifacts.some(a => a.name.endsWith('.deb'));
    const hasNewExe = foundArtifacts.some(a => a.name.endsWith('.exe'));

    // Remove older version artifacts from release staging folder ONLY for artifact types that were newly built and staged
    const releaseFiles = fs.readdirSync(releaseDir);
    for (const f of releaseFiles) {
      if (hasNewAppImage && f.startsWith('SkyDrop-') && f.endsWith('.AppImage') && f !== appImageName) {
        try {
          fs.unlinkSync(path.join(releaseDir, f));
          console.log(`  - Cleaned up prior AppImage: ${f}`);
        } catch (_) {}
      }
      if (hasNewDeb && f.startsWith('skydrop-desktop_') && f.endsWith('.deb') && f !== debName) {
        try {
          fs.unlinkSync(path.join(releaseDir, f));
          console.log(`  - Cleaned up prior deb: ${f}`);
        } catch (_) {}
      }
      if (hasNewExe && f.toLowerCase().includes('skydrop') && f.endsWith('.exe') && !f.includes(version)) {
        try {
          fs.unlinkSync(path.join(releaseDir, f));
          console.log(`  - Cleaned up prior Windows installer: ${f}`);
        } catch (_) {}
      }
    }

    // Recalculate checksums across all active release artifacts in releaseDir
    const releaseFilesAfter = fs.readdirSync(releaseDir);
    const stagedArtifacts = releaseFilesAfter.filter(f =>
      f.endsWith('.AppImage') || f.endsWith('.deb') || f.endsWith('.exe')
    );
    stagedArtifacts.sort();
    const releaseChecksumLines = [];
    for (const file of stagedArtifacts) {
      const buf = fs.readFileSync(path.join(releaseDir, file));
      const hash = crypto.createHash('sha256').update(buf).digest('hex');
      releaseChecksumLines.push(`${hash}  ${file}`);
    }
    const releaseChecksumPath = path.join(releaseDir, 'SHA256SUMS.txt');
    fs.writeFileSync(releaseChecksumPath, releaseChecksumLines.join('\n') + '\n', 'utf8');
    console.log(`  ✓ Updated skydrop-release/SHA256SUMS.txt with ${stagedArtifacts.length} artifacts`);

    console.log(`[SkyDrop Release] Successfully staged v${version} release files in skydrop-release/`);
  }
} catch (err) {
  console.error('[SkyDrop Release] Error processing release artifacts:', err.message);
  process.exit(1);
}
