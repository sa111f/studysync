'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore and drive the finjan (Arabic coffee-cup) SVG
 *   - Update the gradient liquid fill level inside the cup each tick
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on finjan click
 *
 * Fill mechanism
 *   The SVG contains a gradient fill rect (#fj-fill-rect) clipped to
 *   the interior cup shape.  Each tick, focus.js sets:
 *
 *     fillY  = FINJAN_TOP + (1 − progress) × FINJAN_FILL_HEIGHT
 *     fillH  = progress × FINJAN_FILL_HEIGHT
 *
 *   The bottom of the fill rect stays fixed at y=200; the top rises as
 *   time drains — liquid depletes from the top down.
 *   The gradient is anchored in SVG user-space so the surface colour
 *   (bright lavender) always appears at the current liquid level.
 */

// ── Finjan geometry (must match the SVG viewBox in focus.html) ─
const FINJAN_TOP         = 60;   // y where cup interior begins
const FINJAN_FILL_HEIGHT = 140;  // interior height used for fill (y=60→200)

// ── DOM refs ──────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

// SVG fill elements
const _fjFill    = document.getElementById('fj-fill-rect');
const _fjDepth   = document.getElementById('fj-depth-rect');
const _fjShimmer = document.getElementById('fj-shimmer-rect');
const _fjSurface = document.getElementById('fj-surface');

// ── Time formatter ────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Update all finjan visuals from a state snapshot ──────────
function _updateFinjan(state, visState) {
    const progress = state.totalSeconds > 0
        ? state.remaining / state.totalSeconds
        : 0;

    // 1. Timer text below SVG
    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    // 2. Liquid fill level
    //    Bottom of fill stays fixed at FINJAN_TOP + FINJAN_FILL_HEIGHT (y=200).
    //    Top rises as time drains.
    const fillH = Math.max(0, Math.round(progress * FINJAN_FILL_HEIGHT));
    const fillY = FINJAN_TOP + FINJAN_FILL_HEIGHT - fillH;  // top of liquid

    if (_fjFill) {
        _fjFill.setAttribute('y',      String(fillY));
        _fjFill.setAttribute('height', String(fillH));
    }
    if (_fjDepth) {
        _fjDepth.setAttribute('y',      String(fillY));
        _fjDepth.setAttribute('height', String(fillH));
    }

    // Shimmer strip: 14 px at the liquid surface, hidden when cup is empty
    if (_fjShimmer) {
        _fjShimmer.setAttribute('y', String(fillY));
        _fjShimmer.style.opacity = fillH > 6 ? '1' : '0';
    }

    // Surface hairline
    if (_fjSurface) {
        _fjSurface.setAttribute('y1', String(fillY));
        _fjSurface.setAttribute('y2', String(fillY));
        _fjSurface.style.opacity = fillH > 4 ? '0.55' : '0';
    }

    // 3. Progress bar
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
    let visState;
    if      (event === 'done')  visState = 'done';
    else if (event === 'pause') visState = 'paused';
    else                        visState = state.isRunning ? 'running' : '';

    if (event !== 'sessionEnd' && event !== 'skip') {
        _updateFinjan(state, visState);
        _updateTabs(state);
        _updateSessionLabel(state);
        if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';
    }

    // Session logging
    if (event === 'sessionEnd') {
        if (window.currentUser) {
            const mins = Math.round(state.totalSeconds / 60);
            fetch('/api/tracker/sessions', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    materialName:    'Focus Session',
                    durationMinutes: mins,
                    timerMode:       _phaseLabel(state.phase),
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
    const state = TimerCore.getState();
    _updateFinjan(state, state.isRunning ? 'running' : '');
    _updateTabs(state);
    _updateSessionLabel(state);
    if (_toggleBtn) _toggleBtn.textContent = state.isRunning ? 'Pause' : 'Start';

    // Wire finjan click → fullscreen
    const finjanSvg = document.getElementById('finjan-svg');
    if (finjanSvg) {
        finjanSvg.addEventListener('click', _toggleFullscreen);
        finjanSvg.addEventListener('keydown', e => {
            if (e.key === 'Enter') _toggleFullscreen();
        });
    }
})();

_initAuth();
