const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const repoDir = path.resolve(__dirname, '..');
const tvDir = path.join(repoDir, 'skydrop-tv-app');
const gradleFile = path.join(tvDir, 'tv', 'build.gradle.kts');

try {
  const bumpArg = process.argv[2] || 'patch';
  console.log(`\n======================================================`);
  console.log(`[SkyDrop TV Release] Target increment: ${bumpArg}`);
  console.log(`======================================================\n`);

  if (!fs.existsSync(gradleFile)) {
    throw new Error(`Gradle file not found at ${gradleFile}`);
  }

  let content = fs.readFileSync(gradleFile, 'utf8');

  // Extract current versionCode and versionName
  const vcMatch = content.match(/versionCode\s*=\s*(\d+)/);
  const vnMatch = content.match(/versionName\s*=\s*"([^"]+)"/);

  if (!vcMatch || !vnMatch) {
    throw new Error('Could not parse versionCode or versionName from build.gradle.kts');
  }

  const currentCode = parseInt(vcMatch[1], 10);
  const currentName = vnMatch[1];

  let newName = '';
  if (/^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$/.test(bumpArg.replace(/^v/i, ''))) {
    newName = bumpArg.replace(/^v/i, '');
  } else {
    const parts = currentName.split('-')[0].split('.').map(n => parseInt(n, 10) || 0);
    while (parts.length < 3) parts.push(0);

    if (bumpArg === 'major') {
      parts[0] += 1;
      parts[1] = 0;
      parts[2] = 0;
    } else if (bumpArg === 'minor') {
      parts[1] += 1;
      parts[2] = 0;
    } else {
      parts[2] += 1;
    }
    newName = parts.join('.');
  }

  const newCode = currentCode + 1;

  console.log(`Current: v${currentName} (code ${currentCode})`);
  console.log(`Updating to: v${newName} (code ${newCode})`);

  content = content.replace(/versionCode\s*=\s*\d+/, `versionCode = ${newCode}`);
  content = content.replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${newName}"`);
  fs.writeFileSync(gradleFile, content, 'utf8');
  console.log(`✓ Updated build.gradle.kts`);

  // Run tests
  console.log('\n[SkyDrop TV Release] Verifying test suite...');
  execSync('./gradlew :tv:testDebugUnitTest', { cwd: tvDir, stdio: 'inherit' });

  // Stage and commit
  console.log('\n[SkyDrop TV Release] Committing release changes...');
  execSync('git add skydrop-tv-app/tv/build.gradle.kts', { cwd: repoDir, stdio: 'inherit' });
  execSync(`git commit -m "chore(tv): release v${newName}"`, { cwd: repoDir, stdio: 'inherit' });

  // Create release tag
  const tagName = `tv-v${newName}`;
  console.log(`\n[SkyDrop TV Release] Creating git tag: ${tagName}...`);
  execSync(`git tag "${tagName}"`, { cwd: repoDir, stdio: 'inherit' });

  // Push
  console.log(`\n[SkyDrop TV Release] Pushing commit and tag to GitHub...`);
  execSync(`git push origin main && git push origin "${tagName}"`, { cwd: repoDir, stdio: 'inherit' });

  console.log(`\n======================================================`);
  console.log(`🎉 Successfully released SkyDrop TV v${newName}!`);
  console.log(`GitHub Actions will build SkyDrop-tv-${newName}.apk`);
  console.log(`and publish to PMGEECODE/skydrop-release.`);
  console.log(`======================================================\n`);
} catch (err) {
  console.error('\n❌ TV release script failed:', err.message);
  process.exit(1);
}
