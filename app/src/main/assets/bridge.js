/* ============================================================
 * DeepBridge 注入桥接脚本 v3（纯网页层操作）
 * 运行于 chat.deepseek.com 页面上下文（WebView 注入）
 *
 * 设计原则：不直接调用底层 API、不自行创建会话。
 * 一切通过网页层完成：找输入框 -> （附件则注入 <input type=file>）-> 填入 prompt -> 点击发送按钮。
 * 模式选择（DeepThink、Search 等）由用户手动点击；DeepSeek 已统一三模，附件由同一输入区承载。
 *
 * 职责：
 *  1. 钩住 XMLHttpRequest / fetch：
 *     - 截获页面自身的 /api/v0/chat/completion SSE 流 -> 解析回复（只取 RESPONSE，思考过滤）
 *       -> 撤回检测（TEMPLATE_RESPONSE / CONTENT_FILTER，保留真实内容）
 *     - 监测 /api/v0/file/upload_file 与 fetch_files，用于判断附件是否上传/解析完成
 *  2. window.DSKB 能力（供原生侧调用）：
 *     - send(obj)       填入 prompt 并点击发送（等待页面空闲后执行）
 *     - attachFile(obj) 把 base64 文件注入官网文件输入框，等官网自行上传+解析+挂载
 *     - newChat()       点击「New chat」新建对话（会话轮换用）
 *     - probe()         上报页面状态
 *  3. 事件回传 window.DSB.onEvent(json)：
 *     - {type:'reply'|'attach'|'newChat'|'probe'|'boot'|'pageReply'|...}
 * ============================================================ */
(function () {
  if (window.__DSKB_LOADED) {
    try { window.DSB.onEvent(JSON.stringify({ type: 'boot', already: true, url: location.href })); } catch (e) {}
    return;
  }
  window.__DSKB_LOADED = true;

  var CONTENT_FILTER = 'CONTENT_FILTER';
  var RECALL_TIP = '⚠️ 此回复已被撤回，以下为本地缓存内容';

  // DOM 兜底模式下的等待关联：sessionId -> reqId（__next 为通配）
  var domWait = {};

  /* ============ 附件上传网络监测（XHR + fetch） ============ */
  // 只记录时间戳/计数，供 attachFile 判定「该附件已被官网上传并解析」
  var uploads = { active: 0, lastOkAt: 0, lastFail: '', parsedAt: 0, failAt: 0 };

  function isFileUploadUrl(u) { return u && u.indexOf('/api/v0/file/upload_file') !== -1; }
  function isFileFetchUrl(u) { return u && u.indexOf('/api/v0/file/fetch_files') !== -1; }
  function isAnyFileUrl(u) { return u && u.indexOf('/api/v0/file/') !== -1; }

  function noteFileXhr(xhr, url) {
    var up = isFileUploadUrl(url), ff = isFileFetchUrl(url);
    if (!up && !ff) return;
    if (up) uploads.active++;
    xhr.addEventListener('load', function () {
      try {
        if (up) {
          uploads.active = Math.max(0, uploads.active - 1);
          if (xhr.status >= 200 && xhr.status < 300) uploads.lastOkAt = Date.now();
          else { uploads.failAt = Date.now(); uploads.lastFail = 'HTTP ' + xhr.status; }
        }
        if (ff && xhr.status >= 200 && xhr.status < 300) {
          var t = xhr.responseText || '';
          if (t.indexOf('SUCCESS') !== -1) uploads.parsedAt = Date.now();
        }
      } catch (e) {}
    });
    xhr.addEventListener('error', function () {
      if (up) { uploads.active = Math.max(0, uploads.active - 1); uploads.failAt = Date.now(); uploads.lastFail = 'network'; }
    });
  }

  // fetch 钩子（DeepSeek 上传可能走 fetch）
  if (window.fetch) {
    var _origFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : ((input && input.url) || '');
      var up = isFileUploadUrl(url), ff = isFileFetchUrl(url);
      if (up) uploads.active++;
      var p = _origFetch(input, init);
      if (up || ff) {
        p.then(function (resp) {
          if (up) {
            uploads.active = Math.max(0, uploads.active - 1);
            if (resp.ok) uploads.lastOkAt = Date.now();
            else { uploads.failAt = Date.now(); uploads.lastFail = 'HTTP ' + resp.status; }
          }
          if (ff && resp.ok) {
            try {
              resp.clone().json().then(function (j) {
                if (JSON.stringify(j).indexOf('SUCCESS') !== -1) uploads.parsedAt = Date.now();
              }).catch(function () {});
            } catch (e) {}
          }
          return resp;
        }).catch(function (e) {
          if (up) { uploads.active = Math.max(0, uploads.active - 1); uploads.failAt = Date.now(); uploads.lastFail = '' + e; }
        });
      }
      return p;
    };
  }

  function log(m) { try { console.log('[DSKB] ' + m); } catch (e) {} }
  function emit(obj) {
    obj.t = Date.now();
    try { window.DSB.onEvent(JSON.stringify(obj)); } catch (e) { log('emit fail: ' + e.message); }
  }

  /* ============ SSE 状态机（回复解析 + 防撤回核心） ============ */

  function _parseKey(key, container) {
    if (Array.isArray(container) && /^[-+]?\d+$/.test(key)) {
      var i = parseInt(key, 10);
      return i < 0 ? container.length + i : i;
    }
    return key;
  }

  function _setValueByPath(obj, path, value, isAppend) {
    var keys = path.split('/'), current = obj;
    for (var i = 0; i < keys.length - 1; i++) {
      var key = _parseKey(keys[i], current);
      if (!(key in current)) current[key] = typeof _parseKey(keys[i + 1], current) === 'number' ? [] : {};
      current = current[key];
    }
    var lastKey = _parseKey(keys[keys.length - 1], current);
    if (isAppend) {
      if (Array.isArray(current[lastKey])) current[lastKey] = current[lastKey].concat(value);
      else current[lastKey] = (current[lastKey] || '') + value;
    } else {
      current[lastKey] = value;
    }
    return obj;
  }

  function DSState() {
    this.fields = {};
    this.sessId = '';
    this.recalled = false;
    this.recalledContent = '';
    this._updatePath = '';
    this._updateMode = 'SET';
  }

  DSState.prototype.preCheck = function (data) {
    // 在 setField 之前执行：此时 fragments 仍是真实内容（防撤回关键时机）
    var path = data.p !== undefined ? data.p : this._updatePath;
    var mode = data.o !== undefined ? data.o : this._updateMode;
    if (mode === 'BATCH' && path === 'response' && Array.isArray(data.v)) {
      for (var i = 0; i < data.v.length; i++) {
        var v = data.v[i];
        if (v && v.p === 'fragments' && v.v && v.v.length > 0 && v.v[0].type === 'TEMPLATE_RESPONSE') {
          this.recalledContent = this.extractContent();
          this.recalled = true;
        }
        if (v && v.p === 'status' && v.v === CONTENT_FILTER) {
          this.recalled = true;
        }
      }
    }
  };

  DSState.prototype.update = function (data) {
    this.preCheck(data);
    if (data.p !== undefined) this._updatePath = data.p;
    if (data.o !== undefined) this._updateMode = data.o;
    var value = data.v;
    if (typeof value === 'object' && value !== null && this._updatePath === '') {
      for (var key in value) { if (value.hasOwnProperty(key)) this.fields[key] = value[key]; }
      return;
    }
    this.setField(this._updatePath, value, this._updateMode);
  };

  DSState.prototype.setField = function (path, value, mode) {
    if (mode === 'BATCH') {
      for (var i = 0; i < value.length; i++) {
        var v = value[i];
        this.setField(path + '/' + v.p, v.v, v.o || 'SET');
      }
    } else if (mode === 'SET') {
      _setValueByPath(this.fields, path, value, false);
    } else if (mode === 'APPEND') {
      _setValueByPath(this.fields, path, value, true);
    }
  };

  DSState.prototype.fragments = function () {
    try { return (this.fields.response && this.fields.response.fragments) || []; } catch (e) { return []; }
  };

  DSState.prototype.extractContent = function (frags) {
    var list = frags || this.fragments();
    var content = '';
    for (var i = 0; i < list.length; i++) {
      // 只取 RESPONSE 片段：思考(THINKING)内容自动被过滤
      if (list[i] && list[i].type === 'RESPONSE' && list[i].content) content += list[i].content;
    }
    return content;
  };

  DSState.prototype.content = function () {
    if (this.recalled && this.recalledContent) return this.recalledContent; // 撤回：返回缓存的真实内容
    return this.extractContent();
  };

  DSState.prototype.messageId = function () {
    try { return (this.fields.response && this.fields.response.message_id) || ''; } catch (e) { return ''; }
  };

  function feedSSE(state, rawText, lastLen) {
    if (!rawText || rawText.length <= lastLen) return lastLen;
    var newPart = rawText.substring(lastLen);
    var lines = newPart.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line || line.indexOf('data:') !== 0) continue;
      try {
        var data = JSON.parse(line.replace(/^data:\s*/, ''));
        if (data && data.v !== undefined) state.update(data);
      } catch (e) { /* 跳过非 JSON 行 */ }
    }
    return rawText.length;
  }

  /* ============ XHR 钩子：截取 completion 回复 + 监测文件上传 ============ */

  function isCompletionUrl(url) {
    return url.indexOf('/api/v0/chat/completion') !== -1 ||
           url.indexOf('/api/v0/chat/edit_message') !== -1 ||
           url.indexOf('/api/v0/chat/regenerate') !== -1 ||
           url.indexOf('/api/v0/chat/continue') !== -1 ||
           url.indexOf('/api/v0/chat/resume_stream') !== -1;
  }

  var _origOpen = XMLHttpRequest.prototype.open;
  var _origSend = XMLHttpRequest.prototype.send;
  var _respTextDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
  var _origRespTextGetter = _respTextDesc ? _respTextDesc.get : null;

  XMLHttpRequest.prototype.open = function (method, url) {
    this._dskb_url = (url || '') + '';
    return _origOpen.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function (body) {
    var xhr = this;
    var url = xhr._dskb_url || '';
    // 文件上传/解析监测
    if (isAnyFileUrl(url)) {
      try { noteFileXhr(xhr, url); } catch (e) {}
    }
    // 回复流拦截
    if (isCompletionUrl(url) && _origRespTextGetter) {
      try { hookPageCompletion(xhr, body); } catch (e) { log('hook fail: ' + e.message); }
    }
    return _origSend.apply(this, arguments);
  };

  function hookPageCompletion(xhr, body) {
    var sessId = '';
    try { if (body) sessId = JSON.parse(body).chat_session_id || ''; } catch (e) {}
    var state = new DSState();
    state.sessId = sessId;
    var lastLen = 0;
    var recallHandled = false;
    var patchedText = null;

    Object.defineProperty(xhr, 'responseText', {
      get: function () {
        var raw = _origRespTextGetter.call(xhr);
        if (!raw) return raw;
        lastLen = feedSSE(state, raw, lastLen);
        // 页面内可视防撤回：用缓存内容替换被撤回的 SSE 片段
        if (state.recalled && !recallHandled && state.recalledContent) {
          recallHandled = true;
          try {
            var m = raw.match(/data:\s*(\{[^\n]*"fragments"[^\n]*TEMPLATE_RESPONSE[^\n]*\})/);
            if (m) {
              patchedText = raw.replace(m[1], 'data: ' + JSON.stringify({
                v: [{ v: [{ id: 1, type: 'TIP', style: 'WARNING', content: RECALL_TIP }], p: 'fragments', o: 'APPEND' }],
                p: 'response', o: 'BATCH'
              }));
            }
          } catch (e) {}
          if (!patchedText) patchedText = raw;
        }
        return (state.recalled && patchedText) ? patchedText : raw;
      },
      configurable: true,
      enumerable: true
    });

    // 即使页面不主动读 responseText（fetch 流式渲染），也驱动状态机解析
    xhr.addEventListener('progress', function () {
      try { void xhr.responseText; } catch (e) {}
    });

    xhr.addEventListener('load', function () {
      try {
        // 最终兜底：把全部 SSE 喂完
        try { lastLen = feedSSE(state, _origRespTextGetter.call(xhr) || '', lastLen); } catch (e) {}
        var content = state.content();
        // 关联等待中的桥请求：优先精确会话，其次通配 __next
        var reqId = (sessId && domWait[sessId]) || domWait.__next || null;
        if (reqId) {
          if (sessId && domWait[sessId] === reqId) delete domWait[sessId];
          else if (domWait.__next === reqId) delete domWait.__next;
          emit({
            type: 'reply', reqId: reqId, ok: !!content, via: 'dom',
            content: content, recalled: state.recalled,
            sessionId: sessId, error: content ? '' : 'EMPTY'
          });
        } else {
          // 用户手动发送：同步状态（供日志/状态展示）
          emit({
            type: 'pageReply', sessionId: sessId, content: content,
            recalled: state.recalled
          });
        }
      } catch (e) { log('page load cb fail: ' + (e.getMessage ? e.getMessage() : e.message)); }
    });
  }

  /* ============ DOM：输入框 / 发送按钮 / 新建对话 ============ */

  function findInput() {
    // 多策略：id 优先 -> placeholder -> 任意 textarea -> contenteditable
    return document.querySelector('textarea#chat-input') ||
           document.querySelector('textarea[placeholder]') ||
           document.querySelector('textarea') ||
           document.querySelector('[contenteditable="true"]') ||
           document.querySelector('[role="textbox"]');
  }

  function nativeFill(el, text) {
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      el.textContent = text;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
    }
  }

  function inputScopeButtons(inputEl) {
    // 沿输入框父链向上找按钮容器（输入区按钮组）
    var scope = inputEl;
    for (var i = 0; i < 10 && scope; i++) {
      scope = scope.parentElement;
      if (!scope || scope === document.body) break;
      var bs = scope.querySelectorAll('button, .ds-button, [role="button"]');
      if (bs.length >= 2) return Array.prototype.slice.call(bs); // 找到按钮组
    }
    return inputEl ? Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]')) : [];
  }

  function isBtnDisabled(b) {
    var cls = (b.className || '') + '';
    return b.disabled === true || b.getAttribute('aria-disabled') === 'true' || cls.indexOf('disabled') !== -1;
  }

  function findSendButton(inputEl) {
    var btns = inputScopeButtons(inputEl);
    // 策略1：DeepSeek 官方 ds-button 发送按钮（primary + filled/circle）
    for (var i = 0; i < btns.length; i++) {
      var cls = (btns[i].className || '') + '';
      if (cls.indexOf('ds-button--primary') !== -1 &&
          (cls.indexOf('filled') !== -1 || cls.indexOf('circle') !== -1)) {
        return btns[i];
      }
    }
    // 策略2：aria-label 含 send/发送
    for (var j = 0; j < btns.length; j++) {
      var al = (btns[j].getAttribute('aria-label') || '').toLowerCase();
      if (al.indexOf('send') !== -1 || al.indexOf('发送') !== -1) return btns[j];
    }
    // 策略3：按钮文本为 发送/Send
    for (var k = 0; k < btns.length; k++) {
      var txt = (btns[k].textContent || '').trim();
      if (txt === '发送' || txt === 'Send') return btns[k];
    }
    // 策略4：输入区最后一个按钮
    return btns.length ? btns[btns.length - 1] : null;
  }

  function waitForSendEnabled(btn, timeoutMs, cb) {
    // 等待按钮可用（空输入/生成中会 disabled）
    var t0 = Date.now();
    (function check() {
      if (!isBtnDisabled(btn)) { cb(true); return; }
      if (Date.now() - t0 > timeoutMs) { cb(false); return; }
      setTimeout(check, 500);
    })();
  }

  /* ============ 附件：文件输入框注入（核心新增） ============ */

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var len = bin.length, u8 = new Uint8Array(len);
    for (var i = 0; i < len; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  }

  function listFileInputs() {
    return Array.prototype.slice.call(document.querySelectorAll('input[type="file"]'));
  }

  // 依据 MIME 选最合适的 file input：accept 命中优先，否则取最后一个（通常属于输入区）
  function pickFileInput(mime) {
    var list = listFileInputs().filter(function (i) { return !i.disabled; });
    if (!list.length) return null;
    var isImg = (mime || '').indexOf('image/') === 0;
    for (var i = 0; i < list.length; i++) {
      var acc = (list[i].getAttribute('accept') || '').toLowerCase();
      if (!acc) continue;
      if (isImg && (acc.indexOf('image') !== -1 || acc.indexOf('.png') !== -1 || acc.indexOf('.jpg') !== -1)) return list[i];
      if (!isImg && acc.indexOf('image') === -1) return list[i];
    }
    // 兜底：accept 最宽（含 */* 或为空）的，最后是输入区的
    var permissive = list.filter(function (i) {
      var a = (i.getAttribute('accept') || '').trim();
      return a === '' || a.indexOf('*/*') !== -1;
    });
    if (permissive.length) return permissive[permissive.length - 1];
    return list[list.length - 1];
  }

  // 某些版本要点一下「+ / 上传」才挂载 input；尝试点击输入区附近的附件切换按钮
  function clickAttachToggle() {
    var input = findInput();
    var scope = input ? input.parentElement : document.body;
    var nodes = scope.querySelectorAll('button, [role="button"], div[class*="icon"], span[class*="icon"]');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var al = (n.getAttribute('aria-label') || '').toLowerCase();
      var cls = (n.className || '').toString().toLowerCase();
      if (/upload|attach|file|add|上传|附件|添加/.test(al) ||
          (/upload|attach|attach-file|add/.test(cls) && !/send/.test(cls))) {
        try { n.click(); return true; } catch (e) {}
      }
    }
    return false;
  }

  function assignFiles(input, file) {
    var dt = new DataTransfer();
    dt.items.add(file);
    try {
      input.files = dt.files;
    } catch (e) {
      try { Object.defineProperty(input, 'files', { value: dt.files, configurable: true, writable: true }); }
      catch (e2) { return false; }
    }
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    return true;
  }

  // 输入区是否已出现该附件且无「上传/解析中」状态（文档类按文件名，图片靠网络信号）
  function attachmentSettled(name) {
    var input = findInput();
    var scope = input;
    for (var i = 0; i < 6 && scope; i++) { scope = scope.parentElement; if (!scope) break; }
    if (!scope) scope = document.body;
    var base = (name || '').replace(/\.[^.]+$/, '').toLowerCase();
    var chip = false;
    if (base) {
      var txts = scope.querySelectorAll('*');
      for (var j = 0; j < txts.length; j++) {
        var own = txts[j].childNodes;
        for (var k = 0; k < own.length; k++) {
          if (own[k].nodeType === 3 && (own[k].textContent || '').toLowerCase().indexOf(base) !== -1) { chip = true; break; }
        }
        if (chip) break;
      }
    }
    var busy = scope.querySelector('[class*="loading" i],[class*="progress" i],[class*="spinner" i],[role="progressbar"]') ||
               /上传中|解析中|正在处理|uploading|processing/i.test(scope.textContent || '');
    return chip && !busy;
  }

  function awaitAttached(o, file, dispatchAt) {
    var deadline = Date.now() + 120000;
    var netGraceAt = 0;
    (function check() {
      var now = Date.now();
      var netOk = uploads.lastOkAt >= dispatchAt;
      var parsed = uploads.parsedAt >= dispatchAt;
      var failedAfter = uploads.failAt >= dispatchAt;
      var chip = attachmentSettled(file.name);
      if (netOk && netGraceAt === 0) netGraceAt = now;

      // 成功：网络上传完成 且（解析成功 / DOM 就绪 / 宽限 4s）；或无网络信号但 DOM 已稳定挂载
      if ((netOk && (parsed || chip || (netGraceAt && now - netGraceAt > 4000))) ||
          (!netOk && chip && uploads.active === 0 && now - dispatchAt > 3000)) {
        emit({ type: 'attach', reqId: o.reqId, ok: true, name: file.name, via: netOk ? 'net' : 'dom' });
        return;
      }
      if (failedAfter && !netOk) {
        emit({ type: 'attach', reqId: o.reqId, ok: false, name: file.name, error: 'UPLOAD_FAIL:' + uploads.lastFail });
        return;
      }
      if (now > deadline) {
        // 超时前若已上传成功仍放行，否则报错
        if (netOk || chip) emit({ type: 'attach', reqId: o.reqId, ok: true, name: file.name, via: 'timeout-pass' });
        else emit({ type: 'attach', reqId: o.reqId, ok: false, name: file.name, error: 'ATTACH_TIMEOUT' });
        return;
      }
      setTimeout(check, 400);
    })();
  }

  function domAttachFile(o) {
    try {
      var bytes = b64ToBytes(o.b64 || '');
      if (!bytes.length) { emit({ type: 'attach', reqId: o.reqId, ok: false, name: o.name, error: 'EMPTY' }); return; }
      var file = new File([bytes], o.name || 'file', { type: o.mime || 'application/octet-stream' });

      var tries = 0;
      (function ensureAndAssign() {
        var input = pickFileInput(o.mime);
        if (!input) {
          if (tries++ < 6) { clickAttachToggle(); setTimeout(ensureAndAssign, 350); return; }
          emit({ type: 'attach', reqId: o.reqId, ok: false, name: file.name, error: 'NO_FILE_INPUT' });
          return;
        }
        var dispatchAt = Date.now();
        // 重置基线，避免把上一个文件的信号算进来
        if (uploads.lastOkAt < dispatchAt) { /* 保持，比较时用 dispatchAt 过滤 */ }
        if (!assignFiles(input, file)) {
          emit({ type: 'attach', reqId: o.reqId, ok: false, name: file.name, error: 'ASSIGN_FAIL' });
          return;
        }
        awaitAttached(o, file, dispatchAt);
      })();
    } catch (e) {
      emit({ type: 'attach', reqId: o.reqId, ok: false, name: o.name, error: 'EX:' + e.message });
    }
  }

  /* ============ 主流程：填入 + 发送 ============ */

  function domSend(o) {
    // o: {reqId, sessionId, prompt}
    var input = findInput();
    if (!input) {
      emit({ type: 'reply', reqId: o.reqId, ok: false, via: 'dom', content: '', error: 'NO_INPUT', sessionId: '' });
      return;
    }
    // 注册等待关联（精确会话 + 通配）
    if (o.sessionId) domWait[o.sessionId] = o.reqId;
    domWait.__next = o.reqId;

    nativeFill(input, o.prompt);

    // 尽快发送：先给框架一个极短 tick（60ms）登记输入并启用发送按钮，随后以 150ms 细粒度轮询；
    // 只有页面正在生成（按钮 disabled）时才真正等待，避免每条消息都固定多等数百毫秒。
    var startedAt = Date.now();
    function enterFallback() {
      try {
        input.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true
        }));
      } catch (e) {}
    }
    function attempt() {
      var btn = findSendButton(input);
      if (btn) {
        if (!isBtnDisabled(btn)) {
          try { btn.click(); } catch (e) { log('click fail: ' + e.message); }
          return;
        }
        if (Date.now() - startedAt > 90000) {
          log('发送按钮持续不可用（页面可能生成中），改用 Enter 键');
          enterFallback();
          return;
        }
        setTimeout(attempt, 150);
      } else {
        enterFallback(); // 兜底：Enter 键发送
      }
    }
    setTimeout(attempt, 60);

    // 超时守护：180s 未捕获回复则报错
    setTimeout(function () {
      var rid = (o.sessionId && domWait[o.sessionId]) || domWait.__next;
      if (rid === o.reqId) {
        if (o.sessionId && domWait[o.sessionId] === rid) delete domWait[o.sessionId];
        else if (domWait.__next === rid) delete domWait.__next;
        emit({ type: 'reply', reqId: o.reqId, ok: false, via: 'dom', content: '', error: 'DOM_TIMEOUT', sessionId: o.sessionId || '' });
      }
    }, 180000);
  }

  /* ============ 新建对话（会话轮换） ============ */

  function clickNewChat() {
    // 多语言/多结构匹配 New chat 元素
    var candidates = document.querySelectorAll('div, a, button, span');
    var target = null;
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (el.children.length > 3) continue;
      var txt = (el.textContent || '').trim();
      if (txt === 'New chat' || txt === '开启新对话' || txt === '新对话' || txt === '新建对话') {
        // 优先可点击的（有 cursor 样式或是链接）
        target = el;
        if (el.tagName === 'A' || el.getAttribute('role') === 'button' || (el.className || '').indexOf('cursor') !== -1) break;
      }
    }
    if (target) {
      try { target.click(); return true; } catch (e) {}
    }
    return false;
  }

  /* ============ 原生侧入口：window.DSKB ============ */

  window.DSKB = {
    ping: function () { return 'pong'; },

    probe: function () {
      var loggedIn = location.href.indexOf('sign_in') === -1 && document.cookie.indexOf('userToken') !== -1;
      var hasInput = !!findInput();
      var hasFileInput = listFileInputs().length > 0;
      emit({
        type: 'probe', url: location.href,
        loggedIn: location.href.indexOf('sign_in') === -1,
        hasInput: hasInput,
        hasFileInput: hasFileInput,
        title: document.title
      });
    },

    // 发送 prompt：填入 + 点击发送（等待页面空闲）
    send: function (o) { domSend(o || {}); },
    // 别名（保持兼容）
    sendDom: function (o) { domSend(o || {}); },

    // 挂载附件：base64 -> File -> 注入官网文件输入框 -> 等官网上传/解析完成
    attachFile: function (o) { domAttachFile(o || {}); },

    // 新建对话（会话轮换）。事件回传结果
    newChat: function (o) {
      o = o || {};
      var ok = clickNewChat();
      emit({ type: 'newChat', reqId: o.reqId || '', ok: ok });
    },

    // 获取输入框内容（调试）
    inputText: function () { var el = findInput(); return el ? (el.value || el.textContent || '') : ''; }
  };

  emit({ type: 'boot', url: location.href, loggedIn: location.href.indexOf('sign_in') === -1 });
  log('bridge v3 loaded @ ' + location.href);
})();
