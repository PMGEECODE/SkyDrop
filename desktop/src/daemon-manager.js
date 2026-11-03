const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const https = require('https');
const http = require('http');

/**
 * Manages the lifecycle of the local SkyDrop Go daemon process.
 */
class DaemonManager {
  constructor(options = {}) {
    this.port = options.port || 8443;
    this.useTLS = options.useTLS !== false;
    this.childProcess = null;
    this.isQuitting = false;
    this.isManaged = false;
    this.restartAttempts = 0;
    this.maxRestarts = 5;
    this.onStatusChange = options.onStatusChange || (() => {});
    this.agent = new https.Agent({ rejectUnauthorized: false });
  }

  getBaseUrl() {
    const proto = this.useTLS ? 'https' : 'http';
    return `${proto}://127.0.0.1:${this.port}`;
  }

  resolveBinaryPath() {
    const candidatePaths = [
      process.resourcesPath ? path.join(process.resourcesPath, 'bin', 'skydrop-daemon') : null,
      path.resolve(__dirname, '../bin/skydrop-daemon'),
      path.resolve(__dirname, '../../daemon/bin/skydrop-daemon'),
      path.resolve(__dirname, '../../desktop/bin/skydrop-daemon')
    ].filter(Boolean);

    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        return p;
      }
    }
    return 'skydrop-daemon';
  }

  async checkRunning() {
    return new Promise((resolve) => {
      const url = `${this.getBaseUrl()}/api/peers`;
      const client = this.useTLS ? https : http;
      const req = client.get(
        url,
        { agent: this.agent, timeout: 1500 },
        (res) => {
          if (res.statusCode >= 200 && res.statusCode < 500) {
            resolve(true);
          } else {
            resolve(false);
          }
        }
      );
      req.on('error', () => resolve(false));
      req.on('timeout', () => {
        req.destroy();
        resolve(false);
      });
    });
  }

  async start() {
    this.isQuitting = false;
    const isAlreadyRunning = await this.checkRunning();
    if (isAlreadyRunning) {
      console.log('[DaemonManager] Existing SkyDrop daemon detected on port', this.port);
      this.isManaged = false;
      this.onStatusChange({ state: 'connected', managed: false, port: this.port });
      return true;
    }

    const binPath = this.resolveBinaryPath();
    if (!fs.existsSync(binPath)) {
      console.warn('[DaemonManager] Daemon binary not found at candidates, attempting PATH:', binPath);
    }

    console.log('[DaemonManager] Spawning daemon binary:', binPath);
    try {
      this.childProcess = spawn(binPath, [], {
        env: {
          ...process.env,
          SKYDROP_PORT: String(this.port),
          SKYDROP_ENABLE_TLS: this.useTLS ? 'true' : 'false'
        },
        stdio: ['ignore', 'pipe', 'pipe']
      });

      this.isManaged = true;

      this.childProcess.stdout.on('data', (data) => {
        const out = data.toString().trim();
        if (out) console.log(`[Daemon] ${out}`);
      });

      this.childProcess.stderr.on('data', (data) => {
        const err = data.toString().trim();
        if (err) console.error(`[Daemon ERR] ${err}`);
      });

      this.childProcess.on('exit', (code, signal) => {
        console.log(`[DaemonManager] Daemon process exited (code=${code}, signal=${signal})`);
        this.childProcess = null;
        if (!this.isQuitting) {
          this.onStatusChange({ state: 'stopped', error: `Process exited code ${code}` });
          this.handleUnexpectedExit();
        }
      });

      // Poll until responsive
      let ready = false;
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 300));
        if (await this.checkRunning()) {
          ready = true;
          break;
        }
      }

      if (ready) {
        this.restartAttempts = 0;
        this.onStatusChange({ state: 'running', managed: true, port: this.port });
        return true;
      } else {
        console.warn('[DaemonManager] Spawned process did not respond in time');
        return false;
      }
    } catch (err) {
      console.error('[DaemonManager] Failed to launch daemon:', err);
      this.onStatusChange({ state: 'error', error: err.message });
      return false;
    }
  }

  handleUnexpectedExit() {
    if (this.restartAttempts >= this.maxRestarts) {
      console.error('[DaemonManager] Max daemon restart attempts reached.');
      return;
    }
    this.restartAttempts++;
    const delay = Math.min(1000 * Math.pow(2, this.restartAttempts), 10000);
    console.log(`[DaemonManager] Scheduling restart attempt ${this.restartAttempts} in ${delay}ms`);
    setTimeout(() => {
      if (!this.isQuitting && !this.childProcess) {
        this.start();
      }
    }, delay);
  }

  stop() {
    this.isQuitting = true;
    if (this.childProcess) {
      console.log('[DaemonManager] Terminating managed daemon process...');
      this.childProcess.kill('SIGTERM');
      setTimeout(() => {
        if (this.childProcess) {
          this.childProcess.kill('SIGKILL');
          this.childProcess = null;
        }
      }, 2500);
    }
  }

  async request(apiPath, options = {}) {
    return new Promise((resolve, reject) => {
      const isHttps = this.useTLS;
      const client = isHttps ? https : http;
      const url = `${this.getBaseUrl()}${apiPath}`;
      const req = client.request(
        url,
        {
          method: options.method || 'GET',
          agent: this.agent,
          timeout: options.timeout || 5000,
          headers: { Accept: 'application/json', ...(options.headers || {}) }
        },
        (res) => {
          let data = '';
          res.on('data', (c) => (data += c));
          res.on('end', () => resolve({ status: res.statusCode, data }));
        }
      );
      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy();
        reject(new Error('timeout'));
      });
      if (options.body) {
        req.write(typeof options.body === 'string' ? options.body : JSON.stringify(options.body));
      }
      req.end();
    });
  }
}

module.exports = DaemonManager;
