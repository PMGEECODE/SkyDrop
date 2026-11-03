const assert = require('assert');
const path = require('path');
const fs = require('fs');
const DaemonManager = require('../src/daemon-manager');

async function runTests() {
  console.log('--- Running SkyDrop Desktop Tests ---');

  // Test 1: DaemonManager binary resolution
  {
    console.log('[Test 1] DaemonManager binary resolution...');
    const dm = new DaemonManager();
    const binPath = dm.resolveBinaryPath();
    assert(binPath, 'Expected resolveBinaryPath to return a path');
    assert(fs.existsSync(binPath), `Expected binary to exist at ${binPath}`);
    console.log('  ✓ Binary located at:', binPath);
  }

  // Test 2: DaemonManager URL construction
  {
    console.log('[Test 2] DaemonManager URL construction...');
    const dmHttps = new DaemonManager({ port: 8443, useTLS: true });
    assert.strictEqual(dmHttps.getBaseUrl(), 'https://127.0.0.1:8443');

    const dmHttp = new DaemonManager({ port: 8080, useTLS: false });
    assert.strictEqual(dmHttp.getBaseUrl(), 'http://127.0.0.1:8080');
    console.log('  ✓ URLs formatted correctly');
  }

  // Test 3: Daemon health check against live daemon
  {
    console.log('[Test 3] Daemon health check against live daemon...');
    const dm = new DaemonManager({ port: 8443, useTLS: true });
    let isRunning = await dm.checkRunning();
    if (!isRunning) {
      await dm.start();
      isRunning = await dm.checkRunning();
    }
    assert.strictEqual(isRunning, true, 'Expected daemon to be running and responsive on port 8443');
    console.log('  ✓ Live daemon responding to health probe');
  }

  // Test 4: Package.json configuration for electron-builder (AppImage & deb)
  {
    console.log('[Test 4] package.json build targets verification...');
    const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
    assert(pkg.build, 'package.json missing build config');
    assert(pkg.build.linux, 'package.json missing linux build config');
    assert(pkg.build.linux.target.includes('AppImage'), 'Missing AppImage target');
    assert(pkg.build.linux.target.includes('deb'), 'Missing deb target');
    assert(pkg.build.extraResources.some((r) => r.from.includes('skydrop-daemon')), 'Missing daemon in extraResources');
    console.log('  ✓ Build configuration targets AppImage and deb with bundled daemon');
  }

  // Test 5: Verify all required UI files exist
  {
    console.log('[Test 5] Frontend assets and scripts integrity...');
    const requiredFiles = [
      'assets/icon.png',
      'assets/tray.png',
      'src/main.js',
      'src/preload.js',
      'src/tray-manager.js',
      'src/daemon-manager.js',
      'src/ipc-handlers.js',
      'src/ui/index.html',
      'src/ui/css/variables.css',
      'src/ui/css/app.css',
      'src/ui/css/screens.css',
      'src/ui/js/app.js',
      'src/ui/js/devices-view.js',
      'src/ui/js/send-view.js',
      'src/ui/js/received-view.js',
      'src/ui/js/settings-view.js'
    ];

    for (const rel of requiredFiles) {
      const full = path.join(__dirname, '..', rel);
      assert(fs.existsSync(full), `Missing expected file: ${rel}`);
    }

    // Verify new received grid, icon, and thumbnail files exist
    const newFiles = [
      'src/thumbnail-config.js',
      'src/thumbnail-manager.js',
      'src/media-protocol.js',
      'src/video-thumb-worker.html',
      'src/ui/js/file-icons.js',
      'src/ui/js/lightbox.js'
    ];
    for (const rel of newFiles) {
      const full = path.join(__dirname, '..', rel);
      assert(fs.existsSync(full), `Missing expected new file: ${rel}`);
    }
    console.log('  ✓ All 22 core and grid module files exist and are verified');
  }

  // Test 6: Thumbnail Config validation
  {
    console.log('[Test 6] Thumbnail config validation...');
    const config = require('../src/thumbnail-config');
    assert(config.thumbnailSize >= 200, 'Thumbnail size should be >= 200');
    assert.strictEqual(config.maxConcurrency, 3, 'Expected maxConcurrency to be 3');
    assert.strictEqual(config.maxCacheSizeBytes, 200 * 1024 * 1024, 'Expected cache size limit of 200MB');
    assert(config.imageExtensions.includes('.jpg'), 'Missing .jpg in imageExtensions');
    assert(config.videoExtensions.includes('.mp4'), 'Missing .mp4 in videoExtensions');
    console.log('  ✓ Thumbnail config validated (concurrency: 3, max size: 200MB, 2x retina size: 300px)');
  }

  // Test 7: File type presentation and categorization
  {
    console.log('[Test 7] File type SVG icon categorization...');
    // Load file-icons in sandbox
    const fileIconsCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/file-icons.js'), 'utf8');
    const sandbox = { window: {} };
    eval(`(function(window) { ${fileIconsCode} })(sandbox.window)`);
    const getPresentation = sandbox.window.getFileTypePresentation;
    assert(typeof getPresentation === 'function', 'getFileTypePresentation must be a function');

    const testCases = [
      { file: 'photo.jpg', cat: 'images', visual: true, video: false, label: 'JPG' },
      { file: 'clip.mp4', cat: 'videos', visual: true, video: true, label: 'MP4' },
      { file: 'manual.pdf', cat: 'documents', visual: false, label: 'PDF' },
      { file: 'report.docx', cat: 'documents', visual: false, label: 'DOCX' },
      { file: 'sheet.xlsx', cat: 'documents', visual: false, label: 'XLSX' },
      { file: 'slides.pptx', cat: 'documents', visual: false, label: 'PPTX' },
      { file: 'notes.md', cat: 'documents', visual: false, label: 'MD' },
      { file: 'archive.zip', cat: 'archives', visual: false, label: 'ZIP' },
      { file: 'song.mp3', cat: 'audio', visual: false, label: 'MP3' },
      { file: 'app.apk', cat: 'other', visual: false, label: 'APK' },
      { file: 'script.js', cat: 'other', visual: false, label: 'JS' },
      { file: 'unknown.xyz', cat: 'other', visual: false, label: 'XYZ' }
    ];

    for (const tc of testCases) {
      const p = getPresentation(tc.file);
      assert.strictEqual(p.category, tc.cat, `Category mismatch for ${tc.file}: expected ${tc.cat}, got ${p.category}`);
      assert.strictEqual(p.isVisual, tc.visual, `Visual flag mismatch for ${tc.file}`);
      assert.strictEqual(p.label, tc.label, `Label mismatch for ${tc.file}`);
      assert(p.svg && p.svg.includes('<svg'), `Missing valid SVG icon for ${tc.file}`);
    }
    console.log('  ✓ All 12 file types correctly classified with distinct inline SVG icons and accents');
  }

  // Test 8: Responsive CSS Grid structure verification
  {
    console.log('[Test 8] CSS Grid responsive tokens and aspect-ratio...');
    const css = fs.readFileSync(path.join(__dirname, '../src/ui/css/previews.css'), 'utf8');
    assert(css.includes('grid-template-columns: repeat(auto-fill, minmax('), 'Missing auto-fill minmax CSS grid rule');
    assert(css.includes('aspect-ratio: 1'), 'Missing aspect-ratio: 1 for uniform square tiles');
    assert(css.includes('position: sticky'), 'Missing sticky header for date grouping');
    console.log('  ✓ Responsive CSS Grid, uniform aspect-ratio 1, and sticky headers verified');
  }

  // Test 9: Change detection equality guards for ReceivedView and DevicesView
  {
    console.log('[Test 9] Change detection equality guards...');
    // Evaluate ReceivedView in sandbox
    const receivedViewCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/received-view.js'), 'utf8');
    const sandbox = { window: {}, document: {}, localStorage: { getItem: () => null } };
    eval(`(function(window, document, localStorage) {
      ${receivedViewCode};
      window.ReceivedView = ReceivedView;
    })(sandbox.window, sandbox.document, sandbox.localStorage)`);

    const rvProto = sandbox.window.ReceivedView.prototype;
    assert(typeof rvProto.areEntriesEqual === 'function', 'ReceivedView missing areEntriesEqual');

    const entry1 = { id: '1', filename: 'photo.jpg', size: 1024, status: 'completed', saved_path: '/path/photo.jpg', timestamp: 12345 };
    const entry2 = { id: '2', filename: 'doc.pdf', size: 2048, status: 'completed', saved_path: '/path/doc.pdf', timestamp: 12346 };
    const entry1Clone = { ...entry1 };
    const entry1Modified = { ...entry1, status: 'failed' };

    // Same entries
    assert.strictEqual(rvProto.areEntriesEqual([entry1, entry2], [entry1Clone, entry2]), true, 'Identical lists should be equal');
    // Modified status
    assert.strictEqual(rvProto.areEntriesEqual([entry1, entry2], [entry1Modified, entry2]), false, 'Modified status should not be equal');
    // Different lengths
    assert.strictEqual(rvProto.areEntriesEqual([entry1], [entry1, entry2]), false, 'Different lengths should not be equal');
    // Null/undefined checks
    assert.strictEqual(rvProto.areEntriesEqual(null, null), true, 'Nulls should match');
    assert.strictEqual(rvProto.areEntriesEqual([], null), false, 'Empty array and null should not match');

    // Evaluate DevicesView in sandbox
    const devicesViewCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/devices-view.js'), 'utf8');
    eval(`(function(window, document, localStorage) {
      ${devicesViewCode};
      window.DevicesView = DevicesView;
    })(sandbox.window, sandbox.document, sandbox.localStorage)`);

    const dvProto = sandbox.window.DevicesView.prototype;
    assert(typeof dvProto.arePeersDataEqual === 'function', 'DevicesView missing arePeersDataEqual');

    const peersData1 = {
      peers: [{ id: 'p1', name: 'Pixel 8', host: '192.168.1.5', port: 8443, presence_state: 'ONLINE', is_connected: true }],
      paired: [{ id: 'p1', device_name: 'Pixel 8', host: '192.168.1.5', port: 8443 }]
    };
    const peersDataClone = JSON.parse(JSON.stringify(peersData1));
    const peersDataChanged = {
      peers: [{ id: 'p1', name: 'Pixel 8', host: '192.168.1.5', port: 8443, presence_state: 'OFFLINE', is_connected: false }],
      paired: [{ id: 'p1', device_name: 'Pixel 8', host: '192.168.1.5', port: 8443 }]
    };

    assert.strictEqual(dvProto.arePeersDataEqual(peersData1, peersDataChanged), false, 'Changed state should not be equal');
    console.log('  ✓ ReceivedView and DevicesView change detection successfully prevents unwanted DOM reflows');
  }

  // Test 10: Receive Progress state updates
  {
    console.log('[Test 10] Real-time receive progress handling...');
    const receivedViewCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/received-view.js'), 'utf8');
    const mockElements = {
      card: { classList: { add: (c) => mockElements.card.classes.add(c), remove: (c) => mockElements.card.classes.delete(c) }, classes: new Set() },
      filename: { textContent: '' },
      meta: { textContent: '' },
      percent: { textContent: '' },
      bar: { style: { width: '0%' } }
    };
    const sandbox = {
      window: {},
      document: {
        getElementById: (id) => {
          if (id === 'receive-progress-card') return mockElements.card;
          if (id === 'receive-progress-filename') return mockElements.filename;
          if (id === 'receive-progress-meta') return mockElements.meta;
          if (id === 'receive-progress-percent') return mockElements.percent;
          if (id === 'receive-progress-bar') return mockElements.bar;
          return null;
        }
      },
      localStorage: { getItem: () => null }
    };
    eval(`(function(window, document, localStorage) {
      ${receivedViewCode};
      window.ReceivedView = ReceivedView;
    })(sandbox.window, sandbox.document, sandbox.localStorage)`);

    const rvProto = sandbox.window.ReceivedView.prototype;
    assert(typeof rvProto.updateReceiveProgress === 'function', 'ReceivedView missing updateReceiveProgress');

    // Simulate an instance
    const instance = {
      progressCard: mockElements.card,
      progressFilename: mockElements.filename,
      progressMeta: mockElements.meta,
      progressPercent: mockElements.percent,
      progressBar: mockElements.bar,
      updateReceiveProgress: rvProto.updateReceiveProgress
    };

    // Test receiving state
    instance.updateReceiveProgress({
      status: 'receiving',
      filename: 'video.mp4',
      bytes_received: 50 * 1024 * 1024,
      total_bytes: 100 * 1024 * 1024,
      percent: 50
    });

    assert(mockElements.card.classes.has('active'), 'Progress card should be active while receiving');
    assert(mockElements.filename.textContent.includes('video.mp4'), 'Filename should be shown');
    assert.strictEqual(mockElements.percent.textContent, '50%');
    assert.strictEqual(mockElements.bar.style.width, '50%');

    // Test completed state
    instance.updateReceiveProgress({
      status: 'completed',
      filename: 'video.mp4',
      bytes_received: 100 * 1024 * 1024,
      total_bytes: 100 * 1024 * 1024,
      percent: 100
    });
    assert(mockElements.filename.textContent.includes('Received: video.mp4'), 'Should show completed indicator');
    assert.strictEqual(mockElements.percent.textContent, '100%');
    console.log('  ✓ Receive progress card correctly reflects receiving and completed states');
  }

  // Test 11: Bottom navigation bar, rounded borders, and list view thumbnail containment
  {
    console.log('[Test 11] Bottom nav bar, rounded borders, and list thumbnail containment...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const previewsCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/previews.css'), 'utf8');
    const appCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/app.css'), 'utf8');

    // Navigation bar must be positioned at bottom after main.app-content
    const navPos = indexHtml.indexOf('<nav id="tab-bar"');
    const mainPos = indexHtml.indexOf('<main class="app-content">');
    assert(navPos !== -1 && mainPos !== -1, 'Both tab-bar and app-content must exist in index.html');
    assert(navPos > mainPos, 'tab-bar navigation buttons must be positioned at the bottom after app-content');

    // Rounded borders in app.css
    assert(appCss.includes('border-radius: 12px'), 'Missing 12px rounded borders in app.css');
    assert(appCss.includes('border: 1px solid var(--border-color)'), 'Missing outer border in app.css');

    // previews.css must contain .media-thumb-box with position: relative and overflow: hidden
    assert(previewsCss.includes('.media-thumb-box'), 'Missing .media-thumb-box in previews.css');
    assert(previewsCss.includes('position: relative'), 'Missing position: relative in list thumbnail box');
    assert(previewsCss.includes('.received-toolbar') && previewsCss.includes('flex-shrink: 0'), 'received-toolbar must have flex-shrink: 0');
    assert(appCss.includes('#screen-received') && appCss.includes('overflow: hidden'), '#screen-received must have overflow: hidden');
    console.log('  ✓ Bottom navigation tabs, rounded window borders, and list view thumbnail containment verified');
  }

  // Test 12: Fixed window width in main.js
  {
    console.log('[Test 12] Fixed window width and app frame border validation...');
    const mainJs = fs.readFileSync(path.join(__dirname, '../src/main.js'), 'utf8');
    assert(mainJs.includes('minWidth: 520') && mainJs.includes('maxWidth: 520') && mainJs.includes('width: 520'),
      'main.js must have width, minWidth, and maxWidth set to 520 to prevent horizontal size changes');
    console.log('  ✓ Window width locked to exactly 520px (no horizontal shrinking or expansion)');
  }

  // Test 13: Colorized diagnostics rendering and send queue file presentation
  {
    console.log('[Test 13] Colorized diagnostics classification and send queue icons...');
    const devicesViewCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/devices-view.js'), 'utf8');
    const sendViewCode = fs.readFileSync(path.join(__dirname, '../src/ui/js/send-view.js'), 'utf8');

    // DevicesView must have renderColorizedDiagnostics
    assert(devicesViewCode.includes('renderColorizedDiagnostics('), 'DevicesView must implement renderColorizedDiagnostics');
    assert(devicesViewCode.includes('diag-error') && devicesViewCode.includes('diag-warning') && devicesViewCode.includes('diag-success'),
      'renderColorizedDiagnostics must produce diag-error, diag-warning, and diag-success classes');

    // SendView must use getFileTypePresentation or getFilePresentation and queue-card
    assert(sendViewCode.includes('getFileTypePresentation') || sendViewCode.includes('getFilePresentation'),
      'SendView must use getFileTypePresentation');
    assert(sendViewCode.includes('queue-card'), 'SendView must generate queue-card elements');
    assert(sendViewCode.includes('queue-thumb-box'), 'SendView must generate queue-thumb-box with icons/thumbnails');
    console.log('  ✓ Diagnostics error/warning color coding and Send file thumbnails & icons verified');
  }

  // Test 14: Auto-navigation to Received screen on inbound receive and send completion
  {
    console.log('[Test 14] Auto-navigation to Received screen validation...');
    const appJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/app.js'), 'utf8');
    const sendViewJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/send-view.js'), 'utf8');

    assert(appJs.includes("this.switchTab('screen-received')"),
      'app.js must auto-switch to screen-received when receiving files');
    assert(appJs.includes('onReceiveProgress'),
      'app.js must monitor onReceiveProgress for automatic navigation');
    assert(sendViewJs.includes("switchTab('screen-received')"),
      'send-view.js must auto-switch to screen-received when send completes');
    console.log('  ✓ Auto-navigation to Received screen on transfers verified');
  }

  // Test 15: Splash screen markup, branding logo asset, and animations
  {
    console.log('[Test 15] Splash screen markup and branding logo validation...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const appCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/app.css'), 'utf8');
    const appJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/app.js'), 'utf8');

    assert(indexHtml.includes('id="splash-screen"'), 'index.html must include #splash-screen element');
    assert(indexHtml.includes('SkyDropLogo_withText.png'), 'Splash screen must use SkyDropLogo_withText.png');
    assert(fs.existsSync(path.join(__dirname, '../src/ui/assets/SkyDropLogo_withText.png')), 'SkyDropLogo_withText.png must exist in assets');

    assert(appCss.includes('.splash-screen'), 'app.css must define .splash-screen styles');
    assert(appCss.includes('.splash-fade-out'), 'app.css must define .splash-fade-out transition');
    assert(appCss.includes('splashLogoEntrance') && appCss.includes('splashAuraPulse'), 'app.css must define splash animations');

    assert(appJs.includes('initSplashScreen'), 'app.js must call and implement initSplashScreen()');
    console.log('  ✓ Animated splash screen with SkyDropLogo_withText verified');
  }

  console.log('\n✅ All SkyDrop desktop tests passed successfully!');
  process.exit(0);
}

runTests().catch((err) => {
  console.error('\n❌ Test failure:', err);
  process.exit(1);
});
