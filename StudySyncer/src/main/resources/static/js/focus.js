'use strict';

// ─────────────────────────────────────────────────────────────
//  focus.js — self-contained timer for the /focus page.
//
//  Replicates the phase-based state machine from timer.js but
//  does NOT import it — timer.js grabs dashboard DOM nodes that
//  don't exist on this page and would throw null-ref errors.
//
//  API surface preserved (same phase names, same backend routes):
//    /api/timer/save  — POST  — persist state
//    /api/timer/load  — GET   — restore state
//    /api/auth/me     — GET   — identify current user (session saving)
//    /api/tracker/sessions — POST — log completed sessions
// ─────────────────────────────────────────────────────────────

// ── Durations (minutes) ───────────────────────────────────────
let pomodoroMins   = 25;
let shortBreakMins = 5;
let longBreakMins  = 15;

// ── Timer state ───────────────────────────────────────────────
let totalSeconds     = pomodoroMins * 60;
let remainingSeconds = totalSeconds;
let timerInterval    = null;
let isRunning        = false;

// currentPhase: 'pomodoro' | 'shortbreak' | 'longbreak'
let currentPhase = 'pomodoro';
let sessionCount = 1;

// window.currentUser — set by initFocusAuth(); gates backend calls
window.currentUser = null;

// ── DOM refs ──────────────────────────────────────────────────
const orbFill    = document.getElementById('orb-fill');
const orbTime    = document.getElementById('orb-time');
const barFill    = document.getElementById('focus-bar-fill');
const sessionLbl = document.getElementById('focus-session-label');
const toggleBtn  = document.getElementById('focus-toggle-btn');
const orbScene   = document.getElementById('orb-scene');

// ── Formatting ────────────────────────────────────────────────
function fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Update all visuals from current state ─────────────────────
//
//  state: '' | 'running' | 'paused' | 'done'
//
//  Three things are driven by progress (0 → 1):
//    1. Orb fill height      — CSS height on .orb-fill
//    2. Glow intensity       — CSS custom property --glow-opacity
//    3. Progress bar width   — CSS width on .focus-bar-fill
//
//  CSS transitions on height/width give the smooth 1-second glide.
//  --glow-opacity fades from 1.0 (full) → 0.25 (empty) so the
//  ambient halo behind the orb dims as the session drains.
function updateVisuals(state) {
    const progress = totalSeconds > 0 ? remainingSeconds / totalSeconds : 0;

    // 1. Timer text
    if (orbTime) orbTime.textContent = fmt(remainingSeconds);

    // 2. Orb fill — bottom-anchored height driven by remaining %
    if (orbFill) orbFill.style.height = (progress * 100) + '%';

    // 3. Glow — ranges 0.25 (empty) to 1.0 (full); never fully dark
    if (orbScene) {
        const glow = (0.25 + progress * 0.75).toFixed(3);
        orbScene.style.setProperty('--glow-opacity', glow);
    }

    // 4. Progress bar
    if (barFill) barFill.style.width = (progress * 100) + '%';

    // 5. Body state classes drive CSS animations
    document.body.classList.toggle('is-running', state === 'running');
    document.body.classList.toggle('is-paused',  state === 'paused');
    document.body.classList.toggle('is-done',    state === 'done');

    // 6. Toggle button label
    if (toggleBtn) toggleBtn.textContent = isRunning ? 'Pause' : 'Start';
}

// ── Session / phase label ─────────────────────────────────────
function updateSessionLabel() {
    if (!sessionLbl) return;
    switch (currentPhase) {
        case 'shortbreak':
            sessionLbl.textContent = `Short Break \u2014 ${shortBreakMins} min`;
            break;
        case 'longbreak':
            sessionLbl.textContent = `Long Break \u2014 ${longBreakMins} min`;
            break;
        default:
            sessionLbl.textContent = `Session ${sessionCount} of 4`;
    }
}

// ── Phase switching ───────────────────────────────────────────
function setPhase(phase, skipTransition) {
    if (isRunning) pauseTimer();
    currentPhase = phase;

    // Tab ARIA + visual state
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('ftab-' + p);
        if (!tab) return;
        const active = (p === phase);
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
    });

    // Duration for this phase
    switch (phase) {
        case 'shortbreak': totalSeconds = shortBreakMins * 60; break;
        case 'longbreak':  totalSeconds = longBreakMins  * 60; break;
        default:           totalSeconds = pomodoroMins   * 60; break;
    }
    remainingSeconds = totalSeconds;

    // When switching phases we want the fill/bar to snap instantly, not
    // animate from the previous phase's level. Temporarily disable the
    // CSS transitions, apply the new values, then restore transitions.
    if (skipTransition !== false) {
        _disableTransitions();
        updateVisuals('');
        _restoreTransitions();
    } else {
        updateVisuals('');
    }

    updateSessionLabel();
}

function _disableTransitions() {
    if (orbFill) orbFill.style.transition = 'none';
    if (barFill) barFill.style.transition = 'none';
}

function _restoreTransitions() {
    // Two rAF calls: first commits the no-transition paint,
    // second re-enables so subsequent ticks animate smoothly.
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            if (orbFill) orbFill.style.transition = '';
            if (barFill) barFill.style.transition = '';
        });
    });
}

// ── Core timer ────────────────────────────────────────────────
function startTimer() {
    if (isRunning) return;
    isRunning = true;
    updateVisuals('running');

    timerInterval = setInterval(() => {
        if (remainingSeconds <= 0) {
            clearInterval(timerInterval);
            isRunning = false;
            updateVisuals('done');
            handleSessionEnd();
            return;
        }
        remainingSeconds--;
        updateVisuals('running');
    }, 1000);
}

function pauseTimer() {
    if (!isRunning) return;
    isRunning = false;
    clearInterval(timerInterval);
    updateVisuals('paused');
}

function toggleTimer() {
    if (isRunning) pauseTimer(); else startTimer();
}

// ── Skip current phase ────────────────────────────────────────
function skipPhase() {
    clearInterval(timerInterval);
    isRunning = false;

    if (currentPhase === 'pomodoro') {
        const nextBreak = (sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
        sessionCount++;
        setPhase(nextBreak);
        setTimeout(startTimer, 700);
    } else {
        if (sessionCount > 4) sessionCount = 1;
        setPhase('pomodoro');
    }

    saveState();
}

// ── Natural phase completion ──────────────────────────────────
function handleSessionEnd() {
    const mode = currentPhase === 'pomodoro' ? 'Pomodoro (work)' :
                 currentPhase === 'shortbreak' ? 'Short Break' : 'Long Break';
    const mins = Math.round(totalSeconds / 60);

    // Log to tracker backend (silently skipped for guests)
    if (window.currentUser) {
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

    if (currentPhase === 'pomodoro') {
        const nextBreak = (sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
        sessionCount++;
        setPhase(nextBreak);
        setTimeout(startTimer, 1100);
    } else {
        if (sessionCount > 4) sessionCount = 1;
        setPhase('pomodoro');
    }

    saveState();
}

// ── Persistence ───────────────────────────────────────────────
async function saveState() {
    if (!window.currentUser) return;
    try {
        await fetch('/api/timer/save', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                mode:         'pomodoro',
                totalSeconds: totalSeconds,
                sessionCount: sessionCount,
            }),
        });
    } catch (_) { /* silent — never block the UI */ }
}

async function loadState() {
    if (!window.currentUser) return;
    try {
        const res = await fetch('/api/timer/load');
        if (!res.ok) return;
        const state = await res.json();
        if (state && state.sessionCount) {
            sessionCount = state.sessionCount;
            updateSessionLabel();
        }
    } catch (_) { /* silent */ }
}

// ── Auth check (minimal) ──────────────────────────────────────
// Only needed to gate backend session-saving.
// Does not render any auth UI on this page.
async function initFocusAuth() {
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            window.currentUser = data.username;
            await loadState();
        }
    } catch (_) { /* guest — silent */ }
}

// ── Keyboard shortcut ─────────────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        toggleTimer();
    }
});

// ── Init ──────────────────────────────────────────────────────
setPhase('pomodoro');   // render initial state immediately (no flash)
initFocusAuth();        // async — sets currentUser silently in background
