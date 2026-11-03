const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, nativeImage, BrowserWindow, ipcMain } = require('electron');
const config = require('./thumbnail-config');

class ThumbnailManager {
  constructor() {
    this.cacheDir = path.join(app.getPath('userData'), 'thumbnails');
    this.queue = [];
    this.activeWorkers = 0;
    this.maxWorkers = config.maxConcurrency || 3;
    this.memoryCache = new Map(); // Fast in-memory hot cache: key -> dataUrl
    this.videoWorkerWindow = null;
    this.videoTasks = new Map();
    this.nextTaskId = 1;

    this.initCacheDir();
    this.initVideoWorker();
  }

  initCacheDir() {
    try {
      if (!fs.existsSync(this.cacheDir)) {
        fs.mkdirSync(this.cacheDir, { recursive: true });
      }
    } catch (e) {
      console.warn('[ThumbnailManager] Could not create cache dir:', e);
    }
  }

  initVideoWorker() {
    ipcMain.on('video-frame-captured', (event, { taskId, result }) => {
      const task = this.videoTasks.get(taskId);
      if (task) {
        this.videoTasks.delete(taskId);
        task.resolve(result);
      }
    });
  }

  ensureVideoWorker() {
    if (this.videoWorkerWindow && !this.videoWorkerWindow.isDestroyed()) {
      return this.videoWorkerWindow;
    }
    this.videoWorkerWindow = new BrowserWindow({
      width: 320,
      height: 240,
      show: false,
      webPreferences: {
        nodeIntegration: true,
        contextIsolation: false,
        backgroundThrottling: false
      }
    });
    this.videoWorkerWindow.loadFile(path.join(__dirname, 'video-thumb-worker.html'));
    return this.videoWorkerWindow;
  }

  getCacheKey(filePath, stats) {
    const raw = `${filePath}:${stats.size}:${stats.mtimeMs}`;
    return crypto.createHash('sha256').update(raw).digest('hex');
  }

  async getThumbnail(filePath) {
    if (!filePath) return null;

    let stats;
    try {
      stats = await fs.promises.stat(filePath);
    } catch (_) {
      return { missing: true }; // File missing from disk
    }

    const ext = path.extname(filePath).toLowerCase();
    const isImage = config.imageExtensions.includes(ext);
    const isVideo = config.videoExtensions.includes(ext);

    if (!isImage && !isVideo) {
      return null; // Non-visual file, will use SVG icon
    }

    const key = this.getCacheKey(filePath, stats);

    // 1. Check in-memory hot cache
    if (this.memoryCache.has(key)) {
      return this.memoryCache.get(key);
    }

    // 2. Check disk cache
    const cachePath = path.join(this.cacheDir, `${key}.jpg`);
    try {
      if (fs.existsSync(cachePath)) {
        const buf = await fs.promises.readFile(cachePath);
        const dataUrl = `data:image/jpeg;base64,${buf.toString('base64')}`;
        this.rememberMemoryCache(key, dataUrl);
        // Update atime asynchronously
        fs.utimes(cachePath, new Date(), new Date(), () => {});
        return dataUrl;
      }
    } catch (_) {}

    // 3. Queue generation job
    return new Promise((resolve) => {
      this.queue.push({
        key,
        filePath,
        cachePath,
        isImage,
        isVideo,
        resolve
      });
      this.processQueue();
    });
  }

  processQueue() {
    if (this.activeWorkers >= this.maxWorkers || this.queue.length === 0) {
      return;
    }

    const job = this.queue.shift();
    this.activeWorkers++;

    this.generateThumbnail(job)
      .then((dataUrl) => {
        if (dataUrl) {
          this.rememberMemoryCache(job.key, dataUrl);
        }
        job.resolve(dataUrl);
      })
      .catch(() => {
        job.resolve(null);
      })
      .finally(() => {
        this.activeWorkers--;
        this.processQueue();
      });
  }

  async generateThumbnail(job) {
    try {
      if (job.isImage) {
        if (nativeImage) {
          let img = null;
          if (typeof nativeImage.createThumbnailFromPath === 'function') {
            try {
              img = await nativeImage.createThumbnailFromPath(job.filePath, {
                width: config.thumbnailSize,
                height: config.thumbnailSize
              });
            } catch (_) {}
          }
          // Fallback for formats like WebP or AVIF that may not be supported by system thumbnailer
          if (!img || img.isEmpty()) {
            try {
              img = nativeImage.createFromPath(job.filePath);
              if (img && !img.isEmpty()) {
                img = img.resize({ width: config.thumbnailSize, height: config.thumbnailSize });
              }
            } catch (_) {}
          }
          if (img && !img.isEmpty()) {
            const jpegBuf = img.toJPEG(82);
            await fs.promises.writeFile(job.cachePath, jpegBuf);
            this.checkAndEvictCache();
            return `data:image/jpeg;base64,${jpegBuf.toString('base64')}`;
          }
        }
      } else if (job.isVideo) {
        const worker = this.ensureVideoWorker();
        const taskId = this.nextTaskId++;

        const capturePromise = new Promise((resolve) => {
          this.videoTasks.set(taskId, { resolve });
        });

        // Use custom media protocol URL so video loads securely in worker
        const encodedPath = encodeURIComponent(job.filePath);
        const fileUrl = `skydrop-media://local/${encodedPath}`;

        worker.webContents.send('capture-video-frame', {
          taskId,
          fileUrl,
          targetSize: config.thumbnailSize
        });

        const res = await capturePromise;
        if (res && res.success && res.dataUrl) {
          const base64Data = res.dataUrl.replace(/^data:image\/jpeg;base64,/, '');
          const buf = Buffer.from(base64Data, 'base64');
          await fs.promises.writeFile(job.cachePath, buf);
          this.checkAndEvictCache();
          return res.dataUrl;
        }
      }
    } catch (e) {
      console.warn('[ThumbnailManager] Error generating thumbnail for:', job.filePath, e.message);
    }
    return null;
  }

  rememberMemoryCache(key, dataUrl) {
    if (this.memoryCache.size > 300) {
      const first = this.memoryCache.keys().next().value;
      this.memoryCache.delete(first);
    }
    this.memoryCache.set(key, dataUrl);
  }

  async checkAndEvictCache() {
    try {
      const files = await fs.promises.readdir(this.cacheDir);
      let totalSize = 0;
      const fileStats = [];

      for (const file of files) {
        if (!file.endsWith('.jpg')) continue;
        const p = path.join(this.cacheDir, file);
        try {
          const s = await fs.promises.stat(p);
          totalSize += s.size;
          fileStats.push({ path: p, size: s.size, mtime: s.mtimeMs });
        } catch (_) {}
      }

      if (totalSize > config.maxCacheSizeBytes) {
        // Evict oldest until under 75% of limit
        fileStats.sort((a, b) => a.mtime - b.mtime);
        const targetSize = config.maxCacheSizeBytes * 0.75;
        for (const item of fileStats) {
          if (totalSize <= targetSize) break;
          try {
            await fs.promises.unlink(item.path);
            totalSize -= item.size;
          } catch (_) {}
        }
      }
    } catch (_) {}
  }

  async clearCache() {
    this.memoryCache.clear();
    try {
      const files = await fs.promises.readdir(this.cacheDir);
      for (const file of files) {
        await fs.promises.unlink(path.join(this.cacheDir, file)).catch(() => {});
      }
      return { ok: true };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  }

  async getCacheStats() {
    try {
      const files = await fs.promises.readdir(this.cacheDir);
      let totalSize = 0;
      let count = 0;
      for (const file of files) {
        if (!file.endsWith('.jpg')) continue;
        count++;
        const s = await fs.promises.stat(path.join(this.cacheDir, file)).catch(() => ({ size: 0 }));
        totalSize += s.size;
      }
      return { count, totalSizeBytes: totalSize };
    } catch (_) {
      return { count: 0, totalSizeBytes: 0 };
    }
  }

  destroy() {
    if (this.videoWorkerWindow && !this.videoWorkerWindow.isDestroyed()) {
      this.videoWorkerWindow.destroy();
      this.videoWorkerWindow = null;
    }
  }
}

module.exports = ThumbnailManager;
