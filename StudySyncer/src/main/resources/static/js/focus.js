'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Procedurally generate a symmetric pixel-art Arabic finjan
 *   - Drain fill pixels top-row-first as timer progresses
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on finjan click
 *
 * FINJAN PIXEL-ART ARCHITECTURE
 * ─────────────────────────────
 * The cup is a grid of square cells.  For each body row r we compute a
 * half-width hw(r); a cell (c,r) is "inside" iff |c+0.5 − CENTER| < hw(r).
 * Because the half-width is computed from the same function on both sides,
 * the resulting shape is pixel-perfect symmetric about the central column.
 *
 * Classification of each inside cell:
 *   BORDER → inside, and at least one 4-neighbor is outside the combined shape
 *   FILL   → inside, fully surrounded by other inside cells (body rows only)
 * The foot section is rendered as solid white — pure saucer, no interior.
 *
 * Drain order: fill cells are sorted by ascending row, so the topmost row
 * vanishes first as time runs out, giving the "cup emptying" illusion.
 */

// ── Grid geometry ─────────────────────────────────────────────
const GRID_COLS       = 42;
const GRID_ROWS_BODY  = 42;
const GRID_ROWS_FOOT  = 3;
const GRID_ROWS       = GRID_ROWS_BODY + GRID_ROWS_FOOT;
const CELL            = 5;                 // grid stride (SVG units)
const VIS_CELL        = 4.2;               // visible square side (0.8 gap)
const PIXEL_R         = 0.55;              // tiny rounding on corners
const ORIGIN_X        = 45;
const ORIGIN_Y        = 38;
const CENTER_COL      = GRID_COLS / 2;     // 21 — vertical axis of symmetry

// ── Cup silhouette — body half-width in cells at body row r ──
function _bodyHalfAt(r) {
    const N = GRID_ROWS_BODY - 1;
    const t = r / N;                       // 0 at rim, 1 at bottom of body

    const rimHalf  = 18.5;                 // wide stout Arabic rim
    const baseHalf = 4.5;                  // narrow pedestal base

    // Cosine smoothstep — gentle taper from rim to base
    const eased = 0.5 - 0.5 * Math.cos(Math.PI * t);
    let hw = rimHalf - (rimHalf - baseHalf) * Math.pow(eased, 1.25);

    // Traditional Arabic shoulder — slight outward bulge just below rim
    if (t > 0.03 && t < 0.30) {
        const u = (t - 0.03) / 0.27;
        hw += Math.sin(Math.PI * u) * 0.9;
    }
    return hw;
}

// ── Foot profile — flared saucer beneath body ────────────────
const FOOT_PROFILE = [6.0, 7.2, 7.2];
function _footHalfAt(fr) { return FOOT_PROFILE[fr]; }

// ── Inside tests ─────────────────────────────────────────────
function _inBody(c, r) {
    if (r < 0 || r >= GRID_ROWS_BODY) return false;
    return Math.abs(c + 0.5 - CENTER_COL) < _bodyHalfAt(r);
}
function _inFoot(c, r) {
    const fr = r - GRID_ROWS_BODY;
    if (fr < 0 || fr >= GRID_ROWS_FOOT) return false;
    return Math.abs(c + 0.5 - CENTER_COL) < _footHalfAt(fr);
}
function _inside(c, r) { return _inBody(c, r) || _inFoot(c, r); }

// ── Classify every cell into border / fill buckets ───────────
function _buildGrid() {
    const borders = [];
    const fills   = [];

    for (let r = 0; r < GRID_ROWS; r++) {
        for (let c = 0; c < GRID_COLS; c++) {
            if (!_inside(c, r)) continue;

            const x = ORIGIN_X + c * CELL;
            const y = ORIGIN_Y + r * CELL;

            // Foot: solid white saucer — no interior fill
            if (r >= GRID_ROWS_BODY) {
                borders.push({ x, y });
                continue;
            }

            const isBorder =
                !_inside(c - 1, r) ||
                !_inside(c + 1, r) ||
                !_inside(c,     r - 1) ||
                !_inside(c,     r + 1);

            if (isBorder) borders.push({ x, y });
            else          fills.push({ x, y, r });
        }
    }

    // Drain order: topmost row empties first
    fills.sort((a, b) => a.r - b.r || a.x - b.x);
    return { borders, fills };
}

// ── DOM refs ─────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

// ── Pixel state ──────────────────────────────────────────────
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
    r.setAttribute('fill',   fill);
    return r;
}

// ── Build and render both pixel groups ───────────────────────
function _initFinjan() {
    const borderGroup = document.getElementById('fj-border-pixels');
    const fillGroup   = document.getElementById('fj-pixels');
    if (!borderGroup || !fillGroup) return;

    const { borders, fills } = _buildGrid();

    borders.forEach(p => borderGroup.appendChild(_makeRect(p.x, p.y, '#ffffff')));

    _allPixels = fills.map(p => {
        const rect = _makeRect(p.x, p.y, '#9f7dff');
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

    const showFrom = total - visible;
    _allPixels.forEach((px, i) => {
        if (!px.el) return;
        px.el.style.opacity = i >= showFrom ? '' : '0';
    });
}

// ── Update all finjan visuals from a state snapshot ──────────
function _updateFinjan(state, visState) {
    const progress = state.totalSeconds > 0
        ? state.remaining / state.totalSeconds
        : 0;

    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    _updatePixels(progress);

    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    document.body.classList.toggle('is-running', visState === 'running');
    document.body.classList.toggle('is-paused',  visState === 'paused');
    document.body.classList.toggle('is-done',    visState === 'done');
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
        _updateFinjan(state, visState);
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
    _initFinjan();

    const state = TimerCore.getState();
    const initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _updateFinjan(state, initVis);
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
