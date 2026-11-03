package web

import (
	"net/http"
)

func (wc *WebController) handleDashboard(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(dashboardHTML))
}

const dashboardHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SkyDrop • Linux Control Panel</title>
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #f0f6fc;
      --text-muted: #8b949e;
      --primary: #58a6ff;
      --primary-hover: #79b8ff;
      --accent: #238636;
      --accent-hover: #2ea043;
      --danger: #f85149;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background-color: var(--bg); color: var(--text); padding: 32px 16px; min-height: 100vh; }
    .container { max-width: 800px; margin: 0 auto; }
    header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border); }
    h1 { font-size: 24px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
    .badge { font-size: 12px; padding: 3px 8px; border-radius: 12px; background: #1f6feb26; color: var(--primary); border: 1px solid #1f6feb66; }
    .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 8px; padding: 20px; margin-bottom: 20px; }
    .card-title { font-size: 16px; font-weight: 600; margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center; }
    .btn { background: var(--accent); color: white; border: none; padding: 8px 14px; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: 500; transition: background 0.2s; }
    .btn:hover { background: var(--accent-hover); }
    .btn-secondary { background: var(--border); color: var(--text); }
    .btn-secondary:hover { background: #3c444d; }
    .btn-danger { background: #da3633; color: white; }
    .btn-danger:hover { background: #f85149; }
    .dropzone { border: 2px dashed var(--border); border-radius: 8px; padding: 40px 20px; text-align: center; cursor: pointer; transition: all 0.2s ease; margin-bottom: 16px; }
    .dropzone.dragover { border-color: var(--primary); background: rgba(88, 166, 255, 0.08); }
    .dropzone svg { width: 48px; height: 48px; fill: var(--text-muted); margin-bottom: 12px; }
    .peer-item { display: flex; align-items: center; justify-content: space-between; padding: 12px; background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 6px; margin-bottom: 8px; cursor: pointer; }
    .peer-item.selected { border-color: var(--primary); background: rgba(88,166,255,0.08); }
    .peer-info { display: flex; flex-direction: column; gap: 2px; }
    .peer-name { font-weight: 600; font-size: 14px; }
    .peer-meta { font-size: 12px; color: var(--text-muted); }
    .pin-display { font-family: monospace; font-size: 20px; background: #000; padding: 8px 16px; border-radius: 6px; letter-spacing: 2px; color: #7ee787; border: 1px solid var(--border); display: inline-block; }
    .progress-bar { width: 100%; height: 8px; background: var(--border); border-radius: 4px; overflow: hidden; margin-top: 12px; display: none; }
    .progress-fill { height: 100%; width: 0%; background: var(--primary); transition: width 0.1s linear; }
    #status-msg { margin-top: 8px; font-size: 13px; font-weight: 500; }
    .history-table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 13px; }
    .history-table th, .history-table td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); }
    .history-table th { color: var(--text-muted); font-weight: 600; }
    .status-badge { font-size: 11px; padding: 3px 8px; border-radius: 10px; font-weight: 600; display: inline-flex; align-items: center; gap: 5px; }
    .status-connected { background: rgba(35, 134, 54, 0.2); color: #7ee787; border: 1px solid rgba(35, 134, 54, 0.4); }
    .status-offline { background: rgba(248, 81, 73, 0.15); color: #ff7b72; border: 1px solid rgba(248, 81, 73, 0.3); }
    .status-unpaired { background: rgba(88, 166, 255, 0.15); color: #79b8ff; border: 1px solid rgba(88, 166, 255, 0.3); }
    .status-completed { background: rgba(35, 134, 54, 0.2); color: #7ee787; border: 1px solid rgba(35, 134, 54, 0.4); }
    .status-failed { background: rgba(248, 81, 73, 0.2); color: #ff7b72; border: 1px solid rgba(248, 81, 73, 0.4); }
    .dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; }
    .dot-green { background: #3fb950; box-shadow: 0 0 6px #3fb950; }
    .dot-red { background: #f85149; }
    .dot-blue { background: #58a6ff; }
    .connected-banner { background: rgba(35, 134, 54, 0.15); border: 1px solid rgba(35, 134, 54, 0.4); color: #7ee787; padding: 12px 16px; border-radius: 6px; margin-bottom: 16px; display: none; align-items: center; justify-content: space-between; }
    .pairing-success-box { background: rgba(35, 134, 54, 0.2); border: 1px solid #3fb950; color: #7ee787; padding: 14px; border-radius: 8px; margin-top: 12px; display: none; font-weight: 600; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1><span>⚡ SkyDrop</span> <span class="badge" id="device-badge">Linux</span></h1>
      <button class="btn btn-secondary" onclick="openFolder()">📂 Open Downloads</button>
    </header>

    <div id="connection-banner" class="connected-banner"></div>

    <div class="card">
      <div class="card-title">Send to Nearby Phone or Peer</div>
      <div id="dropzone" class="dropzone" onclick="document.getElementById('file-input').click()">
        <svg viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
        <p style="font-weight: 600; margin-bottom: 4px;">Drag and drop files here to send</p>
        <p style="color: var(--text-muted); font-size: 13px;">or click to browse your filesystem (multiple files supported)</p>
        <input type="file" id="file-input" style="display:none;" multiple onchange="handleFileSelect(this.files)">
      </div>
      <div class="progress-bar" id="progress-bar"><div class="progress-fill" id="progress-fill"></div></div>
      <div id="status-msg"></div>
    </div>

    <div class="card">
      <div class="card-title">
        <span>Discovered Peers (mDNS)</span>
        <button class="btn btn-secondary" onclick="loadPeers()">🔄 Scan</button>
      </div>
      <div id="peer-list"><p style="color:var(--text-muted); font-size:13px;">Scanning local Wi-Fi...</p></div>
    </div>

    <div class="card">
      <div class="card-title">
        <span>Device Pairing</span>
        <button class="btn" onclick="generatePairSession()">Generate Pair PIN & QR</button>
      </div>
      <div id="pair-status-info" style="display:none; padding:10px 14px; background:rgba(35, 134, 54, 0.15); border:1px solid rgba(35, 134, 54, 0.4); border-radius:6px; color:#7ee787; font-size:13px; margin-bottom:12px;"></div>
      <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 12px;">
        Scan the QR code with your phone or enter the 6-digit PIN in the SkyDrop app:
      </p>
      <div id="pairing-area" style="display:none; text-align:center; padding:12px 0;">
        <div style="margin-bottom:12px;">
          <span style="font-size:12px; color:var(--text-muted); text-transform:uppercase; letter-spacing:1px; display:block; margin-bottom:6px;">6-Digit Pairing PIN</span>
          <div class="pin-display" id="pin-code">------</div>
        </div>
        <div id="qr-container" style="display:inline-block; margin:8px auto; padding:10px; background:#fff; border-radius:8px;">
          <img id="qr-img" src="" alt="Pairing QR Code" style="width:200px; height:200px; display:block;" />
        </div>
        <p style="font-size:12px; color:var(--text-muted); margin-top:8px; word-break:break-all;" id="pair-uri"></p>
        <div id="pairing-success" class="pairing-success-box"></div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <span>Transfer History</span>
        <button class="btn btn-secondary btn-danger" onclick="clearHistory()">Clear</button>
      </div>
      <div id="history-container"><p style="color:var(--text-muted); font-size:13px;">Loading history...</p></div>
    </div>
  </div>

  <script>
    let selectedPeer = null;
    let initialPairedCount = -1;

    async function loadPeers(forceScan) {
      try {
        const url = forceScan ? '/api/peers?scan=true' : '/api/peers';
        const res = await fetch(url);
        const data = await res.json();
        document.getElementById('device-badge').textContent = data.device || 'Linux';
        const list = document.getElementById('peer-list');
        list.innerHTML = '';

        const peers = data.peers || [];
        const pairedList = data.paired || [];

        // Check if pairing completed live
        if (initialPairedCount >= 0 && pairedList.length > initialPairedCount) {
          const newlyPaired = pairedList[pairedList.length - 1];
          const successBox = document.getElementById('pairing-success');
          if (successBox) {
            successBox.style.display = 'block';
            successBox.innerHTML = '🎉 Successfully Paired & Connected with <strong>' + (newlyPaired.name || 'Phone') + '</strong>!';
          }
          const pinEl = document.getElementById('pin-code');
          if (pinEl) pinEl.innerHTML = '<span style="color:#7ee787;">PAIRED & CONNECTED</span>';
          const qrContainer = document.getElementById('qr-container');
          if (qrContainer) qrContainer.style.display = 'none';
          initialPairedCount = pairedList.length;
        }

        // Top live connection banner
        const activePaired = peers.find(p => p.is_paired && p.is_connected);
        const banner = document.getElementById('connection-banner');
        if (banner) {
          if (activePaired) {
            banner.style.display = 'flex';
            banner.style.borderColor = 'rgba(35, 134, 54, 0.4)';
            banner.style.background = 'rgba(35, 134, 54, 0.15)';
            banner.innerHTML = '<span><span class="dot dot-green"></span> <strong>Connected & Paired:</strong> ' + activePaired.name + ' (' + activePaired.host + ':' + activePaired.port + ') • Ready for instantaneous drops</span><span class="badge" style="background:#238636;color:white;">Online</span>';
          } else {
            const anyPaired = peers.find(p => p.is_paired);
            if (anyPaired) {
              banner.style.display = 'flex';
              banner.style.borderColor = 'rgba(248, 81, 73, 0.4)';
              banner.style.background = 'rgba(248, 81, 73, 0.1)';
              banner.innerHTML = '<span style="color:#ff7b72;"><span class="dot dot-red"></span> <strong>' + anyPaired.name + '</strong> is Disconnected (Offline) • Reconnects automatically on Wi-Fi</span>';
            } else {
              banner.style.display = 'none';
            }
          }
        }

        const pairStatusInfo = document.getElementById('pair-status-info');
        if (pairStatusInfo) {
          if (activePaired) {
            pairStatusInfo.style.display = 'block';
            pairStatusInfo.innerHTML = '🟢 <strong>Currently Paired & Connected:</strong> ' + activePaired.name + ' (' + activePaired.host + ':' + activePaired.port + ') • Ready for instantaneous drops!';
          } else {
            pairStatusInfo.style.display = 'none';
          }
        }

        if (peers.length === 0) {
          list.innerHTML = '<p style="color:var(--text-muted); font-size:13px;">No peers detected. Ensure SkyDrop is open on your phone on the same Wi-Fi network.</p>';
          return;
        }

        peers.forEach(peer => {
          const item = document.createElement('div');
          const isSelected = selectedPeer && (selectedPeer.id === peer.id || (selectedPeer.host === peer.host && selectedPeer.port === peer.port));
          item.className = 'peer-item' + (isSelected ? ' selected' : '');

          let badgeHtml = '';
          let btnText = 'Select';
          let btnDisabled = false;

          if (peer.is_paired && peer.is_connected) {
            badgeHtml = '<span class="status-badge status-connected"><span class="dot dot-green"></span>Connected & Paired</span>';
            btnText = isSelected ? '✓ Ready to Send' : 'Select';
          } else if (peer.is_paired && !peer.is_connected) {
            badgeHtml = '<span class="status-badge status-offline"><span class="dot dot-red"></span>Disconnected</span>';
            btnText = 'Offline';
            btnDisabled = true;
          } else {
            badgeHtml = '<span class="status-badge status-unpaired"><span class="dot dot-blue"></span>Ready to Pair</span>';
            btnText = 'Pair Device';
          }

          item.innerHTML = '<div class="peer-info">' +
            '<div style="display:flex; align-items:center; gap:8px;">' +
            '<span class="peer-name">' + peer.name + '</span>' +
            badgeHtml +
            '</div>' +
            '<span class="peer-meta">' + peer.host + ':' + peer.port + ' • ' + (peer.is_tls ? 'HTTPS' : 'HTTP') + (peer.os ? ' (' + peer.os + ')' : '') + (peer.is_connected ? '' : ' • Last known address') + '</span>' +
            '</div>' +
            '<button class="btn ' + (isSelected ? '' : 'btn-secondary') + '" ' + (btnDisabled ? 'disabled' : '') + '>' + btnText + '</button>';

          item.onclick = () => {
            if (btnDisabled) return;
            if (!peer.is_paired) {
              generatePairSession();
              return;
            }
            selectedPeer = peer;
            loadPeers(false);
          };
          list.appendChild(item);
        });

        if (!selectedPeer && peers.length > 0) {
          const firstReady = peers.find(p => p.is_paired && p.is_connected) || peers.find(p => p.is_connected);
          if (firstReady) {
            selectedPeer = firstReady;
          }
        }
      } catch (e) {
        console.error(e);
      }
    }

    async function generatePairSession() {
      try {
        const peersRes = await fetch('/api/peers');
        const peersData = await peersRes.json();
        initialPairedCount = (peersData.paired || []).length;

        const res = await fetch('/api/pair/new', { method: 'POST' });
        const data = await res.json();
        document.getElementById('pairing-area').style.display = 'block';
        document.getElementById('pairing-success').style.display = 'none';
        document.getElementById('pin-code').textContent = data.secret;
        document.getElementById('pair-uri').textContent = 'URI: ' + data.pairing_uri;
        if (data.qr_code) {
          document.getElementById('qr-img').src = data.qr_code;
          document.getElementById('qr-container').style.display = 'inline-block';
        } else {
          document.getElementById('qr-container').style.display = 'none';
        }
      } catch (e) {
        alert('Failed to generate pairing session: ' + e);
      }
    }

    async function openFolder() {
      await fetch('/api/folder/open', { method: 'POST' });
    }

    async function loadHistory() {
      try {
        const res = await fetch('/api/history');
        const entries = await res.json();
        const container = document.getElementById('history-container');
        if (!entries || entries.length === 0) {
          container.innerHTML = '<p style="color:var(--text-muted); font-size:13px;">No transfer history yet.</p>';
          return;
        }
        let html = '<table class="history-table"><thead><tr><th>Status</th><th>Type</th><th>Name</th><th>Size</th><th>Time</th></tr></thead><tbody>';
        entries.forEach(e => {
          const badgeClass = e.status === 'completed' ? 'status-completed' : 'status-failed';
          const dirIcon = e.direction === 'inbound' ? '⬇️ In' : '⬆️ Out';
          const time = new Date(e.timestamp).toLocaleTimeString();
          const sz = formatBytes(e.size);
          html += '<tr>' +
            '<td><span class="status-badge ' + badgeClass + '">' + e.status + '</span></td>' +
            '<td>' + dirIcon + '</td>' +
            '<td>' + (e.filename || '-') + '</td>' +
            '<td>' + sz + '</td>' +
            '<td>' + time + '</td>' +
            '</tr>';
        });
        html += '</tbody></table>';
        container.innerHTML = html;
      } catch (e) {
        console.error(e);
      }
    }

    async function clearHistory() {
      try {
        await fetch('/api/history/clear', { method: 'POST' });
        loadHistory();
      } catch (e) {
        alert('Failed to clear history: ' + e);
      }
    }

    function formatBytes(bytes) {
      if (!bytes || bytes === 0) return '0 B';
      const k = 1024;
      const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
      const i = Math.floor(Math.log(bytes) / Math.log(k));
      return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    const dropzone = document.getElementById('dropzone');
    dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      handleFileSelect(e.dataTransfer.files);
    });

    async function handleFileSelect(files) {
      if (!files || files.length === 0) return;
      if (!selectedPeer) {
        alert('Please select a destination peer from the list first!');
        return;
      }

      const status = document.getElementById('status-msg');
      const pbar = document.getElementById('progress-bar');
      const pfill = document.getElementById('progress-fill');
      pbar.style.display = 'block';
      pfill.style.width = '0%';

      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        status.textContent = 'Uploading [' + (i+1) + '/' + files.length + '] ' + file.name + ' to ' + selectedPeer.name + '...';
        status.style.color = 'var(--text)';

        const formData = new FormData();
        const targetUrl = (selectedPeer.is_tls ? 'https://' : 'http://') + selectedPeer.host + ':' + selectedPeer.port;
        formData.append('target_url', targetUrl);
        formData.append('file', file);
        if (selectedPeer.auth_token) {
          formData.append('auth_token', selectedPeer.auth_token);
        }

        try {
          const res = await fetch('/api/send', { method: 'POST', body: formData });
          if (!res.ok) {
            const err = await res.json();
            throw new Error(err.message || res.statusText);
          }
          pfill.style.width = Math.round(((i + 1) / files.length) * 100) + '%';
        } catch (err) {
          status.textContent = 'Failed to send ' + file.name + ': ' + err.message;
          status.style.color = 'var(--danger)';
          loadHistory();
          return;
        }
      }

      status.textContent = '✓ Successfully sent ' + files.length + ' file(s) to ' + selectedPeer.name;
      status.style.color = 'var(--accent)';
      loadHistory();
      setTimeout(() => { pbar.style.display = 'none'; }, 2000);
    }

    loadPeers(true);
    loadHistory();
    setInterval(() => loadPeers(false), 2500);
    setInterval(loadHistory, 10000);
  </script>
</body>
</html>`
