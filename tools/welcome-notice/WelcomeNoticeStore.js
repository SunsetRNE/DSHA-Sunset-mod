// DSHA_WELCOME_STORE_RC2_V1：保留原有 ConfigForm/宿主事务及配置协调。
var WelcomeNoticeStore = class {
  scope;
  store = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)({
    status: "idle", acknowledged: false, sessionOnly: false, error: null,
    mode: "host", request: 0, startedAt: null, detached: false
  });
  saving = false;
  request = 0;
  detached = false;
  sessionOnly = false;
  following;
  constructor(scope) { this.scope = scope; }
  load() {
    this.following ??= this.scope.subscribe(() => { this.derive(); });
    this.derive();
    return Promise.resolve();
  }
  async acknowledge() {
    if (this.saving || this.sessionOnly) return false;
    const request = ++this.request;
    this.detached = false;
    this.saving = true;
    this.store.update((state) => {
      state.status = "saving";
      state.error = null;
      state.request = request;
      state.startedAt = Date.now();
      state.detached = false;
    });
    try {
      const accepted = await this.scope.set(WELCOME_NOTICE_ACK_FIELD, WELCOME_NOTICE_VERSION);
      if (request !== this.request) return false;
      this.saving = false;
      this.derive();
      const { acknowledged } = this.store.getSnapshot();
      if (!acknowledged) this.reportError(accepted === false ? "save-refused" : "save-not-persisted");
      return acknowledged;
    } catch (_error) {
      if (request === this.request) this.reportError("save-exception");
      return false;
    } finally {
      // 晚到结果无权覆盖新请求或用户已经选择的退出方式。
      if (request === this.request) this.saving = false;
    }
  }
  reportError(code) {
    this.saving = false;
    // 旧 scope 通知不能静默覆盖错误或用户选择；重试时才重新接管。
    this.detached = true;
    this.store.update((state) => {
      state.status = "error";
      state.acknowledged = false;
      state.error = code;
      state.detached = true;
    });
  }
  returnToNotice() {
    ++this.request;
    this.reportError("wait-left-host-request-not-cancelled");
  }
  continueForSession() {
    ++this.request;
    this.saving = false;
    this.detached = true;
    this.sessionOnly = true;
    this.store.update((state) => {
      state.status = "ready";
      state.acknowledged = false;
      state.sessionOnly = true;
      state.error = null;
      state.detached = true;
    });
  }
  dispose() {
    ++this.request;
    if (this.saving) this.reportError("disposed-host-request-not-cancelled");
    this.saving = false;
    this.detached = true;
    this.following?.();
    this.following = void 0;
  }
  derive() {
    if (this.saving || this.detached || this.sessionOnly) return;
    const scope = this.scope.getSnapshot();
    this.store.update((state) => {
      // 0.2.1 binds this notice to the durable Host section. A session-only
      // choice remains explicit below and never fabricates persisted state.
      state.mode = "host";
      state.sessionOnly = false;
      state.acknowledged = false;
      state.error = null;
      switch (scope.status) {
        case "loading": state.status = "loading"; break;
        case "unavailable":
          state.status = "error";
          state.error = "settings-unavailable";
          break;
        case "ready":
          state.status = "ready";
          state.acknowledged = scope.value?.[WELCOME_NOTICE_ACK_FIELD] === WELCOME_NOTICE_VERSION;
          break;
        default: return assertNever(scope.status);
      }
    });
  }
};
