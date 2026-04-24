'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * Owns no timer state. Reads from TimerCore and renders the
 * pixel-art battery, progress bar, countdown/overtime display,
 * and control buttons. Fullscreen toggle lives on the battery.
 */

// ── Grid geometry ─────────────────────────────────────────────
const BODY_COLS     = 36;
const BODY_ROWS     = 58;
const TERMINAL_COLS = 12;
const TERMINAL_ROWS = 4;
const CELL          = 5;
const VIS_CELL      = 4.25;
const PIXEL_R       = 0.55;
const ORIGIN_X      = 40;
const TERM_ORIGIN_Y = 30;
const BODY_ORIGIN_Y = TERM_ORIGIN_Y + TERMINAL_ROWS * CELL;

function _inBody(c, r) {
    if (c < 0 || c >= BODY_COLS || r < 0 || r >= BODY_ROWS) return false;

    const xe      = Math.min(c, BODY_COLS - 1 - c);
    const yBottom = BODY_ROWS - 1 - r;

    if (xe === 0 && yBottom <= 1) return false;
    if (yBottom === 0 && xe <= 1) return false;
    if (xe === 0 && r === 0) return false;

    return true;
}

function _buildBattery() {
    const borders = [];
    const fills   = [];

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

    const termColOffset = (BODY_COLS - TERMINAL_COLS) / 2;
    for (let r = 0; r < TERMINAL_ROWS; r++) {
        for (let c = 0; c < TERMINAL_COLS; c++) {
            const x = ORIGIN_X      + (termColOffset + c) * CELL;
            const y = TERM_ORIGIN_Y + r * CELL;
            borders.push({ x, y });
        }
    }

    fills.sort((a, b) => a.row - b.row || a.col - b.col);
    return { borders, fills };
}

const _barFill    = document.getElementById('focus-bar-fill');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

let _allPixels   = [];
let _lastVisible = -1;

let _letterPixels     = [];
let _lastDrainLineRow = -1;

function _fmt(secs) {
    secs = Math.max(0, secs | 0);
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

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

// ══════════════════════════════════════════════════════════════
//  LOCK IN TEXT REVEAL — pixel-art letters revealed as the
//  battery drains row-by-row.
// ══════════════════════════════════════════════════════════════
const LTR_L = ['10000','10000','10000','10000','10000','10000','11111'];
const LTR_O = ['01110','10001','10001','10001','10001','10001','01110'];
const LTR_C = ['01111','10000','10000','10000','10000','10000','01111'];
const LTR_K = ['10001','10010','10100','11000','10100','10010','10001'];
const LTR_I = ['111','010','010','010','010','010','111'];
const LTR_N = ['10001','11001','10101','10101','10101','10011','10001'];

const LETTER_LAYOUT = [
    { bitmap: LTR_L, col:  5, row: 20 },
    { bitmap: LTR_O, col: 12, row: 20 },
    { bitmap: LTR_C, col: 19, row: 20 },
    { bitmap: LTR_K, col: 26, row: 20 },
    { bitmap: LTR_I, col: 13, row: 31 },
    { bitmap: LTR_N, col: 18, row: 31 },
];

function _buildLetterCells() {
    const cells = [];
    for (const { bitmap, col: cOff, row: rOff } of LETTER_LAYOUT) {
        for (let r = 0; r < bitmap.length; r++) {
            const rowStr = bitmap[r];
            for (let c = 0; c < rowStr.length; c++) {
                if (rowStr[c] === '1') {
                    cells.push({ col: cOff + c, row: rOff + r });
                }
            }
        }
    }
    return cells;
}

function _initBattery() {
    const borderGroup = document.getElementById('fj-border-pixels');
    const fillGroup   = document.getElementById('fj-pixels');
    const letterGroup = document.getElementById('fj-letter-pixels');
    if (!borderGroup || !fillGroup) return;

    const { borders, fills } = _buildBattery();

    borders.forEach(p => borderGroup.appendChild(_makeRect(p.x, p.y, '#ffffff')));

    _allPixels = fills.map(p => {
        const rect = _makeRect(p.x, p.y, null);
        fillGroup.appendChild(rect);
        return { x: p.x, y: p.y, row: p.row, el: rect };
    });

    if (letterGroup) {
        const letterCells = _buildLetterCells();
        _letterPixels = letterCells.map(lc => {
            const x = ORIGIN_X      + lc.col * CELL;
            const y = BODY_ORIGIN_Y + lc.row * CELL;
            const rect = _makeRect(x, y, null);
            letterGroup.appendChild(rect);
            return { row: lc.row, el: rect };
        });
    }
}

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

    const drainLineRow = showFrom < total ? _allPixels[showFrom].row : BODY_ROWS;
    if (drainLineRow !== _lastDrainLineRow && _letterPixels.length) {
        _lastDrainLineRow = drainLineRow;
        for (let i = 0; i < _letterPixels.length; i++) {
            const lp = _letterPixels[i];
            lp.el.classList.toggle('revealed', lp.row < drainLineRow);
        }
    }
}

function _renderBattery(state, visState) {
    const target  = state.targetSeconds || 0;
    const elapsed = state.elapsedSeconds || 0;
    // In overtime: freeze battery at full (progress = 1 so no pixels hidden)
    // with a pulse driven by the body.is-overtime CSS class.
    let progress  = state.isOvertime
        ? 1
        : (target > 0 ? (1 - elapsed / target) : 0);
    if (progress < 0) progress = 0;
    if (progress > 1) progress = 1;

    const timeEl = document.getElementById('finjan-time');
    if (timeEl) {
        timeEl.textContent = state.isOvertime
            ? '+' + _fmt(state.overtimeSeconds)
            : _fmt(state.remainingSeconds);
        timeEl.classList.toggle('overtime', !!state.isOvertime);
    }

    _updatePixels(progress);

    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    const body = document.body;
    body.classList.toggle('is-running',  visState === 'running');
    body.classList.toggle('is-paused',   visState === 'paused');
    body.classList.toggle('is-overtime', !!state.isOvertime);

    // Low/mid charge tiers — drive the colour swap. Suppressed in overtime
    // so the is-overtime styling takes over.
    body.classList.toggle('battery-low', !state.isOvertime && progress > 0    && progress <= 0.20);
    body.classList.toggle('battery-mid', !state.isOvertime && progress > 0.20 && progress <= 0.50);
}

function _updateStopBtn(state) {
    const stopBtn = document.getElementById('focus-stop-btn');
    if (!stopBtn) return;
    const hasActive = state.isRunning || state.isPaused || state.elapsedSeconds > 0;
    stopBtn.classList.toggle('hidden', !hasActive);
}

function _announceTimer(msg) {
    const el = document.getElementById('timer-announcer');
    if (!el) return;
    el.textContent = msg;
}

function _onCoreEvent(event, state) {
    let visState;
    if      (event === 'pause') visState = 'paused';
    else if (state.isRunning)   visState = 'running';
    else if (state.isPaused)    visState = 'paused';
    else                        visState = '';

    if (event !== 'sessionEnd') {
        _renderBattery(state, visState);
        _updateStopBtn(state);
        if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';
    }

    if      (event === 'start' && !state.isPaused) _announceTimer('Timer started');
    else if (event === 'pause')                    _announceTimer('Timer paused');
    else if (event === 'milestone')                _announceTimer('Target reached — overtime');

    if (event === 'sessionEnd') {
        const actualMins = Math.max(0, Math.round(state.actualDurationSeconds / 60));
        const plannedMin = Math.max(0, Math.round(state.plannedSeconds       / 60));
        const overtime   = Math.max(0, actualMins - plannedMin);
        if (actualMins > 0 && window.currentUser) {
            fetch('/api/tracker/sessions', {
                method:    'POST',
                headers:   { 'Content-Type': 'application/json' },
                keepalive: true,
                body: JSON.stringify({
                    materialName:    'Focus Session',
                    durationMinutes: actualMins,
                    plannedMinutes:  plannedMin,
                    overtimeMinutes: overtime,
                    timerMode:       'study',
                    completed:       true,
                }),
            }).catch(() => {});
        }
    }
}

TimerCore.subscribe(_onCoreEvent);

// ── Public controls — called from HTML onclick ───────────────
function toggleTimer()    { TimerCore.toggle(); }
function skipPhase()      { TimerCore.skip();   }
function stopPhase()      { TimerCore.stop();   }

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

document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

(function () {
    _initBattery();

    const state = TimerCore.getState();
    const initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _renderBattery(state, initVis);
    _updateStopBtn(state);
    if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';

    const svg = document.getElementById('finjan-svg');
    if (svg) {
        svg.addEventListener('click', _toggleFullscreen);
        svg.addEventListener('keydown', e => {
            if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
                e.preventDefault();
                _toggleFullscreen();
            }
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
