#!/usr/bin/env node

/**
 * SkyDrop Unified Independent Release Dispatcher
 *
 * Usage:
 *   node scripts/release.js desktop [patch|minor|major]
 *   node scripts/release.js tv [patch|minor|major]
 *   node scripts/release.js auto [patch|minor|major]
 */

const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const rootDir = path.resolve(__dirname, '..');
const target = (process.argv[2] || 'auto').toLowerCase();
const bumpType = process.argv[3] || 'patch';

function detectChangedComponents() {
  try {
    const diffFiles = execSync('git diff --name-only HEAD~1 HEAD', { cwd: rootDir, encoding: 'utf8' })
      + execSync('git status --porcelain', { cwd: rootDir, encoding: 'utf8' });
    
    const lines = diffFiles.split('\n').filter(Boolean);
    const hasDesktop = lines.some((l) => l.includes('desktop/') || l.includes('daemon/'));
    const hasTv = lines.some((l) => l.includes('skydrop-tv-app/'));

    return { hasDesktop, hasTv };
  } catch (_) {
    return { hasDesktop: true, hasTv: false };
  }
}

console.log('\n======================================================');
console.log(`[SkyDrop Release Dispatcher] Target: ${target} | Bump: ${bumpType}`);
console.log('======================================================\n');

if (target === 'desktop') {
  console.log('Dispatching Desktop Release (only desktop packages & release notes will be built)...');
  execSync(`node scripts/release.js ${bumpType}`, { cwd: path.join(rootDir, 'desktop'), stdio: 'inherit' });
} else if (target === 'tv') {
  console.log('Dispatching TV Release (only TV APK & TV release notes will be built)...');
  execSync(`node scripts/release-tv.js ${bumpType}`, { cwd: rootDir, stdio: 'inherit' });
} else if (target === 'auto') {
  const { hasDesktop, hasTv } = detectChangedComponents();
  console.log(`Auto-detected modified components -> Desktop: ${hasDesktop}, TV: ${hasTv}`);

  if (hasDesktop && !hasTv) {
    console.log('Only Desktop changes detected. Triggering Desktop release...');
    execSync(`node scripts/release.js ${bumpType}`, { cwd: path.join(rootDir, 'desktop'), stdio: 'inherit' });
  } else if (hasTv && !hasDesktop) {
    console.log('Only TV changes detected. Triggering TV release...');
    execSync(`node scripts/release-tv.js ${bumpType}`, { cwd: rootDir, stdio: 'inherit' });
  } else if (hasDesktop && hasTv) {
    console.log('Both Desktop and TV changes detected. Triggering independent releases for both...');
    execSync(`node scripts/release.js ${bumpType}`, { cwd: path.join(rootDir, 'desktop'), stdio: 'inherit' });
    execSync(`node scripts/release-tv.js ${bumpType}`, { cwd: rootDir, stdio: 'inherit' });
  } else {
    console.log('No recent modifications detected. Please specify target: node scripts/release.js [desktop|tv]');
  }
} else {
  console.error(`Unknown release target: "${target}". Expected "desktop", "tv", or "auto".`);
  process.exit(1);
}
