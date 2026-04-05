'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore and drive the flask SVG visuals
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Silent auth check so session saving works for logged-in users
 *
 * Flask fill mechanism
 *   The SVG contains a dark cover rect (#flask-cover) anchored at
 *   y=FLASK_TOP with height=0. As progress decreases from 1→0,
 *   the cover height grows from 0→FLASK_FILL_HEIGHT, hiding pixels
 *   row by row from the TOP. The clipping by #c-flask keeps
 *   everything inside the flask shape.
 */

// ── Flask geometry (must match the SVG viewBox in focus.html) ─
const FLASK_TOP         = 16;   // y where neck opening starts
const FLASK_FILL_HEIGHT = 244;  // y=16 to y=260 (body base)

// ── DOM refs ──────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');
const _flaskCover = document.getElementById('flask-cover');
const _flaskScene = document.getElementById('flask-scene');

// ── Time formatter ────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Generate the pixel grid inside the flask SVG ──────────────
//
//   Pixels are small squares laid out on a dense grid.
//   The SVG clipPath #c-flask clips them to the flask shape.
//   The cover rect (#flask-cover) then hides the top portion
//   as time decreases. Pixel color varies slightly for a richer look.
function _generatePixels() {
    const g = document.getElementById('flask-pixels');
    if (!g) return;

    const PX   = 6;   // pixel size (SVG user units)
    const GAP  = 3;   // gap between pixels
    const STEP = PX + GAP;

    // Color palette — varying shades of purple
    const COLORS = ['#7c5cfc', '#8b5cf6', '#9061f9', '#a078fb', '#6748e8', '#b39dfa'];

    let html = '';
    const colCount = Math.ceil(200  / STEP) + 1;
    const rowCount = Math.ceil(285  / STEP) + 1;

    for (let r = 0; r < rowCount; r++) {
        for (let c = 0; c < colCount; c++) {
            const x = c * STEP + 1;
            const y = r * STEP + FLASK_TOP;
            // Non-trivial index so adjacent pixels differ
            const color = COLORS[(r * 5 + c * 3 + (r ^ c)) % COLORS.length];
            html += `<rect x="${x}" y="${y}" width="${PX}" height="${PX}" rx="1" fill="${color}"/>`;
        }
    }
    g.innerHTML = html;
}

// ── Update all flask visuals from a state snapshot ────────────
function _updateFlask(state, visState) {
    const progress = state.totalSeconds > 0 ? state.remaining / state.totalSeconds : 0;

    // 1. Timer text (SVG <text> element)
    const timeEl = document.getElementById('flask-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    // 2. Cover rect height = (1 − progress) × FLASK_FILL_HEIGHT
    //    Grows from top → hides pixels row by row as time drains.
    if (_flaskCover) {
        const coverH = Math.round((1 - progress) * FLASK_FILL_HEIGHT);
        _flaskCover.setAttribute('height', String(Math.max(0, coverH)));
    }

    // 3. Ambient glow: dims from 1.0 (full) → 0.25 (empty)
    if (_flaskScene) {
        const glow = (0.25 + progress * 0.75).toFixed(3);
        _flaskScene.style.setProperty('--flask-glow', glow);
    }

    // 4. Progress bar (HTML element, CSS-transitioned for smooth fill)
    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    // 5. Body state classes drive CSS animations
    document.body.classList.toggle('is-running', visState === 'running');
    document.body.classList.toggle('is-paused',  visState === 'paused');
    document.body.classList.toggle('is-done',    visState === 'done');
}

// ── Update phase tabs ─────────────────────────────────────────
function _updateTabs(state) {
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('ftab-' + p);
        if (!tab) return;
        // countdown shares the pomodoro tab highlight
        const active = (p === state.phase)
                    || (state.phase === 'countdown' && p === 'pomodoro');
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
    });
}

// ── Update session / phase label ──────────────────────────────
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

// ── TimerCore event subscriber ────────────────────────────────
function _onCoreEvent(event, state) {
    // Compute visual state
    let visState;
    if      (event === 'done')  visState = 'done';
    else if (event === 'pause') visState = 'paused';
    else                        visState = state.isRunning ? 'running' : '';

    // Update flask visuals for all events except logging-only
    if (event !== 'sessionEnd' && event !== 'skip') {
        _updateFlask(state, visState);
        _updateTabs(state);
        _updateSessionLabel(state);
        if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';
    }

    // Session logging — only when focus page is the active page / master
    if (event === 'sessionEnd') {
        if (window.currentUser) {
            const mins = Math.round(state.totalSeconds / 60);
            const mode = _phaseLabel(state.phase);
            fetch('/api/tracker/sessions', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    materialName:    'Focus Session',
                    durationMinutes: mins,
                    timerMode:       mode,
                    completed:       true,
                }),
            }).catch(() => {});
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

// ── Public controls — called from HTML onclick ─────────────────
function setPhase(phase)  { TimerCore.setPhase(phase);  }
function toggleTimer()    { TimerCore.toggle();          }
function skipPhase()      { TimerCore.skip();            }

// ── Silent auth check ─────────────────────────────────────────
window.currentUser = null;

async function _initAuth() {
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            window.currentUser = data.username;
        }
    } catch (_) { /* guest mode — silent */ }
}

// ── Keyboard shortcut ─────────────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

// ── Init ──────────────────────────────────────────────────────
_generatePixels();

(function () {
    const state = TimerCore.getState();
    _updateFlask(state, state.isRunning ? 'running' : '');
    _updateTabs(state);
    _updateSessionLabel(state);
    if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';
})();

_initAuth();
