'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore and drive the finjan (Arabic coffee-cup) SVG
 *   - Generate a pixel grid inside the cup interior; pixels drain as time passes
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on finjan click
 *
 * Finjan fill mechanism
 *   The SVG contains:
 *     1. A pixel grid <g id="finjan-pixels"> populated with colored squares,
 *        all clipped to the cup interior via #fj-clip.
 *     2. A dark cover rect (#finjan-cover) anchored at y=FINJAN_TOP (44),
 *        height=0 when full. As progress decreases from 1→0,
 *        cover height grows from 0→FINJAN_FILL_HEIGHT (170),
 *        hiding pixels from the top down — coffee drains visually.
 */

// ── Finjan geometry (must match the SVG viewBox in focus.html) ─
const FINJAN_TOP         = 44;   // y where cup interior starts (top of clip)
const FINJAN_FILL_HEIGHT = 170;  // interior height: y=44 to y=214

// ── Pixel grid constants ──────────────────────────────────────
const PIXEL_SIZE   = 8;    // px square side
const PIXEL_GAP    = 2;    // gap between squares
const PIXEL_STEP   = PIXEL_SIZE + PIXEL_GAP;   // 10
const PIXEL_COLORS = ['#7c3aed', '#8b5cf6', '#a78bfa', '#6d28d9', '#9061f9'];

// ── DOM refs ──────────────────────────────────────────────────
const _barFill     = document.getElementById('focus-bar-fill');
const _sessionLbl  = document.getElementById('focus-session-label');
const _toggleBtn   = document.getElementById('focus-toggle-btn');
const _finjanCover = document.getElementById('finjan-cover');
const _finjanScene = document.getElementById('finjan-scene');

// ── Time formatter ────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Generate pixel grid inside the cup ───────────────────────
// Fills #finjan-pixels with a grid of 8x8 SVG rects covering the
// interior bounding box. The clip-path #fj-clip masks out anything
// outside the actual cup shape. Colors are assigned pseudo-randomly
// per position so the grid looks textured rather than uniform.
function _generatePixels() {
    const container = document.getElementById('finjan-pixels');
    if (!container) return;

    // Bounding box of the interior (matches FINJAN_TOP + FINJAN_FILL_HEIGHT)
    const startX = 44;   // left margin inside clip
    const endX   = 196;  // right margin
    const startY = FINJAN_TOP;
    const endY   = FINJAN_TOP + FINJAN_FILL_HEIGHT; // 214

    const rects = [];
    for (let y = startY; y < endY; y += PIXEL_STEP) {
        for (let x = startX; x < endX; x += PIXEL_STEP) {
            // Deterministic color based on position (avoid uniform look)
            const ci = ((x * 3) ^ (y * 7)) % PIXEL_COLORS.length;
            const color = PIXEL_COLORS[Math.abs(ci)];
            rects.push(
                `<rect x="${x}" y="${y}" width="${PIXEL_SIZE}" height="${PIXEL_SIZE}" fill="${color}" opacity="0.82"/>`
            );
        }
    }
    container.innerHTML = rects.join('');
}

// ── Update all finjan visuals from a state snapshot ──────────
function _updateFinjan(state, visState) {
    const progress = state.totalSeconds > 0 ? state.remaining / state.totalSeconds : 0;

    // 1. Timer text (HTML element below SVG)
    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    // 2. Cover rect height = (1 − progress) × FINJAN_FILL_HEIGHT
    //    Grows from top — pixels drain from top as time passes.
    if (_finjanCover) {
        const coverH = Math.round((1 - progress) * FINJAN_FILL_HEIGHT);
        _finjanCover.setAttribute('height', String(Math.max(0, coverH)));
    }

    // 3. Progress bar (HTML element, CSS-transitioned for smooth fill)
    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    // 4. Body state classes drive CSS animations
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

    // Update finjan visuals for all events except logging-only
    if (event !== 'sessionEnd' && event !== 'skip') {
        _updateFinjan(state, visState);
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
    } else if (event === 'skip') {
        if (state.phase === 'pomodoro' || state.phase === 'countdown') {
            const elapsed = state.totalSeconds - state.remaining;
            const mins    = Math.round(Math.max(0, elapsed) / 60);
            if (mins > 0 && window.currentUser) {
                fetch('/api/tracker/sessions', {
                    method:  'POST',
                    headers: { 'Content-Type': 'application/json' },
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

// ── Public controls — called from HTML onclick ─────────────────
function setPhase(phase)  { TimerCore.setPhase(phase);  }
function toggleTimer()    { TimerCore.toggle();          }
function skipPhase()      { TimerCore.skip();            }

// ── Fullscreen toggle (wired to finjan click) ─────────────────
function _toggleFullscreen() {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
    } else {
        document.exitFullscreen().catch(() => {});
    }
}

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
(function () {
    // Build pixel grid first (static; doesn't change per tick)
    _generatePixels();

    const state = TimerCore.getState();
    _updateFinjan(state, state.isRunning ? 'running' : '');
    _updateTabs(state);
    _updateSessionLabel(state);
    if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';

    // Wire finjan click → fullscreen toggle
    const finjanSvg = document.getElementById('finjan-svg');
    if (finjanSvg) {
        finjanSvg.addEventListener('click', _toggleFullscreen);
        finjanSvg.addEventListener('keydown', e => {
            if (e.key === 'Enter') _toggleFullscreen();
        });
    }
})();

_initAuth();
