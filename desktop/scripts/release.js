const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const desktopDir = path.resolve(__dirname, '..');
const repoDir = path.resolve(__dirname, '../..');
const pkgPath = path.join(desktopDir, 'package.json');

try {
  const bumpArg = process.argv[2] || 'patch';
  console.log(`\n======================================================`);
  console.log(`[SkyDrop Automated Release] Target increment: ${bumpArg}`);
  console.log(`======================================================\n`);

  // 1. Run bump-version.js to advance version and sync package-lock.json
  execSync(`node scripts/bump-version.js ${bumpArg}`, { cwd: desktopDir, stdio: 'inherit' });

  // 2. Read new version
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
  const version = pkg.version;
  console.log(`\n✓ Advanced version to v${version}`);

  // 3. Update RELEASE_NOTES.md header if it exists
  const notesPath = path.join(desktopDir, 'RELEASE_NOTES.md');
  if (fs.existsSync(notesPath)) {
    let notes = fs.readFileSync(notesPath, 'utf8');
    notes = notes.replace(/^##\s+What's\s+New(\s+in\s+v[0-9.]+)?/im, `## What's New in v${version}`);
    fs.writeFileSync(notesPath, notes, 'utf8');
    console.log(`✓ Synchronized desktop/RELEASE_NOTES.md to v${version}`);
  }

  // 4. Run automated test suite
  console.log('\n[SkyDrop Automated Release] Verifying test suite...');
  execSync('npm test', { cwd: desktopDir, stdio: 'inherit' });

  // 5. Stage files and commit
  console.log('\n[SkyDrop Automated Release] Committing release files...');
  execSync('git add desktop/package.json desktop/package-lock.json desktop/RELEASE_NOTES.md', { cwd: repoDir, stdio: 'inherit' });
  execSync(`git commit -m "chore(desktop): release v${version}"`, { cwd: repoDir, stdio: 'inherit' });

  // 6. Create the single workflow trigger tag (desktop-v*) — no v* tag to avoid a parallel duplicate CI run
  console.log(`\n[SkyDrop Automated Release] Creating release tag: desktop-v${version}...`);
  execSync(`git tag "desktop-v${version}"`, { cwd: repoDir, stdio: 'inherit' });

  // 7. Push commit and tag to origin — only desktop-v* triggers the release workflow
  console.log('\n[SkyDrop Automated Release] Pushing main branch and release tag to GitHub...');
  execSync(`git push origin main && git push origin "desktop-v${version}"`, { cwd: repoDir, stdio: 'inherit' });

  console.log(`\n======================================================`);
  console.log(`🎉 Successfully released SkyDrop v${version}!`);
  console.log(`GitHub Actions is now automatically building Linux & Windows packages`);
  console.log(`and will publish to PMGEECODE/skydrop-release.`);
  console.log(`======================================================\n`);
} catch (err) {
  console.error('\n❌ Release script aborted:', err.message);
  process.exit(1);
}
