'use strict';
/**
 * timer.js — Dashboard UI layer for the timer section.
 *
 * This file owns NO interval and NO timer state. Everything lives
 * in TimerCore (timer-core.js, loaded before this file).
 *
 * Responsibilities:
 *   - Subscribe to TimerCore events and update dashboard DOM
 *     (countdown until zero, then overtime overlay after zero).
 *   - Manage the in-page session log (results table).
 *   - Expose global shims that HTML onclick attributes and
 *     subjects.js / auth.js call.
 *   - POST completed study sessions to /api/tracker/sessions with
 *     planned / actual / overtime fields on manual stop.
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
    secs = Math.max(0, secs | 0);
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
        // Countdown MM:SS until zero, then OVERTIME +MM:SS
        if (state.isOvertime) {
            display.textContent = '+' + _fmt(state.overtimeSeconds);
        } else {
            display.textContent = _fmt(state.remainingSeconds);
        }
        let cls = 'timer-display';
        if (visState) cls += ' ' + visState;
        if (state.isOvertime) cls += ' overtime';
        display.className = cls;
    }

    // SVG progress ring — fills normally to 100%, then freezes (clamped).
    const ring = document.getElementById('timer-ring-progress');
    if (ring) {
        const circ     = 2 * Math.PI * 88;
        let progress   = state.plannedSeconds > 0
            ? state.elapsedSeconds / state.plannedSeconds
            : 0;
        // In the existing UI the ring DEPLETES (remaining / planned).
        // Keep that direction but clamp so overtime leaves it at zero.
        let remainingFrac = Math.max(0, 1 - progress);
        ring.style.strokeDasharray  = circ;
        ring.style.strokeDashoffset = circ * (1 - remainingFrac);
    }

    // Wrapper classes for pulse / overtime glow.
    const wrap = document.getElementById('timer-ring-wrap');
    if (wrap) {
        wrap.classList.toggle('timer-running', visState === 'running');
        wrap.classList.toggle('timer-overtime', !!state.isOvertime);
    }

    // Start/Pause toggle button
    const startBtn = document.getElementById('btn-start-pause');
    if (startBtn) {
        const running = visState === 'running';
        startBtn.textContent = running ? 'Pause' : 'Start';
        startBtn.classList.toggle('is-pausing', running);
    }

    // Stop button — visible whenever there is an active session
    // (running, paused with progress, or in overtime).
    const stopBtn = document.getElementById('btn-stop');
    if (stopBtn) {
        const hasActive = state.isRunning || state.isPaused || state.elapsedSeconds > 0;
        stopBtn.classList.toggle('hidden', !hasActive);
    }

    _updateSessionDots(state);
}

function _updateSessionDots(state) {
    const completed = (state.sessionCount - 1) % 4;
    for (let i = 1; i <= 4; i++) {
        const dot = document.getElementById('dot-' + i);
        if (dot) dot.classList.toggle('filled', i <= completed);
    }
}

function _updateTabs(state) {
    ['pomodoro', 'shortbreak', 'longbreak'].forEach(p => {
        const tab = document.getElementById('tab-' + p);
        if (!tab) return;
        const active = p === state.phase || (state.phase === 'countdown' && p === 'pomodoro');
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
    });
}

// Off-screen live region announces phase + start/pause/stop — but NOT every tick.
function _announceTimer(msg) {
    const el = document.getElementById('timer-announcer');
    if (!el) return;
    el.textContent = msg;
}

function _updatePomodoroInfo(state) {
    if (!pomodoroInfo) return;

    // Overtime banner wins over default phase info while in overtime.
    if (state.isOvertime && (state.phase === 'pomodoro' || state.phase === 'countdown')) {
        pomodoroInfo.textContent =
            `Goal reached \u2014 still studying. +${_fmt(state.overtimeSeconds)} overtime.`;
        return;
    }

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
                `Countdown \u2014 ${Math.round(state.plannedSeconds / 60)} min`;
            break;
        default: // pomodoro
            pomodoroInfo.innerHTML =
                `Session <span id="session-count">${state.sessionCount}</span> of 4 \u2014 `
                + `work ${state.pomodoroMins} min, short break ${state.shortBreakMins} min.`;
    }
}

// ── TimerCore event subscriber ────────────────────────────────
function _onCoreEvent(event, state) {
    let visState;
    if      (event === 'pause') visState = 'paused';
    else if (state.isRunning)   visState = 'running';
    else if (state.isPaused)    visState = 'paused';
    else                        visState = '';

    if (event !== 'sessionEnd' && event !== 'skip') {
        _refreshDisplay(state, visState);
        _updateTabs(state);
        _updatePomodoroInfo(state);
    }

    // Screen-reader announcements. Tick events are intentionally omitted —
    // they would spam a reader every 250 ms.
    if (event === 'phase') {
        if      (state.phase === 'pomodoro')   _announceTimer(`Pomodoro started — ${state.pomodoroMins} minutes`);
        else if (state.phase === 'shortbreak') _announceTimer(`Short break started — ${state.shortBreakMins} minutes`);
        else if (state.phase === 'longbreak')  _announceTimer(`Long break started — ${state.longBreakMins} minutes`);
        else if (state.phase === 'countdown')  _announceTimer(`Countdown — ${Math.round(state.plannedSeconds / 60)} minutes`);
    } else if (event === 'start' && !state.isPaused) {
        _announceTimer('Timer started');
    } else if (event === 'pause') {
        _announceTimer('Timer paused');
    } else if (event === 'milestone') {
        _announceTimer(state.phase === 'shortbreak' || state.phase === 'longbreak'
            ? 'Break complete'
            : 'Pomodoro complete');
    }

    // --- Session logging on manual stop / skip ---
    if (event === 'sessionEnd') {
        // state is the frozen pre-stop snapshot. Only study phases credit
        // the daily goal; breaks are intentionally ignored here.
        if (state.phase === 'pomodoro' || state.phase === 'countdown') {
            const actualMins  = Math.max(0, Math.round(state.actualDurationSeconds / 60));
            const plannedMins = Math.max(0, Math.round(state.plannedSeconds       / 60));
            if (actualMins > 0) {
                logSession(
                    _activeSubject(),
                    _phaseLabel(state.phase),
                    actualMins,
                    plannedMins,
                    true,
                );
            }
        }
    } else if (event === 'skip') {
        if (state.phase === 'pomodoro' || state.phase === 'countdown') {
            const actualMins  = Math.max(0, Math.round(state.actualDurationSeconds / 60));
            const plannedMins = Math.max(0, Math.round(state.plannedSeconds       / 60));
            if (actualMins > 0) {
                logSession(
                    _activeSubject(),
                    _phaseLabel(state.phase),
                    actualMins,
                    plannedMins,
                    false,
                );
            }
        }
    }
}

TimerCore.subscribe(_onCoreEvent);

// ── Session log ───────────────────────────────────────────────
function logSession(subject, mode, mins, plannedMins, completed = true) {
    const now  = new Date();
    const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const date = now.toISOString().split('T')[0];
    const overtime = Math.max(0, mins - (plannedMins | 0));
    sessions.push({
        subject,
        mode,
        duration:     mins + ' min',
        durationMins: mins,
        plannedMins:  plannedMins | 0,
        overtimeMins: overtime,
        time,
        date,
        completed,
    });
    renderResults();

    if (window.currentUser) {
        const docEl = document.getElementById('doc-subject-name');
        const name  = (docEl && docEl.value.trim()) ? docEl.value.trim() : subject;
        fetch('/api/tracker/sessions', {
            method:    'POST',
            headers:   { 'Content-Type': 'application/json' },
            keepalive: true,
            body: JSON.stringify({
                materialName:    name,
                durationMinutes: mins,
                plannedMinutes:  plannedMins | 0,
                overtimeMinutes: overtime,
                timerMode:       mode,
                completed,
            }),
        })
        .then(() => {
            if (typeof onSessionCompleted === 'function') onSessionCompleted(subject, mins, date);
        })
        .catch(() => {
            if (typeof onSessionCompleted === 'function') onSessionCompleted(subject, mins, date);
        });
    } else {
        if (typeof onSessionCompleted === 'function') onSessionCompleted(subject, mins, date);
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
    resultsBody.innerHTML = sessions.map((s, i) => {
        let durationCell = htmlEsc(s.duration);
        if (s.overtimeMins > 0 && s.plannedMins > 0) {
            durationCell = `${s.plannedMins}m planned · ${s.durationMins}m actual <span class="ot-badge">+${s.overtimeMins}m</span>`;
        }
        return `
        <tr>
            <td>${i + 1}</td>
            <td>${htmlEsc(s.subject)}</td>
            <td>${htmlEsc(s.mode)}</td>
            <td>${durationCell}</td>
            <td>${htmlEsc(s.time)}</td>
            <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                ${s.completed ? '&#10003; Completed' : '&#9679; Skipped'}
            </td>
        </tr>`;
    }).join('');
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
    // Guard behind a native confirm — the button is destructive and the
    // in-page session log can't be recovered once cleared.
    if (sessions.length > 0 && !window.confirm("Delete all session history? This can't be undone.")) {
        return;
    }
    sessions = [];
    renderResults();
    if (typeof onSessionsCleared === 'function') onSessionsCleared();
}

// ── Global shims — called from HTML onclick and other scripts ──

function startTimer()  { TimerCore.start();  }
function pauseTimer()  { TimerCore.pause();  }
function toggleTimer() { TimerCore.toggle(); }
function stopTimer()   { TimerCore.stop();   }
function skipTimer()   { TimerCore.skip();   }
function setTimerPhase(phase) { TimerCore.setPhase(phase); }

function applyTimerDuration(mins) {
    console.log('[StudySyncer] applyTimerDuration:', mins, 'min');
    TimerCore.applyCountdownDuration(mins);
    const inp = document.getElementById('duration');
    if (inp) inp.value = mins;
}

function resetTimerForNewMaterial() {
    TimerCore.setPhase('pomodoro');
}

function restoreTimerState(state) {
    TimerCore.restoreFromServer(state);
}

function saveTimerState() { /* intentionally empty */ }

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
    const _initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _refreshDisplay(state, _initVis);
    _updateTabs(state);
    _updatePomodoroInfo(state);

    const inp = document.getElementById('duration');
    if (inp) inp.value = Math.round(state.plannedSeconds / 60);

    const pd = document.getElementById('dur-pomodoro');
    const sd = document.getElementById('dur-shortbreak');
    const ld = document.getElementById('dur-longbreak');
    if (pd) pd.value = state.pomodoroMins;
    if (sd) sd.value = state.shortBreakMins;
    if (ld) ld.value = state.longBreakMins;
})();
