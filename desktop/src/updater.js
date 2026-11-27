const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { URL } = require('url');

const DEFAULT_REPO_OWNER = 'PMGEECODE';
const DEFAULT_REPO_NAME = 'skydrop-release';

/**
 * Compares two semantic version strings (e.g. "1.0.1" vs "1.0.0").
 * Returns 1 if v1 > v2, -1 if v1 < v2, 0 if equal.
 */
function compareVersions(v1, v2) {
  const clean = (v) => (v || '').replace(/^v/i, '').trim();
  const s1 = clean(v1);
  const s2 = clean(v2);

  // Split into core version and prerelease tag if any
  const [core1] = s1.split('-');
  const [core2] = s2.split('-');

  const parts1 = core1.split('.').map((n) => parseInt(n, 10) || 0);
  const parts2 = core2.split('.').map((n) => parseInt(n, 10) || 0);

  const maxLen = Math.max(parts1.length, parts2.length);
  for (let i = 0; i < maxLen; i++) {
    const p1 = parts1[i] || 0;
    const p2 = parts2[i] || 0;
    if (p1 > p2) return 1;
    if (p1 < p2) return -1;
  }
  return 0;
}

class Updater {
  constructor(options = {}) {
    this.app = options.app || null;
    this.repoOwner = options.repoOwner || DEFAULT_REPO_OWNER;
    this.repoName = options.repoName || DEFAULT_REPO_NAME;
    this.configDir = options.configDir || ((this.app && typeof this.app.getPath === 'function') ? this.app.getPath('userData') : path.join(os.homedir(), '.config', 'skydrop'));
    this.settingsFilePath = options.settingsFilePath || path.join(this.configDir, 'desktop-settings.json');
    this.customAppVersion = options.currentVersion || null;
    this.customInstallType = options.installType || null;
    this.pendingUpdate = null;
  }

  getInstallationType() {
    if (this.customInstallType) {
      return this.customInstallType;
    }
    // On Windows, package type is Windows Installer executable (.exe)
    if (process.platform === 'win32') {
      return 'exe';
    }
    // If APPIMAGE environment variable is present, we are running as an AppImage
    if (process.env.APPIMAGE) {
      return 'appimage';
    }
    // If running from system directories used by .deb installation
    const execPath = process.execPath || '';
    if (execPath.startsWith('/opt/SkyDrop') || execPath.startsWith('/usr/')) {
      return 'deb';
    }
    // Check if installed as debian package via dpkg
    try {
      const { execSync } = require('child_process');
      execSync('dpkg -s skydrop-desktop', { stdio: 'ignore' });
      return 'deb';
    } catch (_) {}

    return 'appimage';
  }

  getCurrentVersion() {
    if (this.customAppVersion) return this.customAppVersion;
    if (this.app && typeof this.app.getVersion === 'function') {
      return this.app.getVersion();
    }
    try {
      return require('../package.json').version;
    } catch (_) {
      return '1.0.0';
    }
  }

  getSettings() {
    try {
      if (fs.existsSync(this.settingsFilePath)) {
        return JSON.parse(fs.readFileSync(this.settingsFilePath, 'utf8')) || {};
      }
    } catch (_) {}
    return {};
  }

  saveSettings(newSettings) {
    try {
      if (!fs.existsSync(this.configDir)) {
        fs.mkdirSync(this.configDir, { recursive: true });
      }
      const existing = this.getSettings();
      const updated = { ...existing, ...newSettings };
      fs.writeFileSync(this.settingsFilePath, JSON.stringify(updated, null, 2) + '\n', 'utf8');
      return true;
    } catch (err) {
      console.error('[Updater] Failed to save updater settings:', err.message);
      return false;
    }
  }

  isAutoCheckEnabled() {
    const settings = this.getSettings();
    return settings.autoCheckUpdates !== false; // defaults to true
  }

  setAutoCheckEnabled(enabled) {
    return this.saveSettings({ autoCheckUpdates: Boolean(enabled) });
  }

  getLastCheckTime() {
    return this.getSettings().lastCheckTime || null;
  }

  recordCheckTime() {
    this.saveSettings({ lastCheckTime: new Date().toISOString() });
  }

  /**
   * Helper to execute HTTP GET following redirects
   */
  requestWithRedirects(url, headers = {}, maxRedirects = 5) {
    return new Promise((resolve, reject) => {
      if (maxRedirects < 0) {
        return reject(new Error('Too many redirects while connecting to server'));
      }

      const parsedUrl = new URL(url);
      const client = parsedUrl.protocol === 'http:' ? http : https;

      const req = client.get(url, { headers, timeout: 15000 }, (res) => {
        // Handle HTTP redirects (301, 302, 307, 308)
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          const redirectUrl = new URL(res.headers.location, url).toString();
          res.resume(); // Discard data
          return resolve(this.requestWithRedirects(redirectUrl, headers, maxRedirects - 1));
        }
        resolve(res);
      });

      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy();
        reject(new Error('Connection timed out'));
      });
    });
  }

  /**
   * Checks GitHub releases for a newer version
   */
  async checkGitHubUpdate(options = {}) {
    const includePrereleases = Boolean(options.includePrereleases);
    const currentVersion = this.getCurrentVersion();
    const url = `https://api.github.com/repos/${this.repoOwner}/${this.repoName}/releases`;

    this.recordCheckTime();

    try {
      const res = await this.requestWithRedirects(url, {
        'User-Agent': 'SkyDrop-Desktop-App',
        'Accept': 'application/vnd.github.v3+json'
      });

      if (res.statusCode !== 200) {
        console.warn(`[Updater] GitHub API HTTP ${res.statusCode}, falling back to Atom feed...`);
        return this.checkUpdateViaAtomFeed(options, currentVersion);
      }

      const body = await new Promise((resolve, reject) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve(data));
        res.on('error', reject);
      });

      let releases = JSON.parse(body);
      if (!Array.isArray(releases)) {
        releases = [releases];
      }

      // Filter drafts and (optionally) prereleases
      const validReleases = releases.filter((r) => {
        if (!r || r.draft) return false;
        if (r.prerelease && !includePrereleases) return false;
        return true;
      });

      if (validReleases.length === 0) {
        return {
          ok: true,
          updateAvailable: false,
          currentVersion,
          latestVersion: currentVersion,
          message: 'No published releases found.'
        };
      }

      // Sort releases by semver descending
      validReleases.sort((a, b) => compareVersions(b.tag_name || b.name, a.tag_name || a.name));
      const latestRelease = validReleases[0];
      const latestTag = (latestRelease.tag_name || latestRelease.name || '').replace(/^v/i, '').trim();

      const isNewer = compareVersions(latestTag, currentVersion) > 0;

      // Select matching asset based on host installation type (AppImage vs deb vs exe)
      const installType = this.getInstallationType();
      const assets = latestRelease.assets || [];
      const appImageAsset = assets.find((a) => a.name.toLowerCase().endsWith('.appimage'));
      const debAsset = assets.find((a) => a.name.toLowerCase().endsWith('.deb'));
      const exeAsset = assets.find((a) => a.name.toLowerCase().endsWith('.exe'));
      // Strict asset selection: each install type picks exactly its own artifact.
      // A deb install never receives an AppImage (and vice versa) because they use
      // completely different install paths and cannot substitute for each other.
      const targetAsset = installType === 'exe'
        ? (exeAsset || null)
        : (installType === 'deb'
          ? (debAsset || null)
          : (appImageAsset || null));

      // Locate checksums file (e.g. SHA256SUMS.txt, or asset.sha256)
      const checksumAsset = assets.find((a) => {
        const n = a.name.toLowerCase();
        return n.includes('sha256') || n.includes('checksum');
      });

      const updateData = {
        ok: true,
        updateAvailable: isNewer,
        currentVersion,
        latestVersion: latestTag,
        releaseName: latestRelease.name || `v${latestTag}`,
        releaseNotes: latestRelease.body || 'No release notes provided.',
        releaseUrl: latestRelease.html_url || `https://github.com/${this.repoOwner}/${this.repoName}/releases/tag/v${latestTag}`,
        publishedAt: latestRelease.published_at || null,
        asset: targetAsset ? {
          name: targetAsset.name,
          downloadUrl: targetAsset.browser_download_url,
          size: targetAsset.size
        } : null,
        checksumAsset: checksumAsset ? {
          name: checksumAsset.name,
          downloadUrl: checksumAsset.browser_download_url
        } : null
      };

      if (isNewer && targetAsset) {
        this.pendingUpdate = updateData;
      }

      return updateData;
    } catch (err) {
      console.warn('[Updater] API Check failed, trying Atom feed fallback:', err.message);
      return this.checkUpdateViaAtomFeed(options, currentVersion);
    }
  }

  /**
   * Fallback release check using GitHub releases Atom feed & expanded_assets.
   * Resilient against unauthenticated GitHub API 403 rate limits.
   */
  async checkUpdateViaAtomFeed(options = {}, currentVersion) {
    const atomUrl = `https://github.com/${this.repoOwner}/${this.repoName}/releases.atom`;
    try {
      const res = await this.requestWithRedirects(atomUrl, {
        'User-Agent': 'SkyDrop-Desktop-App'
      });
      if (res.statusCode !== 200) {
        return {
          ok: false,
          updateAvailable: false,
          currentVersion,
          error: `Failed to fetch releases feed (HTTP ${res.statusCode})`
        };
      }

      const xml = await new Promise((resolve, reject) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve(data));
        res.on('error', reject);
      });

      const entryBlocks = xml.split('<entry>').slice(1);
      if (entryBlocks.length === 0) {
        return {
          ok: true,
          updateAvailable: false,
          currentVersion,
          latestVersion: currentVersion,
          message: 'No releases found in feed.'
        };
      }

      const installType = this.getInstallationType();
      let bestTag = '';
      let bestVersion = '';
      let bestTitle = '';
      let bestTargetAsset = null;
      let bestChecksumAsset = null;

      for (const block of entryBlocks) {
        const idMatch = block.match(/<id>[^<]*\/([^/<]+)<\/id>/) || block.match(/href="[^"]*\/releases\/tag\/([^"]+)"/);
        const rawTag = idMatch ? idMatch[1].trim() : '';
        if (!rawTag || rawTag.startsWith('tv-')) continue;

        const titleMatch = block.match(/<title>([^<]+)<\/title>/);
        const title = titleMatch ? titleMatch[1].trim() : rawTag;
        const cleanTag = (rawTag || '').replace(/^desktop-v/i, '').replace(/^v/i, '').trim();

        if (bestTargetAsset && compareVersions(cleanTag, bestVersion) <= 0) {
          continue;
        }

        const expUrl = `https://github.com/${this.repoOwner}/${this.repoName}/releases/expanded_assets/${rawTag}`;
        try {
          const expRes = await this.requestWithRedirects(expUrl, {
            'User-Agent': 'SkyDrop-Desktop-App'
          });
          if (expRes.statusCode === 200) {
            const html = await new Promise((resolve, reject) => {
              let d = '';
              expRes.on('data', (c) => (d += c));
              expRes.on('end', () => resolve(d));
              expRes.on('error', reject);
            });

            const assetRegex = new RegExp(`href=["'](/${this.repoOwner}/${this.repoName}/releases/download/[^"']+)["']`, 'g');
            let m;
            const assetsList = [];
            while ((m = assetRegex.exec(html)) !== null) {
              const p = m[1];
              const name = p.substring(p.lastIndexOf('/') + 1);
              assetsList.push({
                name,
                downloadUrl: `https://github.com${p}`,
                size: 0
              });
            }

            const appImageAsset = assetsList.find((a) => a.name.toLowerCase().endsWith('.appimage'));
            const debAsset = assetsList.find((a) => a.name.toLowerCase().endsWith('.deb'));
            const exeAsset = assetsList.find((a) => a.name.toLowerCase().endsWith('.exe'));
            const target = installType === 'exe'
              ? (exeAsset || null)
              : (installType === 'deb'
                ? (debAsset || null)
                : (appImageAsset || null));

            if (target) {
              bestTag = rawTag;
              bestVersion = cleanTag;
              bestTitle = title;
              bestTargetAsset = target;
              bestChecksumAsset = assetsList.find((a) => a.name.toLowerCase().includes('sha256') || a.name.toLowerCase().includes('checksum')) || null;
            }
          }
        } catch (_) {}
      }

      if (bestTargetAsset) {
        const isNewer = compareVersions(bestVersion, currentVersion) > 0;
        const updateData = {
          ok: true,
          updateAvailable: isNewer,
          currentVersion,
          latestVersion: bestVersion,
          releaseName: bestTitle || `v${bestVersion}`,
          releaseNotes: 'Automatic background update available.',
          releaseUrl: `https://github.com/${this.repoOwner}/${this.repoName}/releases/tag/${bestTag}`,
          asset: bestTargetAsset,
          checksumAsset: bestChecksumAsset
        };
        if (isNewer) this.pendingUpdate = updateData;
        return updateData;
      }

      return {
        ok: true,
        updateAvailable: false,
        currentVersion,
        latestVersion: currentVersion
      };
    } catch (err) {
      return {
        ok: false,
        updateAvailable: false,
        currentVersion,
        error: err.message
      };
    }
  }

  /**
   * Downloads the release asset and safely updates the running AppImage
   */
  async downloadAndInstall(onProgress = () => {}) {
    if (!this.pendingUpdate || !this.pendingUpdate.asset) {
      return { ok: false, error: 'No update ready to download. Please check for updates first.' };
    }

    const { asset, latestVersion, checksumAsset } = this.pendingUpdate;
    const tempDir = os.tmpdir();

    // Derive the correct file extension from the actual asset name so that
    // dpkg, AppImage replacement, and the Windows NSIS runner each receive a
    // file whose extension matches what they expect.
    const assetExt = path.extname(asset.name).toLowerCase() || '.tmp';
    const tempFile = path.join(tempDir, `skydrop-update-${latestVersion}-${Date.now()}${assetExt}`);

    console.log(`[Updater] Downloading asset ${asset.name} from ${asset.downloadUrl}...`);

    try {
      const res = await this.requestWithRedirects(asset.downloadUrl, {
        'User-Agent': 'SkyDrop-Desktop-App',
        'Accept': 'application/octet-stream'
      });

      if (res.statusCode !== 200) {
        return { ok: false, error: `Failed to download update asset (HTTP ${res.statusCode})` };
      }

      const totalBytes = parseInt(res.headers['content-length'] || asset.size || '0', 10);
      let transferredBytes = 0;
      const hash = crypto.createHash('sha256');
      const fileStream = fs.createWriteStream(tempFile);

      await new Promise((resolve, reject) => {
        res.on('data', (chunk) => {
          transferredBytes += chunk.length;
          hash.update(chunk);
          fileStream.write(chunk);

          const percent = totalBytes > 0 ? Math.min(100, Math.round((transferredBytes / totalBytes) * 100)) : 0;
          onProgress({
            percent,
            transferred: transferredBytes,
            total: totalBytes
          });
        });

        res.on('end', () => {
          fileStream.end();
          resolve();
        });

        res.on('error', (err) => {
          fileStream.destroy();
          reject(err);
        });

        fileStream.on('error', reject);
      });

      const calculatedSha256 = hash.digest('hex');
      console.log(`[Updater] Download complete. SHA-256: ${calculatedSha256}`);

      // Verify checksum if available
      if (checksumAsset) {
        try {
          console.log(`[Updater] Verifying checksum against ${checksumAsset.name}...`);
          const checkRes = await this.requestWithRedirects(checksumAsset.downloadUrl, {
            'User-Agent': 'SkyDrop-Desktop-App'
          });
          if (checkRes.statusCode === 200) {
            let checkText = '';
            await new Promise((resolve) => {
              checkRes.on('data', (d) => (checkText += d));
              checkRes.on('end', resolve);
            });

            // Match hash in checksum file
            const lowerCheckText = checkText.toLowerCase();
            if (!lowerCheckText.includes(calculatedSha256.toLowerCase())) {
              try { fs.unlinkSync(tempFile); } catch (_) {}
              return {
                ok: false,
                error: 'Integrity check failed: downloaded file checksum does not match release verification checksum.'
              };
            }
            console.log('[Updater] Checksum integrity verified successfully.');
          }
        } catch (checkErr) {
          console.warn('[Updater] Checksum file download failed, proceeding with caution:', checkErr.message);
        }
      }

      // Set executable permission
      fs.chmodSync(tempFile, 0o755);

      // Handle Windows installer execution
      if (asset.name.toLowerCase().endsWith('.exe')) {
        console.log(`[Updater] Executing Windows installer: ${tempFile}`);
        const { spawn } = require('child_process');
        try {
          const child = spawn(tempFile, ['/S'], { detached: true, stdio: 'ignore' });
          child.unref();
          return {
            ok: true,
            readyToRestart: true,
            installedVersion: latestVersion,
            targetPath: tempFile
          };
        } catch (winErr) {
          console.error('[Updater] Failed to launch Windows installer:', winErr.message);
          return {
            ok: false,
            error: `Failed to launch installer: ${winErr.message}`
          };
        }
      }

      // Handle Debian package installation
      if (asset.name.toLowerCase().endsWith('.deb')) {
        console.log(`[Updater] Installing deb package via pkexec: ${tempFile}`);
        const { execSync } = require('child_process');
        try {
          execSync(`pkexec dpkg -i "${tempFile}"`, { stdio: 'inherit' });
          console.log('[Updater] Successfully installed deb package');
          try { fs.unlinkSync(tempFile); } catch (_) {}
          return {
            ok: true,
            readyToRestart: true,
            installedVersion: latestVersion,
            targetPath: '/usr/bin/skydrop-desktop'
          };
        } catch (pkErr) {
          const destDeb = path.join(os.homedir(), 'Downloads', asset.name);
          try {
            fs.copyFileSync(tempFile, destDeb);
            fs.unlinkSync(tempFile);
          } catch (_) {}
          console.warn('[Updater] pkexec installation cancelled or failed, saved to Downloads:', destDeb);
          return {
            ok: false,
            error: `Authorization cancelled or failed. Saved package to Downloads/${asset.name}.`,
            debPath: destDeb
          };
        }
      }

      // Determine target path for replacement
      // If running inside an AppImage, process.env.APPIMAGE contains the host path
      const targetPath = process.env.APPIMAGE || null;

      if (targetPath) {
        const backupPath = `${targetPath}.bak`;
        console.log(`[Updater] Replacing ${targetPath} atomically with backup at ${backupPath}`);

        try {
          // Create backup
          if (fs.existsSync(targetPath)) {
            fs.copyFileSync(targetPath, backupPath);
          }

          // Atomic move/replace
          try {
            fs.renameSync(tempFile, targetPath);
          } catch (renameErr) {
            // If cross-device move, copy and delete
            fs.copyFileSync(tempFile, targetPath);
            fs.unlinkSync(tempFile);
          }

          console.log('[Updater] Successfully replaced AppImage file with new version');
        } catch (replaceErr) {
          console.error('[Updater] Failed to replace AppImage, rolling back...', replaceErr.message);
          if (fs.existsSync(backupPath)) {
            try {
              fs.copyFileSync(backupPath, targetPath);
            } catch (_) {}
          }
          try { fs.unlinkSync(tempFile); } catch (_) {}
          return { ok: false, error: `Failed to install update: ${replaceErr.message}` };
        }
      } else {
        console.log('[Updater] App is running in development or non-AppImage mode; update saved to:', tempFile);
      }

      return {
        ok: true,
        readyToRestart: true,
        installedVersion: latestVersion,
        targetPath: targetPath || tempFile
      };
    } catch (err) {
      console.error('[Updater] Download & install failed:', err.message);
      try {
        if (fs.existsSync(tempFile)) fs.unlinkSync(tempFile);
      } catch (_) {}
      return { ok: false, error: `Download failed: ${err.message}` };
    }
  }

  restartApp() {
    if (this.app) {
      console.log('[Updater] Relaunching SkyDrop application...');
      const execPath = process.env.APPIMAGE || process.execPath;
      this.app.relaunch({ execPath });
      this.app.exit(0);
    }
  }
}

module.exports = {
  Updater,
  compareVersions
};
