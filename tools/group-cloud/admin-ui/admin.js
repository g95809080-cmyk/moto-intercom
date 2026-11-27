(() => {
  'use strict';

  const SESSION_TOKEN_KEY = 'motocom_admin_session_token';
  const THEME_STORAGE_KEY = 'motocom_admin_theme';
  const PORTAL_SSO_TOKEN = 'portal-sso';
  const PORTAL_ORIGIN = 'https://entry.toneride.top';
  const PAGE_SIZE = 25;
  const AUTO_REFRESH_MS = 15_000;
  const VOLC_FREE_LIMIT_MINUTES = 10_000;

  const VIEW_TITLES = {
    overview: '总览监控',
    codes: '激活码管理',
    entitlements: '权益与设备',
    rooms: '实时房间',
    policy: '熔断与策略',
    audit: '操作审计'
  };

  const ACTION_LABELS = {
    MINT_CODES: '批量发码',
    REVEAL_CODE: '查看激活码',
    REVOKE_CODE: '吊销激活码',
    TOPUP_ENTITLEMENT: '权益充值',
    RESET_REBIND: '重置换绑',
    REVOKE_ENTITLEMENT: '停用权益',
    FORCE_END_ROOM: '强制解散房间',
    UPDATE_GLOBAL_QUOTA: '更新全局配额'
  };

  const MINT_PRESETS = {
    beta: {
      type: 'PLAN',
      count: 10,
      minutes: 300,
      days: 14,
      maxMembers: 6,
      maxRooms: 1,
      maxSession: 120,
      note: '微信群内测小队码'
    },
    leader: {
      type: 'PLAN',
      count: 2,
      minutes: 1000,
      days: 30,
      maxMembers: 20,
      maxRooms: 2,
      maxSession: 240,
      note: '领队大队测试码'
    },
    topup: {
      type: 'TOPUP',
      count: 5,
      minutes: 300,
      days: 30,
      maxMembers: 6,
      maxRooms: 1,
      maxSession: 120,
      note: '额度加油包'
    }
  };

  const state = {
    token: '',
    activeView: 'overview',
    rankMode: 'today',
    overview: null,
    codes: { items: [], total: 0, offset: 0, batchId: '', status: '', codeMask: '' },
    entitlements: { items: [], total: 0, offset: 0, status: '' },
    rooms: { items: [], total: 0 },
    audit: { items: [], total: 0, offset: 0 },
    refreshTimer: null,
    refreshing: false,
    lastMinted: null,
    mintIdempotencyKey: '',
    revealedCodes: new Map(),
    revealGeneration: 0,
    topupTarget: null,
    topupIdempotencyKey: '',
    confirmAction: null,
    policyDirty: false
  };

  const $ = (id) => document.getElementById(id);
  const SVG_NS = 'http://www.w3.org/2000/svg';

  function svgEl(tag, attrs = {}) {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      node.setAttribute(k, String(v));
    }
    return node;
  }

  function el(tag, options = {}, children = []) {
    const node = document.createElement(tag);
    if (options.className) node.className = options.className;
    if (options.text !== undefined) node.textContent = String(options.text);
    if (options.type) node.type = options.type;
    if (options.title) node.title = options.title;
    if (options.disabled) node.disabled = true;
    if (options.attrs) {
      for (const [k, v] of Object.entries(options.attrs)) {
        node.setAttribute(k, String(v));
      }
    }
    if (options.onClick) {
      node.addEventListener('click', options.onClick);
    }
    for (const child of children) {
      if (child !== null && child !== undefined) {
        node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
      }
    }
    return node;
  }

  function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function isPortalManagedHost() {
    const { protocol, hostname } = window.location;
    return protocol === 'https:' && hostname.endsWith('.toneride.top');
  }

  function createIdempotencyKey(prefix) {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return `${prefix}-${window.crypto.randomUUID()}`;
    }
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    return `${prefix}-${hex}`;
  }

  function formatNumber(num) {
    const safe = Number.isFinite(Number(num)) ? Number(num) : 0;
    return safe.toLocaleString('zh-CN');
  }

  function formatTimeShanghai(ms) {
    if (!ms || !Number.isFinite(Number(ms))) return '--';
    const d = new Date(Number(ms) + 8 * 3_600_000);
    const iso = d.toISOString();
    return `${iso.slice(0, 10)} ${iso.slice(11, 19)}`;
  }

  function formatShortDate(ms) {
    if (!ms || !Number.isFinite(Number(ms))) return '--';
    return new Date(Number(ms) + 8 * 3_600_000).toISOString().slice(0, 10);
  }

  function shortId(id, keep = 14) {
    if (!id) return '--';
    const s = String(id);
    if (s.length <= keep) return s;
    return `${s.slice(0, keep - 4)}…${s.slice(-4)}`;
  }

  function showToast(message, variant = 'default') {
    const container = $('toast-container');
    const cls = variant === 'error' ? 'toast toast-error' : variant === 'ok' ? 'toast toast-ok' : 'toast';
    const item = el('div', { className: cls, text: message });
    container.appendChild(item);
    window.setTimeout(() => {
      if (item.parentNode === container) container.removeChild(item);
    }, 3400);
  }

  async function copyText(text, successMsg) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.className = 'sr-copy-buffer';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      showToast(successMsg || '已复制到剪贴板', 'ok');
    } catch {
      showToast('复制失败，请手动选中复制', 'error');
    }
  }

  function applyTheme(theme) {
    const next = theme === 'dark' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', next);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // ignore storage errors
    }
    const label = next === 'dark' ? '日间亮色' : '夜航暗色';
    $('theme-toggle-btn').textContent = label;
    $('login-theme-btn').textContent = next === 'dark' ? '切换日间亮色' : '切换夜航暗色';
  }

  function toggleTheme() {
    const current = document.documentElement.getAttribute('data-theme');
    applyTheme(current === 'dark' ? 'light' : 'dark');
  }

  function stopAutoRefresh() {
    if (state.refreshTimer) {
      window.clearInterval(state.refreshTimer);
      state.refreshTimer = null;
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh();
    if (!state.token || !$('auto-refresh-toggle').checked) return;
    state.refreshTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && state.token && !state.refreshing) {
        refreshCurrentView({ silent: true });
      }
    }, AUTO_REFRESH_MS);
  }

  function clearSensitiveDom() {
    state.lastMinted = null;
    state.overview = null;
    state.revealGeneration += 1;
    state.revealedCodes.clear();
    state.codes.items = [];
    state.entitlements.items = [];
    state.rooms.items = [];
    state.audit.items = [];
    $('hide-all-revealed-btn').classList.add('hidden');
    clearChildren($('mint-result-list'));
    clearChildren($('codes-tbody'));
    clearChildren($('entitlements-tbody'));
    clearChildren($('rooms-tbody'));
    clearChildren($('audit-tbody'));
    clearChildren($('consumers-list'));
    clearChildren($('daily-chart'));
  }

  function lockSession(message = '') {
    stopAutoRefresh();
    state.token = '';
    try {
      window.sessionStorage.removeItem(SESSION_TOKEN_KEY);
    } catch {
      // ignore
    }
    closeModal('mint-modal');
    closeModal('topup-modal');
    closeModal('confirm-modal');
    clearSensitiveDom();
    $('token-input').value = '';
    $('app-view').classList.add('hidden');
    $('login-view').classList.remove('hidden');
    const errBox = $('login-error');
    if (message) {
      errBox.textContent = message;
      errBox.classList.remove('hidden');
    } else {
      errBox.classList.add('hidden');
    }
  }

  async function apiRequest(method, endpoint, { body = null, idempotencyKey = '', overrideToken = '' } = {}) {
    const activeToken = overrideToken || state.token;
    if (!activeToken) {
      throw Object.assign(new Error('未登录管理员会话'), { status: 401, code: 'ADMIN_UNAUTHORIZED' });
    }
    const headers = {
      Accept: 'application/json',
      Authorization: `Bearer ${activeToken}`
    };
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    if (body !== null) headers['Content-Type'] = 'application/json';

    const response = await fetch(endpoint, {
      method,
      headers,
      cache: 'no-store',
      body: body !== null ? JSON.stringify(body) : undefined
    });

    let payload = null;
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }

    if (!response.ok) {
      const message = payload?.message || `请求失败（HTTP ${response.status}）`;
      const err = Object.assign(new Error(message), {
        status: response.status,
        code: payload?.code || `HTTP_${response.status}`
      });
      if (response.status === 401 && !overrideToken) {
        if (state.token === PORTAL_SSO_TOKEN) {
          stopAutoRefresh();
          state.token = '';
          clearSensitiveDom();
          window.location.replace(
            `${PORTAL_ORIGIN}/login?next=${encodeURIComponent(window.location.href)}`
          );
          throw err;
        }
        lockSession('管理员会话已失效，请重新输入 adminToken');
      }
      throw err;
    }
    return payload ?? {};
  }

  function statusPill(status) {
    const map = {
      UNUSED: { text: '可用', cls: 'pill-ok' },
      REDEEMED: { text: '已兑换', cls: 'pill-info' },
      REVOKED: { text: '已吊销', cls: 'pill-danger' },
      ACTIVE: { text: '正常', cls: 'pill-ok' },
      CLOUD: { text: 'CLOUD 计费', cls: 'pill-accent' },
      LOCAL: { text: 'LOCAL 零扣费', cls: 'pill-ok' }
    };
    const item = map[status] || { text: String(status || '--'), cls: 'pill-neutral' };
    return el('span', { className: `pill ${item.cls}`, text: item.text });
  }

  /* ---------------- Renderers ---------------- */

  function renderOverview(data) {
    state.overview = data;
    const gq = data.globalQuota || {};
    const usedMin = Math.floor((gq.usedSeconds ?? 0) / 60);
    const hardMin = gq.hardLimitMinutes ?? 8500;
    const remMin = gq.remainingMinutes ?? 0;
    const ratio = hardMin > 0 ? Math.min(1, (gq.usedSeconds ?? 0) / (hardMin * 60)) : 1;
    const pctText = hardMin > 0 ? `${Math.round(ratio * 1000) / 10}%` : '已熔断';
    const warnRatio = Number.isFinite(Number(gq.warnThresholdRatio)) ? Number(gq.warnThresholdRatio) : 0.8;

    $('sidebar-month').textContent = `${gq.monthKey || '--'} 自然月`;
    $('kpi-used-min').textContent = formatNumber(usedMin);
    $('kpi-limit-min').textContent = `/ ${formatNumber(hardMin)} 人·分`;
    $('kpi-remaining-min').textContent = `剩余 ${formatNumber(remMin)} 人·分`;

    const pctBadge = $('kpi-used-pct');
    pctBadge.textContent = pctText;
    pctBadge.className =
      hardMin === 0 || remMin <= 0
        ? 'pill pill-danger tabular'
        : ratio >= warnRatio
          ? 'pill pill-warn tabular'
          : 'pill pill-ok tabular';

    $('kpi-active-rooms').textContent = formatNumber(data.activeRoomsCount ?? 0);
    $('kpi-rtc-members').textContent = `计费中 ${formatNumber(data.activeCloudRoomsCount ?? 0)} 房 · RTC ${formatNumber(data.activeRtcMembersTotal ?? 0)} 人`;
    const burnRate = data.activeRtcMembersTotal ?? 0;
    const burnPill = $('kpi-burn-rate');
    burnPill.textContent = `${formatNumber(burnRate)} 人·分/分钟`;
    burnPill.className = burnRate > 0 ? 'pill pill-accent tabular' : 'pill pill-neutral tabular';

    $('kpi-entitlements').textContent = formatNumber(data.activeEntitlementCount ?? 0);
    $('kpi-join-policy').textContent = gq.requireCodeForJoin ? '入群门槛 全员持码' : '入群门槛 仅房主需码';

    const stats = data.codeStats || { UNUSED: 0, REDEEMED: 0, REVOKED: 0 };
    const totalCodes = (stats.UNUSED || 0) + (stats.REDEEMED || 0) + (stats.REVOKED || 0);
    $('kpi-codes-unused').textContent = formatNumber(stats.UNUSED || 0);
    $('kpi-codes-breakdown').textContent = `已兑 ${formatNumber(stats.REDEEMED || 0)} · 已吊销 ${formatNumber(stats.REVOKED || 0)}`;
    const unusedW = totalCodes ? ((stats.UNUSED || 0) / totalCodes) * 100 : 0;
    const redeemedW = totalCodes ? ((stats.REDEEMED || 0) / totalCodes) * 100 : 0;
    const revokedW = totalCodes ? ((stats.REVOKED || 0) / totalCodes) * 100 : 0;
    $('pool-seg-unused').setAttribute('x', '0');
    $('pool-seg-unused').setAttribute('width', unusedW.toFixed(2));
    $('pool-seg-redeemed').setAttribute('x', unusedW.toFixed(2));
    $('pool-seg-redeemed').setAttribute('width', redeemedW.toFixed(2));
    $('pool-seg-revoked').setAttribute('x', (unusedW + redeemedW).toFixed(2));
    $('pool-seg-revoked').setAttribute('width', revokedW.toFixed(2));

    // Nav badges
    const codesBadge = $('nav-badge-codes');
    codesBadge.textContent = String(stats.UNUSED || 0);
    codesBadge.classList.toggle('hidden', (stats.UNUSED || 0) === 0);
    const roomsBadge = $('nav-badge-rooms');
    roomsBadge.textContent = String(data.activeRoomsCount || 0);
    roomsBadge.classList.toggle('hidden', (data.activeRoomsCount || 0) === 0);

    // Global banner
    const alertBanner = $('global-alert');
    if (hardMin === 0 || remMin <= 0) {
      alertBanner.textContent =
        hardMin === 0
          ? '当前处于紧急全局熔断状态（当月上限 0 人·分钟），所有云端建房均已阻断。可在“熔断与策略”中恢复额度。'
          : `本月云端测试总配额（${formatNumber(hardMin)} 人·分钟）已耗尽，系统已自动熔断并引导用户使用本地直连。`;
      alertBanner.className = 'global-alert danger';
    } else if (ratio >= warnRatio) {
      alertBanner.textContent = `本月云端消耗已达 ${pctText}（${formatNumber(usedMin)} / ${formatNumber(hardMin)} 人·分钟），接近自然月熔断红线。`;
      alertBanner.className = 'global-alert warn';
    } else {
      alertBanner.className = 'global-alert hidden';
    }

    // Gauge
    const gaugeFill = $('gauge-fill');
    const gaugeBase = Math.max(VOLC_FREE_LIMIT_MINUTES, hardMin || VOLC_FREE_LIMIT_MINUTES);
    const usedWidth = Math.min(1000, Math.round((usedMin / gaugeBase) * 1000));
    const warnX = Math.min(997, Math.max(0, Math.round(((hardMin * warnRatio) / gaugeBase) * 1000)));
    const hardX = Math.min(997, Math.max(0, Math.round((hardMin / gaugeBase) * 1000)));
    gaugeFill.setAttribute('width', String(usedWidth));
    gaugeFill.setAttribute(
      'class',
      hardMin === 0 || remMin <= 0 ? 'gauge-fill danger' : ratio >= warnRatio ? 'gauge-fill warn' : 'gauge-fill'
    );
    $('gauge-warn-tick').setAttribute('x', String(warnX));
    $('gauge-hard-tick').setAttribute('x', String(hardX));
    $('gauge-warn-label').textContent = `预警线 ${formatNumber(Math.floor(hardMin * warnRatio))}`;
    $('gauge-hard-label').textContent = `硬熔断线 ${formatNumber(hardMin)}`;
    const bufferMin = Math.max(0, VOLC_FREE_LIMIT_MINUTES - hardMin);
    $('quota-gauge-sub').textContent = `${gq.monthKey || '--'} 自然月 · 相对火山 10,000 人·分免费额度预留 ${formatNumber(bufferMin)} 人·分安全缓冲`;

    const statePill = $('quota-state-pill');
    if (hardMin === 0 || remMin <= 0) {
      statePill.textContent = '已熔断阻断';
      statePill.className = 'pill pill-danger';
    } else if (ratio >= warnRatio) {
      statePill.textContent = '预警水位';
      statePill.className = 'pill pill-warn';
    } else {
      statePill.textContent = '正常放行';
      statePill.className = 'pill pill-ok';
    }

    renderDailyChart(data.dailyUsage || []);
    renderTopConsumers();
    syncPolicyFormFromOverview(gq);
  }

  function renderDailyChart(dailyUsage) {
    const host = $('daily-chart');
    const detail = $('daily-chart-detail');
    clearChildren(host);
    if (!dailyUsage.length) {
      detail.textContent = '暂无按日用量记录';
      return;
    }
    const maxMinutes = Math.max(1, ...dailyUsage.map((d) => Number(d.totalMinutes) || 0));
    let totalWindowMin = 0;

    const cols = dailyUsage.map((day) => {
      const netMin = Number(day.networkMinutes) || 0;
      const hybMin = Number(day.hybridMinutes) || 0;
      const totMin = Number(day.totalMinutes) || 0;
      totalWindowMin += totMin;
      const chartH = 140;
      const netH = totMin > 0 && netMin > 0 ? Math.max(5, Math.round((netMin / maxMinutes) * chartH)) : 0;
      const hybH = totMin > 0 && hybMin > 0 ? Math.max(5, Math.round((hybMin / maxMinutes) * chartH)) : 0;
      const clampedNetH = Math.min(chartH, netH);
      const clampedHybH = Math.min(chartH - clampedNetH, hybH);
      const netY = chartH - clampedNetH;
      const hybY = netY - clampedHybH;

      const svg = svgEl('svg', {
        class: 'bar-svg',
        viewBox: `0 0 24 ${chartH}`,
        preserveAspectRatio: 'none',
        'aria-hidden': 'true'
      });
      if (clampedHybH > 0) {
        svg.appendChild(svgEl('rect', { class: 'bar-seg-hybrid', x: 0, y: hybY, width: 24, height: clampedHybH }));
      }
      if (clampedNetH > 0) {
        svg.appendChild(svgEl('rect', { class: 'bar-seg-network', x: 0, y: netY, width: 24, height: clampedNetH }));
      }

      const stack = el('div', { className: 'bar-stack' }, [svg]);
      const dateLabel = el('span', { className: 'bar-date tabular', text: day.dateKey.slice(5) });
      const col = el(
        'div',
        {
          className: 'bar-col',
          title: `${day.dateKey} 合计 ${totMin} 人·分钟`
        },
        [stack, dateLabel]
      );

      const showDay = () => {
        for (const c of cols) c.classList.remove('active');
        col.classList.add('active');
        detail.textContent = `${day.dateKey} · 合计 ${totMin} 人·分（NETWORK ${netMin} · HYBRID 云端 ${hybMin}）`;
      };
      col.addEventListener('mouseenter', showDay);
      col.addEventListener('click', showDay);
      return col;
    });

    for (const c of cols) host.appendChild(c);
    detail.textContent = `近 14 天累计 ${Math.round(totalWindowMin * 10) / 10} 人·分 · 悬停或点击柱条查看单日拆分`;
  }

  function renderTopConsumers() {
    const list = $('consumers-list');
    clearChildren(list);
    const data = state.overview;
    const rows =
      state.rankMode === 'today' ? data?.topConsumersToday || [] : data?.topConsumersMonth || [];
    if (!rows.length) {
      list.appendChild(
        el('div', {
          className: 'empty-state',
          text: state.rankMode === 'today' ? '近 24 小时暂无云端消耗记录' : '本月暂无云端消耗记录'
        })
      );
      return;
    }
    const maxSec = Math.max(1, ...rows.map((r) => Number(r.usedSeconds) || 0));
    for (const row of rows) {
      const pct = Math.max(3, Math.round(((Number(row.usedSeconds) || 0) / maxSec) * 100));
      const meter = el('progress', { className: 'meter', attrs: { max: 100, value: pct } });
      const displayTitle = row.ownerDeviceId
        ? `${row.ownerDeviceId} (${shortId(row.entitlementId, 12)})`
        : row.entitlementId;
      const usedMinText = `${Math.round(((Number(row.usedSeconds) || 0) / 60) * 10) / 10} 人·分`;
      list.appendChild(
        el('div', { className: 'rank-row' }, [
          el('div', { className: 'rank-meta' }, [
            el('span', { className: 'rank-name mono', text: displayTitle, title: `${row.ownerDeviceId} / ${row.entitlementId}` }),
            el('span', { className: 'tabular', text: usedMinText })
          ]),
          meter
        ])
      );
    }
  }

  function syncPolicyFormFromOverview(gq) {
    if (!gq || state.policyDirty) return;
    const hardInput = $('policy-hard-limit');
    const warnInput = $('policy-warn-pct');
    const joinSelect = $('policy-require-join');
    if (gq.hardLimitMinutes > 0) {
      hardInput.value = String(gq.hardLimitMinutes);
    }
    if (Number.isFinite(Number(gq.warnThresholdRatio))) {
      warnInput.value = String(Math.round(Number(gq.warnThresholdRatio) * 100));
    }
    joinSelect.value = gq.requireCodeForJoin ? 'true' : 'false';
  }

  function renderCodes(data) {
    state.codes.items = data.items || [];
    state.codes.total = data.total || 0;
    const tbody = $('codes-tbody');
    clearChildren(tbody);
    const visibleIds = new Set(state.codes.items.map((it) => it.codeId));
    for (const key of state.revealedCodes.keys()) {
      if (!visibleIds.has(key)) state.revealedCodes.delete(key);
    }
    $('hide-all-revealed-btn').classList.toggle('hidden', state.revealedCodes.size === 0);

    if (!state.codes.items.length) {
      tbody.appendChild(
        el('tr', {}, [el('td', { className: 'empty-state', text: '没有符合条件的激活码', attrs: { colspan: 9 } })])
      );
    } else {
      for (const item of state.codes.items) {
        const ratio = item.quotaMinutes > 0 ? item.remainingMinutes / item.quotaMinutes : 0;
        const pct = Math.max(0, Math.min(100, Math.round(ratio * 100)));
        const meter = el('progress', {
          className: `meter meter-mini ${ratio <= 0 ? 'zero' : ratio <= 0.2 ? 'low' : ''}`,
          attrs: { max: 100, value: pct }
        });

        const revealedCode = state.revealedCodes.get(item.codeId) || '';
        const actionButtons = [];
        if (revealedCode) {
          actionButtons.push(
            el('button', {
              type: 'button',
              className: 'btn btn-secondary btn-sm',
              text: '复制',
              onClick: () => copyText(revealedCode, `已复制 ${revealedCode}`)
            }),
            el('button', {
              type: 'button',
              className: 'btn btn-ghost btn-sm',
              text: '发微信',
              onClick: () => copyText(buildSingleWechatMessage(item, revealedCode), '已复制单条微信发放文案')
            }),
            el('button', {
              type: 'button',
              className: 'btn btn-ghost btn-sm',
              text: '隐藏',
              onClick: () => {
                state.revealedCodes.delete(item.codeId);
                renderCodes({ items: state.codes.items, total: state.codes.total });
              }
            })
          );
        } else if (item.canReveal) {
          actionButtons.push(
            el('button', {
              type: 'button',
              className: 'btn btn-secondary btn-sm',
              text: '查看',
              onClick: (ev) => revealCodeItem(item, ev.currentTarget)
            })
          );
        }
        if (item.status !== 'REVOKED') {
          actionButtons.push(
            el('button', {
              type: 'button',
              className: 'btn btn-danger-outline btn-sm',
              text: '吊销',
              onClick: () => openRevokeCodeConfirm(item)
            })
          );
        }

        const actionCell = el('td', { className: 'col-actions' });
        if (actionButtons.length > 0) {
          actionCell.appendChild(el('div', { className: 'row-actions' }, actionButtons));
        } else {
          actionCell.appendChild(el('span', { className: 'cell-sub', text: item.status === 'REVOKED' ? '已停用' : '无原码' }));
        }

        tbody.appendChild(
          el('tr', {}, [
            el('td', {}, [
              el('div', {
                className: revealedCode ? 'mono revealed-code' : 'mono',
                text: revealedCode || item.codeMask
              }),
              el('span', { className: 'cell-sub mono', text: shortId(item.codeId, 16), title: item.codeId })
            ]),
            el('td', {}, [
              el('div', { text: item.batchId || '--' }),
              el('span', {
                className: `pill ${item.codeType === 'TOPUP' ? 'pill-info' : 'pill-neutral'}`,
                text: item.codeType === 'TOPUP' ? '加油包' : '套餐'
              })
            ]),
            el('td', { className: 'tabular' }, [
              el('div', { text: `${formatNumber(item.remainingMinutes)} / ${formatNumber(item.quotaMinutes)} 分` }),
              meter
            ]),
            el('td', { className: 'tabular' }, [
              el('div', { text: `限 ${item.maxMembers} 人 · ${item.validDays} 天` }),
              el('span', { className: 'cell-sub', text: `${item.maxConcurrentRooms} 房 · 单次 ${item.maxSessionMinutes} 分` })
            ]),
            el('td', {}, [statusPill(item.status)]),
            el('td', {}, [
              el('span', {
                className: 'mono',
                text: item.redeemedEntitlementId ? shortId(item.redeemedEntitlementId, 14) : '--',
                title: item.redeemedEntitlementId || ''
              })
            ]),
            el('td', { className: 'tabular', text: formatTimeShanghai(item.createdAtMs) }),
            el('td', { text: item.note || '--' }),
            actionCell
          ])
        );
      }
    }

    const page = Math.floor(state.codes.offset / PAGE_SIZE) + 1;
    const pages = Math.max(1, Math.ceil(state.codes.total / PAGE_SIZE));
    $('codes-page-info').textContent = `共 ${formatNumber(state.codes.total)} 条 · 第 ${page} / ${pages} 页`;
    $('codes-prev-btn').disabled = state.codes.offset <= 0;
    $('codes-next-btn').disabled = state.codes.offset + PAGE_SIZE >= state.codes.total;
  }

  function renderEntitlements(data) {
    state.entitlements.items = data.items || [];
    state.entitlements.total = data.total || 0;
    const tbody = $('entitlements-tbody');
    clearChildren(tbody);

    if (!state.entitlements.items.length) {
      tbody.appendChild(
        el('tr', {}, [el('td', { className: 'empty-state', text: '暂无已兑换的设备权益', attrs: { colspan: 7 } })])
      );
    } else {
      for (const item of state.entitlements.items) {
        const ratio = item.totalSeconds > 0 ? item.remainingSeconds / item.totalSeconds : 0;
        const pct = Math.max(0, Math.min(100, Math.round(ratio * 100)));
        const meter = el('progress', {
          className: `meter meter-mini ${ratio <= 0 ? 'zero' : ratio <= 0.2 ? 'low' : ''}`,
          attrs: { max: 100, value: pct }
        });

        const buttons = [
          el('button', {
            type: 'button',
            className: 'btn btn-secondary btn-sm',
            text: '充值续期',
            onClick: () => openTopupModal(item)
          }),
          el('button', {
            type: 'button',
            className: 'btn btn-ghost btn-sm',
            text: '重置换绑',
            onClick: () => openResetRebindConfirm(item)
          })
        ];
        if (item.status !== 'REVOKED') {
          buttons.push(
            el('button', {
              type: 'button',
              className: 'btn btn-danger-outline btn-sm',
              text: '停用',
              onClick: () => openRevokeEntitlementConfirm(item)
            })
          );
        }

        tbody.appendChild(
          el('tr', {}, [
            el('td', {}, [
              el('div', { className: 'mono', text: item.ownerDeviceId || '--' }),
              el('span', { className: 'cell-sub mono', text: item.entitlementId, title: item.entitlementId })
            ]),
            el('td', {}, [statusPill(item.status)]),
            el('td', { className: 'tabular' }, [
              el('div', { text: `${formatNumber(item.remainingMinutes)} / ${formatNumber(item.totalMinutes)} 分` }),
              meter
            ]),
            el('td', { className: 'tabular', text: formatShortDate(item.expiresAtMs) }),
            el('td', { className: 'tabular' }, [
              el('div', { text: `限 ${item.maxMembers} 人 · ${item.maxConcurrentRooms} 房` }),
              el('span', { className: 'cell-sub', text: `单次 ${item.maxSessionMinutes} 分` })
            ]),
            el('td', { className: 'tabular' }, [
              el('div', { text: `7天换绑 ${item.rebindCount7d ?? 0}/2` }),
              el('span', { className: 'cell-sub', text: `活跃赞助房 ${item.activeSponsoredRoomsCount ?? 0}` })
            ]),
            el('td', { className: 'col-actions' }, [el('div', { className: 'row-actions' }, buttons)])
          ])
        );
      }
    }

    const page = Math.floor(state.entitlements.offset / PAGE_SIZE) + 1;
    const pages = Math.max(1, Math.ceil(state.entitlements.total / PAGE_SIZE));
    $('entitlements-page-info').textContent = `共 ${formatNumber(state.entitlements.total)} 条 · 第 ${page} / ${pages} 页`;
    $('entitlements-prev-btn').disabled = state.entitlements.offset <= 0;
    $('entitlements-next-btn').disabled = state.entitlements.offset + PAGE_SIZE >= state.entitlements.total;
  }

  function renderRooms(data) {
    state.rooms.items = data.items || [];
    state.rooms.total = data.total || 0;
    const tbody = $('rooms-tbody');
    clearChildren(tbody);
    const cloudRooms = state.rooms.items.filter((r) => (r.activeCloudMembers ?? 0) >= 2).length;
    const rtcMembers = state.rooms.items.reduce((sum, r) => sum + (r.activeCloudMembers ?? 0), 0);
    $('rooms-summary-text').textContent =
      `当前活跃 ${state.rooms.total} 房 · 正在计费 ${cloudRooms} 房 · 在线 RTC ${rtcMembers} 人`;

    if (!state.rooms.items.length) {
      tbody.appendChild(
        el('tr', {}, [el('td', { className: 'empty-state', text: '当前无在线房间', attrs: { colspan: 8 } })])
      );
      return;
    }

    for (const room of state.rooms.items) {
      tbody.appendChild(
        el('tr', {}, [
          el('td', {}, [
            el('div', { className: 'mono', text: room.roomId }),
            el('span', { className: 'cell-sub mono', text: `房主 ${room.ownerId || '--'}` })
          ]),
          el('td', {}, [
            el('span', { className: 'pill pill-neutral', text: room.mode }),
            document.createTextNode(' '),
            statusPill(room.activeTransport)
          ]),
          el('td', { className: 'tabular', text: `${room.admittedMembers} / ${room.maxMembers} 人` }),
          el('td', { className: 'tabular', text: `${room.activeCloudMembers} 人` }),
          el('td', { className: 'tabular', text: `${formatNumber(room.totalBilledMinutes)} 人·分 (${room.totalBilledSeconds}s)` }),
          el('td', { className: 'tabular', text: `${room.durationMinutes} 分钟` }),
          el('td', { className: 'mono', text: shortId(room.sponsorEntitlementId, 16), title: room.sponsorEntitlementId || '' }),
          el('td', { className: 'col-actions' }, [
            el('button', {
              type: 'button',
              className: 'btn btn-danger-outline btn-sm',
              text: '强制解散',
              onClick: () => openForceEndRoomConfirm(room)
            })
          ])
        ])
      );
    }
  }

  function renderAudit(data) {
    state.audit.items = data.items || [];
    state.audit.total = data.total || 0;
    const tbody = $('audit-tbody');
    clearChildren(tbody);

    if (!state.audit.items.length) {
      tbody.appendChild(
        el('tr', {}, [el('td', { className: 'empty-state', text: '暂无审计日志', attrs: { colspan: 5 } })])
      );
    } else {
      for (const item of state.audit.items) {
        const chips = el('div', { className: 'audit-chips' });
        const summary = item.payloadSummary && typeof item.payloadSummary === 'object' ? item.payloadSummary : {};
        const entries = Object.entries(summary).filter(([, v]) => v !== '' && v !== null && v !== undefined);
        if (!entries.length) {
          chips.appendChild(el('span', { className: 'cell-sub', text: '--' }));
        } else {
          for (const [k, v] of entries.slice(0, 8)) {
            const valText = typeof v === 'object' ? JSON.stringify(v) : String(v);
            chips.appendChild(el('span', { className: 'audit-chip', text: `${k}=${valText}` }));
          }
        }
        tbody.appendChild(
          el('tr', {}, [
            el('td', { className: 'tabular', text: formatTimeShanghai(item.createdAtMs) }),
            el('td', {}, [el('span', { className: 'pill pill-neutral', text: ACTION_LABELS[item.action] || item.action })]),
            el('td', {}, [
              el('div', { className: 'mono', text: shortId(item.targetId, 18), title: item.targetId }),
              el('span', { className: 'cell-sub', text: item.targetType })
            ]),
            el('td', { className: 'mono', text: item.actorIp || '--' }),
            el('td', {}, [chips])
          ])
        );
      }
    }

    const page = Math.floor(state.audit.offset / PAGE_SIZE) + 1;
    const pages = Math.max(1, Math.ceil(state.audit.total / PAGE_SIZE));
    $('audit-page-info').textContent = `共 ${formatNumber(state.audit.total)} 条 · 第 ${page} / ${pages} 页`;
    $('audit-prev-btn').disabled = state.audit.offset <= 0;
    $('audit-next-btn').disabled = state.audit.offset + PAGE_SIZE >= state.audit.total;
  }

  /* ---------------- Data Loaders ---------------- */

  async function loadOverview() {
    const data = await apiRequest('GET', '/admin/v1/overview');
    renderOverview(data);
  }

  async function loadCodes() {
    const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(state.codes.offset) });
    if (state.codes.batchId) params.set('batchId', state.codes.batchId);
    if (state.codes.status) params.set('status', state.codes.status);
    if (state.codes.codeMask) params.set('codeMask', state.codes.codeMask);
    const data = await apiRequest('GET', `/admin/v1/codes?${params.toString()}`);
    renderCodes(data);
  }

  async function loadEntitlements() {
    const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(state.entitlements.offset) });
    if (state.entitlements.status) params.set('status', state.entitlements.status);
    const data = await apiRequest('GET', `/admin/v1/entitlements?${params.toString()}`);
    renderEntitlements(data);
  }

  async function loadRooms() {
    const data = await apiRequest('GET', '/admin/v1/rooms/active');
    renderRooms(data);
  }

  async function loadAudit() {
    const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(state.audit.offset) });
    const data = await apiRequest('GET', `/admin/v1/audit-logs?${params.toString()}`);
    renderAudit(data);
  }

  async function refreshCurrentView({ silent = false } = {}) {
    if (!state.token || state.refreshing) return;
    state.refreshing = true;
    if (!silent) $('refresh-btn').disabled = true;
    try {
      const tasks = [loadOverview()];
      if (state.activeView === 'codes') tasks.push(loadCodes());
      else if (state.activeView === 'entitlements') tasks.push(loadEntitlements());
      else if (state.activeView === 'rooms') tasks.push(loadRooms());
      else if (state.activeView === 'audit') tasks.push(loadAudit());
      await Promise.all(tasks);
      $('sync-status').textContent = `上次同步 ${formatTimeShanghai(Date.now()).slice(11)}`;
    } catch (error) {
      if (!silent && error.status !== 401) {
        showToast(error.message || '刷新失败', 'error');
      }
    } finally {
      state.refreshing = false;
      $('refresh-btn').disabled = false;
    }
  }

  function clearRevealedCodesAndMaskDom() {
    state.revealGeneration += 1;
    if (state.revealedCodes.size === 0) {
      $('hide-all-revealed-btn').classList.add('hidden');
      return;
    }
    state.revealedCodes.clear();
    $('hide-all-revealed-btn').classList.add('hidden');
    if (state.codes.items.length > 0) {
      renderCodes({ items: state.codes.items, total: state.codes.total });
    } else {
      clearChildren($('codes-tbody'));
    }
  }

  function switchView(viewName) {
    if (!VIEW_TITLES[viewName]) return;
    if (state.activeView === 'codes' && viewName !== 'codes') {
      clearRevealedCodesAndMaskDom();
    }
    state.activeView = viewName;
    if (viewName === 'policy') state.policyDirty = false;
    $('view-title').textContent = VIEW_TITLES[viewName];
    for (const btn of document.querySelectorAll('.nav-item')) {
      btn.classList.toggle('active', btn.dataset.view === viewName);
    }
    for (const panel of document.querySelectorAll('.view-panel')) {
      panel.classList.toggle('hidden', panel.id !== `panel-${viewName}`);
    }
    refreshCurrentView();
  }

  /* ---------------- Modals ---------------- */

  function openModal(id) {
    $(id).classList.remove('hidden');
  }

  function closeModal(id) {
    $(id).classList.add('hidden');
    if (id === 'mint-modal') {
      state.lastMinted = null;
      clearChildren($('mint-result-list'));
    }
    if (id === 'topup-modal') {
      state.topupTarget = null;
    }
    if (id === 'confirm-modal') {
      state.confirmAction = null;
    }
  }

  function openMintModal() {
    state.lastMinted = null;
    state.mintIdempotencyKey = createIdempotencyKey('ui-mint');
    $('mint-form-stage').classList.remove('hidden');
    $('mint-result-stage').classList.add('hidden');
    $('mint-error').classList.add('hidden');
    openModal('mint-modal');
  }

  function applyMintPreset(presetKey) {
    const p = MINT_PRESETS[presetKey];
    if (!p) return;
    $('mint-type').value = p.type;
    $('mint-count').value = String(p.count);
    $('mint-minutes').value = String(p.minutes);
    $('mint-days').value = String(p.days);
    $('mint-max-members').value = String(p.maxMembers);
    $('mint-max-rooms').value = String(p.maxRooms);
    $('mint-max-session').value = String(p.maxSession);
    $('mint-note').value = p.note;
  }

  function renderMintResult(result) {
    state.lastMinted = result;
    $('mint-form-stage').classList.add('hidden');
    $('mint-result-stage').classList.remove('hidden');
    $('mint-result-meta').textContent =
      `批次 ${result.batchId} · 共 ${result.count} 张 · ${result.quotaMinutes} 人·分 · 限 ${result.maxMembers} 人 · ${result.validDays} 天`;
    const list = $('mint-result-list');
    clearChildren(list);
    for (const item of result.codes || []) {
      const codeVal = item.code || item.codeMask;
      list.appendChild(
        el('div', { className: 'minted-item' }, [
          el('div', {}, [
            el('div', { className: 'minted-code', text: codeVal }),
            el('span', { className: 'cell-sub mono', text: item.codeId })
          ]),
          el('button', {
            type: 'button',
            className: 'btn btn-secondary btn-sm',
            text: '复制',
            onClick: () => copyText(codeVal, `已复制 ${codeVal}`)
          })
        ])
      );
    }
  }

  function buildWechatTemplate(result) {
    const codesText = (result.codes || []).map((c, i) => `${String(i + 1).padStart(2, '0')}. ${c.code}`).join('\n');
    return [
      `【MotoCom 云端对讲内测激活码 · 批次 ${result.batchId}】`,
      `每码额度 ${result.quotaMinutes} 人·分钟 · 限 ${result.maxMembers} 人 · 激活后 ${result.validDays} 天有效`,
      '使用方法：仅房主在 App 设置或建房入口输入激活码即可创建网络/双模房间，队员输入 6 位邀请码直接免码加入。',
      '',
      codesText
    ].join('\n');
  }

  function buildSingleWechatMessage(item, plaintextCode) {
    return [
      `MotoCom 云端对讲内测激活码：${plaintextCode}`,
      `规格：${item.quotaMinutes} 人·分钟 · 限 ${item.maxMembers} 人 · 激活后 ${item.validDays} 天有效`,
      '使用方法：仅房主在 App 设置或建房入口输入激活码即可建房，队员输入 6 位邀请码直接免码加入。'
    ].join('\n');
  }

  async function revealCodeItem(item, triggerBtn) {
    const requestGen = state.revealGeneration;
    if (triggerBtn) {
      triggerBtn.disabled = true;
      triggerBtn.textContent = '解密中...';
    }
    try {
      const res = await apiRequest('POST', `/admin/v1/codes/${encodeURIComponent(item.codeId)}/reveal`, { body: {} });
      if (requestGen !== state.revealGeneration || !state.token || state.activeView !== 'codes') {
        return;
      }
      if (res.code) {
        state.revealedCodes.set(item.codeId, res.code);
        renderCodes({ items: state.codes.items, total: state.codes.total });
      }
    } catch (error) {
      if (error.status !== 401) {
        showToast(error.message || '查看激活码失败', 'error');
      }
      if (triggerBtn && requestGen === state.revealGeneration) {
        triggerBtn.disabled = false;
        triggerBtn.textContent = '查看';
      }
    }
  }

  function downloadMintedCodes(result) {
    const text = buildWechatTemplate(result) + '\n';
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `motocom-codes-${result.batchId || 'batch'}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('已导出激活码文本文件', 'ok');
  }

  function openTopupModal(ent) {
    state.topupTarget = ent;
    state.topupIdempotencyKey = createIdempotencyKey('ui-topup');
    $('topup-target-info').textContent =
      `设备 ${ent.ownerDeviceId} · 当前剩余 ${formatNumber(ent.remainingMinutes)} 人·分 · 上限 ${ent.maxMembers} 人`;
    $('topup-minutes').value = '300';
    $('topup-days').value = '30';
    $('topup-max-members').value = '';
    $('topup-reason').value = '';
    $('topup-error').classList.add('hidden');
    openModal('topup-modal');
  }

  function openConfirmDialog({ title, desc, buttonText, showExtra = false, reasonLabel = '原因说明', onConfirm }) {
    $('confirm-modal-title').textContent = title;
    $('confirm-modal-desc').textContent = desc;
    $('confirm-reason-label').textContent = reasonLabel;
    $('confirm-reason-input').value = '';
    $('confirm-extra-wrap').classList.toggle('hidden', !showExtra);
    $('confirm-extra-input').value = '管理员已结束该云端房间';
    $('confirm-error').classList.add('hidden');
    $('confirm-submit-btn').textContent = buttonText;
    state.confirmAction = onConfirm;
    openModal('confirm-modal');
  }

  function openRevokeCodeConfirm(codeItem) {
    openConfirmDialog({
      title: '吊销激活码',
      desc: `确认吊销 ${codeItem.codeMask}（${codeItem.codeId}）？吊销后关联额度将立即作废，若房主剩余额度归零将同步终止在线房间。`,
      buttonText: '确认吊销',
      onConfirm: async (reason) => {
        const res = await apiRequest('POST', `/admin/v1/codes/${encodeURIComponent(codeItem.codeId)}/revoke`, {
          body: { reason }
        });
        const endedCount = (res.terminatedRoomIds || []).length;
        showToast(endedCount > 0 ? `已吊销激活码并终止 ${endedCount} 个房间` : '已吊销激活码', 'ok');
      }
    });
  }

  function openResetRebindConfirm(ent) {
    openConfirmDialog({
      title: '重置 7 天换绑次数',
      desc: `确认将设备 ${ent.ownerDeviceId}（${shortId(ent.entitlementId, 16)}）的近 7 天换绑计数归零？`,
      buttonText: '确认重置',
      onConfirm: async (reason) => {
        await apiRequest('POST', `/admin/v1/entitlements/${encodeURIComponent(ent.entitlementId)}/reset-rebind`, {
          body: { reason }
        });
        showToast('已重置换绑次数', 'ok');
      }
    });
  }

  function openRevokeEntitlementConfirm(ent) {
    openConfirmDialog({
      title: '停用设备权益',
      desc: `确认永久停用 ${ent.ownerDeviceId} 的权益？该操作会作废该设备凭证并立即通过 BanRoomUser 结束其赞助的云端房间。`,
      buttonText: '确认停用',
      onConfirm: async (reason) => {
        const res = await apiRequest('POST', `/admin/v1/entitlements/${encodeURIComponent(ent.entitlementId)}/revoke`, {
          body: { reason }
        });
        const endedCount = (res.terminatedRoomIds || []).length;
        showToast(endedCount > 0 ? `已停用权益并终止 ${endedCount} 个房间` : '已停用设备权益', 'ok');
      }
    });
  }

  function openForceEndRoomConfirm(room) {
    openConfirmDialog({
      title: '强制解散云端房间',
      desc: `确认立即结束房间 ${room.roomId}（${room.mode} · 当前 ${room.admittedMembers} 人在线）？服务端将同步调用 BanRoomUser 断流。`,
      buttonText: '立即解散',
      showExtra: true,
      reasonLabel: '解散原因标识',
      onConfirm: async (reason, message) => {
        await apiRequest('POST', `/admin/v1/rooms/${encodeURIComponent(room.roomId)}/force-end`, {
          body: {
            reason: reason || 'ADMIN_ENDED',
            message: message || '管理员已结束该云端房间'
          }
        });
        showToast(`房间 ${room.roomId} 已强制解散`, 'ok');
      }
    });
  }

  /* ---------------- Event Binding ---------------- */

  async function handleLogin(event) {
    event.preventDefault();
    const rawToken = $('token-input').value.trim();
    const errBox = $('login-error');
    errBox.classList.add('hidden');
    if (!rawToken) {
      errBox.textContent = '请输入管理员 Token';
      errBox.classList.remove('hidden');
      return;
    }
    const btn = $('login-submit-btn');
    btn.disabled = true;
    btn.textContent = '验证中...';
    try {
      const overview = await apiRequest('GET', '/admin/v1/overview', { overrideToken: rawToken });
      state.token = rawToken;
      if ($('remember-session-checkbox').checked) {
        try {
          window.sessionStorage.setItem(SESSION_TOKEN_KEY, rawToken);
        } catch {
          // ignore
        }
      }
      $('token-input').value = '';
      $('login-view').classList.add('hidden');
      $('app-view').classList.remove('hidden');
      renderOverview(overview);
      $('sync-status').textContent = `上次同步 ${formatTimeShanghai(Date.now()).slice(11)}`;
      startAutoRefresh();
    } catch (error) {
      errBox.textContent = error.message || '认证失败，请检查 adminToken';
      errBox.classList.remove('hidden');
    } finally {
      btn.disabled = false;
      btn.textContent = '验证并进入控制台';
    }
  }

  function bindEvents() {
    $('login-form').addEventListener('submit', handleLogin);
    $('toggle-token-btn').addEventListener('click', () => {
      const input = $('token-input');
      const showing = input.type === 'text';
      input.type = showing ? 'password' : 'text';
      $('toggle-token-btn').textContent = showing ? '显示' : '隐藏';
    });
    $('login-theme-btn').addEventListener('click', toggleTheme);
    $('theme-toggle-btn').addEventListener('click', toggleTheme);
    $('logout-btn').addEventListener('click', () => {
      if (state.token === PORTAL_SSO_TOKEN) {
        stopAutoRefresh();
        state.token = '';
        clearSensitiveDom();
        window.location.replace(`${PORTAL_ORIGIN}/logout`);
        return;
      }
      lockSession();
    });
    $('refresh-btn').addEventListener('click', () => refreshCurrentView());
    $('rooms-refresh-btn').addEventListener('click', () => refreshCurrentView());
    $('open-mint-btn').addEventListener('click', openMintModal);

    $('auto-refresh-toggle').addEventListener('change', () => {
      if ($('auto-refresh-toggle').checked) startAutoRefresh();
      else stopAutoRefresh();
    });

    for (const btn of document.querySelectorAll('.nav-item')) {
      btn.addEventListener('click', () => switchView(btn.dataset.view));
    }
    for (const btn of document.querySelectorAll('[data-jump]')) {
      btn.addEventListener('click', () => switchView(btn.dataset.jump));
    }

    // Rank switch
    $('rank-tab-today').addEventListener('click', () => {
      state.rankMode = 'today';
      $('rank-tab-today').classList.add('active');
      $('rank-tab-month').classList.remove('active');
      renderTopConsumers();
    });
    $('rank-tab-month').addEventListener('click', () => {
      state.rankMode = 'month';
      $('rank-tab-month').classList.add('active');
      $('rank-tab-today').classList.remove('active');
      renderTopConsumers();
    });

    // Codes filters & pagination
    $('codes-filter-form').addEventListener('submit', (e) => {
      e.preventDefault();
      clearRevealedCodesAndMaskDom();
      state.codes.batchId = $('filter-batch').value.trim();
      state.codes.status = $('filter-code-status').value;
      state.codes.codeMask = $('filter-mask').value.trim();
      state.codes.offset = 0;
      loadCodes().catch((err) => showToast(err.message, 'error'));
    });
    $('filter-codes-reset').addEventListener('click', () => {
      clearRevealedCodesAndMaskDom();
      $('filter-batch').value = '';
      $('filter-code-status').value = '';
      $('filter-mask').value = '';
      state.codes.batchId = '';
      state.codes.status = '';
      state.codes.codeMask = '';
      state.codes.offset = 0;
      loadCodes().catch((err) => showToast(err.message, 'error'));
    });
    $('hide-all-revealed-btn').addEventListener('click', () => {
      clearRevealedCodesAndMaskDom();
    });
    $('codes-prev-btn').addEventListener('click', () => {
      clearRevealedCodesAndMaskDom();
      state.codes.offset = Math.max(0, state.codes.offset - PAGE_SIZE);
      loadCodes().catch((err) => showToast(err.message, 'error'));
    });
    $('codes-next-btn').addEventListener('click', () => {
      if (state.codes.offset + PAGE_SIZE < state.codes.total) {
        clearRevealedCodesAndMaskDom();
        state.codes.offset += PAGE_SIZE;
        loadCodes().catch((err) => showToast(err.message, 'error'));
      }
    });

    // Entitlements filter & pagination
    $('entitlements-filter-form').addEventListener('submit', (e) => {
      e.preventDefault();
      state.entitlements.status = $('filter-ent-status').value;
      state.entitlements.offset = 0;
      loadEntitlements().catch((err) => showToast(err.message, 'error'));
    });
    $('entitlements-prev-btn').addEventListener('click', () => {
      state.entitlements.offset = Math.max(0, state.entitlements.offset - PAGE_SIZE);
      loadEntitlements().catch((err) => showToast(err.message, 'error'));
    });
    $('entitlements-next-btn').addEventListener('click', () => {
      if (state.entitlements.offset + PAGE_SIZE < state.entitlements.total) {
        state.entitlements.offset += PAGE_SIZE;
        loadEntitlements().catch((err) => showToast(err.message, 'error'));
      }
    });

    // Audit pagination
    $('audit-prev-btn').addEventListener('click', () => {
      state.audit.offset = Math.max(0, state.audit.offset - PAGE_SIZE);
      loadAudit().catch((err) => showToast(err.message, 'error'));
    });
    $('audit-next-btn').addEventListener('click', () => {
      if (state.audit.offset + PAGE_SIZE < state.audit.total) {
        state.audit.offset += PAGE_SIZE;
        loadAudit().catch((err) => showToast(err.message, 'error'));
      }
    });

    // Policy form
    const markPolicyDirty = () => {
      state.policyDirty = true;
    };
    $('policy-form').addEventListener('input', markPolicyDirty);
    $('policy-form').addEventListener('change', markPolicyDirty);
    $('policy-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const hardLimitMinutes = Number($('policy-hard-limit').value);
      const warnPct = Number($('policy-warn-pct').value);
      const requireCodeForJoin = $('policy-require-join').value === 'true';
      try {
        await apiRequest('PUT', '/admin/v1/config/global-quota', {
          body: {
            hardLimitMinutes,
            warnThresholdRatio: Math.min(1, Math.max(0.1, warnPct / 100)),
            requireCodeForJoin
          }
        });
        state.policyDirty = false;
        showToast('配额与入群策略已更新', 'ok');
        await refreshCurrentView();
      } catch (error) {
        showToast(error.message || '保存策略失败', 'error');
      }
    });

    // Emergency breaker
    $('emergency-stop-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      if ($('emergency-confirm-input').value.trim().toUpperCase() !== 'STOP') {
        showToast('请先准确输入 STOP 确认执行全局熔断', 'error');
        return;
      }
      try {
        const res = await apiRequest('PUT', '/admin/v1/config/global-quota', {
          body: { hardLimitMinutes: 0 }
        });
        $('emergency-confirm-input').value = '';
        const ended = (res.terminatedRoomIds || []).length;
        showToast(ended > 0 ? `已全局熔断并终止 ${ended} 个在线房间` : '已触发全局熔断（当月上限清零）', 'ok');
        await refreshCurrentView();
      } catch (error) {
        showToast(error.message || '熔断执行失败', 'error');
      }
    });

    $('emergency-restore-btn').addEventListener('click', async () => {
      try {
        await apiRequest('PUT', '/admin/v1/config/global-quota', {
          body: { hardLimitMinutes: 8500 }
        });
        state.policyDirty = false;
        showToast('已恢复本月全局配额至 8,500 人·分钟', 'ok');
        await refreshCurrentView();
      } catch (error) {
        showToast(error.message || '恢复配额失败', 'error');
      }
    });

    // Mint modal presets & submit
    const resetMintIdem = () => {
      state.mintIdempotencyKey = createIdempotencyKey('ui-mint');
    };
    $('mint-form').addEventListener('input', resetMintIdem);
    $('mint-form').addEventListener('change', resetMintIdem);
    for (const chip of document.querySelectorAll('.preset-chip')) {
      chip.addEventListener('click', () => {
        applyMintPreset(chip.dataset.preset);
        resetMintIdem();
      });
    }
    $('mint-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const errBox = $('mint-error');
      errBox.classList.add('hidden');
      const submitBtn = $('mint-submit-btn');
      submitBtn.disabled = true;
      submitBtn.textContent = '签发中...';
      try {
        const payload = {
          codeType: $('mint-type').value,
          count: Number($('mint-count').value),
          quotaMinutes: Number($('mint-minutes').value),
          validDays: Number($('mint-days').value),
          maxMembers: Number($('mint-max-members').value),
          maxConcurrentRooms: Number($('mint-max-rooms').value),
          maxSessionMinutes: Number($('mint-max-session').value),
          batchId: $('mint-batch').value.trim(),
          note: $('mint-note').value.trim()
        };
        const res = await apiRequest('POST', '/admin/v1/codes/batch', {
          body: payload,
          idempotencyKey: state.mintIdempotencyKey
        });
        renderMintResult(res);
        state.mintIdempotencyKey = createIdempotencyKey('ui-mint');
        showToast(`已成功签发 ${res.count} 张激活码`, 'ok');
        refreshCurrentView({ silent: true });
      } catch (error) {
        errBox.textContent = error.message || '签发失败';
        errBox.classList.remove('hidden');
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = '确认签发';
      }
    });
    $('mint-again-btn').addEventListener('click', () => {
      state.lastMinted = null;
      state.mintIdempotencyKey = createIdempotencyKey('ui-mint');
      clearChildren($('mint-result-list'));
      $('mint-result-stage').classList.add('hidden');
      $('mint-form-stage').classList.remove('hidden');
    });
    $('copy-all-codes-btn').addEventListener('click', () => {
      if (!state.lastMinted) return;
      const text = (state.lastMinted.codes || []).map((c) => c.code).join('\n');
      copyText(text, '已复制全部激活码');
    });
    $('copy-wechat-tpl-btn').addEventListener('click', () => {
      if (!state.lastMinted) return;
      copyText(buildWechatTemplate(state.lastMinted), '已复制微信群发放文案');
    });
    $('download-codes-btn').addEventListener('click', () => {
      if (state.lastMinted) downloadMintedCodes(state.lastMinted);
    });

    // Topup modal submit
    const resetTopupIdem = () => {
      state.topupIdempotencyKey = createIdempotencyKey('ui-topup');
    };
    $('topup-form').addEventListener('input', resetTopupIdem);
    $('topup-form').addEventListener('change', resetTopupIdem);
    $('topup-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!state.topupTarget) return;
      const errBox = $('topup-error');
      errBox.classList.add('hidden');
      const addMinutes = Number($('topup-minutes').value);
      const extendDays = Number($('topup-days').value);
      if (addMinutes === 0 && extendDays === 0) {
        errBox.textContent = '追加分钟数与顺延天数不能同时为 0';
        errBox.classList.remove('hidden');
        return;
      }
      const body = {
        addMinutes,
        extendDays,
        reason: $('topup-reason').value.trim()
      };
      const maxMembersRaw = $('topup-max-members').value.trim();
      if (maxMembersRaw) body.maxMembers = Number(maxMembersRaw);

      const submitBtn = $('topup-submit-btn');
      submitBtn.disabled = true;
      try {
        await apiRequest('POST', `/admin/v1/entitlements/${encodeURIComponent(state.topupTarget.entitlementId)}/topup`, {
          body,
          idempotencyKey: state.topupIdempotencyKey
        });
        closeModal('topup-modal');
        showToast('权益充值续期已生效', 'ok');
        await refreshCurrentView();
      } catch (error) {
        errBox.textContent = error.message || '充值失败';
        errBox.classList.remove('hidden');
      } finally {
        submitBtn.disabled = false;
      }
    });

    // Generic confirm modal submit
    $('confirm-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!state.confirmAction) return;
      const errBox = $('confirm-error');
      errBox.classList.add('hidden');
      const btn = $('confirm-submit-btn');
      btn.disabled = true;
      try {
        await state.confirmAction($('confirm-reason-input').value.trim(), $('confirm-extra-input').value.trim());
        closeModal('confirm-modal');
        await refreshCurrentView();
      } catch (error) {
        errBox.textContent = error.message || '操作失败';
        errBox.classList.remove('hidden');
      } finally {
        btn.disabled = false;
      }
    });

    // Close buttons & ESC
    for (const btn of document.querySelectorAll('[data-close-modal]')) {
      btn.addEventListener('click', () => closeModal(btn.dataset.closeModal));
    }
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      for (const id of ['confirm-modal', 'topup-modal', 'mint-modal']) {
        if (!$(id).classList.contains('hidden')) {
          closeModal(id);
          break;
        }
      }
    });
  }

  async function bootstrap() {
    let savedTheme = 'light';
    try {
      savedTheme = window.localStorage.getItem(THEME_STORAGE_KEY) || 'light';
    } catch {
      // ignore
    }
    applyTheme(savedTheme);
    bindEvents();

    if (isPortalManagedHost()) {
      // 统一入口由网关注入真实管理令牌，浏览器仅携带占位值。
      try {
        const overview = await apiRequest('GET', '/admin/v1/overview', {
          overrideToken: PORTAL_SSO_TOKEN
        });
        state.token = PORTAL_SSO_TOKEN;
        $('logout-btn').textContent = '退出统一入口';
        $('login-view').classList.add('hidden');
        $('app-view').classList.remove('hidden');
        renderOverview(overview);
        $('sync-status').textContent = `上次同步 ${formatTimeShanghai(Date.now()).slice(11)}`;
        startAutoRefresh();
        return;
      } catch {
        // 保留 adminToken 登录，作为未接入统一入口时的兜底。
      }
    }

    let sessionToken = '';
    try {
      sessionToken = window.sessionStorage.getItem(SESSION_TOKEN_KEY) || '';
    } catch {
      // ignore
    }
    if (sessionToken) {
      try {
        const overview = await apiRequest('GET', '/admin/v1/overview', { overrideToken: sessionToken });
        state.token = sessionToken;
        $('login-view').classList.add('hidden');
        $('app-view').classList.remove('hidden');
        renderOverview(overview);
        $('sync-status').textContent = `上次同步 ${formatTimeShanghai(Date.now()).slice(11)}`;
        startAutoRefresh();
        return;
      } catch {
        try {
          window.sessionStorage.removeItem(SESSION_TOKEN_KEY);
        } catch {
          // ignore
        }
      }
    }
    $('login-view').classList.remove('hidden');
    $('app-view').classList.add('hidden');
  }

  bootstrap();
})();
