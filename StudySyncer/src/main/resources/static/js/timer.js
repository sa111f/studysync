'use strict';
/**
 * timer.js — Dashboard UI layer for the timer card.
 *
 * Owns no timer state. Subscribes to TimerCore and renders the
 * countdown/overtime display, progress ring, and control buttons.
 * POSTs completed sessions to /api/tracker/sessions on Stop.
 */

const display        = document.getElementById('timer-display');
const overtimeLabel  = document.getElementById('timer-overtime-label');

function _fmt(secs) {
    secs = Math.max(0, secs | 0);
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

function _refreshDisplay(state, visState) {
    if (display) {
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

    if (overtimeLabel) {
        overtimeLabel.classList.toggle('hidden', !state.isOvertime);
    }

    const ring = document.getElementById('timer-ring-progress');
    if (ring) {
        const circ = 2 * Math.PI * 88;
        let progress = state.targetSeconds > 0
            ? state.elapsedSeconds / state.targetSeconds
            : 0;
        let remainingFrac = Math.max(0, 1 - progress);
        ring.style.strokeDasharray  = circ;
        ring.style.strokeDashoffset = circ * (1 - remainingFrac);
    }

    const wrap = document.getElementById('timer-ring-wrap');
    if (wrap) {
        wrap.classList.toggle('timer-running', visState === 'running');
        wrap.classList.toggle('timer-overtime', !!state.isOvertime);
    }

    const startBtn = document.getElementById('btn-start-pause');
    if (startBtn) {
        const running = visState === 'running';
        startBtn.textContent = running ? 'Pause' : 'Start';
        startBtn.classList.toggle('is-pausing', running);
    }

    const stopBtn = document.getElementById('btn-stop');
    if (stopBtn) {
        const hasActive = state.isRunning || state.isPaused || state.elapsedSeconds > 0;
        stopBtn.classList.toggle('hidden', !hasActive);
    }
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

    if (event !== 'sessionEnd') _refreshDisplay(state, visState);

    if      (event === 'start' && !state.isPaused) _announceTimer('Timer started');
    else if (event === 'pause')                    _announceTimer('Timer paused');
    else if (event === 'milestone')                _announceTimer('Target reached — overtime');

    if (event === 'sessionEnd') {
        const actualMins = Math.max(0, Math.round(state.actualDurationSeconds / 60));
        const plannedMin = Math.max(0, Math.round(state.plannedSeconds       / 60));
        if (actualMins > 0 && window.currentUser) {
            const overtime = Math.max(0, actualMins - plannedMin);
            fetch('/api/tracker/sessions', {
                method:    'POST',
                headers:   { 'Content-Type': 'application/json' },
                keepalive: true,
                body: JSON.stringify({
                    materialName:    'Study Session',
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

function startTimer()  { TimerCore.start();  }
function pauseTimer()  { TimerCore.pause();  }
function toggleTimer() { TimerCore.toggle(); }
function stopTimer()   { TimerCore.stop();   }
function skipTimer()   { TimerCore.skip();   }

function applyTimerDuration(mins) {
    TimerCore.setTargetMinutes(mins);
}

function restoreTimerState(state) {
    TimerCore.restoreFromServer(state);
}

function saveTimerState() { /* intentionally empty */ }

function toggleTimerSettings() {
    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (!panel) return;
    const nowHidden = panel.classList.toggle('hidden');
    if (toggle) toggle.classList.toggle('open', !nowHidden);
}

function applyTimerSettings() {
    const t = parseInt(document.getElementById('dur-target')?.value, 10);
    if (!isNaN(t)) TimerCore.setTargetMinutes(t);

    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (panel)  panel.classList.add('hidden');
    if (toggle) toggle.classList.remove('open');
}

document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

(function () {
    const state = TimerCore.getState();
    const _initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _refreshDisplay(state, _initVis);

    const tgt = document.getElementById('dur-target');
    if (tgt) tgt.value = state.targetMins;
})();
