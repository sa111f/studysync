'use strict';

// ── State ──────────────────────────────────────────────
let isLoggedIn    = false;
let currentRange  = 'week';
let currentOffset = 0;

// All sessions cached for client-side search filtering
let _allSessions = [];

// Dot colors for the material breakdown
const MATERIAL_COLORS = [
    '#7C5CFF', '#FF6B6B', '#5ebe78', '#ffd27e',
    '#38bdf8', '#f472b6', '#a78bfa', '#34d399',
];

// ── Boot ───────────────────────────────────────────────
/**
 * Entry point — checks auth state, then either shows guest state or loads data.
 */
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

    updateNextBtn();

    if (!isLoggedIn) {
        showGuestState();
    } else {
        showLoggedInState();
        loadAll();
    }
}

// ── Auth UI helpers ────────────────────────────────────

/** Hides every guest overlay and lock — shows all data panels. */
function showLoggedInState() {
    document.getElementById('tracker-guest-banner').classList.add('hidden');
    document.getElementById('guest-stat-overlay').classList.add('hidden');
    document.getElementById('chart-guest-lock').classList.add('hidden');
    document.getElementById('chart-area').classList.remove('hidden');
    document.getElementById('detail-guest-lock').classList.add('hidden');
    document.getElementById('session-list-area').classList.remove('hidden');
}

/** Shows all guest locks — hides data panels. */
function showGuestState() {
    document.getElementById('tracker-guest-banner').classList.remove('hidden');
    document.getElementById('guest-stat-overlay').classList.remove('hidden');
    document.getElementById('chart-guest-lock').classList.remove('hidden');
    document.getElementById('chart-area').classList.add('hidden');
    document.getElementById('detail-guest-lock').classList.remove('hidden');
    document.getElementById('session-list-area').classList.add('hidden');
}

// ── Tab switching ──────────────────────────────────────
/**
 * Switches the visible tracker tab with a fade-in transition.
 * @param {string} tab - 'summary' | 'detail' | 'ranking'
 */
function switchTab(tab) {
    document.querySelectorAll('.tracker-tab').forEach(b =>
        b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.tracker-panel').forEach(p =>
        p.classList.add('hidden'));
    const panel = document.getElementById('tab-' + tab);
    panel.classList.remove('hidden');
    // Populate the ranking stat row if real data is available
    if (tab === 'ranking') _syncRankingStats();
}

// ── Range filter ───────────────────────────────────────
/**
 * Changes the active time range and reloads all data.
 * @param {string} range - 'week' | 'month' | 'year'
 */
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
/**
 * Moves one period forward (dir=1) or backward (dir=-1).
 * @param {number} dir
 */
function navigatePeriod(dir) {
    const next = currentOffset + dir;
    if (next > 0) return;
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

        const totalH = Math.floor(data.totalMinutes / 60);
        const totalM = data.totalMinutes % 60;
        const totalStr = totalH > 0
            ? `${totalH}h${totalM > 0 ? ' ' + totalM + 'm' : ''}`
            : `${totalM}m`;
        document.getElementById('chart-total').textContent =
            data.totalMinutes > 0 ? `Total: ${totalStr}` : '';

        const isEmpty = !data.values || data.values.every(v => v === 0);
        const emptyEl = document.getElementById('chart-empty');
        const canvas  = document.getElementById('focus-chart');

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

        const maxMins = Math.max(...data.map(m => m.minutes), 1);

        el.innerHTML = data.map((m, i) => {
            const color   = MATERIAL_COLORS[i % MATERIAL_COLORS.length];
            const pct     = Math.round((m.minutes / maxMins) * 100);
            return `
            <div class="material-row">
                <span class="material-dot" style="background:${color}"></span>
                <span class="material-name" title="${esc(m.name)}">${esc(m.name)}</span>
                <div class="material-bar-wrap">
                    <div class="material-bar-fill" style="width:${pct}%;background:${color}"></div>
                </div>
                <span class="material-time">${fmtMins(m.minutes)}</span>
            </div>`;
        }).join('');
    } catch (_) {}
}

// ── Session log ────────────────────────────────────────
async function loadSessions() {
    try {
        const res  = await fetch(`/api/tracker/sessions?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();
        _allSessions = data || [];
        renderSessionRows(_allSessions);
    } catch (_) {}
}

/**
 * Renders the session table rows from a given list.
 * @param {Array} sessions
 */
function renderSessionRows(sessions) {
    const emptyEl = document.getElementById('sessions-empty');
    const table   = document.getElementById('sessions-table');
    const body    = document.getElementById('sessions-body');

    if (!sessions || sessions.length === 0) {
        emptyEl.classList.remove('hidden');
        table.classList.add('hidden');
        return;
    }

    emptyEl.classList.add('hidden');
    table.classList.remove('hidden');
    body.innerHTML = sessions.map(s => `
        <tr>
            <td>${esc(s.date)}</td>
            <td>${esc(s.time)}</td>
            <td>${esc(s.materialName)}</td>
            <td style="font-variant-numeric:tabular-nums">${fmtMins(s.durationMinutes)}</td>
            <td>${esc(s.timerMode)}</td>
            <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                ${s.completed ? '&#10003; Completed' : '&#9679; Skipped'}
            </td>
        </tr>`).join('');
}

/**
 * Filters the session table by the search input value (client-side).
 * Matched against date, material name, timer mode (case-insensitive).
 */
function filterSessions() {
    const q = (document.getElementById('session-search')?.value || '').toLowerCase().trim();
    if (!q) {
        renderSessionRows(_allSessions);
        return;
    }
    const filtered = _allSessions.filter(s =>
        (s.date         || '').toLowerCase().includes(q) ||
        (s.materialName || '').toLowerCase().includes(q) ||
        (s.timerMode    || '').toLowerCase().includes(q)
    );
    renderSessionRows(filtered);
}

// ── Period label sync ──────────────────────────────────
function setPeriodLabels(label) {
    document.querySelectorAll('.period-label').forEach(el => { el.textContent = label; });
}

// ── Ranking stats sync ─────────────────────────────────
/** Fills in the "You" row of the ranking mockup with real streak/hours from summary. */
function _syncRankingStats() {
    const hoursEl  = document.getElementById('lb-your-hours');
    const streakEl = document.getElementById('lb-your-streak');
    if (!hoursEl || !streakEl) return;

    const hours  = document.getElementById('stat-hours-val')?.textContent;
    const streak = document.getElementById('stat-streak-val')?.textContent;
    if (hours  && hours  !== '—') hoursEl.textContent  = hours;
    if (streak && streak !== '—') streakEl.textContent = `🔥 ${streak}`;
}

// ── Edit toast ─────────────────────────────────────────
function showEditToast() {
    // Remove any existing toast
    document.querySelectorAll('.cs-toast').forEach(t => t.remove());
    const toast = document.createElement('div');
    toast.className = 'cs-toast';
    toast.textContent = '✏️ Session editing is coming soon!';
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2200);
}

// ── Bar chart (pure Canvas, no library) ───────────────
// Stores bar geometry for tooltip hit-testing
let _chartBars = [];

/**
 * Draws an animated bar chart onto the given canvas.
 * Bars animate in over ~400ms via requestAnimationFrame.
 *
 * @param {HTMLCanvasElement} canvas
 * @param {string[]} labels
 * @param {number[]} values   - Minutes per bar
 */
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

    const padL = 46, padR = 12, padT = 18, padB = 38;
    const chartW = W - padL - padR;
    const chartH = H - padT - padB;
    const n      = values.length;
    const maxVal = Math.max(...values, 1);

    const gap  = Math.max(2, Math.floor(chartW / n * 0.18));
    const barW = Math.max(4, (chartW - gap * (n - 1)) / n);

    const accent     = '#7C5CFF';
    const accentFade = 'rgba(124,92,255,0.22)';

    // Pre-compute bar positions for tooltips
    _chartBars = values.map((v, i) => ({
        x:     padL + i * (barW + gap),
        fullH: v > 0 ? Math.max(4, (v / maxVal) * chartH) : 0,
        value: v,
        label: labels[i],
    }));

    // ── Animation ──
    const START     = performance.now();
    const DURATION  = 420;

    function frame(now) {
        const t   = Math.min((now - START) / DURATION, 1);
        const ease = 1 - Math.pow(1 - t, 3);   // cubic ease-out

        ctx.clearRect(0, 0, W, H);

        // Grid lines + Y labels
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

        // Bars
        for (let i = 0; i < n; i++) {
            const bar = _chartBars[i];
            const x   = bar.x;
            const bh  = bar.fullH * ease;
            const y   = padT + chartH - bh;

            if (bh > 0) {
                const grad = ctx.createLinearGradient(0, y, 0, padT + chartH);
                grad.addColorStop(0, accent);
                grad.addColorStop(1, accentFade);
                ctx.fillStyle = grad;
                drawRoundedTopRect(ctx, x, y, barW, bh, Math.min(4, barW / 2));
                ctx.fill();
            } else {
                ctx.fillStyle = 'rgba(46,50,80,0.6)';
                ctx.fillRect(x, padT + chartH - 2, barW, 2);
            }

            // X axis label — skip some when many bars
            const skip = n > 20 ? 4 : n > 12 ? 2 : 1;
            if (i % skip === 0) {
                ctx.fillStyle = '#8b8fa8';
                ctx.textAlign = 'center';
                ctx.fillText(bar.label, x + barW / 2, padT + chartH + 20);
            }
        }

        if (t < 1) requestAnimationFrame(frame);
    }

    requestAnimationFrame(frame);

    // ── Tooltip on hover ──
    _attachChartTooltip(canvas, padL, padT, chartH, barW, gap);
}

/**
 * Attaches a mousemove listener to the canvas for bar tooltips.
 * Replaces any previous listener by using a named property.
 */
function _attachChartTooltip(canvas, padL, padT, chartH, barW, gap) {
    const tooltip = document.getElementById('chart-tooltip');
    if (!tooltip) return;

    // Remove old listener if any
    if (canvas._tooltipHandler) {
        canvas.removeEventListener('mousemove', canvas._tooltipHandler);
        canvas.removeEventListener('mouseleave', canvas._tooltipLeaveHandler);
    }

    canvas._tooltipHandler = function(e) {
        const rect = canvas.getBoundingClientRect();
        const mx   = e.clientX - rect.left;
        const my   = e.clientY - rect.top;

        let hit = null;
        for (const bar of _chartBars) {
            if (bar.fullH === 0) continue;
            const y = padT + chartH - bar.fullH;
            if (mx >= bar.x && mx <= bar.x + barW && my >= y && my <= padT + chartH) {
                hit = bar;
                break;
            }
        }

        if (hit) {
            tooltip.textContent = `${hit.label}: ${fmtMins(hit.value)}`;
            tooltip.classList.remove('hidden');
            // Position relative to canvas wrap
            const wrapRect = canvas.parentElement.getBoundingClientRect();
            const tx = e.clientX - wrapRect.left + 10;
            const ty = e.clientY - wrapRect.top  - 28;
            tooltip.style.left = tx + 'px';
            tooltip.style.top  = ty + 'px';
        } else {
            tooltip.classList.add('hidden');
        }
    };

    canvas._tooltipLeaveHandler = () => tooltip.classList.add('hidden');

    canvas.addEventListener('mousemove', canvas._tooltipHandler);
    canvas.addEventListener('mouseleave', canvas._tooltipLeaveHandler);
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
/** Formats minutes into a human-readable "1h 30m" style string. */
function fmtMins(mins) {
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

/** Safe HTML escaping. */
function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// ── Kick off ───────────────────────────────────────────
initTracker();
