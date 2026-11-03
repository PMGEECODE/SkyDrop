/**
 * Inline SVG Icon Library & Presentation Helpers for SkyDrop File Types.
 * Zero network dependencies. Designed to look sharp in both dark and light themes.
 */

const SVG_ICONS = {
  pdf: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
    <polyline points="14 2 14 8 20 8"></polyline>
    <line x1="16" y1="13" x2="8" y2="13"></line>
    <line x1="16" y1="17" x2="8" y2="17"></line>
    <polyline points="10 9 9 9 8 9"></polyline>
  </svg>`,

  word: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
    <polyline points="14 2 14 8 20 8"></polyline>
    <line x1="16" y1="13" x2="8" y2="13"></line>
    <line x1="16" y1="17" x2="8" y2="17"></line>
    <path d="M10 9l2 8 2-8"></path>
  </svg>`,

  excel: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
    <polyline points="14 2 14 8 20 8"></polyline>
    <rect x="8" y="12" width="8" height="6" rx="1"></rect>
    <line x1="12" y1="12" x2="12" y2="18"></line>
    <line x1="8" y1="15" x2="16" y2="15"></line>
  </svg>`,

  powerpoint: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
    <polyline points="14 2 14 8 20 8"></polyline>
    <circle cx="12" cy="14" r="3"></circle>
    <path d="M12 11v3h3"></path>
  </svg>`,

  text: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
    <polyline points="14 2 14 8 20 8"></polyline>
    <line x1="16" y1="13" x2="8" y2="13"></line>
    <line x1="16" y1="17" x2="8" y2="17"></line>
    <line x1="10" y1="9" x2="8" y2="9"></line>
  </svg>`,

  archive: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <polyline points="21 8 21 21 3 21 3 8"></polyline>
    <rect x="1" y="3" width="22" height="5"></rect>
    <line x1="10" y1="12" x2="14" y2="12"></line>
  </svg>`,

  audio: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M9 18V5l12-2v13"></path>
    <circle cx="6" cy="18" r="3"></circle>
    <circle cx="18" cy="16" r="3"></circle>
  </svg>`,

  apk: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="5" y="11" width="14" height="10" rx="2"></rect>
    <circle cx="9" cy="7" r="1"></circle>
    <circle cx="15" cy="7" r="1"></circle>
    <path d="M8 3l2 3"></path>
    <path d="M16 3l-2 3"></path>
    <path d="M9 11V8a3 3 0 0 1 6 0v3"></path>
  </svg>`,

  code: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <polyline points="16 18 22 12 16 6"></polyline>
    <polyline points="8 6 2 12 8 18"></polyline>
  </svg>`,

  image: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
    <circle cx="8.5" cy="8.5" r="1.5"></circle>
    <polyline points="21 15 16 10 5 21"></polyline>
  </svg>`,

  video: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <polygon points="23 7 16 12 23 17 23 7"></polygon>
    <rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect>
  </svg>`,

  generic: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
    <polyline points="13 2 13 9 20 9"></polyline>
  </svg>`
};

function getFileTypePresentation(filename) {
  if (!filename) {
    return {
      svg: SVG_ICONS.generic,
      label: 'FILE',
      accent: '#64748b',
      bgTint: 'rgba(100, 116, 139, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'other'
    };
  }

  const ext = filename.split('.').pop().toLowerCase();

  // Images
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', 'svg', 'heic'].includes(ext)) {
    return {
      svg: SVG_ICONS.image,
      label: ext.toUpperCase(),
      accent: '#3b82f6',
      bgTint: 'rgba(59, 130, 246, 0.12)',
      isVisual: true,
      isVideo: false,
      category: 'images'
    };
  }

  // Videos
  if (['mp4', 'mkv', 'mov', 'webm', 'avi', 'm4v', '3gp'].includes(ext)) {
    return {
      svg: SVG_ICONS.video,
      label: ext.toUpperCase(),
      accent: '#8b5cf6',
      bgTint: 'rgba(139, 92, 246, 0.12)',
      isVisual: true,
      isVideo: true,
      category: 'videos'
    };
  }

  // Audio
  if (['mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'wma', 'opus'].includes(ext)) {
    return {
      svg: SVG_ICONS.audio,
      label: ext.toUpperCase(),
      accent: '#06b6d4',
      bgTint: 'rgba(6, 182, 212, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'audio'
    };
  }

  // PDF
  if (ext === 'pdf') {
    return {
      svg: SVG_ICONS.pdf,
      label: 'PDF',
      accent: '#ef4444',
      bgTint: 'rgba(239, 68, 68, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'documents'
    };
  }

  // Word
  if (['doc', 'docx', 'odt', 'rtf'].includes(ext)) {
    return {
      svg: SVG_ICONS.word,
      label: ext.toUpperCase(),
      accent: '#2563eb',
      bgTint: 'rgba(37, 99, 235, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'documents'
    };
  }

  // Excel
  if (['xls', 'xlsx', 'csv', 'ods', 'tsv'].includes(ext)) {
    return {
      svg: SVG_ICONS.excel,
      label: ext.toUpperCase(),
      accent: '#10b981',
      bgTint: 'rgba(16, 185, 129, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'documents'
    };
  }

  // PowerPoint
  if (['ppt', 'pptx', 'odp'].includes(ext)) {
    return {
      svg: SVG_ICONS.powerpoint,
      label: ext.toUpperCase(),
      accent: '#f97316',
      bgTint: 'rgba(249, 115, 22, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'documents'
    };
  }

  // Text / Markdown
  if (['txt', 'md', 'markdown', 'log'].includes(ext)) {
    return {
      svg: SVG_ICONS.text,
      label: ext.toUpperCase(),
      accent: '#64748b',
      bgTint: 'rgba(100, 116, 139, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'documents'
    };
  }

  // Archives
  if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso'].includes(ext)) {
    return {
      svg: SVG_ICONS.archive,
      label: ext.toUpperCase(),
      accent: '#a855f7',
      bgTint: 'rgba(168, 85, 247, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'archives'
    };
  }

  // Android APK
  if (ext === 'apk') {
    return {
      svg: SVG_ICONS.apk,
      label: 'APK',
      accent: '#22c55e',
      bgTint: 'rgba(34, 197, 94, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'other'
    };
  }

  // Code files
  if (['js', 'ts', 'jsx', 'tsx', 'py', 'go', 'rs', 'java', 'kt', 'c', 'cpp', 'h', 'cs', 'php', 'rb', 'json', 'yaml', 'yml', 'xml', 'html', 'css'].includes(ext)) {
    return {
      svg: SVG_ICONS.code,
      label: ext.toUpperCase(),
      accent: '#0284c7',
      bgTint: 'rgba(2, 132, 199, 0.12)',
      isVisual: false,
      isVideo: false,
      category: 'other'
    };
  }

  // Generic fallback
  return {
    svg: SVG_ICONS.generic,
    icon: SVG_ICONS.generic,
    label: ext ? ext.toUpperCase().slice(0, 4) : 'FILE',
    accent: '#64748b',
    bgTint: 'rgba(100, 116, 139, 0.12)',
    isVisual: false,
    isVideo: false,
    category: 'other'
  };
}

window.SVG_ICONS = SVG_ICONS;
window.getFileTypePresentation = getFileTypePresentation;
window.getFilePresentation = getFileTypePresentation;

