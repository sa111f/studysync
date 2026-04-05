'use strict';
/**
 * timer.js — Dashboard UI layer for the timer section.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore events and update dashboard DOM
 *   - Manage the in-page session log (results table)
 *   - Expose global shims that HTML onclick attributes and
 *     subjects.js / auth.js call (same API as the old timer.js)
 *   - POST completed sessions to /api/tracker/sessions
 *   - POST timer state to /api/timer/save on significant events
 */

// ── DOM refs ──────────────────────────────────────────────────
const display      = document.getElementById('timer-display');
const pomodoroInfo = document.getElementById('pomodoro-info');
const resultsEmpty = document.getElementById('results-empty');
const resultsTable = document.getElementById('results-table');
const resultsBody  = document.getElementById('results-body');

// ── Session log (dashboard-only) ──────────────────────────────
let sessions = [];

// ── Helpers ───────────────────────────────────────────────────
function _fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

function _phaseLabel(phase) {
    if (phase === 'shortbreak') return 'Short Break';
    if (phase === 'longbreak')  return 'Long Break';
    if (phase === 'countdown')  return 'Countdown';
    return 'Pomodoro (work)';
}

function _activeSubject() {
    return (typeof getActiveSubjectName === 'function')
        ? getActiveSubjectName()
        : 'General';
}

// ── Refresh dashboard timer display ───────────────────────────
function _refreshDisplay(state, visState) {
    if (display) {
        display.textContent = _fmt(state.remaining);
        display.className   = 'timer-display' + (visState ? ' ' + visState : '');
    }

    // SVG progress ring
    const ring = document.getElementById('timer-ring-progress');
    if (ring) {
        const circ     = 2 * Math.PI * 88;
        const progress = state.totalSeconds > 0 ? state.remaining / state.totalSeconds : 0;
        ring.style.strokeDasharray  = circ;
        ring.style.strokeDashoffset = circ * (1 - progress);
    }

    // Pulse class on ring wrapper
    const wrap = document.getElementById('timer-ring-wrap');
    if (wrap) wrap.classList.toggle('timer-running', visState === 'running');
}

// ── Update phase tabs ─────────────────────────────────────────
function _updateTabs(state) {
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('tab-' + p);
        if (tab) tab.classList.toggle('active', p === state.phase);
    });
}

// ── Update pomodoro info line ─────────────────────────────────
function _updatePomodoroInfo(state) {
    if (!pomodoroInfo) return;
    switch (state.phase) {
        case 'shortbreak':
            pomodoroInfo.textContent =
                `Short break \u2014 ${state.shortBreakMins} min. Session ${state.sessionCount - 1} done.`;
            break;
        case 'longbreak':
            pomodoroInfo.textContent =
                `Long break \u2014 ${state.longBreakMins} min. Great work!`;
            break;
        case 'countdown':
            pomodoroInfo.textContent =
                `Countdown \u2014 ${Math.round(state.totalSeconds / 60)} min`;
            break;
        default: // pomodoro
            pomodoroInfo.innerHTML =
                `Session <span id="session-count">${state.sessionCount}</span> of 4 \u2014 `
                + `work ${state.pomodoroMins} min, short break ${state.shortBreakMins} min.`;
    }
}

// ── Backend timer-state save ──────────────────────────────────
function _saveToBackend(state) {
    if (!window.currentUser) return;
    fetch('/api/timer/save', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            mode:         state.phase === 'countdown' ? 'countdown' : 'pomodoro',
            totalSeconds: state.totalSeconds,
            sessionCount: state.sessionCount,
        }),
    }).catch(() => {});
}

// ── TimerCore event subscriber ────────────────────────────────
function _onCoreEvent(event, state) {
    // --- Compute visual state ---
    let visState;
    if      (event === 'done')  visState = 'done';
    else if (event === 'pause') visState = 'paused';
    else                        visState = state.isRunning ? 'running' : '';

    // --- Update dashboard DOM for all events except logging-only ---
    if (event !== 'sessionEnd' && event !== 'skip') {
        _refreshDisplay(state, visState);
        _updateTabs(state);
        _updatePomodoroInfo(state);
    }

    // --- Session logging ---
    if (event === 'sessionEnd') {
        // state is the pre-completion snapshot (phase that just finished)
        const mins = Math.round(state.totalSeconds / 60);
        logSession(_activeSubject(), _phaseLabel(state.phase), mins, true);
        _saveToBackend(state);
    } else if (event === 'skip') {
        // state is the pre-skip snapshot
        if (state.phase === 'pomodoro' || state.phase === 'countdown') {
            const elapsed = state.totalSeconds - state.remaining;
            const mins    = Math.round(elapsed / 60);
            if (mins > 0) logSession(_activeSubject(), _phaseLabel(state.phase), mins, false);
        }
    }

    // --- Backend save on significant events (not every tick) ---
    if (event === 'pause' || event === 'phase' || event === 'done') {
        _saveToBackend(state);
    }
}

TimerCore.subscribe(_onCoreEvent);

// ── Session log ───────────────────────────────────────────────
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
    if (!resultsEmpty || !resultsTable || !resultsBody) return;
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

// ── Global shims — called from HTML onclick and other scripts ──

/** Called directly from HTML: <button onclick="startTimer()"> */
function startTimer()  { TimerCore.start();  }

/** Called directly from HTML: <button onclick="pauseTimer()"> */
function pauseTimer()  { TimerCore.pause();  }

/** Called directly from HTML: <button onclick="skipTimer()"> */
function skipTimer()   { TimerCore.skip();   }

/** Called directly from HTML: <button onclick="setTimerPhase(...)"> */
function setTimerPhase(phase) { TimerCore.setPhase(phase); }

/**
 * Called by subjects.js when a study-time recommendation is applied.
 * Switches to countdown phase with the given duration.
 */
function applyTimerDuration(mins) {
    console.log('[StudySyncer] applyTimerDuration:', mins, 'min');
    TimerCore.applyCountdownDuration(mins);
    // Keep hidden input in sync (backward compat)
    const inp = document.getElementById('duration');
    if (inp) inp.value = mins;
}

/** Called by subjects.js when starting a new study material */
function resetTimerForNewMaterial() {
    TimerCore.setPhase('pomodoro');
}

/**
 * Called by auth.js after login to restore backend-persisted state.
 * Delegates to TimerCore which merges carefully with localStorage state.
 */
function restoreTimerState(state) {
    TimerCore.restoreFromServer(state);
}

/** Called occasionally by auth.js / legacy code */
function saveTimerState() {
    _saveToBackend(TimerCore.getState());
}

// ── Settings panel ────────────────────────────────────────────
function toggleTimerSettings() {
    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (!panel) return;
    const nowHidden = panel.classList.toggle('hidden');
    if (toggle) toggle.classList.toggle('open', !nowHidden);
}

function applyTimerSettings() {
    const pd = parseInt(document.getElementById('dur-pomodoro')?.value,   10);
    const sd = parseInt(document.getElementById('dur-shortbreak')?.value, 10);
    const ld = parseInt(document.getElementById('dur-longbreak')?.value,  10);
    TimerCore.setDurations(pd, sd, ld);

    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (panel)  panel.classList.add('hidden');
    if (toggle) toggle.classList.remove('open');
}

// ── Legacy shims ──────────────────────────────────────────────
function setMode(mode) {
    if (mode === 'pomodoro') TimerCore.setPhase('pomodoro');
}

function applyDuration() {
    const inp  = document.getElementById('duration');
    const mins = inp ? Math.max(1, parseInt(inp.value, 10) || 25) : 25;
    if (inp) inp.value = mins;
    TimerCore.applyCountdownDuration(mins);
}

// ── Space-bar shortcut ────────────────────────────────────────
document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

// ── Initial render ────────────────────────────────────────────
(function () {
    const state = TimerCore.getState();
    _refreshDisplay(state, state.isRunning ? 'running' : '');
    _updateTabs(state);
    _updatePomodoroInfo(state);

    // Keep hidden duration input in sync
    const inp = document.getElementById('duration');
    if (inp) inp.value = Math.round(state.totalSeconds / 60);

    // Sync settings inputs to restored durations
    const pd = document.getElementById('dur-pomodoro');
    const sd = document.getElementById('dur-shortbreak');
    const ld = document.getElementById('dur-longbreak');
    if (pd) pd.value = state.pomodoroMins;
    if (sd) sd.value = state.shortBreakMins;
    if (ld) ld.value = state.longBreakMins;
})();
