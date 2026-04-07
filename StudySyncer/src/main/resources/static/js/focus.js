'use strict';
/**
 * focus.js — Focus Mode page UI layer.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Generate white pixel border squares tracing the finjan silhouette
 *   - Generate small purple pixel squares filling the cup interior
 *   - Drive visible pixel count from timer progress (top pixels drain first)
 *   - Update phase tabs, progress bar, session label, toggle button
 *   - POST completed sessions to /api/tracker/sessions
 *   - Handle fullscreen toggle on finjan click
 *
 * Cup geometry (must match focus.html SVG, viewBox 0 0 300 270):
 *
 *   Outer body:
 *     M 48,48 L 252,48
 *     C 268,70  242,162  176,205   ← right side, strong outward shoulder
 *     Q 150,220  124,205            ← rounded base
 *     C  58,162   32, 70   48, 48  ← left (mirror)
 *
 *   Inner clip (purple pixels masked here):
 *     M 62,62 L 238,62
 *     C 252,88  228,162  166,198
 *     Q 150,212  134,198
 *     C  72,162   48, 88   62, 62
 */

// ── Inner clip path — must exactly match <clipPath id="fj-clip"> in HTML ──────
// Uses the SAME path as the outer body so purple pixels fill right up to the
// white border pixels with no dark wall gap in between.
const FJ_INNER_CLIP_D =
    'M 48,48 L 252,48 C 268,70 242,162 176,205 Q 150,220 124,205 C 58,162 32,70 48,48 Z';

// ── Outer body segments used for border pixel sampling ────────────────────────
// Each cubic: [P0, CP1, CP2, P3]   Quad: [P0, CP, P1]
const FJ_RIGHT_CUBIC = [[252, 48], [268, 70], [242, 162], [176, 205]];
const FJ_BOTTOM_QUAD = [[176, 205], [150, 220], [124, 205]];
const FJ_LEFT_CUBIC  = [[124, 205], [58, 162],  [32,  70],  [48,  48]];

// ── Pixel config ──────────────────────────────────────────────────────────────
const FJ_PX_SIZE     = 4;   // inner pixel side length (SVG units)
const FJ_PX_STEP     = 6;   // inner pixel grid step (size + gap)
const FJ_BORDER_SNAP = 4;   // border grid snap interval
const FJ_BORDER_SIZE = 3;   // border pixel side length

// ── DOM refs ──────────────────────────────────────────────────────────────────
const _barFill    = document.getElementById('focus-bar-fill');
const _sessionLbl = document.getElementById('focus-session-label');
const _toggleBtn  = document.getElementById('focus-toggle-btn');

// ── Pixel state ───────────────────────────────────────────────────────────────
let _allPixels   = [];   // sorted top→bottom; each: {x, y, el}
let _lastVisible = -1;

// ── Time formatter ────────────────────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Bezier helpers ────────────────────────────────────────────────────────────
function _cubic(pts, n) {
    const out = [];
    for (let i = 0; i <= n; i++) {
        const t = i / n, mt = 1 - t;
        out.push({
            x: mt*mt*mt*pts[0][0] + 3*mt*mt*t*pts[1][0] + 3*mt*t*t*pts[2][0] + t*t*t*pts[3][0],
            y: mt*mt*mt*pts[0][1] + 3*mt*mt*t*pts[1][1] + 3*mt*t*t*pts[2][1] + t*t*t*pts[3][1]
        });
    }
    return out;
}

function _quad(p0, cp, p1, n) {
    const out = [];
    for (let i = 0; i <= n; i++) {
        const t = i / n, mt = 1 - t;
        out.push({
            x: mt*mt*p0[0] + 2*mt*t*cp[0] + t*t*p1[0],
            y: mt*mt*p0[1] + 2*mt*t*cp[1] + t*t*p1[1]
        });
    }
    return out;
}

// ── Build and render white pixel border ───────────────────────────────────────
/**
 * Samples the outer cup silhouette and inner rim line at fine intervals,
 * snaps each sample point to a 4 px grid, deduplicates, and renders a
 * 3×3 white square at each unique grid position.
 *
 * Traced segments:
 *   · Top rim line       y=48, x=48–252
 *   · Right outer cubic  (252,48) → (176,205)
 *   · Bottom base arc    (176,205) → (124,205)
 *   · Left outer cubic   (124,205) → (48,48)
 *
 * The inner rim line is intentionally omitted — no dark separator line.
 */
function _initBorderPixels() {
    const group = document.getElementById('fj-border-pixels');
    if (!group) return;

    const pts = [];

    // Top rim line
    const RIM_STEPS = 200;
    for (let i = 0; i <= RIM_STEPS; i++) {
        pts.push({ x: 48 + i * (204 / RIM_STEPS), y: 48 });
    }

    // Right outer cubic (fine sampling for smooth coverage)
    _cubic(FJ_RIGHT_CUBIC, 400).forEach(p => pts.push(p));

    // Bottom base quadratic
    _quad(FJ_BOTTOM_QUAD[0], FJ_BOTTOM_QUAD[1], FJ_BOTTOM_QUAD[2], 130).forEach(p => pts.push(p));

    // Left outer cubic
    _cubic(FJ_LEFT_CUBIC, 400).forEach(p => pts.push(p));

    // Snap to pixel grid and deduplicate
    const SNAP = FJ_BORDER_SNAP;
    const SZ   = FJ_BORDER_SIZE;
    const seen = new Set();
    const NS   = 'http://www.w3.org/2000/svg';

    pts.forEach(pt => {
        const gx  = Math.round(pt.x / SNAP) * SNAP;
        const gy  = Math.round(pt.y / SNAP) * SNAP;
        const key = gx + ',' + gy;
        if (seen.has(key)) return;
        seen.add(key);

        const r = document.createElementNS(NS, 'rect');
        r.setAttribute('x',       String(gx));
        r.setAttribute('y',       String(gy));
        r.setAttribute('width',   String(SZ));
        r.setAttribute('height',  String(SZ));
        r.setAttribute('rx',      '0.5');
        r.setAttribute('ry',      '0.5');
        r.setAttribute('fill',    '#ffffff');
        r.setAttribute('opacity', '0.90');
        group.appendChild(r);
    });
}

// ── Build inner pixel grid ────────────────────────────────────────────────────
/**
 * Uses isPointInFill (SVGGeometryElement) with a hidden probe path for
 * accurate point-in-shape testing.  Falls back to a bounding-box heuristic.
 * Returns pixel array sorted top → bottom (ascending y, then ascending x).
 */
function _buildPixelGrid() {
    const svg = document.getElementById('finjan-svg');
    if (!svg) return [];

    const NS    = 'http://www.w3.org/2000/svg';
    const probe = document.createElementNS(NS, 'path');
    probe.setAttribute('d',    FJ_INNER_CLIP_D);
    probe.setAttribute('fill', 'black');
    probe.style.cssText = 'opacity:0;pointer-events:none;';
    svg.appendChild(probe);

    const useAPI = typeof probe.isPointInFill === 'function';
    const pixels = [];
    const SZ     = FJ_PX_SIZE;
    const STEP   = FJ_PX_STEP;

    for (let py = 50; py < 222; py += STEP) {
        for (let px = 44; px < 258; px += STEP) {
            let inside = false;
            if (useAPI) {
                const pt = svg.createSVGPoint();
                pt.x = px + SZ / 2;
                pt.y = py + SZ / 2;
                try { inside = probe.isPointInFill(pt); }
                catch (_) {
                    // Fallback: linear approximation of cup interior (trapezoid)
                    const halfW = 102 - (py + SZ / 2 - 48) * 0.49;
                    inside = Math.abs(px + SZ / 2 - 150) < halfW && py >= 48 && py <= 215;
                }
            } else {
                const halfW = 102 - (py + SZ / 2 - 48) * 0.49;
                inside = Math.abs(px + SZ / 2 - 150) < halfW && py >= 48 && py <= 215;
            }
            if (inside) pixels.push({ x: px, y: py, el: null });
        }
    }

    svg.removeChild(probe);
    pixels.sort((a, b) => a.y !== b.y ? a.y - b.y : a.x - b.x);
    return pixels;
}

// ── Create purple pixel SVG elements ─────────────────────────────────────────
function _initPixels() {
    const group = document.getElementById('fj-pixels');
    if (!group) return;

    _allPixels = _buildPixelGrid();

    const NS = 'http://www.w3.org/2000/svg';
    // Single unified purple — no gradient, no multi-tone, just one consistent color
    const FILL = '#7c5cfc';

    _allPixels.forEach(px => {
        const r = document.createElementNS(NS, 'rect');
        r.setAttribute('x',      String(px.x));
        r.setAttribute('y',      String(px.y));
        r.setAttribute('width',  String(FJ_PX_SIZE));
        r.setAttribute('height', String(FJ_PX_SIZE));
        r.setAttribute('rx',     '0.8');
        r.setAttribute('ry',     '0.8');
        r.setAttribute('fill',   FILL);
        group.appendChild(r);
        px.el = r;
    });
}

// ── Update pixel visibility from timer progress ───────────────────────────────
function _updatePixels(progress) {
    if (!_allPixels.length) return;
    const total   = _allPixels.length;
    const visible = Math.ceil(progress * total);
    if (visible === _lastVisible) return;
    _lastVisible = visible;

    // Keep the bottom `visible` pixels; hide the topmost ones first
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

    // Timer text
    const timeEl = document.getElementById('finjan-time');
    if (timeEl) timeEl.textContent = _fmt(state.remaining);

    // Pixel fill level
    _updatePixels(progress);

    // Progress bar
    if (_barFill) _barFill.style.width = (progress * 100) + '%';

    // Body state classes → CSS animations
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

// ── Fullscreen toggle ─────────────────────────────────────────────────────────
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
    // Build pixel border (white outlines) then inner pixel grid
    _initBorderPixels();
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
