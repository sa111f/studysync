'use strict';
/**
 * TimerCore — the ONE shared timer engine for StudySync.
 *
 * SINGLE SOURCE OF TRUTH for all timer state. Both the dashboard
 * (timer.js) and Focus Mode (focus.js) subscribe to this module.
 * Neither page owns an interval; all display refresh logic lives here.
 *
 * Model
 * ─────
 *   phase              'pomodoro' | 'shortbreak' | 'longbreak' | 'countdown'
 *   plannedSeconds     configured length of the current phase
 *   elapsedBase        seconds accumulated from any PRIOR running segments
 *                      of this session (while the current segment is paused
 *                      or stopped).  Authoritative when !isRunning.
 *   runStartAtMs       epoch ms when the CURRENT running segment began.
 *                      Null when stopped/paused.
 *   isRunning / isPaused
 *
 *   Derived (pure functions of the above):
 *     elapsedSeconds       = isRunning
 *                              ? elapsedBase + (now - runStartAtMs)/1000
 *                              : elapsedBase
 *     remainingSeconds     = max(0, plannedSeconds - elapsedSeconds)
 *     isOvertime           = elapsedSeconds > plannedSeconds
 *     overtimeSeconds      = max(0, elapsedSeconds - plannedSeconds)
 *     actualDurationSeconds = elapsedSeconds
 *
 * Key behavioural rule
 *   Reaching zero does NOT stop the session. The timer continues
 *   counting elapsed, and the UI flips to overtime display. A session
 *   is only finalized when the user calls stop() or skip().
 *
 * Persistence
 *   localStorage           — cross-page navigation in the same browser
 *   BroadcastChannel       — cross-tab live sync of state transitions
 *   Backend /api/timer/*   — cross-device truth for logged-in users
 */
(function (global) {

    var LS_KEY  = 'ss_timer_v4';   // v4 switches to elapsed-based model with overtime
    var BC_NAME = 'ss_timer_bc_v4';

    var DEFAULTS = {
        pomodoroMins:     25,
        shortBreakMins:   5,
        longBreakMins:    15,
        phase:            'pomodoro',
        sessionCount:     1,
        plannedSeconds:   25 * 60,
        elapsedBase:      0,
        runStartAtMs:     null,
        isRunning:        false,
        isPaused:         false,
        milestoneFired:   false,
    };

    var _s    = Object.assign({}, DEFAULTS);
    var _ivl  = null;
    var _subs = [];
    var _bc   = null;
    var _mute = false;

    // ── Helpers ────────────────────────────────────────────────
    function _computeElapsed() {
        if (_s.isRunning && _s.runStartAtMs) {
            var delta = (Date.now() - _s.runStartAtMs) / 1000;
            return Math.max(0, _s.elapsedBase + delta);
        }
        return Math.max(0, _s.elapsedBase);
    }

    function _snap() {
        var elapsed   = _computeElapsed();
        var planned   = Math.max(0, _s.plannedSeconds | 0);
        var remaining = Math.max(0, planned - elapsed);
        var overtime  = Math.max(0, elapsed - planned);
        var isOt      = elapsed > planned;

        return {
            phase:                 _s.phase,
            pomodoroMins:          _s.pomodoroMins,
            shortBreakMins:        _s.shortBreakMins,
            longBreakMins:         _s.longBreakMins,
            sessionCount:          _s.sessionCount,
            plannedSeconds:        planned,
            totalSeconds:          planned,            // legacy alias
            elapsedSeconds:        Math.round(elapsed),
            remainingSeconds:      Math.round(remaining),
            remaining:             Math.round(remaining),   // legacy alias
            overtimeSeconds:       Math.round(overtime),
            actualDurationSeconds: Math.round(elapsed),
            isOvertime:            isOt,
            isRunning:             _s.isRunning,
            isPaused:              _s.isPaused,
            runStartAtMs:          _s.runStartAtMs,
        };
    }

    function _fire(event, overrideState) {
        var snap = overrideState || _snap();
        for (var i = 0; i < _subs.length; i++) {
            try { _subs[i](event, snap); } catch (_) {}
        }
    }

    function _broadcast(type, extra) {
        if (_mute || !_bc) return;
        try {
            _bc.postMessage(Object.assign({ type: type, state: _persistable() }, extra || {}));
        } catch (_) {}
    }

    function _persistable() {
        return {
            phase:          _s.phase,
            pomodoroMins:   _s.pomodoroMins,
            shortBreakMins: _s.shortBreakMins,
            longBreakMins:  _s.longBreakMins,
            sessionCount:   _s.sessionCount,
            plannedSeconds: _s.plannedSeconds,
            elapsedBase:    _s.elapsedBase,
            runStartAtMs:   _s.runStartAtMs,
            isRunning:      _s.isRunning,
            isPaused:       _s.isPaused,
            milestoneFired: _s.milestoneFired,
        };
    }

    function _save() {
        try { localStorage.setItem(LS_KEY, JSON.stringify(_persistable())); } catch (_) {}
    }

    function _durationFor(phase) {
        if (phase === 'shortbreak') return _s.shortBreakMins * 60;
        if (phase === 'longbreak')  return _s.longBreakMins  * 60;
        if (phase === 'countdown')  return _s.plannedSeconds || (_s.pomodoroMins * 60);
        return _s.pomodoroMins * 60;
    }

    // ── BroadcastChannel (cross-tab live sync) ─────────────────
    try {
        _bc = new BroadcastChannel(BC_NAME);
        _bc.onmessage = function (ev) {
            var d = ev.data;
            if (!d || d.type !== 'STATE' || !d.state) return;
            _mute = true;
            Object.assign(_s, d.state);
            _save();
            _fire(_s.isRunning ? 'start' : (_s.isPaused ? 'pause' : 'phase'));
            _mute = false;
        };
    } catch (_) { /* BroadcastChannel unavailable — graceful fallback */ }

    // ── Init: restore from localStorage ────────────────────────
    (function _init() {
        try {
            var raw = localStorage.getItem(LS_KEY);
            if (raw) {
                var saved = JSON.parse(raw);
                Object.assign(_s, saved);
            }
        } catch (_) {}
        _startDisplayInterval();
    })();

    // ── Display interval ───────────────────────────────────────
    function _startDisplayInterval() {
        if (_ivl) return;
        _ivl = setInterval(_tick, 250);
    }

    function _tick() {
        if (!_s.isRunning || !_s.runStartAtMs) return;

        var elapsed = _computeElapsed();

        // Fire the zero-crossing milestone exactly once per session, so the
        // UI can flash / chime. Does NOT stop the session — overtime takes over.
        if (!_s.milestoneFired && elapsed >= _s.plannedSeconds && _s.plannedSeconds > 0) {
            _s.milestoneFired = true;
            _save();
            _broadcast('STATE');
            _fire('milestone');
        }
        _fire('tick');
    }

    // ── Phase reset (internal — no backend call) ───────────────
    function _resetPhaseLocal(phase) {
        _s.phase          = phase;
        _s.plannedSeconds = _durationFor(phase);
        _s.elapsedBase    = 0;
        _s.runStartAtMs   = null;
        _s.isRunning      = false;
        _s.isPaused       = false;
        _s.milestoneFired = false;
        _save();
        _broadcast('STATE');
        _fire('phase');
    }

    function _advancePhaseAfterStop(endedPhase) {
        if (endedPhase === 'pomodoro') {
            var nextBreak = (_s.sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
            _s.sessionCount++;
            _resetPhaseLocal(nextBreak);
        } else if (endedPhase === 'shortbreak' || endedPhase === 'longbreak') {
            if (_s.sessionCount > 4) _s.sessionCount = 1;
            _resetPhaseLocal('pomodoro');
        } else {
            // countdown — reset ready on the same phase
            _s.elapsedBase    = 0;
            _s.runStartAtMs   = null;
            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.milestoneFired = false;
            _save();
            _broadcast('STATE');
            _fire('phase');
        }
    }

    // ── Backend sync helpers ───────────────────────────────────
    function _postBackend(path, body) {
        if (!global.currentUser) return Promise.resolve(null);
        return fetch(path, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    body ? JSON.stringify(body) : '{}',
        }).then(function (r) { return r.ok ? r.json() : null; })
          .catch(function () { return null; });
    }

    function _adoptServerState(serverDto) {
        if (!serverDto || typeof serverDto !== 'object') return;
        _mute = true;
        if (serverDto.phase)             _s.phase          = serverDto.phase;
        if (serverDto.pomodoroMinutes)   _s.pomodoroMins   = serverDto.pomodoroMinutes;
        if (serverDto.shortBreakMinutes) _s.shortBreakMins = serverDto.shortBreakMinutes;
        if (serverDto.longBreakMinutes)  _s.longBreakMins  = serverDto.longBreakMinutes;
        if (serverDto.sessionCount)      _s.sessionCount   = serverDto.sessionCount;
        if (typeof serverDto.plannedSeconds === 'number') _s.plannedSeconds = serverDto.plannedSeconds;
        if (typeof serverDto.elapsedBase    === 'number') _s.elapsedBase    = serverDto.elapsedBase;
        _s.isRunning = !!serverDto.running;
        _s.isPaused  = !!serverDto.paused;

        // Correct for clock skew between server and client.
        if (_s.isRunning && serverDto.runStartAtMs) {
            var skew = Date.now() - (serverDto.serverNowMs || Date.now());
            _s.runStartAtMs = serverDto.runStartAtMs + skew;
        } else {
            _s.runStartAtMs = null;
        }

        // Milestone flag: derive from current elapsed vs planned so we don't
        // refire a crossing that's already happened on another device.
        var elapsed = _computeElapsed();
        _s.milestoneFired = elapsed >= _s.plannedSeconds;

        _save();
        _broadcast('STATE');
        _fire(_s.isRunning ? 'start' : (_s.isPaused ? 'pause' : 'phase'));
        _mute = false;
    }

    // ── Public API ─────────────────────────────────────────────
    var _api = {

        subscribe:   function (fn) {
            if (typeof fn === 'function' && _subs.indexOf(fn) < 0) _subs.push(fn);
        },
        unsubscribe: function (fn) {
            _subs = _subs.filter(function (f) { return f !== fn; });
        },

        getState: function () { return _snap(); },

        /** 0..1 fraction of plannedSeconds elapsed, clamped at 1.0. */
        getProgress: function () {
            if (_s.plannedSeconds <= 0) return 0;
            var elapsed = _computeElapsed();
            return Math.min(1, elapsed / _s.plannedSeconds);
        },

        start: function () {
            if (_s.isRunning) return;

            _s.runStartAtMs = Date.now();
            _s.isRunning    = true;
            _s.isPaused     = false;
            // Re-derive milestone in case elapsedBase already crosses planned
            // (e.g. resuming a session that hit overtime then was paused).
            _s.milestoneFired = _s.elapsedBase >= _s.plannedSeconds;
            _save();
            _broadcast('STATE');
            _fire('start');

            _postBackend('/api/timer/start').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        pause: function () {
            if (!_s.isRunning) return;
            _s.elapsedBase  = _computeElapsed();
            _s.isRunning    = false;
            _s.isPaused     = true;
            _s.runStartAtMs = null;
            _save();
            _broadcast('STATE');
            _fire('pause');

            _postBackend('/api/timer/pause').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        toggle: function () {
            if (_s.isRunning) _api.pause(); else _api.start();
        },

        /**
         * Finalize the running session. Fires 'sessionEnd' with a frozen
         * snapshot so subscribers can persist actual/planned/overtime, then
         * advances to the next phase in stopped state (no auto-start).
         * This is the ONLY way a pomodoro/countdown session is marked done.
         */
        stop: function () {
            // If fully idle with no elapsed time at all, nothing to log.
            var elapsed = _computeElapsed();
            if (!_s.isRunning && !_s.isPaused && elapsed <= 0) {
                return;
            }

            var endedPhase = _s.phase;
            var pre = {
                phase:                 endedPhase,
                pomodoroMins:          _s.pomodoroMins,
                shortBreakMins:        _s.shortBreakMins,
                longBreakMins:         _s.longBreakMins,
                sessionCount:          _s.sessionCount,
                plannedSeconds:        _s.plannedSeconds,
                totalSeconds:          _s.plannedSeconds,
                elapsedSeconds:        Math.round(elapsed),
                actualDurationSeconds: Math.round(elapsed),
                overtimeSeconds:       Math.max(0, Math.round(elapsed - _s.plannedSeconds)),
                isOvertime:            elapsed > _s.plannedSeconds,
                remainingSeconds:      Math.max(0, Math.round(_s.plannedSeconds - elapsed)),
                remaining:             Math.max(0, Math.round(_s.plannedSeconds - elapsed)),
                isRunning:             false,
                isPaused:              false,
                runStartAtMs:          null,
                endedByUser:           true,
            };

            // Stop locally before broadcasting so any concurrent tick can't
            // race a second sessionEnd.
            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.runStartAtMs   = null;
            _s.elapsedBase    = 0;
            _s.milestoneFired = false;

            _fire('sessionEnd', pre);

            _advancePhaseAfterStop(endedPhase);

            _postBackend('/api/timer/stop', {
                phase:                 endedPhase,
                actualDurationSeconds: pre.actualDurationSeconds,
                plannedSeconds:        pre.plannedSeconds,
                overtimeSeconds:       pre.overtimeSeconds,
            }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        /**
         * Skip the current phase. For study phases, logs whatever was elapsed
         * (if anything) as a partial session, then advances.
         */
        skip: function () {
            var elapsed = _computeElapsed();
            var pre = {
                phase:                 _s.phase,
                pomodoroMins:          _s.pomodoroMins,
                shortBreakMins:        _s.shortBreakMins,
                longBreakMins:         _s.longBreakMins,
                sessionCount:          _s.sessionCount,
                plannedSeconds:        _s.plannedSeconds,
                totalSeconds:          _s.plannedSeconds,
                elapsedSeconds:        Math.round(elapsed),
                actualDurationSeconds: Math.round(elapsed),
                overtimeSeconds:       Math.max(0, Math.round(elapsed - _s.plannedSeconds)),
                isOvertime:            elapsed > _s.plannedSeconds,
                remainingSeconds:      Math.max(0, Math.round(_s.plannedSeconds - elapsed)),
                remaining:             Math.max(0, Math.round(_s.plannedSeconds - elapsed)),
                isRunning:             false,
                isPaused:              false,
                runStartAtMs:          null,
                endedByUser:           false,
            };

            _fire('skip', pre);

            _advancePhaseAfterStop(_s.phase);

            _postBackend('/api/timer/skip').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        setPhase: function (phase) {
            if (_s.isRunning) _api.pause();
            _resetPhaseLocal(phase);
            _postBackend('/api/timer/phase', { phase: phase }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        setDurations: function (pomodoro, shortBreak, longBreak) {
            if (!isNaN(pomodoro)   && pomodoro   >= 1 && pomodoro   <= 999) _s.pomodoroMins   = pomodoro;
            if (!isNaN(shortBreak) && shortBreak >= 1 && shortBreak <= 999) _s.shortBreakMins = shortBreak;
            if (!isNaN(longBreak)  && longBreak  >= 1 && longBreak  <= 999) _s.longBreakMins  = longBreak;

            if (_s.phase !== 'countdown') {
                _s.plannedSeconds = _durationFor(_s.phase);
                _s.elapsedBase    = 0;
                _s.runStartAtMs   = null;
                _s.isRunning      = false;
                _s.isPaused       = false;
                _s.milestoneFired = false;
            }
            _save();
            _broadcast('STATE');
            _fire('phase');

            _postBackend('/api/timer/durations', {
                pomodoroMinutes:   _s.pomodoroMins,
                shortBreakMinutes: _s.shortBreakMins,
                longBreakMinutes:  _s.longBreakMins,
            }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        applyCountdownDuration: function (mins) {
            var m = Math.max(1, Math.min(999, mins | 0 || 25));
            if (_s.isRunning) _api.pause();
            _s.phase          = 'countdown';
            _s.plannedSeconds = m * 60;
            _s.elapsedBase    = 0;
            _s.runStartAtMs   = null;
            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.milestoneFired = false;
            _save();
            _broadcast('STATE');
            _fire('phase');

            _postBackend('/api/timer/countdown', { minutes: m }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        restoreFromServer: function (serverState) {
            if (!serverState) return;
            _adoptServerState(serverState);
        },

        enableBackendSync: function () { /* no-op */ },
    };

    global.TimerCore = _api;

})(window);
