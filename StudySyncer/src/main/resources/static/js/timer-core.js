'use strict';
/**
 * TimerCore — shared timer engine for StudySyncer.
 *
 * Single source of truth for timer state across dashboard (timer.js)
 * and Focus Mode (focus.js). Pomodoro phases have been retired: the
 * timer counts up toward a single target duration, then continues
 * into overtime until the user stops it.
 *
 * Model
 *   targetSeconds        configured session length
 *   elapsedBase          seconds accumulated from prior running segments
 *                        (authoritative when !isRunning)
 *   runStartAtMs         epoch ms when current running segment began
 *   isRunning / isPaused
 *
 *   Derived:
 *     elapsedSeconds       = isRunning
 *                              ? elapsedBase + (now - runStartAtMs)/1000
 *                              : elapsedBase
 *     remainingSeconds     = max(0, targetSeconds - elapsedSeconds)
 *     isOvertime           = elapsedSeconds > targetSeconds
 *     overtimeSeconds      = max(0, elapsedSeconds - targetSeconds)
 *
 * Reaching the target does NOT stop the session. The timer keeps
 * counting up; the UI flips to overtime. Stop is the only finalizer.
 */
(function (global) {

    var LS_KEY  = 'ss_timer_v5';   // v5: single target duration, no phases
    var BC_NAME = 'ss_timer_bc_v5';

    var DEFAULTS = {
        targetMins:       25,
        targetSeconds:    25 * 60,
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

    function _computeElapsed() {
        if (_s.isRunning && _s.runStartAtMs) {
            var delta = (Date.now() - _s.runStartAtMs) / 1000;
            return Math.max(0, _s.elapsedBase + delta);
        }
        return Math.max(0, _s.elapsedBase);
    }

    function _snap() {
        var elapsed   = _computeElapsed();
        var target    = Math.max(0, _s.targetSeconds | 0);
        var remaining = Math.max(0, target - elapsed);
        var overtime  = Math.max(0, elapsed - target);
        var isOt      = elapsed > target;

        return {
            targetMins:            _s.targetMins,
            targetSeconds:         target,
            plannedSeconds:        target,            // legacy alias used by UI
            totalSeconds:          target,            // legacy alias
            elapsedSeconds:        Math.round(elapsed),
            remainingSeconds:      Math.round(remaining),
            remaining:             Math.round(remaining),
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

    function _broadcast() {
        if (_mute || !_bc) return;
        try { _bc.postMessage({ type: 'STATE', state: _persistable() }); } catch (_) {}
    }

    function _persistable() {
        return {
            targetMins:     _s.targetMins,
            targetSeconds:  _s.targetSeconds,
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

    (function _init() {
        try {
            var raw = localStorage.getItem(LS_KEY);
            if (raw) Object.assign(_s, JSON.parse(raw));
        } catch (_) {}
        _startDisplayInterval();
    })();

    function _startDisplayInterval() {
        if (_ivl) return;
        _ivl = setInterval(_tick, 250);
    }

    function _tick() {
        if (!_s.isRunning || !_s.runStartAtMs) return;

        var elapsed = _computeElapsed();

        // Fire zero-crossing milestone exactly once per session.
        if (!_s.milestoneFired && elapsed >= _s.targetSeconds && _s.targetSeconds > 0) {
            _s.milestoneFired = true;
            _save();
            _broadcast();
            _fire('milestone');
        }
        _fire('tick');
    }

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
        if (typeof serverDto.targetSeconds === 'number') _s.targetSeconds = serverDto.targetSeconds;
        else if (typeof serverDto.plannedSeconds === 'number') _s.targetSeconds = serverDto.plannedSeconds;
        if (typeof serverDto.targetMinutes === 'number') _s.targetMins = serverDto.targetMinutes;
        else _s.targetMins = Math.max(1, Math.round(_s.targetSeconds / 60));
        if (typeof serverDto.elapsedBase === 'number') _s.elapsedBase = serverDto.elapsedBase;
        _s.isRunning = !!serverDto.running;
        _s.isPaused  = !!serverDto.paused;

        if (_s.isRunning && serverDto.runStartAtMs) {
            var skew = Date.now() - (serverDto.serverNowMs || Date.now());
            _s.runStartAtMs = serverDto.runStartAtMs + skew;
        } else {
            _s.runStartAtMs = null;
        }

        var elapsed = _computeElapsed();
        _s.milestoneFired = elapsed >= _s.targetSeconds;

        _save();
        _broadcast();
        _fire(_s.isRunning ? 'start' : (_s.isPaused ? 'pause' : 'phase'));
        _mute = false;
    }

    var _api = {

        subscribe:   function (fn) {
            if (typeof fn === 'function' && _subs.indexOf(fn) < 0) _subs.push(fn);
        },
        unsubscribe: function (fn) {
            _subs = _subs.filter(function (f) { return f !== fn; });
        },

        getState: function () { return _snap(); },

        getProgress: function () {
            if (_s.targetSeconds <= 0) return 0;
            var elapsed = _computeElapsed();
            return Math.min(1, elapsed / _s.targetSeconds);
        },

        start: function () {
            if (_s.isRunning) return;
            _s.runStartAtMs = Date.now();
            _s.isRunning    = true;
            _s.isPaused     = false;
            _s.milestoneFired = _s.elapsedBase >= _s.targetSeconds;
            _save();
            _broadcast();
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
            _broadcast();
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
         * snapshot so subscribers can persist the actual duration.
         */
        stop: function () {
            var elapsed = _computeElapsed();
            if (!_s.isRunning && !_s.isPaused && elapsed <= 0) return;

            var pre = {
                targetMins:            _s.targetMins,
                targetSeconds:         _s.targetSeconds,
                plannedSeconds:        _s.targetSeconds,
                totalSeconds:          _s.targetSeconds,
                elapsedSeconds:        Math.round(elapsed),
                actualDurationSeconds: Math.round(elapsed),
                overtimeSeconds:       Math.max(0, Math.round(elapsed - _s.targetSeconds)),
                isOvertime:            elapsed > _s.targetSeconds,
                remainingSeconds:      Math.max(0, Math.round(_s.targetSeconds - elapsed)),
                remaining:             Math.max(0, Math.round(_s.targetSeconds - elapsed)),
                isRunning:             false,
                isPaused:              false,
                runStartAtMs:          null,
                endedByUser:           true,
            };

            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.runStartAtMs   = null;
            _s.elapsedBase    = 0;
            _s.milestoneFired = false;

            _fire('sessionEnd', pre);

            _save();
            _broadcast();
            _fire('phase');

            _postBackend('/api/timer/stop', {
                actualDurationSeconds: pre.actualDurationSeconds,
                plannedSeconds:        pre.plannedSeconds,
                overtimeSeconds:       pre.overtimeSeconds,
            }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        /** Skip resets the timer without logging a session. */
        skip: function () {
            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.runStartAtMs   = null;
            _s.elapsedBase    = 0;
            _s.milestoneFired = false;
            _save();
            _broadcast();
            _fire('phase');

            _postBackend('/api/timer/skip').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        setTargetMinutes: function (mins) {
            var m = Math.max(1, Math.min(999, mins | 0 || 25));
            if (_s.isRunning) _api.pause();
            _s.targetMins     = m;
            _s.targetSeconds  = m * 60;
            _s.elapsedBase    = 0;
            _s.runStartAtMs   = null;
            _s.isRunning      = false;
            _s.isPaused       = false;
            _s.milestoneFired = false;
            _save();
            _broadcast();
            _fire('phase');

            _postBackend('/api/timer/durations', { targetMinutes: m }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        /** Legacy alias for code paths that previously adjusted a countdown. */
        applyCountdownDuration: function (mins) { _api.setTargetMinutes(mins); },

        restoreFromServer: function (serverState) {
            if (!serverState) return;
            _adoptServerState(serverState);
        },

        enableBackendSync: function () { /* no-op */ },
    };

    global.TimerCore = _api;

})(window);
