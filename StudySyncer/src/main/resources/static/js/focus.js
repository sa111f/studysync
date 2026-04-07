'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore and drive the finjan (Arabic coffee-cup) SVG
 *   - Generate a pixel grid inside the cup and update visible count each tick
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on finjan click
 *
 * Pixel fill mechanism
 *   On page load, focus.js probes the inner clip path using isPointInFill
 *   to build a sorted list of pixel positions inside the cup.
 *   Each tick:
 *     visibleCount = ceil(progress × totalPixels)
 *   The BOTTOM pixels are always shown first; as time drains the TOP pixels
 *   disappear — liquid depletes from the top down.
 *   Pixels are pre-coloured: bright lavender at top → deep purple at bottom.
 */

// ── Inner clip path — must exactly match <clipPath id="fj-clip"> in HTML ──────
const FJ_INNER_CLIP_D =
    'M 68,62 L 232,62 C 248,86 232,162 188,200 Q 150,214 112,200 C 52,162 52,86 68,62 Z';

// Pixel grid config
const FJ_PX_SIZE = 7;   // square side length (SVG units)
const FJ_PX_STEP = 10;  // grid step (size + gap)

// ── DOM refs ──────────────────────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

// ── Pixel state ───────────────────────────────────────────────────────────────
let _allPixels   = [];   // sorted top→bottom; each entry: {x, y, el}
let _lastVisible = -1;   // last visibleCount to skip redundant updates

// ── Time formatter ────────────────────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Build pixel grid ──────────────────────────────────────────────────────────
/**
 * Uses isPointInFill (SVGGeometryElement) via a hidden probe path for
 * accurate containment within the cup's inner clip region.
 * Falls back to a loose bounding-box check for environments that lack the API.
 *
 * Returns an array of {x, y, el:null} sorted top → bottom (ascending y).
 */
function _buildPixelGrid() {
    const svg = document.getElementById('finjan-svg');
    if (!svg) return [];

    const NS    = 'http://www.w3.org/2000/svg';
    const probe = document.createElementNS(NS, 'path');
    probe.setAttribute('d', FJ_INNER_CLIP_D);
    probe.setAttribute('fill', 'black');
    probe.style.cssText = 'opacity:0;pointer-events:none;position:absolute;';
    svg.appendChild(probe);

    const useAPI = typeof probe.isPointInFill === 'function';
    const pixels = [];

    for (let py = 64; py < 215; py += FJ_PX_STEP) {
        for (let px = 62; px < 238; px += FJ_PX_STEP) {
            let inside = false;
            if (useAPI) {
                const pt = svg.createSVGPoint();
                pt.x = px + FJ_PX_SIZE / 2;
                pt.y = py + FJ_PX_SIZE / 2;
                try { inside = probe.isPointInFill(pt); } catch (_) {
                    inside = px >= 70 && px <= 224 && py >= 62 && py <= 207;
                }
            } else {
                // Fallback: crude horizontal bounding box
                inside = px >= 70 && px <= 224 && py >= 62 && py <= 207;
            }
            if (inside) pixels.push({ x: px, y: py, el: null });
        }
    }

    svg.removeChild(probe);
    pixels.sort((a, b) => a.y !== b.y ? a.y - b.y : a.x - b.x);
    return pixels;
}

// ── Create SVG rect elements for each pixel ───────────────────────────────────
function _initPixels() {
    const group = document.getElementById('fj-pixels');
    if (!group) return;

    _allPixels = _buildPixelGrid();

    const NS   = 'http://www.w3.org/2000/svg';
    const minY = _allPixels.length ? _allPixels[0].y : 62;
    const maxY = _allPixels.length ? _allPixels[_allPixels.length - 1].y : 207;

    _allPixels.forEach(px => {
        // Colour gradient: bright lavender at top → deep purple at bottom
        const rel = (px.y - minY) / Math.max(1, maxY - minY);
        let fill;
        if      (rel < 0.20) fill = '#ddd6fe';
        else if (rel < 0.42) fill = '#c4b5fd';
        else if (rel < 0.62) fill = '#a78bfa';
        else if (rel < 0.80) fill = '#7c3aed';
        else                 fill = '#5b21b6';

        const rect = document.createElementNS(NS, 'rect');
        rect.setAttribute('x',      String(px.x));
        rect.setAttribute('y',      String(px.y));
        rect.setAttribute('width',  String(FJ_PX_SIZE));
        rect.setAttribute('height', String(FJ_PX_SIZE));
        rect.setAttribute('rx',     '1.5');
        rect.setAttribute('ry',     '1.5');
        rect.setAttribute('fill',   fill);
        group.appendChild(rect);
        px.el = rect;
    });
}

// ── Update pixel visibility from timer progress ───────────────────────────────
function _updatePixels(progress) {
    if (!_allPixels.length) return;

    const total   = _allPixels.length;
    const visible = Math.ceil(progress * total);
    if (visible === _lastVisible) return;   // no change
    _lastVisible = visible;

    // Bottom `visible` pixels are shown; top ones are hidden
    const showFrom = total - visible;
    _allPixels.forEach((px, i) => {
        if (!px.el) return;
        px.el.style.opacity = i >= showFrom ? '' : '0';
    });
}

// ── Update all finjan visuals from a state snapshot ───────────────────────────
function _updateFinjan(state, visState) {
    const progress = state.totalSeconds > 0
        ? state.remaining / state.totalSeconds
        : 0;

    // 1. Timer text below SVG
    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    // 2. Pixel fill
    _updatePixels(progress);

    // 3. Progress bar
    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    // 4. Body state classes drive CSS animations
    document.body.classList.toggle('is-running', visState === 'running');
    document.body.classList.toggle('is-paused',  visState === 'paused');
    document.body.classList.toggle('is-done',    visState === 'done');
}

// ── Update phase tabs ─────────────────────────────────────────────────────────
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

// ── Update session / phase label ──────────────────────────────────────────────
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

// ── TimerCore event subscriber ────────────────────────────────────────────────
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

// ── Public controls — called from HTML onclick ─────────────────────────────────
function setPhase(phase)  { TimerCore.setPhase(phase);  }
function toggleTimer()    { TimerCore.toggle();          }
function skipPhase()      { TimerCore.skip();            }

// ── Fullscreen toggle (wired to finjan click) ─────────────────────────────────
function _toggleFullscreen() {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
    } else {
        document.exitFullscreen().catch(() => {});
    }
}

// ── Silent auth check ─────────────────────────────────────────────────────────
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

// ── Keyboard shortcut ─────────────────────────────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

// ── Init ──────────────────────────────────────────────────────────────────────
(function () {
    // Build and render the pixel grid first
    _initPixels();

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
