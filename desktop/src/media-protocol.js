const { protocol, net } = require('electron');
const path = require('path');
const fs = require('fs');

const MIME_MAP = {
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mkv': 'video/x-matroska',
  '.mov': 'video/quicktime',
  '.avi': 'video/x-msvideo',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.bmp': 'image/bmp',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.ogg': 'audio/ogg',
  '.flac': 'audio/flac',
  '.pdf': 'application/pdf'
};

function registerMediaProtocol() {
  protocol.handle('skydrop-media', async (request) => {
    try {
      const url = new URL(request.url);
      let filePath = url.pathname;
      if (filePath.startsWith('/')) {
        filePath = filePath.slice(1);
      }
      filePath = decodeURIComponent(filePath);

      // Clean up path
      const resolvedPath = path.resolve(filePath);

      // Verify file exists
      if (!fs.existsSync(resolvedPath)) {
        return new Response('File not found', { status: 404 });
      }

      const stat = await fs.promises.stat(resolvedPath);
      if (!stat.isFile()) {
        return new Response('Not a file', { status: 400 });
      }

      const ext = path.extname(resolvedPath).toLowerCase();
      const contentType = MIME_MAP[ext] || 'application/octet-stream';

      // Support Range requests for smooth video playback and seeking
      const rangeHeader = request.headers.get('Range');
      if (rangeHeader) {
        const parts = rangeHeader.replace(/bytes=/, '').split('-');
        const start = parseInt(parts[0], 10);
        const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
        const chunkSize = end - start + 1;

        const stream = fs.createReadStream(resolvedPath, { start, end });
        return new Response(stream, {
          status: 206,
          headers: {
            'Content-Range': `bytes ${start}-${end}/${stat.size}`,
            'Accept-Ranges': 'bytes',
            'Content-Length': String(chunkSize),
            'Content-Type': contentType
          }
        });
      }

      const stream = fs.createReadStream(resolvedPath);
      return new Response(stream, {
        status: 200,
        headers: {
          'Content-Length': String(stat.size),
          'Content-Type': contentType,
          'Accept-Ranges': 'bytes'
        }
      });
    } catch (e) {
      console.warn('[MediaProtocol] Error handling request:', e);
      return new Response('Internal error', { status: 500 });
    }
  });
}

module.exports = { registerMediaProtocol };
