/**
 * Configuration for SkyDrop Desktop Thumbnail Pipeline & File Type Classifications
 */
module.exports = {
  // Max thumbnail pixel size (2x for retina displays)
  thumbnailSize: 300,

  // Max concurrent thumbnail generation jobs
  maxConcurrency: 3,

  // Disk cache size limit (200 MB)
  maxCacheSizeBytes: 200 * 1024 * 1024,

  // Supported image extensions for thumbnail generation
  imageExtensions: ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.avif', '.svg'],

  // Supported video extensions for frame extraction
  videoExtensions: ['.mp4', '.mkv', '.mov', '.webm', '.avi', '.m4v'],

  // Density presets for the responsive CSS grid
  densityPresets: {
    compact: 84,
    comfortable: 112,
    large: 148
  },

  // Default grid density
  defaultDensity: 'comfortable',

  // Default view mode: 'grid' or 'list'
  defaultViewMode: 'grid'
};
