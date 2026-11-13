import { randomUUID } from 'node:crypto';

export const LOCAL_STABLE_MS = 10_000, PROBE_FRESH_MS = 3_000, SWITCH_TIMEOUT_MS = 8_000, MAX_SWITCH_ATTEMPTS = 3;

export function failLocalSwitch(r, time) {
  const s = r.localSwitch;
  if (!s || s.phase === 'FAILED') return false;
  s.phase = 'FAILED'; s.failedAt = time; r.localProbes = {};
  if (r.state === 'LOCAL_ACTIVE') {
    r.state = 'CLOUD_PENDING'; r.cloudReason = 'LOCAL_FAILURE'; r.cloudStartedAt = time; r.cloudJoined = [];
  } else r.state = 'CLOUD_ACTIVE';
  r.revision++; return true;
}

export function expireLocalSwitch(r, time) {
  return r.state === 'SWITCHING_LOCAL' && r.localSwitch?.expiresAt <= time && failLocalSwitch(r, time);
}

export function localProbe(r, actor, body, time, need) {
  need(typeof body.ready === 'boolean' && typeof body.probeId === 'string' && /^[a-f0-9]{64}$/.test(body.probeId), 400, 'INVALID_LOCAL_PROBE');
  need(['CLOUD_ACTIVE', 'SWITCHING_LOCAL'].includes(r.state), 409, 'INVALID_RIDE_STATE');
  r.localProbes ??= {};
  const old = r.localProbes[actor];
  if (!body.ready) {
    delete r.localProbes[actor];
    if (r.state === 'SWITCHING_LOCAL' && r.localSwitch.probeId === body.probeId) return failLocalSwitch(r, time);
    return false;
  }
  r.localProbes[actor] = { probeId: body.probeId, seenAt: time,
    stableAt: old?.probeId === body.probeId && time - old.seenAt <= PROBE_FRESH_MS ? old.stableAt : time };
  if (r.state !== 'CLOUD_ACTIVE' || (r.localSwitchAttempts ?? 0) >= MAX_SWITCH_ATTEMPTS) return false;
  const observations = r.members.map(m => r.localProbes[m]);
  if (!observations.every(p => p?.probeId === body.probeId && time - p.seenAt <= PROBE_FRESH_MS && time - p.stableAt >= LOCAL_STABLE_MS)) return false;
  r.localSwitch = { switchId: `switch_${randomUUID()}`, probeId: body.probeId, phase: 'PREPARING', prepared: [], completed: [],
    startedAt: time, expiresAt: time + SWITCH_TIMEOUT_MS };
  r.localSwitchAttempts = (r.localSwitchAttempts ?? 0) + 1;
  r.state = 'SWITCHING_LOCAL'; r.revision++; return true;
}

export function localSwitchCommand(r, actor, body, time, need, cloudUsed) {
  need(['PREPARED', 'COMPLETE', 'FAIL'].includes(body.stage), 400, 'INVALID_LOCAL_SWITCH_STAGE');
  const s = r.localSwitch;
  need(s && body.switchId === s.switchId && body.probeId === s.probeId, 409, 'STALE_LOCAL_SWITCH');
  if (body.stage === 'FAIL') {
    if(s.phase==='FAILED') return false;
    need(r.state==='SWITCHING_LOCAL' || r.state==='LOCAL_ACTIVE' && s.phase==='COMMITTED',409,'STALE_LOCAL_SWITCH');
    return failLocalSwitch(r, time);
  }
  if (s.phase === 'COMMITTED' && body.stage === 'COMPLETE') return false;
  need(r.state === 'SWITCHING_LOCAL' && s.expiresAt > time, 409, 'LOCAL_SWITCH_EXPIRED');
  if (body.stage === 'PREPARED') {
    need(['PREPARING', 'ARMED'].includes(s.phase), 409, 'INVALID_LOCAL_SWITCH_STAGE');
    if (s.prepared.includes(actor)) return false;
    s.prepared.push(actor);
    if (s.prepared.length === 2) s.phase = 'ARMED';
  } else {
    need(s.phase === 'ARMED' && s.prepared.length === 2, 409, 'LOCAL_SWITCH_NOT_ARMED');
    if (s.completed.includes(actor)) return false;
    s.completed.push(actor);
    if (s.completed.length === 2) {
      s.phase = 'COMMITTED'; s.committedAt = time;
      r.cloudUsedMs = cloudUsed(r, time); r.cloudStartedAt = null;
      r.state = 'LOCAL_ACTIVE'; r.everLocalActive = true; r.localEstablishedBy = [...r.members]; r.cloudJoined = [];
      r.localProbes = {};
    }
  }
  r.revision++; return true;
}
