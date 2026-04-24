'use strict';

// ── State ──────────────────────────────────────────────
let isLoggedIn    = false;
let currentRange  = 'week';
let currentOffset = 0;

// All sessions cached for client-side search filtering
let _allSessions = [];

// Today's goal — persisted in localStorage
let todayGoalMins   = 0;   // goal set by user (0 = no goal)
let todayActualMins = 0;   // today's actual focused minutes (from chart data)

// Dot colors for the material breakdown
const MATERIAL_COLORS = [
    '#7C5CFF', '#FF6B6B', '#5ebe78', '#ffd27e',
    '#38bdf8', '#f472b6', '#a78bfa', '#34d399',
];

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

    // Expose the resolved user globally so deadlines.js (which runs on this
    // page via the Deadlines tab) can read the auth state without a second
    // /api/auth/me round-trip.
    window.currentUser = isLoggedIn ? username : null;

    updateHeaderAuthBar(username);
    loadGoal();       // restore saved goal before first render
    updateNextBtn();

    if (!isLoggedIn) {
        showGuestState();
    } else {
        showLoggedInState();
        loadAll();
    }
}

// Sync the tracker header auth bar with the auth state. The tracker page
// has no auth modal of its own — Log in / Sign up buttons link to / where
// the modal lives. Logout calls /api/auth/logout and bounces home.
function updateHeaderAuthBar(username) {
    const guest = document.getElementById('auth-guest');
    const user  = document.getElementById('auth-user');
    const name  = document.getElementById('auth-username');
    if (isLoggedIn) {
        guest?.classList.add('hidden');
        user?.classList.remove('hidden');
        if (name) name.textContent = `Hi, ${username}`;
    } else {
        guest?.classList.remove('hidden');
        user?.classList.add('hidden');
    }
}

async function trackerLogout() {
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (_) {}
    window.location.href = '/';
}

// ── Auth UI helpers ────────────────────────────────────
function showLoggedInState() {
    document.getElementById('tracker-guest-banner').classList.add('hidden');
    document.getElementById('guest-stat-overlay').classList.add('hidden');
    document.getElementById('chart-guest-lock').classList.add('hidden');
    document.getElementById('chart-area').classList.remove('hidden');
    document.getElementById('detail-guest-lock').classList.add('hidden');
    document.getElementById('session-list-area').classList.remove('hidden');
}

function showGuestState() {
    document.getElementById('tracker-guest-banner').classList.remove('hidden');
    document.getElementById('guest-stat-overlay').classList.remove('hidden');
    document.getElementById('chart-guest-lock').classList.remove('hidden');
    document.getElementById('chart-area').classList.add('hidden');
    document.getElementById('detail-guest-lock').classList.remove('hidden');
    document.getElementById('session-list-area').classList.add('hidden');
}

// ── Tab switching ──────────────────────────────────────
function switchTab(tab) {
    document.querySelectorAll('.tracker-tab').forEach(b =>
        b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.tracker-panel').forEach(p =>
        p.classList.add('hidden'));
    const panel = document.getElementById('tab-' + tab);
    if (panel) panel.classList.remove('hidden');
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

        // New total-label in the chart-card-header's center slot — e.g. "0h 45m this week".
        // We always render something, even on empty, so the header layout stays stable.
        setPeriodTotalLabels(totalStr, currentRange);

        const isEmpty = !data.values || data.values.every(v => v === 0);
        const emptyEl = document.getElementById('chart-empty');
        const canvas  = document.getElementById('focus-chart');
        const editBtn = document.getElementById('chart-edit-btn');

        // Identify today's bar and extract today's actual minutes
        const todayIdx = (currentOffset === 0)
            ? getTodayBarIndex(data.labels || [])
            : -1;

        if (!isEmpty) {
            // For the "today" range the full chart represents one day,
            // so today's total = sum of all hourly bars.
            todayActualMins = (currentRange === 'today' && currentOffset === 0)
                ? (data.totalMinutes || 0)
                : (todayIdx >= 0 && data.values[todayIdx] != null)
                    ? data.values[todayIdx]
                    : 0;

            emptyEl.classList.add('hidden');
            canvas.style.display = '';
            editBtn?.classList.remove('hidden');
            drawChart(canvas, data.labels, data.values, todayIdx, todayGoalMins);
        } else {
            todayActualMins = 0;
            emptyEl.classList.remove('hidden');
            canvas.style.display = 'none';
            // No sessions in this period — hide the "Edit sessions" button (nothing to edit).
            editBtn?.classList.add('hidden');
        }

        // Refresh goal progress UI with latest actual data
        updateGoalUI();

        loadMaterials();
    } catch (_) {}
}

/** Writes "0h 45m this week" into each .period-total-label slot. */
function setPeriodTotalLabels(totalStr, range) {
    const rangeSuffix = range === 'today' ? 'today'
                      : range === 'week'  ? 'this week'
                      : range === 'month' ? 'this month'
                      : range === 'year'  ? 'this year'
                      : '';
    const text = `${totalStr} ${rangeSuffix}`.trim();
    document.querySelectorAll('.period-total-label').forEach(el => {
        el.textContent = text;
    });
}

// ── Material breakdown ─────────────────────────────────
async function loadMaterials() {
    try {
        const res  = await fetch(`/api/tracker/materials?range=${currentRange}&offset=${currentOffset}`);
        if (!res.ok) return;
        const data = await res.json();

        const el = document.getElementById('material-list');
        if (!data || data.length === 0) {
            el.innerHTML = `
                <div class="tracker-empty-cta">
                    <div class="tracker-empty-cta-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
                             stroke-linecap="round" stroke-linejoin="round">
                            <rect x="3" y="4" width="18" height="18" rx="2"/>
                            <line x1="16" y1="2" x2="16" y2="6"/>
                            <line x1="8"  y1="2" x2="8"  y2="6"/>
                            <line x1="3"  y1="10" x2="21" y2="10"/>
                        </svg>
                    </div>
                    <p class="tracker-empty-cta-text">No study sessions recorded for this period.</p>
                    <a href="/" class="apply-btn tracker-empty-cta-btn">Start your first Pomodoro →</a>
                </div>`;
            return;
        }

        const maxMins = Math.max(...data.map(m => m.minutes), 1);

        el.innerHTML = data.map((m, i) => {
            const color = MATERIAL_COLORS[i % MATERIAL_COLORS.length];
            const pct   = Math.round((m.minutes / maxMins) * 100);
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

function renderSessionRows(sessions) {
    const emptyEl   = document.getElementById('sessions-empty');
    const table     = document.getElementById('sessions-table');
    const body      = document.getElementById('sessions-body');
    const searchBox = document.getElementById('session-search-wrap');

    if (!sessions || sessions.length === 0) {
        emptyEl.classList.remove('hidden');
        table.classList.add('hidden');
        // Hide the filter input when there's nothing to filter — re-shown below when data exists.
        searchBox?.classList.add('hidden');
        return;
    }

    emptyEl.classList.add('hidden');
    table.classList.remove('hidden');
    searchBox?.classList.remove('hidden');
    body.innerHTML = sessions.map(s => {
        const overtime = (s.overtimeMinutes || 0);
        const planned  = (s.plannedMinutes  || 0);
        let durationCell;
        if (overtime > 0 && planned > 0) {
            durationCell = `<span style="font-variant-numeric:tabular-nums">${planned}m planned · ${s.durationMinutes}m actual</span> <span class="ot-badge">+${overtime}m</span>`;
        } else {
            durationCell = `<span style="font-variant-numeric:tabular-nums">${fmtMins(s.durationMinutes)}</span>`;
        }
        return `
        <tr>
            <td>${esc(s.date)}</td>
            <td>${esc(s.time)}</td>
            <td>${esc(s.materialName)}</td>
            <td>${durationCell}</td>
            <td>${esc(s.timerMode)}</td>
            <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                ${s.completed ? '&#10003; Completed' : '&#9679; Skipped'}
            </td>
        </tr>`;
    }).join('');
}

function filterSessions() {
    const q = (document.getElementById('session-search')?.value || '').toLowerCase().trim();
    if (!q) { renderSessionRows(_allSessions); return; }
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

// ── Edit toast ─────────────────────────────────────────
function showEditToast() {
    document.querySelectorAll('.cs-toast').forEach(t => t.remove());
    const toast = document.createElement('div');
    toast.className = 'cs-toast';
    toast.textContent = '✏️ Session editing is coming soon!';
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2200);
}

// ══════════════════════════════════════════════════════
// TODAY'S GOAL — localStorage-backed daily target
// ══════════════════════════════════════════════════════

const GOAL_KEY = 'studysync_today_goal_mins';

/**
 * Restores the saved goal from localStorage and updates the input + UI.
 * Called once on page load before the first data fetch.
 */
function loadGoal() {
    const saved = localStorage.getItem(GOAL_KEY);
    todayGoalMins = saved ? parseInt(saved, 10) : 0;
    if (todayGoalMins > 0) {
        const input = document.getElementById('today-goal-input');
        if (input) input.value = String(todayGoalMins);
        document.getElementById('today-goal-clear')?.classList.remove('hidden');
    }
}

/**
 * Reads the input, validates, saves to localStorage, and refreshes the chart
 * and progress bar. Called by the "Set" button and Enter key.
 */
function applyGoal() {
    const input = document.getElementById('today-goal-input');
    if (!input) return;
    const val = parseInt(input.value, 10);
    if (isNaN(val) || val <= 0) {
        clearGoal();
        return;
    }
    todayGoalMins = val;
    localStorage.setItem(GOAL_KEY, String(val));
    document.getElementById('today-goal-clear')?.classList.remove('hidden');
    updateGoalUI();
    loadChart();   // redraw chart so today's bar picks up the new color
}

/**
 * Removes the goal, resets the input, and redraws without goal coloring.
 */
function clearGoal() {
    todayGoalMins = 0;
    localStorage.removeItem(GOAL_KEY);
    const input = document.getElementById('today-goal-input');
    if (input) input.value = '';
    document.getElementById('today-goal-clear')?.classList.add('hidden');
    updateGoalUI();
    loadChart();
}

/**
 * Updates the progress bar and status text based on current goal and actual mins.
 * Shows the progress row only when: goal is set AND viewing the current period AND logged in.
 */
function updateGoalUI() {
    const progressEl = document.getElementById('today-goal-progress');
    const barFill    = document.getElementById('today-goal-bar-fill');
    const statusText = document.getElementById('today-goal-status-text');
    if (!progressEl) return;

    // Hide progress when: no goal, not current period, or not logged in
    if (todayGoalMins <= 0 || currentOffset !== 0 || !isLoggedIn) {
        progressEl.classList.add('hidden');
        return;
    }

    progressEl.classList.remove('hidden');

    const pct   = Math.min(100, Math.round((todayActualMins / todayGoalMins) * 100));
    const met   = todayActualMins >= todayGoalMins;
    const color = met ? '#34d399' : '#FF6B6B';

    if (barFill) {
        barFill.style.width      = pct + '%';
        barFill.style.background = color;
        // Subtle glow on the fill bar
        barFill.style.boxShadow  = met
            ? '0 0 8px rgba(52,211,153,0.45)'
            : '0 0 8px rgba(255,107,107,0.35)';
    }

    if (statusText) {
        statusText.className = 'today-goal-status-text ' + (met ? 'goal-met' : 'goal-unmet');
        statusText.textContent = met
            ? `Goal reached! (${fmtMins(todayActualMins)})`
            : `${fmtMins(todayActualMins)} of ${fmtMins(todayGoalMins)} — ${pct}%`;
    }
}

/**
 * Finds the index of "now" in the chart labels array.
 *
 * - today range:  highlights the current-hour bar (e.g. label "14h")
 * - week range:   matches 3-letter day abbreviation (e.g. "Mon")
 * - month range:  matches numeric day-of-month (e.g. "6")
 * Returns -1 when viewing a past period or no match is found.
 */
function getTodayBarIndex(labels) {
    const now = new Date();

    if (currentRange === 'today') {
        // Only highlight when viewing the actual current day
        if (currentOffset !== 0) return -1;
        return labels.findIndex(l => l === now.getHours() + 'h');
    }

    const dayAbbrs = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const abbr     = dayAbbrs[now.getDay()];   // e.g. "Mon"
    const dayNum   = String(now.getDate());     // e.g. "6"

    // Exact 3-letter match (week range API)
    let idx = labels.findIndex(l => l === abbr);
    if (idx >= 0) return idx;

    // 2-letter prefix (some locales / API variants use "Mo", "Tu", etc.)
    idx = labels.findIndex(l => l === abbr.slice(0, 2));
    if (idx >= 0) return idx;

    // Numeric day match (month range API returns "1", "2", ..., "31")
    idx = labels.findIndex(l => String(l) === dayNum);
    if (idx >= 0) return idx;

    return -1;
}

// ── Bar chart (pure Canvas, no library) ───────────────
let _chartBars = [];

/**
 * Draws an animated bar chart.
 *
 * @param {HTMLCanvasElement} canvas
 * @param {string[]} labels
 * @param {number[]} values       Minutes per bar
 * @param {number}   todayIdx     Index of today's bar (-1 = unknown / not current period)
 * @param {number}   goalMins     Goal in minutes (0 = no goal)
 */
function drawChart(canvas, labels, values, todayIdx = -1, goalMins = 0) {
    const dpr  = window.devicePixelRatio || 1;
    const W    = canvas.parentElement.offsetWidth || 720;
    const H    = 220;

    canvas.width  = W * dpr;
    canvas.height = H * dpr;
    canvas.style.width  = W + 'px';
    canvas.style.height = H + 'px';

    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    const padL = 46, padR = 12, padT = 18, padB = 42;
    const chartW = W - padL - padR;
    const chartH = H - padT - padB;
    const n      = values.length;
    const maxVal = Math.max(...values, 1);

    const gap  = Math.max(2, Math.floor(chartW / n * 0.18));
    const barW = Math.max(4, (chartW - gap * (n - 1)) / n);

    // Color palette
    const ACCENT       = '#7C5CFF';
    const ACCENT_FADE  = 'rgba(124,92,255,0.22)';
    const RED          = '#FF6B6B';
    const RED_FADE     = 'rgba(255,107,107,0.22)';
    const GREEN        = '#34d399';
    const GREEN_FADE   = 'rgba(52,211,153,0.22)';

    /**
     * Returns the correct gradient colors for a given bar index.
     * Today's bar changes color based on whether the goal is met.
     */
    function barColors(i) {
        if (i !== todayIdx || goalMins <= 0) {
            return { top: ACCENT, fade: ACCENT_FADE };
        }
        return values[i] >= goalMins
            ? { top: GREEN, fade: GREEN_FADE }
            : { top: RED,   fade: RED_FADE   };
    }

    // Pre-compute bar positions for tooltip hit-testing
    _chartBars = values.map((v, i) => ({
        x:     padL + i * (barW + gap),
        fullH: v > 0 ? Math.max(4, (v / maxVal) * chartH) : 0,
        value: v,
        label: labels[i],
    }));

    // ── Animation ──
    const START    = performance.now();
    const DURATION = 420;

    function frame(now) {
        const t    = Math.min((now - START) / DURATION, 1);
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

        // ── Draw bars ──
        for (let i = 0; i < n; i++) {
            const bar      = _chartBars[i];
            const bh       = bar.fullH * ease;
            const y        = padT + chartH - bh;
            const isToday  = i === todayIdx;
            const colors   = barColors(i);

            if (bh > 0) {
                const grad = ctx.createLinearGradient(0, y, 0, padT + chartH);
                grad.addColorStop(0, colors.top);
                grad.addColorStop(1, colors.fade);
                ctx.fillStyle = grad;
                drawRoundedTopRect(ctx, bar.x, y, barW, bh, Math.min(4, barW / 2));
                ctx.fill();
            } else {
                // Zero-height placeholder tick
                ctx.fillStyle = isToday
                    ? 'rgba(124,92,255,0.20)'
                    : 'rgba(46,50,80,0.6)';
                ctx.fillRect(bar.x, padT + chartH - 2, barW, 2);
            }

            // ── X-axis label ──
            const skip = n > 20 ? 4 : n > 12 ? 2 : 1;
            if (i % skip === 0) {
                // Today's label is slightly brighter
                ctx.fillStyle = isToday ? '#c4b5fd' : '#8b8fa8';
                ctx.textAlign = 'center';
                ctx.fillText(bar.label, bar.x + barW / 2, padT + chartH + 18);

                // Small dot below today's label — color reflects goal state
                if (isToday) {
                    ctx.beginPath();
                    ctx.arc(bar.x + barW / 2, padT + chartH + 30, 2.5, 0, Math.PI * 2);
                    ctx.fillStyle = goalMins > 0
                        ? colors.top    // red or green based on goal
                        : ACCENT;       // default purple dot
                    ctx.fill();
                }
            }
        }

        if (t < 1) requestAnimationFrame(frame);
    }

    requestAnimationFrame(frame);

    // Tooltip on hover
    _attachChartTooltip(canvas, padL, padT, chartH, barW, gap);
}

/**
 * Attaches a mousemove listener to the canvas for bar tooltips.
 */
function _attachChartTooltip(canvas, padL, padT, chartH, barW, gap) {
    const tooltip = document.getElementById('chart-tooltip');
    if (!tooltip) return;

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
            const wrapRect = canvas.parentElement.getBoundingClientRect();
            tooltip.style.left = (e.clientX - wrapRect.left + 10) + 'px';
            tooltip.style.top  = (e.clientY - wrapRect.top  - 28) + 'px';
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
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// ── Kick off ───────────────────────────────────────────
// Expose a boot-complete promise on the same key auth.js uses, so that
// deadlines.js — which awaits `window.authReady` — works identically
// whether it runs on the homepage (auth.js owns the session) or here on
// /tracker (tracker.js owns the session).
window.authReady = initTracker();
