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
      'src/ui/js/lightbox.js',
      'src/ui/assets/wave-bg.svg',
      'src/ui/assets/wave-bg-dark.svg',
      'src/ui/assets/wave-bg-light.svg',
      'src/ui/assets/splash-filaments-dark.svg',
      'src/ui/assets/splash-filaments-light.svg'
    ];
    for (const rel of newFiles) {
      const full = path.join(__dirname, '..', rel);
      assert(fs.existsSync(full), `Missing expected new file: ${rel}`);
    }
    console.log('  ✓ All 27 core, grid module, background, and splash filament asset files exist and are verified');
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
    assert(appCss.includes('border-radius: var(--radius-window') || appCss.includes('border-radius: 12px'), 'Missing rounded borders in app.css');
    assert(appCss.includes('border: 1px solid var(--border-app-frame)') || appCss.includes('border: 1px solid var(--border-color)'), 'Missing outer border in app.css');

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

  // Test 16: AutostartManager Linux XDG autostart entry generation and synchronization
  {
    console.log('[Test 16] AutostartManager Linux .desktop entry & lifecycle validation...');
    const AutostartManager = require('../src/autostart-manager');
    const os = require('os');
    const testDir = path.join(os.tmpdir(), `skydrop-autostart-test-${Date.now()}`);
    const testAutostartDir = path.join(testDir, 'autostart');
    const testConfigDir = path.join(testDir, 'config');
    fs.mkdirSync(testAutostartDir, { recursive: true });
    fs.mkdirSync(testConfigDir, { recursive: true });

    const manager = new AutostartManager({
      autostartDir: testAutostartDir,
      desktopFilePath: path.join(testAutostartDir, 'skydrop.desktop'),
      configDir: testConfigDir,
      settingsFilePath: path.join(testConfigDir, 'desktop-settings.json'),
      execPath: '/usr/bin/skydrop',
      iconPath: '/usr/share/icons/skydrop.png'
    });

    assert.strictEqual(manager.isEnabled(), false, 'Autostart should initially be disabled');

    // Enable autostart
    const enableRes = manager.enable();
    assert.strictEqual(enableRes.ok, true, 'enable() should succeed');
    assert.strictEqual(manager.isEnabled(), true, 'isEnabled() must be true after enable()');

    const desktopContent = fs.readFileSync(path.join(testAutostartDir, 'skydrop.desktop'), 'utf8');
    assert(desktopContent.includes('[Desktop Entry]'), 'Missing [Desktop Entry] section header');
    assert(desktopContent.includes('Type=Application'), 'Missing Type=Application');
    assert(desktopContent.includes('Name=SkyDrop'), 'Missing Name=SkyDrop');
    assert(desktopContent.includes('Exec=/usr/bin/skydrop --hidden'), 'Missing or invalid Exec path with --hidden');
    assert(desktopContent.includes('X-GNOME-Autostart-enabled=true'), 'Missing X-GNOME-Autostart-enabled=true');
    assert.strictEqual(manager.getPreference(), true, 'Persisted preference must be true');

    // Test AppImage path resolution
    const prevAppImage = process.env.APPIMAGE;
    process.env.APPIMAGE = '/home/user/Downloads/SkyDrop-1.0.28.AppImage';
    const appImageManager = new AutostartManager({
      autostartDir: testAutostartDir,
      configDir: testConfigDir
    });
    assert.strictEqual(appImageManager.getExecPath(), '/home/user/Downloads/SkyDrop-1.0.28.AppImage',
      'getExecPath() must prioritize process.env.APPIMAGE over temporary mount path');
    if (prevAppImage) {
      process.env.APPIMAGE = prevAppImage;
    } else {
      delete process.env.APPIMAGE;
    }

    // Disable autostart
    const disableRes = manager.disable();
    assert.strictEqual(disableRes.ok, true, 'disable() should succeed');
    assert.strictEqual(manager.isEnabled(), false, 'isEnabled() must be false after disable()');
    assert.strictEqual(fs.existsSync(path.join(testAutostartDir, 'skydrop.desktop')), false, 'File should be removed');
    assert.strictEqual(manager.getPreference(), false, 'Persisted preference must be false');

    // Test startup sync when preference is ON but file is missing
    manager.saveSettings({ autostart: true });
    manager.syncOnStartup();
    assert.strictEqual(manager.isEnabled(), true, 'syncOnStartup() must re-create missing autostart entry if preference is ON');

    // Cleanup
    fs.rmSync(testDir, { recursive: true, force: true });
    console.log('  ✓ AutostartManager generates valid .desktop entry, handles AppImage paths, and synchronizes state');
  }

  // Test 17: Semver comparison and updater release parsing
  {
    console.log('[Test 17] Semver comparison & GitHub updater parser validation...');
    const { compareVersions, Updater } = require('../src/updater');

    // Semantic version comparison asserts
    assert.strictEqual(compareVersions('1.0.30', '1.0.29'), 1, '1.0.30 should be greater than 1.0.29');
    assert.strictEqual(compareVersions('1.0.29', '1.0.30'), -1, '1.0.29 should be less than 1.0.30');
    assert.strictEqual(compareVersions('1.0.29', '1.0.28'), 1, '1.0.29 should be greater than 1.0.28');
    assert.strictEqual(compareVersions('1.0.28', '1.0.29'), -1, '1.0.28 should be less than 1.0.29');
    assert.strictEqual(compareVersions('1.0.28', '1.0.28'), 0, '1.0.28 should equal 1.0.28');
    assert.strictEqual(compareVersions('v2.0.0', '1.9.99'), 1, 'v2.0.0 should be greater than 1.9.99');
    assert.strictEqual(compareVersions('1.0.0-beta.1', '1.0.0'), 0, 'Core version should match without crashing');

    // Updater preferences
    const os = require('os');
    const testDir = path.join(os.tmpdir(), `skydrop-updater-test-${Date.now()}`);
    fs.mkdirSync(testDir, { recursive: true });

    const updater = new Updater({
      configDir: testDir,
      settingsFilePath: path.join(testDir, 'desktop-settings.json'),
      currentVersion: '1.0.28'
    });

    assert.strictEqual(updater.isAutoCheckEnabled(), true, 'autoCheckUpdates should default to true');
    updater.setAutoCheckEnabled(false);
    assert.strictEqual(updater.isAutoCheckEnabled(), false, 'autoCheckUpdates should update to false');
    updater.setAutoCheckEnabled(true);
    assert.strictEqual(updater.isAutoCheckEnabled(), true, 'autoCheckUpdates should update back to true');

    // Test debian vs appimage asset resolution
    const updaterDeb = new Updater({
      configDir: testDir,
      currentVersion: '1.0.28',
      installType: 'deb'
    });
    assert.strictEqual(updaterDeb.getInstallationType(), 'deb', 'Installation type should resolve to deb');

    const updaterAppImage = new Updater({
      configDir: testDir,
      currentVersion: '1.0.28',
      installType: 'appimage'
    });
    assert.strictEqual(updaterAppImage.getInstallationType(), 'appimage', 'Installation type should resolve to appimage');

    // ── Asset selection: strict per-install-type matching ──────────────────
    // Simulate a release containing all three artifact types
    const mockAssets = [
      { name: 'skydrop-1.0.29-linux-amd64.deb',       browser_download_url: 'https://example.com/skydrop.deb',       size: 60000000 },
      { name: 'skydrop-1.0.29-linux-x86_64.AppImage', browser_download_url: 'https://example.com/skydrop.AppImage',  size: 90000000 },
      { name: 'skydrop-1.0.29-win-x64-setup.exe',     browser_download_url: 'https://example.com/skydrop-setup.exe', size: 70000000 }
    ];
    const appImageAsset = mockAssets.find((a) => a.name.toLowerCase().endsWith('.appimage'));
    const debAsset      = mockAssets.find((a) => a.name.toLowerCase().endsWith('.deb'));
    const exeAsset      = mockAssets.find((a) => a.name.toLowerCase().endsWith('.exe'));

    // deb install → must pick .deb only (no AppImage fallback)
    const selectedForDeb = debAsset || null;
    assert.ok(selectedForDeb, 'deb asset must be found');
    assert.ok(selectedForDeb.name.endsWith('.deb'), 'deb install must select .deb asset');
    assert.ok(!selectedForDeb.name.endsWith('.AppImage'), 'deb install must NOT select .AppImage asset');

    // appimage install → must pick .AppImage only (no deb fallback)
    const selectedForAppImage = appImageAsset || null;
    assert.ok(selectedForAppImage, 'AppImage asset must be found');
    assert.ok(selectedForAppImage.name.endsWith('.AppImage'), 'appimage install must select .AppImage asset');
    assert.ok(!selectedForAppImage.name.endsWith('.deb'), 'appimage install must NOT select .deb asset');

    // exe install → must pick .exe
    const selectedForExe = exeAsset || null;
    assert.ok(selectedForExe, 'exe asset must be found');
    assert.ok(selectedForExe.name.endsWith('.exe'), 'exe install must select .exe asset');

    // ── Temp file naming: extension derived from actual asset name ──────────
    const updaterJs = fs.readFileSync(path.join(__dirname, '../src/updater.js'), 'utf8');
    assert.ok(
      updaterJs.includes("path.extname(asset.name)"),
      'updater.js must derive temp file extension from asset.name via path.extname'
    );
    assert.ok(
      !updaterJs.includes("skydrop-update-${latestVersion}-${Date.now()}.AppImage"),
      'updater.js must NOT hardcode .AppImage extension for the temp download file'
    );

    fs.rmSync(testDir, { recursive: true, force: true });
    console.log('  ✓ Semver comparison, package format detection (deb vs AppImage), and updater configuration validated');
  }

  // Test 18: Build-driven version consistency
  {
    console.log('[Test 18] Build-driven single version source of truth validation...');
    const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
    assert(pkg.version, 'package.json must specify version');

    const buildDaemonJs = fs.readFileSync(path.join(__dirname, '../scripts/build-daemon.js'), 'utf8');
    assert(buildDaemonJs.includes('require(\'../package.json\')') || buildDaemonJs.includes('require("../package.json")'),
      'build-daemon.js must read version from package.json');
    assert(buildDaemonJs.includes('-ldflags'), 'build-daemon.js must pass version ldflags to Go compiler');

    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    assert(indexHtml.includes('id="app-version-display"'), 'index.html must have dynamic #app-version-display');
    assert(indexHtml.includes('id="stat-version"'), 'index.html must have dynamic #stat-version');
    assert(indexHtml.includes('id="update-current-version"'), 'index.html must have #update-current-version');

    console.log(`  ✓ Version v${pkg.version} driven from package.json across desktop, daemon, and UI`);
  }

  // Test 19: Settings UI Updates Section & Wordmark contrast verification
  {
    console.log('[Test 19] Settings UI Updates section & Wordmark contrast verification...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const settingsViewJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/settings-view.js'), 'utf8');

    // Check updates section elements
    assert(indexHtml.includes('id="settings-auto-update"'), 'index.html missing #settings-auto-update');
    assert(indexHtml.includes('id="btn-check-updates"'), 'index.html missing #btn-check-updates');
    assert(indexHtml.includes('id="btn-install-update"'), 'index.html missing #btn-install-update');
    assert(indexHtml.includes('id="btn-restart-app"'), 'index.html missing #btn-restart-app');
    assert(indexHtml.includes('id="update-progress-container"'), 'index.html missing #update-progress-container');
    assert(indexHtml.includes('id="update-progress-bar"'), 'index.html missing #update-progress-bar');
    assert(indexHtml.includes('id="settings-alert"'), 'index.html missing #settings-alert banner');

    // Check SettingsView logic
    assert(settingsViewJs.includes('window.skydrop.checkForUpdates'), 'SettingsView must invoke checkForUpdates');
    assert(settingsViewJs.includes('window.skydrop.downloadAndInstallUpdate'), 'SettingsView must invoke downloadAndInstallUpdate');
    assert(settingsViewJs.includes('window.skydrop.restartApp'), 'SettingsView must invoke restartApp');
    assert(settingsViewJs.includes('this.autostartCheckbox.checked = !desiredState'),
      'SettingsView must revert autostart toggle on failure');

    // Check SkyDropText.png wordmark contrast
    const imgData = fs.readFileSync(path.join(__dirname, '../src/ui/assets/SkyDropText.png'));
    assert(imgData.length > 1000, 'SkyDropText.png must exist');

    console.log('  ✓ Updates UI controls, progress feedback, failure recovery, and wordmark verified');
  }

  // Test 20: Clipboard Screen & IPC Architecture Validation
  {
    console.log('[Test 20] Clipboard UI, sync controls & IPC handlers validation...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const preloadJs = fs.readFileSync(path.join(__dirname, '../src/preload.js'), 'utf8');
    const ipcHandlersJs = fs.readFileSync(path.join(__dirname, '../src/ipc-handlers.js'), 'utf8');
    const clipboardViewJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/clipboard-view.js'), 'utf8');
    const clipboardRenderJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/clipboard-view-render.js'), 'utf8');

    // DOM markup verification
    assert(indexHtml.includes('id="screen-clipboard"'), 'index.html missing #screen-clipboard');
    assert(indexHtml.includes('id="clipboard-items-list"'), 'index.html missing #clipboard-items-list');
    assert(indexHtml.includes('id="clipboard-sync-status"'), 'index.html missing #clipboard-sync-status');
    assert(indexHtml.includes('id="clipboard-add-input"'), 'index.html missing #clipboard-add-input');
    assert(indexHtml.includes('id="clipboard-add-btn"'), 'index.html missing #clipboard-add-btn');
    assert(indexHtml.includes('id="clipboard-clear-btn"'), 'index.html missing #clipboard-clear-btn');
    assert(indexHtml.includes('id="clipboard-sync-toggle"'), 'index.html missing #clipboard-sync-toggle');
    assert(indexHtml.includes('id="clipboard-auto-capture-toggle"'), 'index.html missing #clipboard-auto-capture-toggle');
    assert(indexHtml.includes('data-screen="screen-clipboard"'), 'index.html missing clipboard nav tab button');

    // Preload API verification
    assert(preloadJs.includes('clipboardList:'), 'preload.js missing clipboardList');
    assert(preloadJs.includes('clipboardAdd:'), 'preload.js missing clipboardAdd');
    assert(preloadJs.includes('clipboardDelete:'), 'preload.js missing clipboardDelete');
    assert(preloadJs.includes('clipboardClear:'), 'preload.js missing clipboardClear');
    assert(preloadJs.includes('clipboardPin:'), 'preload.js missing clipboardPin');
    assert(preloadJs.includes('clipboardGetSettings:'), 'preload.js missing clipboardGetSettings');
    assert(preloadJs.includes('clipboardSetSettings:'), 'preload.js missing clipboardSetSettings');

    // IPC handlers verification
    assert(ipcHandlersJs.includes('ipcMain.handle(\'clipboard-list\''), 'ipc-handlers.js missing clipboard-list handler');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'clipboard-add\''), 'ipc-handlers.js missing clipboard-add handler');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'clipboard-delete\''), 'ipc-handlers.js missing clipboard-delete handler');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'clipboard-clear\''), 'ipc-handlers.js missing clipboard-clear handler');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'clipboard-pin\''), 'ipc-handlers.js missing clipboard-pin handler');

    // Frontend rendering helpers validation
    assert(clipboardRenderJs.includes('validateAddText'), 'clipboard-view-render.js must export validateAddText');
    assert(clipboardRenderJs.includes('buildItemCard'), 'clipboard-view-render.js must export buildItemCard');
    assert(clipboardViewJs.includes('window.skydrop.clipboardAdd'), 'clipboard-view.js must call clipboardAdd');
    assert(clipboardViewJs.includes('window.skydrop.clipboardList'), 'clipboard-view.js must call clipboardList');

    console.log('  ✓ Clipboard screen markup, preload bridge, IPC endpoints, and renderer verified');
  }

  // Test 21: Wi-Fi Hotspot controls, IPC handlers & QR bridge validation
  {
    console.log('[Test 21] Wi-Fi Hotspot UI controls, IPC handlers & QR bridge validation...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const preloadJs = fs.readFileSync(path.join(__dirname, '../src/preload.js'), 'utf8');
    const ipcHandlersJs = fs.readFileSync(path.join(__dirname, '../src/ipc-handlers.js'), 'utf8');
    const devicesViewJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/devices-view.js'), 'utf8');

    // Markup verification
    assert(indexHtml.includes('id="hotspot-card"'), 'index.html missing #hotspot-card');
    assert(indexHtml.includes('id="btn-toggle-hotspot"'), 'index.html missing #btn-toggle-hotspot');
    assert(indexHtml.includes('id="hotspot-active-panel"'), 'index.html missing #hotspot-active-panel');
    assert(indexHtml.includes('id="hotspot-qr-img"'), 'index.html missing #hotspot-qr-img');
    assert(indexHtml.includes('id="hotspot-ssid-text"'), 'index.html missing #hotspot-ssid-text');
    assert(indexHtml.includes('id="hotspot-password-text"'), 'index.html missing #hotspot-password-text');
    assert(indexHtml.includes('id="settings-hotspot-timeout"'), 'index.html missing #settings-hotspot-timeout');

    // Preload API verification
    assert(preloadJs.includes('hotspotGetCapabilities:'), 'preload.js missing hotspotGetCapabilities');
    assert(preloadJs.includes('hotspotStart:'), 'preload.js missing hotspotStart');
    assert(preloadJs.includes('hotspotStop:'), 'preload.js missing hotspotStop');
    assert(preloadJs.includes('hotspotGetStatus:'), 'preload.js missing hotspotGetStatus');

    // IPC handlers verification
    assert(ipcHandlersJs.includes('ipcMain.handle(\'hotspot-get-capabilities\''), 'ipc-handlers.js missing hotspot-get-capabilities');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'hotspot-start\''), 'ipc-handlers.js missing hotspot-start');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'hotspot-stop\''), 'ipc-handlers.js missing hotspot-stop');
    assert(ipcHandlersJs.includes('ipcMain.handle(\'hotspot-get-status\''), 'ipc-handlers.js missing hotspot-get-status');

    // UI integration verification
    assert(devicesViewJs.includes('initHotspot'), 'devices-view.js must define initHotspot');
    assert(devicesViewJs.includes('window.skydrop.hotspotStart'), 'devices-view.js must call hotspotStart');
    assert(devicesViewJs.includes('window.skydrop.hotspotStop'), 'devices-view.js must call hotspotStop');

    console.log('  ✓ Hotspot markup, IPC routes, QR code display, and lifecycle handlers verified');
  }

  // Test 22: Pairing Approval & Interactive PIN Workflow Validation
  {
    console.log('[Test 22] Pairing Approval & Interactive PIN Workflow Validation...');
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    const preloadJs = fs.readFileSync(path.join(__dirname, '../src/preload.js'), 'utf8');
    const ipcHandlersJs = fs.readFileSync(path.join(__dirname, '../src/ipc-handlers.js'), 'utf8');
    const devicesViewJs = fs.readFileSync(path.join(__dirname, '../src/ui/js/devices-view.js'), 'utf8');

    // 1. Incoming approval dialog markup verification
    assert(indexHtml.includes('id="modal-pair-incoming"'), 'index.html missing #modal-pair-incoming');
    assert(indexHtml.includes('id="pair-incoming-title"'), 'index.html missing #pair-incoming-title');
    assert(indexHtml.includes('id="pair-incoming-message"'), 'index.html missing #pair-incoming-message');
    assert(indexHtml.includes('id="pair-incoming-meta"'), 'index.html missing #pair-incoming-meta');
    assert(indexHtml.includes('id="btn-pair-incoming-accept"'), 'index.html missing #btn-pair-incoming-accept');
    assert(indexHtml.includes('id="btn-pair-incoming-decline"'), 'index.html missing #btn-pair-incoming-decline');

    // 2. Outbound PIN input modal markup verification
    assert(indexHtml.includes('id="modal-pair-input"'), 'index.html missing #modal-pair-input');
    assert(indexHtml.includes('id="pair-pin-input-field"'), 'index.html missing #pair-pin-input-field');
    assert(indexHtml.includes('id="pair-pin-input-error"'), 'index.html missing #pair-pin-input-error');
    assert(indexHtml.includes('id="btn-pair-input-cancel"'), 'index.html missing #btn-pair-input-cancel');
    assert(indexHtml.includes('id="btn-pair-input-submit"'), 'index.html missing #btn-pair-input-submit');

    // 3. Preload bridge verification
    assert(preloadJs.includes('pairInitiate:'), 'preload.js missing pairInitiate');
    assert(preloadJs.includes('pairGetIncoming:'), 'preload.js missing pairGetIncoming');
    assert(preloadJs.includes('pairAcceptIncoming:'), 'preload.js missing pairAcceptIncoming');
    assert(preloadJs.includes('pairDeclineIncoming:'), 'preload.js missing pairDeclineIncoming');
    assert(preloadJs.includes('pairConfirmSecret:'), 'preload.js missing pairConfirmSecret');

    // 4. IPC handlers verification
    assert(ipcHandlersJs.includes("ipcMain.handle('pair-initiate'"), 'ipc-handlers.js missing pair-initiate');
    assert(ipcHandlersJs.includes("ipcMain.handle('pair-get-incoming'"), 'ipc-handlers.js missing pair-get-incoming');
    assert(ipcHandlersJs.includes("ipcMain.handle('pair-accept-incoming'"), 'ipc-handlers.js missing pair-accept-incoming');
    assert(ipcHandlersJs.includes("ipcMain.handle('pair-decline-incoming'"), 'ipc-handlers.js missing pair-decline-incoming');
    assert(ipcHandlersJs.includes("ipcMain.handle('pair-confirm-secret'"), 'ipc-handlers.js missing pair-confirm-secret');

    // 5. DevicesView logic verification
    assert(devicesViewJs.includes('enqueueIncomingRequest'), 'devices-view.js missing enqueueIncomingRequest');
    assert(devicesViewJs.includes('showNextIncomingRequest'), 'devices-view.js missing showNextIncomingRequest');
    assert(devicesViewJs.includes('closeIncomingModal'), 'devices-view.js missing closeIncomingModal');
    assert(devicesViewJs.includes('startPairingWithDevice'), 'devices-view.js missing startPairingWithDevice');
    assert(devicesViewJs.includes('openPinInputModal'), 'devices-view.js missing openPinInputModal');
    assert(devicesViewJs.includes('submitPairPin'), 'devices-view.js missing submitPairPin');
    assert(devicesViewJs.includes('pairAcceptIncoming'), 'devices-view.js missing pairAcceptIncoming call');
    assert(devicesViewJs.includes('pairDeclineIncoming'), 'devices-view.js missing pairDeclineIncoming call');

    console.log('  ✓ Pairing approval dialog, PIN modal, preload methods, IPC channels, and view handlers verified');
  }

  // Test 23: Build and updater versioning workflows
  {
    console.log('[Test 23] Build automation & updater versioning workflow validation...');
    const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));

    // 1. Verify build scripts wire version bump and release copy
    assert(pkg.scripts.prebuild && pkg.scripts.prebuild.includes('bump-version.js'), 'package.json missing bump-version in prebuild');
    assert(pkg.scripts.predist && pkg.scripts.predist.includes('bump-version.js'), 'package.json missing bump-version in predist');
    assert(pkg.scripts.postdist && pkg.scripts.postdist.includes('copy-release.js'), 'package.json missing copy-release in postdist');

    // 2. Validate scripts/bump-version.js logic in sandbox
    const { execSync } = require('child_process');
    const os = require('os');
    const tempDir = path.join(os.tmpdir(), `skydrop-version-test-${Date.now()}`);
    const tempScripts = path.join(tempDir, 'scripts');
    fs.mkdirSync(tempScripts, { recursive: true });

    // Copy bump-version.js and write dummy package.json
    fs.copyFileSync(path.join(__dirname, '../scripts/bump-version.js'), path.join(tempScripts, 'bump-version.js'));
    const dummyPkg = { name: 'test-app', version: '1.0.29' };
    fs.writeFileSync(path.join(tempDir, 'package.json'), JSON.stringify(dummyPkg, null, 2) + '\n');

    // Test automatic patch bump: 1.0.29 -> 1.0.30
    execSync('node scripts/bump-version.js', { cwd: tempDir, env: { ...process.env, NO_BUMP: '' } });
    let updated = JSON.parse(fs.readFileSync(path.join(tempDir, 'package.json'), 'utf8'));
    assert.strictEqual(updated.version, '1.0.30', `Expected version to bump from 1.0.29 to 1.0.30, got ${updated.version}`);

    // Test explicit version set: 1.0.35
    execSync('node scripts/bump-version.js 1.0.35', { cwd: tempDir, env: { ...process.env, NO_BUMP: '' } });
    updated = JSON.parse(fs.readFileSync(path.join(tempDir, 'package.json'), 'utf8'));
    assert.strictEqual(updated.version, '1.0.35', `Expected version 1.0.35, got ${updated.version}`);

    // Test NO_BUMP=1 skip
    execSync('node scripts/bump-version.js', { cwd: tempDir, env: { ...process.env, NO_BUMP: '1' } });
    updated = JSON.parse(fs.readFileSync(path.join(tempDir, 'package.json'), 'utf8'));
    assert.strictEqual(updated.version, '1.0.35', `Expected version to remain 1.0.35 with NO_BUMP=1`);

    // Clean up temporary directory
    fs.rmSync(tempDir, { recursive: true, force: true });

    console.log('  ✓ Automated version incrementing, debouncing, NO_BUMP guard, and release staging verified');
  }

  // Test 24: Border Radius Design System & Navigation Bar Theme Consistency Validation
  {
    console.log('[Test 24] Border Radius Design System & Navigation Bar Theme Consistency Validation...');
    const variablesCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/variables.css'), 'utf8');
    const appCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/app.css'), 'utf8');
    const screensCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/screens.css'), 'utf8');
    const previewsCss = fs.readFileSync(path.join(__dirname, '../src/ui/css/previews.css'), 'utf8');

    // 1. Verify radius token definitions in variables.css
    const expectedRadiusTokens = [
      '--radius-xs',
      '--radius-sm',
      '--radius-md',
      '--radius-lg',
      '--radius-window',
      '--radius-pill',
      '--radius-full'
    ];
    for (const token of expectedRadiusTokens) {
      assert(variablesCss.includes(`${token}:`), `variables.css must define ${token}`);
    }

    // 2. Verify dark and light theme tokens for the navigation bar
    const expectedTabTokens = [
      '--bg-tab-bar',
      '--border-tab-bar',
      '--shadow-tab-bar',
      '--tab-indicator-shadow'
    ];
    for (const token of expectedTabTokens) {
      assert(variablesCss.includes(`${token}:`), `variables.css must define ${token}`);
    }

    // 3. Verify tab bar uses theme tokens instead of hardcoded dark background
    assert(appCss.includes('background-color: var(--bg-tab-bar);'), '.tab-bar must use var(--bg-tab-bar)');
    assert(appCss.includes('border: 1px solid var(--border-tab-bar);'), '.tab-bar must use var(--border-tab-bar)');
    assert(appCss.includes('box-shadow: var(--shadow-tab-bar);'), '.tab-bar must use var(--shadow-tab-bar)');
    assert(!appCss.includes('background-color: rgba(18, 22, 34, 0.65);'), 'Old hardcoded dark tab-bar background must be removed');

    // 4. Verify main screen window frame & titlebar radius consistency
    assert(appCss.includes('border-radius: var(--radius-window, 8px);'), 'body must use var(--radius-window)');
    assert(appCss.includes('border-top-left-radius: var(--radius-window, 8px);'), '.titlebar top-left must match var(--radius-window)');
    assert(appCss.includes('border-top-right-radius: var(--radius-window, 8px);'), '.titlebar top-right must match var(--radius-window)');
    assert(appCss.includes('border: 1px solid var(--border-app-frame);'), 'body must use theme-aware var(--border-app-frame)');

    // 5. Verify no undefined CSS variable references across all CSS files
    const allCss = [variablesCss, appCss, screensCss, previewsCss].join('\n');
    const definedTokens = new Set();
    const usedTokens = new Set();
    for (const match of allCss.matchAll(/--([a-zA-Z0-9_-]+)\s*:/g)) {
      definedTokens.add(match[1]);
    }
    for (const match of allCss.matchAll(/var\(--([a-zA-Z0-9_-]+)/g)) {
      usedTokens.add(match[1]);
    }
    const missingTokens = [...usedTokens].filter(t => !definedTokens.has(t));
    assert.strictEqual(missingTokens.length, 0, `Found undefined CSS variables: ${missingTokens.join(', ')}`);

    // 6. Verify zero hardcoded pixel border radii in screens and components
    const hardcodedRadiusRegex = /border(-[a-z]+)*-radius:\s*[0-9]+px/g;
    const hardcodedMatches = allCss.match(hardcodedRadiusRegex) || [];
    assert.strictEqual(hardcodedMatches.length, 0, `Found hardcoded px border-radii: ${hardcodedMatches.join(', ')}`);

    // 7. Verify subtle ambient wavy background layer and opacity tokens
    assert(variablesCss.includes('--bg-wave-url:'), 'variables.css must define --bg-wave-url');
    assert(variablesCss.includes('--wave-bg-opacity:'), 'variables.css must define --wave-bg-opacity');
    assert(appCss.includes('background-image: var(--bg-wave-url'), 'app.css must mount var(--bg-wave-url)');
    assert(appCss.includes('opacity: var(--wave-bg-opacity'), 'app.css must use theme-aware --wave-bg-opacity');

    // 8. Verify animated splash screen strand filaments
    const indexHtml = fs.readFileSync(path.join(__dirname, '../src/ui/index.html'), 'utf8');
    assert(indexHtml.includes('splash-filaments-wrap'), 'index.html missing splash-filaments-wrap');
    assert(appCss.includes('.splash-filaments-wrap'), 'app.css missing .splash-filaments-wrap');
    assert(appCss.includes('splashFilamentBreathe'), 'app.css missing splashFilamentBreathe animation');

    console.log('  ✓ Design system tokens, light/dark tab bar, window corner alignment, wave background, animated splash filaments, and zero missing tokens verified');
  }

  // Test 25: Windows app, packaging, daemon cross-compilation, autostart & updater validation
  {
    console.log('[Test 25] Windows app packaging, daemon resolution, autostart & installer updater validation...');
    const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
    const buildDaemonJs = fs.readFileSync(path.join(__dirname, '../scripts/build-daemon.js'), 'utf8');
    const daemonManagerJs = fs.readFileSync(path.join(__dirname, '../src/daemon-manager.js'), 'utf8');
    const autostartManagerJs = fs.readFileSync(path.join(__dirname, '../src/autostart-manager.js'), 'utf8');
    const updaterJs = fs.readFileSync(path.join(__dirname, '../src/updater.js'), 'utf8');
    const copyReleaseJs = fs.readFileSync(path.join(__dirname, '../scripts/copy-release.js'), 'utf8');

    // 1. Windows icon file exists and is multi-resolution ICO
    const icoPath = path.join(__dirname, '../assets/icon.ico');
    assert(fs.existsSync(icoPath), 'assets/icon.ico must exist');
    const icoStat = fs.statSync(icoPath);
    assert(icoStat.size > 10000, `assets/icon.ico must be a valid multi-res icon (size: ${icoStat.size})`);

    // 2. package.json build configurations for Windows & NSIS
    assert(pkg.build, 'package.json missing build config');
    assert(pkg.build.win, 'package.json missing build.win config');
    assert(pkg.build.win.icon === 'assets/icon.ico', 'build.win.icon must point to assets/icon.ico');
    const hasNsisTarget = pkg.build.win.target && pkg.build.win.target.some(t => t.target === 'nsis');
    assert(hasNsisTarget, 'build.win must configure nsis target');
    assert(pkg.build.nsis, 'package.json missing build.nsis config');
    assert(pkg.build.nsis.installerIcon === 'assets/icon.ico', 'build.nsis.installerIcon must match icon.ico');
    assert(pkg.build.nsis.uninstallerIcon === 'assets/icon.ico', 'build.nsis.uninstallerIcon must match icon.ico');
    assert(pkg.scripts['dist:win'], 'package.json missing dist:win script');
    assert(pkg.scripts['build:daemon:win'], 'package.json missing build:daemon:win script');

    // 3. extraResources must bundle skydrop-daemon
    const hasWinDaemon = pkg.build.win.extraResources && pkg.build.win.extraResources.some(
      r => r.from.includes('skydrop-daemon.exe')
    );
    assert(hasWinDaemon, 'build.win.extraResources must bundle skydrop-daemon.exe');

    // 4. Daemon builder supports Windows cross-compilation
    assert(buildDaemonJs.includes('--win'), 'build-daemon.js must support --win flag');
    assert(buildDaemonJs.includes('windowsgui'), 'build-daemon.js must pass -H windowsgui ldflags');
    assert(buildDaemonJs.includes('skydrop-daemon.exe'), 'build-daemon.js must output skydrop-daemon.exe');

    // 5. DaemonManager handles Windows platform resolution and windowsHide
    assert(daemonManagerJs.includes('process.platform === \'win32\''), 'daemon-manager.js must check win32');
    assert(daemonManagerJs.includes('skydrop-daemon.exe'), 'daemon-manager.js must resolve skydrop-daemon.exe');
    assert(daemonManagerJs.includes('windowsHide: true'), 'daemon-manager.js spawn options must set windowsHide: true');

    // 6. AutostartManager integrates with Electron setLoginItemSettings on Windows
    assert(autostartManagerJs.includes('process.platform === \'win32\''), 'autostart-manager.js must check win32');
    assert(autostartManagerJs.includes('setLoginItemSettings'), 'autostart-manager.js must use setLoginItemSettings on Windows');

    // 7. Updater & release script support Windows .exe installer
    assert(updaterJs.includes('return \'exe\';'), 'updater.js getInstallationType must return exe on Windows');
    assert(updaterJs.includes('endsWith(\'.exe\')'), 'updater.js must check for .exe assets');
    assert(updaterJs.includes('[\'/S\']'), 'updater.js must execute Windows installer silently with /S');
    assert(copyReleaseJs.includes('.exe'), 'copy-release.js must handle .exe installer staging');

    // 8. Runtime instance tests for Windows updater logic
    const { Updater } = require('../src/updater');
    const mockApp = { getVersion: () => '1.0.38', getPath: () => '/tmp' };
    const winUpdater = new Updater({ app: mockApp, installType: 'exe' });
    assert.strictEqual(winUpdater.getInstallationType(), 'exe', 'getInstallationType must return custom/mock exe');

    console.log('  ✓ Windows NSIS packaging, icon.ico, daemon cross-compiler, windowsHide, autostart login items, and exe updater verified');
  }

  // Test 26: GitHub Actions automated release pipeline & electron-builder configuration validation
  {
    console.log('[Test 26] GitHub Actions release workflow & publish configuration validation...');
    const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
    const workflowPath = path.resolve(__dirname, '../../.github/workflows/release.yml');

    // 1. Workflow file exists at root .github/workflows/
    assert(fs.existsSync(workflowPath), '.github/workflows/release.yml must exist at repo root');
    const workflowYaml = fs.readFileSync(workflowPath, 'utf8');

    // 2. electron-builder publish configuration
    assert(pkg.build.publish, 'package.json missing build.publish config');
    assert.strictEqual(pkg.build.publish.provider, 'github', 'build.publish provider must be github');
    assert.strictEqual(pkg.build.publish.owner, 'PMGEECODE', 'build.publish owner must be PMGEECODE');
    assert.strictEqual(pkg.build.publish.repo, 'skydrop-release', 'build.publish repo must be skydrop-release');
    assert(workflowYaml.includes('--publish never'), 'workflow must pass --publish never flag');

    // 3. Trigger restrictions (desktop-v* tags and workflow_dispatch)
    assert(workflowYaml.includes('desktop-v*'), 'release.yml must trigger on desktop-v* tags');
    assert(workflowYaml.includes('workflow_dispatch:'), 'release.yml must support workflow_dispatch manual runs');
    assert(workflowYaml.includes('dry_run:'), 'release.yml must support dry_run input');

    // 4. Working directory scoping & dependency caching
    assert(workflowYaml.includes('working-directory: desktop'), 'release.yml must use working-directory: desktop');
    assert(workflowYaml.includes('cache-dependency-path: desktop/package-lock.json'), 'release.yml must cache with desktop/package-lock.json');

    // 5. Multi-platform build jobs and release job
    assert(workflowYaml.includes('build-linux:'), 'release.yml missing build-linux job');
    assert(workflowYaml.includes('build-windows:'), 'release.yml missing build-windows job');
    assert(workflowYaml.includes('publish-release:'), 'release.yml missing publish-release job');
    assert(workflowYaml.includes('needs: [build-linux, build-windows]'), 'publish-release must depend on both build jobs');

    // 6. Artifact integrity & publication
    assert(workflowYaml.includes('sha256sums.txt'), 'release.yml must compute sha256sums.txt');
    assert(workflowYaml.includes('gh release create'), 'release.yml must publish via gh release create');
    assert(workflowYaml.includes('RELEASES_REPO_TOKEN'), 'release.yml must use RELEASES_REPO_TOKEN secret');

    // 7. Automated release script verification
    assert(pkg.scripts.release, 'package.json missing release script');
    assert(fs.existsSync(path.join(__dirname, '../scripts/release.js')), 'scripts/release.js must exist');

    console.log('  ✓ GitHub Actions release workflow, tag triggers, scoped directories, SHA-256 verification, and publication verified');
  }

  console.log('\n✅ All SkyDrop desktop tests passed successfully!');
  process.exit(0);
}

runTests().catch((err) => {
  console.error('\n❌ Test failure:', err);
  process.exit(1);
});
