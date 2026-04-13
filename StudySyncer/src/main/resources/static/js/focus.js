'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Procedurally generate a symmetric pixel-art vertical battery
 *   - Drain fill pixels top-row-first as timer progresses
 *     (visual "water level drops" effect)
 *   - Swap fill colour via body classes at mid / low charge tiers
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on battery click
 *
 * PIXEL-ART BATTERY ARCHITECTURE
 * ──────────────────────────────
 * The battery is a fixed cell grid.  _inBody(c,r) decides whether a cell
 * is inside the rounded-rectangle silhouette.  Because the function uses
 * the symmetric column index  min(c, BODY_COLS-1-c),  the resulting shape
 * is pixel-perfect left/right symmetric.
 *
 * Classification:
 *   BORDER cells → inside cells that have at least one outside 4-neighbor
 *                  (plus every cell of the positive terminal cap, which
 *                  is rendered as a solid white block).
 *   FILL   cells → inside body cells completely surrounded by inside cells.
 *
 * Drain order: fills are sorted by ascending row (then column) so the
 * topmost row empties first — the energy level literally drops.
 *
 * Geometry (SVG-unit cells, stride = CELL):
 *   Body     36 cols × 58 rows   → 180 × 290 units
 *   Terminal 12 cols ×  4 rows   →  60 ×  20 units (centered on top edge)
 *   Total visual footprint       → 180 × 310 units
 *   ViewBox                      → 260 × 360 (40 side / 30 top / 30 bottom pad)
 */

// ── Grid geometry ─────────────────────────────────────────────
const BODY_COLS     = 36;
const BODY_ROWS     = 58;
const TERMINAL_COLS = 12;
const TERMINAL_ROWS = 4;
const CELL          = 5;                // grid stride (SVG units)
const VIS_CELL      = 4.25;             // visible square side (0.75 gap)
const PIXEL_R       = 0.55;             // tiny rounding on each cell
const ORIGIN_X      = 40;               // body grid origin X
const TERM_ORIGIN_Y = 30;               // terminal cap top Y
const BODY_ORIGIN_Y = TERM_ORIGIN_Y + TERMINAL_ROWS * CELL;   // 50

// ── Body silhouette — rounded-rectangle with pixel-art bevels ──
// · 2-cell diagonal bevel on BOTTOM corners  (recognisable battery foot)
// · 1-cell round        on TOP    corners    (visually covered by cap)
function _inBody(c, r) {
    if (c < 0 || c >= BODY_COLS || r < 0 || r >= BODY_ROWS) return false;

    const xe      = Math.min(c, BODY_COLS - 1 - c);   // distance from vertical edge
    const yBottom = BODY_ROWS - 1 - r;                 // distance from bottom

    // Bottom bevel — remove three corner cells per side
    if (xe === 0 && yBottom <= 1) return false;
    if (yBottom === 0 && xe <= 1) return false;

    // Top round — remove one corner cell per side
    if (xe === 0 && r === 0) return false;

    return true;
}

// ── Build border + fill buckets ───────────────────────────────
function _buildBattery() {
    const borders = [];
    const fills   = [];

    // Body cells
    for (let r = 0; r < BODY_ROWS; r++) {
        for (let c = 0; c < BODY_COLS; c++) {
            if (!_inBody(c, r)) continue;

            const x = ORIGIN_X      + c * CELL;
            const y = BODY_ORIGIN_Y + r * CELL;

            const isBorder =
                !_inBody(c - 1, r) ||
                !_inBody(c + 1, r) ||
                !_inBody(c,     r - 1) ||
                !_inBody(c,     r + 1);

            if (isBorder) borders.push({ x, y });
            else          fills.push({ x, y, row: r, col: c });
        }
    }

    // Positive terminal cap — solid white block centred above the body
    const termColOffset = (BODY_COLS - TERMINAL_COLS) / 2;
    for (let r = 0; r < TERMINAL_ROWS; r++) {
        for (let c = 0; c < TERMINAL_COLS; c++) {
            const x = ORIGIN_X      + (termColOffset + c) * CELL;
            const y = TERM_ORIGIN_Y + r * CELL;
            borders.push({ x, y });
        }
    }

    // Drain order — topmost row empties first, left→right within a row.
    // This produces the "water level drops" effect as cells fade out.
    fills.sort((a, b) => a.row - b.row || a.col - b.col);
    return { borders, fills };
}

// ── DOM refs ──────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

// ── Pixel state ───────────────────────────────────────────────
let _allPixels   = [];
let _lastVisible = -1;

// ── Time formatter ───────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── SVG rect factory ─────────────────────────────────────────
const _NS = 'http://www.w3.org/2000/svg';
function _makeRect(px, py, fill) {
    const r = document.createElementNS(_NS, 'rect');
    r.setAttribute('x',      String(px));
    r.setAttribute('y',      String(py));
    r.setAttribute('width',  String(VIS_CELL));
    r.setAttribute('height', String(VIS_CELL));
    r.setAttribute('rx',     String(PIXEL_R));
    r.setAttribute('ry',     String(PIXEL_R));
    if (fill) r.setAttribute('fill', fill);
    return r;
}

// ── Build and render both pixel groups ───────────────────────
function _initBattery() {
    const borderGroup = document.getElementById('fj-border-pixels');
    const fillGroup   = document.getElementById('fj-pixels');
    if (!borderGroup || !fillGroup) return;

    const { borders, fills } = _buildBattery();

    // Border + terminal are solid white — set via attribute
    borders.forEach(p => borderGroup.appendChild(_makeRect(p.x, p.y, '#ffffff')));

    // Fills have NO fill attribute — CSS drives colour so the
    // body.battery-mid / body.battery-low tiers can swap hues smoothly.
    _allPixels = fills.map(p => {
        const rect = _makeRect(p.x, p.y, null);
        fillGroup.appendChild(rect);
        return { x: p.x, y: p.y, el: rect };
    });
}

// ── Update pixel visibility from timer progress ──────────────
function _updatePixels(progress) {
    if (!_allPixels.length) return;
    const total   = _allPixels.length;
    const visible = Math.ceil(progress * total);
    if (visible === _lastVisible) return;
    _lastVisible = visible;

    // fills sorted top→bottom; hide the topmost (total − visible) cells
    const showFrom = total - visible;
    _allPixels.forEach((px, i) => {
        if (!px.el) return;
        px.el.style.opacity = i >= showFrom ? '' : '0';
    });
}

// ── Update all battery visuals from a state snapshot ─────────
function _renderBattery(state, visState) {
    const progress = state.totalSeconds > 0
        ? state.remaining / state.totalSeconds
        : 0;

    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    _updatePixels(progress);

    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    const body = document.body;
    body.classList.toggle('is-running', visState === 'running');
    body.classList.toggle('is-paused',  visState === 'paused');
    body.classList.toggle('is-done',    visState === 'done');

    // Charge tiers — drive the CSS colour swap on #fj-pixels rect.
    body.classList.toggle('battery-low', progress > 0   && progress <= 0.20);
    body.classList.toggle('battery-mid', progress > 0.20 && progress <= 0.50);
}

// ── Phase tabs ───────────────────────────────────────────────
function _updateTabs(state) {
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('ftab-' + p);
        if (!tab) return;
        const active = (p === state.phase)
                    || (state.phase === 'countdown' && p === 'pomodoro');
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
    });
}

// ── Session / phase label ────────────────────────────────────
function _updateSessionLabel(state) {
    if (!_sessionLbl) return;
    switch (state.phase) {
        case 'shortbreak':
            _sessionLbl.textContent = `Short Break \u2014 ${state.shortBreakMins} min`;
            break;
        case 'longbreak':
            _sessionLbl.textContent = `Long Break \u2014 ${state.longBreakMins} min`;
            break;
        default:
            _sessionLbl.textContent = `Session ${state.sessionCount} of 4`;
    }
}

// ── TimerCore event subscriber ───────────────────────────────
function _onCoreEvent(event, state) {
    let visState;
    if      (event === 'done')  visState = 'done';
    else if (event === 'pause') visState = 'paused';
    else if (state.isRunning)   visState = 'running';
    else if (state.isPaused)    visState = 'paused';
    else                        visState = '';

    if (event !== 'sessionEnd' && event !== 'skip') {
        _renderBattery(state, visState);
        _updateTabs(state);
        _updateSessionLabel(state);
        if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';
    }

    // Session logging — study phases only.  Break phases MUST NOT be
    // POSTed to /api/tracker/sessions or they would credit the daily goal.
    if (event === 'sessionEnd') {
        if (window.currentUser
            && (state.phase === 'pomodoro' || state.phase === 'countdown')) {
            const mins = Math.round(state.totalSeconds / 60);
            if (mins > 0) {
                fetch('/api/tracker/sessions', {
                    method:    'POST',
                    headers:   { 'Content-Type': 'application/json' },
                    keepalive: true,
                    body: JSON.stringify({
                        materialName:    'Focus Session',
                        durationMinutes: mins,
                        timerMode:       _phaseLabel(state.phase),
                        completed:       true,
                    }),
                })
                .then(() => {
                    if (typeof window.onSessionCompleted === 'function') {
                        window.onSessionCompleted('Focus Session', mins, new Date().toISOString().split('T')[0]);
                    }
                })
                .catch(() => {});
            }
        }
    } else if (event === 'skip') {
        if (state.phase === 'pomodoro' || state.phase === 'countdown') {
            const elapsed = state.totalSeconds - state.remaining;
            const mins    = Math.round(Math.max(0, elapsed) / 60);
            if (mins > 0 && window.currentUser) {
                fetch('/api/tracker/sessions', {
                    method:    'POST',
                    headers:   { 'Content-Type': 'application/json' },
                    keepalive: true,
                    body: JSON.stringify({
                        materialName:    'Focus Session',
                        durationMinutes: mins,
                        timerMode:       _phaseLabel(state.phase),
                        completed:       false,
                    }),
                }).catch(() => {});
            }
        }
    }
}

function _phaseLabel(phase) {
    if (phase === 'shortbreak') return 'Short Break';
    if (phase === 'longbreak')  return 'Long Break';
    if (phase === 'countdown')  return 'Countdown';
    return 'Pomodoro (work)';
}

TimerCore.subscribe(_onCoreEvent);

// ── Public controls — called from HTML onclick ───────────────
function setPhase(phase)  { TimerCore.setPhase(phase); }
function toggleTimer()    { TimerCore.toggle();        }
function skipPhase()      { TimerCore.skip();          }

// ── Fullscreen toggle ────────────────────────────────────────
function _toggleFullscreen() {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
    } else {
        document.exitFullscreen().catch(() => {});
    }
}

// ── Silent auth check + hydrate timer from server ────────────
window.currentUser = null;
async function _initAuth() {
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            window.currentUser = data.username;
            TimerCore.enableBackendSync();

            try {
                const s = await fetch('/api/timer/state');
                if (s.ok) TimerCore.restoreFromServer(await s.json());
            } catch (_) {}
        }
    } catch (_) { /* guest mode — silent */ }
}

// ── Keyboard shortcut ────────────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

// ── Init ─────────────────────────────────────────────────────
(function () {
    _initBattery();

    const state = TimerCore.getState();
    const initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _renderBattery(state, initVis);
    _updateTabs(state);
    _updateSessionLabel(state);
    if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';

    const svg = document.getElementById('finjan-svg');
    if (svg) {
        svg.addEventListener('click', _toggleFullscreen);
        svg.addEventListener('keydown', e => {
            if (e.key === 'Enter') _toggleFullscreen();
        });
    }

    function _onFsChange() {
        const isFs = !!(document.fullscreenElement || document.webkitFullscreenElement);
        document.documentElement.classList.toggle('is-fullscreen', isFs);
    }
    document.addEventListener('fullscreenchange',       _onFsChange);
    document.addEventListener('webkitfullscreenchange', _onFsChange);
})();

_initAuth();
