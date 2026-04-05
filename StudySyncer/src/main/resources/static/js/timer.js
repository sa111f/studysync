'use strict';

// ── Editable durations (user-configurable) ─────────────
let pomodoroMins   = 25;
let shortBreakMins = 5;
let longBreakMins  = 15;

// ── Timer state ────────────────────────────────────────
let totalSeconds     = pomodoroMins * 60;
let remainingSeconds = totalSeconds;
let timerInterval    = null;
let isRunning        = false;

// currentPhase: which tab/phase is active
// 'pomodoro' | 'shortbreak' | 'longbreak' | 'countdown'
let currentPhase = 'pomodoro';

// currentMode: kept for session logging and backend save/restore compat
// 'pomodoro' | 'countdown'
let currentMode = 'pomodoro';

let sessionCount = 1;
let sessions     = [];

// ── DOM refs ───────────────────────────────────────────
const display      = document.getElementById('timer-display');
const pomodoroInfo = document.getElementById('pomodoro-info');
const resultsEmpty = document.getElementById('results-empty');
const resultsTable = document.getElementById('results-table');
const resultsBody  = document.getElementById('results-body');

// ── Formatting ─────────────────────────────────────────
function fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

// ── Display + ring update ──────────────────────────────
function setDisplay(secs, state) {
    display.textContent = fmt(secs);
    display.className   = 'timer-display' + (state ? ' ' + state : '');

    // Progress ring
    const ring = document.getElementById('timer-ring-progress');
    if (ring) {
        const circ     = 2 * Math.PI * 88;  // r=88 matches SVG
        const progress = totalSeconds > 0 ? remainingSeconds / totalSeconds : 0;
        ring.style.strokeDasharray  = circ;
        ring.style.strokeDashoffset = circ * (1 - progress);
    }

    // Pulse animation class on wrapper
    const wrap = document.getElementById('timer-ring-wrap');
    if (wrap) wrap.classList.toggle('timer-running', state === 'running');
}

// ── Pomodoro info text ─────────────────────────────────
function updatePomodoroInfo() {
    if (!pomodoroInfo) return;
    switch (currentPhase) {
        case 'shortbreak':
            pomodoroInfo.textContent =
                `Short break \u2014 ${shortBreakMins} min. Session ${sessionCount - 1} done.`;
            break;
        case 'longbreak':
            pomodoroInfo.textContent =
                `Long break \u2014 ${longBreakMins} min. Great work!`;
            break;
        case 'countdown':
            pomodoroInfo.textContent =
                `Countdown from Study Estimator \u2014 ${Math.round(totalSeconds / 60)} min`;
            break;
        default: // pomodoro work
            pomodoroInfo.innerHTML =
                `Session <span id="session-count">${sessionCount}</span> of 4 \u2014 work ${pomodoroMins} min, short break ${shortBreakMins} min.`;
    }
}

// ── Phase switching ────────────────────────────────────
/**
 * Switch the active timer phase. Pauses any running timer,
 * updates tab visuals, sets duration, resets remaining time.
 *
 * @param {'pomodoro'|'shortbreak'|'longbreak'|'countdown'} phase
 */
function setTimerPhase(phase) {
    if (isRunning) pauseTimer();
    currentPhase = phase;

    // Tab visual state (only the 3 named tabs exist)
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('tab-' + p);
        if (tab) tab.classList.toggle('active', p === phase);
    });

    // Map phase → duration
    switch (phase) {
        case 'shortbreak':
            currentMode  = 'pomodoro';   // part of the pomodoro cycle
            totalSeconds = shortBreakMins * 60;
            break;
        case 'longbreak':
            currentMode  = 'pomodoro';
            totalSeconds = longBreakMins * 60;
            break;
        case 'countdown':
            currentMode  = 'countdown';
            // totalSeconds already set by applyTimerDuration before calling here
            break;
        default: // 'pomodoro'
            currentMode  = 'pomodoro';
            totalSeconds = pomodoroMins * 60;
    }

    remainingSeconds = totalSeconds;
    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// Backward-compat shim — subjects.js calls applyTimerDuration, not setMode.
// This is kept so any direct call to setMode('pomodoro') still works.
function setMode(mode) {
    if (mode === 'pomodoro') setTimerPhase('pomodoro');
}

// ── Duration settings panel ────────────────────────────
function toggleTimerSettings() {
    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (!panel) return;
    const nowHidden = panel.classList.toggle('hidden');
    if (toggle) toggle.classList.toggle('open', !nowHidden);
}

/**
 * Read the three duration inputs, update the live variables,
 * then refresh the current phase display.
 */
function applyTimerSettings() {
    const pd = parseInt(document.getElementById('dur-pomodoro').value,   10);
    const sd = parseInt(document.getElementById('dur-shortbreak').value, 10);
    const ld = parseInt(document.getElementById('dur-longbreak').value,  10);

    if (pd >= 1 && pd <= 999) pomodoroMins   = pd;
    if (sd >= 1 && sd <= 999) shortBreakMins = sd;
    if (ld >= 1 && ld <= 999) longBreakMins  = ld;

    // Refresh whichever phase is currently active
    const refreshPhase = (currentPhase === 'countdown') ? 'pomodoro' : currentPhase;
    setTimerPhase(refreshPhase);

    // Collapse the panel
    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (panel)  panel.classList.add('hidden');
    if (toggle) toggle.classList.remove('open');
}

// ── Apply duration from Study Estimator (subjects.js API) ──
/**
 * Called by subjects.js when a study-time recommendation is selected.
 * Switches to a special "countdown" phase with the given duration.
 *
 * @param {number} mins - Duration in minutes.
 */
function applyTimerDuration(mins) {
    console.log('[StudySyncer] applyTimerDuration:', mins, 'min');
    if (isRunning) pauseTimer();

    totalSeconds     = mins * 60;
    remainingSeconds = totalSeconds;

    // Keep the hidden input in sync (used by restoreTimerState)
    const inp = document.getElementById('duration');
    if (inp) inp.value = mins;

    // Switch to countdown phase (no tab active)
    currentPhase = 'countdown';
    currentMode  = 'countdown';

    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('tab-' + p);
        if (tab) tab.classList.remove('active');
    });

    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// Legacy shim — preserved in case anything still calls applyDuration()
function applyDuration() {
    const inp  = document.getElementById('duration');
    const mins = inp ? Math.max(1, parseInt(inp.value, 10) || pomodoroMins) : pomodoroMins;
    if (inp) inp.value = mins;
    if (isRunning) pauseTimer();
    totalSeconds     = mins * 60;
    remainingSeconds = totalSeconds;
    setDisplay(remainingSeconds, '');
}

// ── Core timer ─────────────────────────────────────────
function startTimer() {
    if (isRunning) return;
    isRunning = true;
    setDisplay(remainingSeconds, 'running');

    timerInterval = setInterval(() => {
        if (remainingSeconds <= 0) {
            clearInterval(timerInterval);
            isRunning = false;
            setDisplay(0, 'done');
            handleSessionEnd();
            return;
        }
        remainingSeconds--;
        setDisplay(remainingSeconds, 'running');
    }, 1000);
}

function pauseTimer() {
    if (!isRunning) return;
    isRunning = false;
    clearInterval(timerInterval);
    setDisplay(remainingSeconds, 'paused');
}

/**
 * Skip the current phase.
 *
 * - Pomodoro work  → log partial minutes (if ≥1), auto-advance to break.
 * - Short/Long break → skip rest, return to pomodoro work.
 * - Countdown      → log partial minutes, reset to full duration.
 */
function skipTimer() {
    const elapsed = totalSeconds - remainingSeconds;
    clearInterval(timerInterval);
    isRunning = false;

    if (currentPhase === 'pomodoro') {
        const mins = Math.round(elapsed / 60);
        if (mins > 0) {
            const subj = _activeSubject();
            logSession(subj, 'Pomodoro (work)', mins, false);
        }
        // Decide next break type: long every 4th session
        const nextBreak = (sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
        sessionCount++;
        setTimerPhase(nextBreak);
        setTimeout(startTimer, 800);  // auto-start break

    } else if (currentPhase === 'shortbreak' || currentPhase === 'longbreak') {
        // Skip break → back to work
        setTimerPhase('pomodoro');

    } else {
        // Countdown — log partial, reset
        const mins = Math.round(elapsed / 60);
        if (mins > 0) {
            const subj = _activeSubject();
            logSession(subj, 'Countdown', mins, false);
        }
        remainingSeconds = totalSeconds;
        setDisplay(remainingSeconds, '');
    }

    saveTimerState();
}

// ── Session end (natural completion) ──────────────────
function handleSessionEnd() {
    const subj = _activeSubject();

    if (currentPhase === 'pomodoro') {
        logSession(subj, 'Pomodoro (work)', pomodoroMins);
        const nextBreak = (sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
        sessionCount++;
        setTimerPhase(nextBreak);
        setTimeout(startTimer, 1000);  // auto-start break

    } else if (currentPhase === 'shortbreak' || currentPhase === 'longbreak') {
        // Break done → reset count if needed, go back to work
        if (sessionCount > 4) sessionCount = 1;
        setTimerPhase('pomodoro');

    } else {
        // Countdown complete
        const mins = Math.round(totalSeconds / 60);
        logSession(subj, 'Countdown', mins);
    }

    saveTimerState();
}

// ── Persistence (STUD-34/35) ───────────────────────────
async function saveTimerState() {
    if (!window.currentUser) return;
    try {
        await fetch('/api/timer/save', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                mode:         currentMode,
                totalSeconds: totalSeconds,
                sessionCount: sessionCount,
            }),
        });
    } catch (_) { /* silent */ }
}

function restoreTimerState(state) {
    currentMode  = state.mode         || 'pomodoro';
    totalSeconds = state.totalSeconds || (pomodoroMins * 60);
    sessionCount = state.sessionCount || 1;
    remainingSeconds = totalSeconds;
    isRunning = false;

    // Map saved mode → phase
    currentPhase = (currentMode === 'countdown') ? 'countdown' : 'pomodoro';

    // Sync tab UI
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('tab-' + p);
        if (tab) tab.classList.toggle('active', p === currentPhase);
    });

    // Keep hidden input in sync
    const inp = document.getElementById('duration');
    if (inp) inp.value = Math.round(totalSeconds / 60);

    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// ── Results log ────────────────────────────────────────
function logSession(subject, mode, mins, completed = true) {
    const now  = new Date();
    const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const date = now.toISOString().split('T')[0];
    sessions.push({ subject, mode, duration: mins + ' min', durationMins: mins, time, date, completed });
    renderResults();
    if (typeof onSessionCompleted === 'function') onSessionCompleted(subject, mins, date);

    if (window.currentUser) {
        const docEl = document.getElementById('doc-subject-name');
        const name  = (docEl && docEl.value.trim()) ? docEl.value.trim() : subject;
        fetch('/api/tracker/sessions', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                materialName:    name,
                durationMinutes: mins,
                timerMode:       mode,
                completed,
            }),
        }).catch(() => {});
    }
}

function renderResults() {
    if (sessions.length === 0) {
        resultsEmpty.classList.remove('hidden');
        resultsTable.classList.add('hidden');
        return;
    }
    resultsEmpty.classList.add('hidden');
    resultsTable.classList.remove('hidden');

    resultsBody.innerHTML = sessions.map((s, i) => `
        <tr>
            <td>${i + 1}</td>
            <td>${htmlEsc(s.subject)}</td>
            <td>${htmlEsc(s.mode)}</td>
            <td>${htmlEsc(s.duration)}</td>
            <td>${htmlEsc(s.time)}</td>
            <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                ${s.completed ? '&#10003; Completed' : '&#9679; Skipped'}
            </td>
        </tr>
    `).join('');
}

function htmlEsc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g,  '&amp;')
        .replace(/</g,  '&lt;')
        .replace(/>/g,  '&gt;')
        .replace(/"/g,  '&quot;');
}

function clearResults() {
    sessions = [];
    renderResults();
    if (typeof onSessionsCleared === 'function') onSessionsCleared();
}

// ── Reset for new material (called by subjects.js) ─────
function resetTimerForNewMaterial() {
    isRunning = false;
    clearInterval(timerInterval);
    timerInterval = null;
    sessionCount  = 1;
    setTimerPhase('pomodoro');
}

// ── Internal helpers ───────────────────────────────────
function _activeSubject() {
    return (typeof getActiveSubjectName === 'function')
        ? getActiveSubjectName()
        : 'General';
}

// ── Space-bar shortcut ─────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        if (isRunning) pauseTimer(); else startTimer();
    }
});

// ── Init ───────────────────────────────────────────────
setDisplay(remainingSeconds, '');
updatePomodoroInfo();
