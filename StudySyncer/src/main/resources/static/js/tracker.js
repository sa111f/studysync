'use strict';

// ── State ──────────────────────────────────────────────
let isLoggedIn    = false;
let currentRange  = 'week';
let currentOffset = 0;

// ── Boot ───────────────────────────────────────────────
async function initTracker() {
    let username = null;
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            username   = data.username || null;
        }
        isLoggedIn = res.ok;
    } catch (_) {
        isLoggedIn = false;
    }

    console.debug('[Tracker] isLoggedIn =', isLoggedIn, '| username =', username);

    updateNextBtn();

    if (!isLoggedIn) {
        showGuestState();
    } else {
        showLoggedInState();
        loadAll();
    }
}

// ── Auth UI helpers ────────────────────────────────────

/** Explicitly show the authenticated state — hides every guest overlay/lock. */
function showLoggedInState() {
    document.getElementById('tracker-guest-banner').classList.add('hidden');
    document.getElementById('guest-stat-overlay').classList.add('hidden');

    // Chart area — ensure guest lock is hidden, real chart is visible
    document.getElementById('chart-guest-lock').classList.add('hidden');
    document.getElementById('chart-area').classList.remove('hidden');

    // Detail tab — ensure guest lock is hidden, session list is visible
    document.getElementById('detail-guest-lock').classList.add('hidden');
    document.getElementById('session-list-area').classList.remove('hidden');
}

/** Show the locked guest state — hides all data panels. */
function showGuestState() {
    document.getElementById('tracker-guest-banner').classList.remove('hidden');
    document.getElementById('guest-stat-overlay').classList.remove('hidden');

    // Chart area
    document.getElementById('chart-guest-lock').classList.remove('hidden');
    document.getElementById('chart-area').classList.add('hidden');

    // Detail tab
    document.getElementById('detail-guest-lock').classList.remove('hidden');
    document.getElementById('session-list-area').classList.add('hidden');
}

// ── Tab switching ──────────────────────────────────────
function switchTab(tab) {
    document.querySelectorAll('.tracker-tab').forEach(b =>
        b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.tracker-panel').forEach(p =>
        p.classList.add('hidden'));
    document.getElementById('tab-' + tab).classList.remove('hidden');
}

// ── Range filter ───────────────────────────────────────
function setRange(range) {
    if (currentRange === range) return;
    currentRange  = range;
    currentOffset = 0;
    document.querySelectorAll('.range-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.range === range));
    updateNextBtn();
    loadAll();
}

// ── Period navigation ──────────────────────────────────
function navigatePeriod(dir) {
    const next = currentOffset + dir;
    if (next > 0) return;   // no future navigation
    currentOffset = next;
    updateNextBtn();
    loadAll();
}

function updateNextBtn() {
    const btn = document.getElementById('btn-next-period');
    if (!btn) return;
    btn.disabled = currentOffset >= 0;
    btn.style.opacity = currentOffset >= 0 ? '0.35' : '1';
}

// ── Load all panels ────────────────────────────────────
function loadAll() {
    if (!isLoggedIn) return;
    loadSummary();
    loadChart();
    loadSessions();
}

// ── Summary stats ──────────────────────────────────────
async function loadSummary() {
    try {
        const res  = await fetch(`/api/tracker/summary?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();

        const h = Math.floor(data.totalMinutes / 60);
        const m = data.totalMinutes % 60;
        const hoursStr = h > 0 ? `${h}h${m > 0 ? ' ' + m + 'm' : ''}` : `${m}m`;

        document.getElementById('stat-hours-val').textContent  = hoursStr;
        document.getElementById('stat-hours-sub').textContent  = data.periodLabel || '';
        document.getElementById('stat-days-val').textContent   = data.daysAccessed;
        document.getElementById('stat-streak-val').textContent = data.streak;

        setPeriodLabels(data.periodLabel || '');
    } catch (_) {}
}

// ── Focus chart ────────────────────────────────────────
async function loadChart() {
    try {
        const res  = await fetch(`/api/tracker/chart?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();

        setPeriodLabels(data.periodLabel || '');

        // Total label
        const totalH = Math.floor(data.totalMinutes / 60);
        const totalM = data.totalMinutes % 60;
        const totalStr = totalH > 0
            ? `${totalH}h${totalM > 0 ? ' ' + totalM + 'm' : ''}`
            : `${totalM}m`;
        document.getElementById('chart-total').textContent =
            data.totalMinutes > 0 ? `Total: ${totalStr}` : '';

        const isEmpty  = !data.values || data.values.every(v => v === 0);
        const emptyEl  = document.getElementById('chart-empty');
        const canvas   = document.getElementById('focus-chart');

        if (isEmpty) {
            emptyEl.classList.remove('hidden');
            canvas.style.display = 'none';
        } else {
            emptyEl.classList.add('hidden');
            canvas.style.display = '';
            drawChart(canvas, data.labels, data.values);
        }

        loadMaterials();
    } catch (_) {}
}

// ── Material breakdown ─────────────────────────────────
async function loadMaterials() {
    try {
        const res  = await fetch(`/api/tracker/materials?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();

        const el = document.getElementById('material-list');
        if (!data || data.length === 0) {
            el.innerHTML = '<p class="tracker-empty">No data for this period.</p>';
            return;
        }

        el.innerHTML = `
            <table class="results-table">
                <thead><tr><th>Material</th><th style="text-align:right">Time</th></tr></thead>
                <tbody>
                    ${data.map(m => `
                        <tr>
                            <td>${esc(m.name)}</td>
                            <td style="text-align:right;font-variant-numeric:tabular-nums">${fmtMins(m.minutes)}</td>
                        </tr>`).join('')}
                </tbody>
            </table>`;
    } catch (_) {}
}

// ── Session log ────────────────────────────────────────
async function loadSessions() {
    try {
        const res  = await fetch(`/api/tracker/sessions?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();

        const emptyEl = document.getElementById('sessions-empty');
        const table   = document.getElementById('sessions-table');
        const body    = document.getElementById('sessions-body');

        if (!data || data.length === 0) {
            emptyEl.classList.remove('hidden');
            table.classList.add('hidden');
            return;
        }

        emptyEl.classList.add('hidden');
        table.classList.remove('hidden');
        body.innerHTML = data.map(s => `
            <tr>
                <td>${esc(s.date)}</td>
                <td>${esc(s.time)}</td>
                <td>${esc(s.materialName)}</td>
                <td style="font-variant-numeric:tabular-nums">${fmtMins(s.durationMinutes)}</td>
                <td>${esc(s.timerMode)}</td>
                <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                    ${s.completed ? 'Completed' : 'Skipped'}
                </td>
            </tr>`).join('');
    } catch (_) {}
}

// ── Period label sync ──────────────────────────────────
function setPeriodLabels(label) {
    const els = document.querySelectorAll('.period-label');
    els.forEach(el => { el.textContent = label; });
}

// ── Bar chart (pure Canvas, no library) ───────────────
function drawChart(canvas, labels, values) {
    const dpr  = window.devicePixelRatio || 1;
    const W    = canvas.parentElement.offsetWidth || 720;
    const H    = 220;

    canvas.width  = W * dpr;
    canvas.height = H * dpr;
    canvas.style.width  = W + 'px';
    canvas.style.height = H + 'px';

    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, W, H);

    const padL = 46, padR = 12, padT = 18, padB = 38;
    const chartW = W - padL - padR;
    const chartH = H - padT - padB;
    const n      = values.length;
    const maxVal = Math.max(...values, 1);

    const gap  = Math.max(2, Math.floor(chartW / n * 0.15));
    const barW = Math.max(2, (chartW - gap * (n - 1)) / n);

    // ── Grid lines + Y labels ──
    const gridCount = 4;
    ctx.strokeStyle = 'rgba(46,50,80,0.9)';
    ctx.lineWidth   = 1;
    ctx.font        = `10px 'Segoe UI', system-ui, sans-serif`;
    ctx.fillStyle   = '#8b8fa8';

    for (let i = 0; i <= gridCount; i++) {
        const y = padT + chartH - (i / gridCount) * chartH;
        ctx.beginPath();
        ctx.moveTo(padL, y);
        ctx.lineTo(padL + chartW, y);
        ctx.stroke();

        const val = Math.round((i / gridCount) * maxVal);
        const lbl = val >= 60 ? `${Math.floor(val / 60)}h` : `${val}m`;
        ctx.textAlign = 'right';
        ctx.fillText(lbl, padL - 6, y + 4);
    }

    // ── Bars ──
    const accent     = '#6c63ff';
    const accentFade = 'rgba(108,99,255,0.25)';

    for (let i = 0; i < n; i++) {
        const x    = padL + i * (barW + gap);
        const bh   = values[i] > 0 ? Math.max(3, (values[i] / maxVal) * chartH) : 0;
        const y    = padT + chartH - bh;

        if (bh > 0) {
            const grad = ctx.createLinearGradient(0, y, 0, padT + chartH);
            grad.addColorStop(0, accent);
            grad.addColorStop(1, accentFade);
            ctx.fillStyle = grad;
            drawRoundedTopRect(ctx, x, y, barW, bh, Math.min(3, barW / 2));
            ctx.fill();
        } else {
            // Empty slot placeholder
            ctx.fillStyle = 'rgba(46,50,80,0.6)';
            ctx.fillRect(x, padT + chartH - 2, barW, 2);
        }

        // X axis label — skip some when many bars
        const skip = n > 20 ? 4 : n > 12 ? 2 : 1;
        if (i % skip === 0) {
            ctx.fillStyle = '#8b8fa8';
            ctx.textAlign = 'center';
            ctx.fillText(labels[i], x + barW / 2, padT + chartH + 20);
        }
    }
}

/** Draw a rectangle with rounded top corners only. */
function drawRoundedTopRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y,     x + w, y + h, r);
    ctx.lineTo(x + w, y + h);
    ctx.lineTo(x,     y + h);
    ctx.arcTo(x,      y,     x + r, y,     r);
    ctx.closePath();
}

// ── Utilities ──────────────────────────────────────────
function fmtMins(mins) {
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

// ── Kick off ───────────────────────────────────────────
initTracker();
