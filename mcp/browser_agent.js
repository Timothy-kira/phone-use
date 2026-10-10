// Page-side helper for phone_browser, injected into a tab over the DevTools Protocol.
//
// Written for this plugin. The approach - number the visible interactive elements, keep a stable
// handle per element, give the model a compact text view of the page, act by handle - follows
// browser-use (MIT) and chrome-devtools-mcp / Playwright MCP (Apache-2.0); no code is copied.
// Installs window.__pu once per document; every function returns plain JSON.
(function () {
  if (window.__pu && window.__pu.v === 3) return;
  const refs = [];                // id -> WeakRef(element)
  const ids = new WeakMap();      // element -> id
  const INTERACTIVE_ROLES = new Set(["button", "link", "checkbox", "radio", "tab", "menuitem", "menuitemcheckbox",
    "menuitemradio", "option", "switch", "combobox", "textbox", "searchbox", "slider", "spinbutton", "treeitem"]);
  const SKIP = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "META", "LINK", "HEAD", "svg", "PATH"]);
  const SENSITIVE = /(pass(word|wd)?|pwd|passcode|card.?(number|no)|cc-?(number|csc|exp)|cvv|cvc|security.?code|ssn|social.?security|iban|routing|account.?number|身份证|证件号|护照|密码|口令|银行卡|卡号|有效期|安全码|验证码|one-time-code)/i;
  const RISKY = /(\bpay\b|payment|purchase|\bbuy\b|checkout|place.?order|order.?now|confirm.?order|subscribe|donate|transfer|send money|sign.?up|register|create.?account|delete|remove.?account|unsubscribe|支付|付款|购买|立即购|下单|提交订单|确认订单|结算|充值|转账|注册|开通|订阅|删除|注销|解绑)/i;

  function idOf(el) {
    let id = ids.get(el);
    if (id === undefined) { id = refs.length; refs.push(new WeakRef(el)); ids.set(el, id); }
    return id;
  }
  function get(id) {
    const r = refs[id];
    const el = r && r.deref();
    if (!el || !el.isConnected) return null;
    return el;
  }
  function clean(s, max) {
    s = (s || "").replace(/\s+/g, " ").trim();
    return max && s.length > max ? s.slice(0, max - 1) + "…" : s;
  }
  function visible(el) {
    if (!(el instanceof Element)) return false;
    if (el.closest("[aria-hidden=true]") && !el.matches("input,select,textarea")) return false;
    const st = getComputedStyle(el);
    if (st.display === "none" || st.visibility === "hidden" || st.visibility === "collapse") return false;
    if (parseFloat(st.opacity) === 0 && !el.matches("input,select")) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 || st.display === "contents";
  }
  function inViewport(el) {
    const r = el.getBoundingClientRect();
    return r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }
  function role(el) { return (el.getAttribute("role") || "").toLowerCase(); }
  function isInteractive(el) {
    const tag = el.tagName;
    if (tag === "A" && el.hasAttribute("href")) return true;
    if (tag === "BUTTON" || tag === "SELECT" || tag === "TEXTAREA" || tag === "SUMMARY") return true;
    if (tag === "INPUT") return el.type !== "hidden";
    if (el.isContentEditable && (el.parentElement === null || !el.parentElement.isContentEditable)) return true;
    if (INTERACTIVE_ROLES.has(role(el))) return true;
    if (el.hasAttribute("onclick")) return true;
    const ti = el.getAttribute("tabindex");
    if (ti !== null && parseInt(ti, 10) >= 0 && tag !== "DIV" && tag !== "SPAN") return true;
    if (tag === "DIV" || tag === "SPAN" || tag === "LI" || tag === "IMG") {
      // Clickable cards and icons: pointer cursor that does not just inherit from a clickable parent.
      if (getComputedStyle(el).cursor === "pointer") {
        const p = el.parentElement;
        if (!p || getComputedStyle(p).cursor !== "pointer") return true;
      }
    }
    return false;
  }
  function textOfIds(list) {
    return clean((list || "").split(/\s+/).map(id => { const n = document.getElementById(id); return n ? n.innerText || n.textContent : ""; }).join(" "));
  }
  function labelOf(el) {
    const aria = el.getAttribute("aria-label");
    if (aria) return clean(aria, 120);
    const by = el.getAttribute("aria-labelledby");
    if (by) { const t = textOfIds(by); if (t) return clean(t, 120); }
    if (el.labels && el.labels.length) { const t = clean(el.labels[0].innerText); if (t) return clean(t, 120); }
    const wrap = el.closest("label");
    if (wrap) { const t = clean(wrap.innerText); if (t) return clean(t, 120); }
    return "";
  }
  function nameOf(el) {
    const tag = el.tagName;
    let n = labelOf(el);
    if (!n && (tag === "INPUT" || tag === "TEXTAREA")) n = el.placeholder || "";
    if (!n && tag === "INPUT" && /^(submit|button|reset)$/.test(el.type)) n = el.value;
    if (!n && tag !== "INPUT" && tag !== "TEXTAREA" && tag !== "SELECT") n = el.innerText || el.textContent || "";
    if (!n) { const img = el.querySelector && el.querySelector("img[alt]"); if (img) n = img.alt; }
    if (!n) n = el.getAttribute("title") || el.getAttribute("alt") || el.getAttribute("name") || "";
    return clean(n, 100);
  }
  function precedingText(el) {
    // The visible text right before a field, for forms that do not use <label>.
    let node = el, steps = 0;
    while (node && steps < 6) {
      let prev = node.previousElementSibling;
      while (prev) {
        const t = clean(prev.innerText || prev.textContent);
        if (t && visible(prev)) return clean(t, 60);
        prev = prev.previousElementSibling;
      }
      node = node.parentElement; steps++;
      if (node && node.matches("form,fieldset,body")) break;
    }
    return "";
  }
  function kindOf(el) {
    const tag = el.tagName, r = role(el);
    if (tag === "A") return "link";
    if (tag === "BUTTON" || r === "button") return "button";
    if (tag === "SELECT") return "select";
    if (tag === "TEXTAREA") return "textarea";
    if (tag === "INPUT") return el.type === "submit" || el.type === "button" || el.type === "reset" ? "button" : "input";
    if (el.isContentEditable) return "editable";
    return r || "clickable";
  }
  function describe(el, opts) {
    const k = kindOf(el);
    let line = `[e${idOf(el)}] ${k}`;
    const name = nameOf(el);
    if (k === "input" || k === "textarea") {
      line += ` type=${el.type || "text"}`;
      const label = labelOf(el) || precedingText(el);
      if (label) line += ` "${clean(label, 80)}"`;
      if (el.placeholder && el.placeholder !== label) line += ` placeholder="${clean(el.placeholder, 60)}"`;
      if (el.type === "checkbox" || el.type === "radio") line += el.checked ? " [x]" : " [ ]";
      else if (el.value) line += SENSITIVE.test(el.name + el.id + el.autocomplete + el.type + label) ? " value=•••" : ` value="${clean(el.value, 80)}"`;
      if (el.required) line += " required";
      if (el.disabled) line += " disabled";
    } else if (k === "select") {
      const label = labelOf(el) || precedingText(el);
      if (label) line += ` "${clean(label, 80)}"`;
      const sel = el.selectedOptions && el.selectedOptions[0];
      line += ` = "${sel ? clean(sel.text, 60) : ""}" (${el.options.length} options)`;
    } else {
      if (name) line += ` "${name}"`;
      if (k === "link") {
        const href = el.getAttribute("href") || "";
        if (href && !href.startsWith("javascript:") && opts.hrefs) line += ` -> ${clean(el.href, 120)}`;
      }
      const checked = el.getAttribute("aria-checked") || el.getAttribute("aria-selected") || el.getAttribute("aria-pressed");
      if (checked === "true") line += " [x]";
      if (el.getAttribute("aria-expanded")) line += ` expanded=${el.getAttribute("aria-expanded")}`;
      if (el.disabled || el.getAttribute("aria-disabled") === "true") line += " disabled";
    }
    if (!inViewport(el)) line += " ·off";
    return line;
  }
  function* children(el) {
    if (el.shadowRoot) yield* el.shadowRoot.children;
    yield* el.children;
    if (el.tagName === "IFRAME") {
      try { if (el.contentDocument && el.contentDocument.body) yield el.contentDocument.body; } catch (e) { /* cross-origin */ }
    }
  }

  function snapshot(opts) {
    opts = Object.assign({ maxChars: 15000, offset: 0, hrefs: true, root: null, interactiveOnly: false }, opts || {});
    const out = [];
    let interactive = 0;
    const root = opts.root != null ? get(opts.root) : document.body;
    if (!root) return { ok: false, code: "REF_STALE", errmsg: "That ref is gone; take a new snapshot." };
    function walk(el, depth, insideInteractive) {
      if (SKIP.has(el.tagName)) return;
      if (!visible(el)) return;
      const tag = el.tagName;
      if (/^H[1-6]$/.test(tag) && !opts.interactiveOnly) {
        const t = clean(el.innerText, 160);
        if (t) out.push("#".repeat(+tag[1]) + " " + t);
      }
      let inter = false;
      if (isInteractive(el)) {
        inter = true; interactive++;
        out.push(describe(el, opts));
        if (tag === "SELECT" || tag === "INPUT" || tag === "TEXTAREA") return;
      } else if (tag === "IMG" && !insideInteractive && !opts.interactiveOnly) {
        const alt = clean(el.alt, 80);
        if (alt && el.width > 40) out.push(`img "${alt}"`);
      }
      if (/^H[1-6]$/.test(tag)) return;
      for (const c of el.childNodes) {
        if (c.nodeType === 3) {
          if (!inter && !insideInteractive && !opts.interactiveOnly) {
            const t = clean(c.textContent, 300);
            if (t.length > 1 || /[一-鿿\d]/.test(t)) out.push(t);
          }
        }
      }
      for (const c of children(el)) walk(c, depth + 1, insideInteractive || inter);
    }
    walk(root, 0, false);
    // Merge consecutive short text lines into readable sentences.
    const lines = [];
    for (const l of out) {
      const last = lines[lines.length - 1];
      if (last !== undefined && !/^(\[e\d+\]|#|img )/.test(l) && !/^(\[e\d+\]|#|img )/.test(last) && last.length + l.length < 200) {
        lines[lines.length - 1] = last + " " + l;
      } else lines.push(l);
    }
    let text = lines.join("\n");
    const total = text.length;
    text = text.slice(opts.offset, opts.offset + opts.maxChars);
    return {
      ok: true, url: location.href, title: document.title, interactive, chars: total,
      offset: opts.offset, truncated: opts.offset + opts.maxChars < total,
      scroll: { y: Math.round(scrollY), height: document.documentElement.scrollHeight, viewport: innerHeight },
      text,
    };
  }

  function frameOffset(el) {
    let x = 0, y = 0, w = el.ownerDocument.defaultView;
    while (w && w !== window && w.frameElement) {
      const r = w.frameElement.getBoundingClientRect();
      x += r.left; y += r.top; w = w.parent;
    }
    return { x, y };
  }
  function box(id) {
    const el = get(id);
    if (!el) return { ok: false, code: "REF_STALE", errmsg: `e${id} is gone (the page changed); take a new snapshot.` };
    el.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
    const r = el.getBoundingClientRect();
    const off = frameOffset(el);
    const x = off.x + r.left + r.width / 2, y = off.y + r.top + r.height / 2;
    let hit = false;
    const top = el.ownerDocument.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    if (top && (top === el || el.contains(top) || top.contains(el) || (top.closest && top.closest("label") && el.labels && [...el.labels].includes(top.closest("label"))))) hit = true;
    const name = nameOf(el);
    const risky = RISKY.test(name + " " + (el.value || "") + " " + (el.getAttribute("aria-label") || "")) ||
      (el.form && (el.type === "submit" || el.tagName === "BUTTON") && RISKY.test((el.form.action || "") + " " + (el.form.id || "") + " " + (el.form.className || "")));
    return { ok: true, x, y, w: r.width, h: r.height, hit, kind: kindOf(el), name, risky,
      covered_by: hit || !top ? "" : clean(nameOf(top) || top.tagName, 60) };
  }
  function focusAndSelect(id) {
    const el = get(id);
    if (!el) return { ok: false, code: "REF_STALE", errmsg: `e${id} is gone; take a new snapshot.` };
    el.scrollIntoView({ block: "center", behavior: "instant" });
    el.focus();
    if (el.isContentEditable) {
      const range = document.createRange(); range.selectNodeContents(el);
      const sel = getSelection(); sel.removeAllRanges(); sel.addRange(range);
    } else if (typeof el.select === "function") {
      try { el.select(); } catch (e) { /* number/date inputs */ }
    }
    return { ok: true, kind: kindOf(el), type: el.type || "", active: document.activeElement === el || el.contains(document.activeElement) };
  }
  function nativeSet(el, value) {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : el.tagName === "SELECT" ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }
  function valueOf(id) {
    const el = get(id);
    if (!el) return null;
    if (el.isContentEditable) return el.innerText;
    if (el.type === "checkbox" || el.type === "radio") return el.checked;
    return el.value;
  }
  function setValue(id, value) {
    const el = get(id);
    if (!el) return { ok: false, code: "REF_STALE" };
    nativeSet(el, value);
    return { ok: true, value: el.value };
  }
  function fireChange(id) {
    const el = get(id);
    if (el) el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }
  function norm(s) { return clean(s).toLowerCase(); }
  function selectOption(id, want) {
    const el = get(id);
    if (!el) return { ok: false, code: "REF_STALE" };
    if (el.tagName !== "SELECT") return { ok: false, code: "NOT_A_SELECT", errmsg: "Not a <select>; click it and pick the option instead." };
    const w = norm(String(want));
    const opts = [...el.options];
    const pick = opts.find(o => norm(o.text) === w || norm(o.value) === w) ||
      opts.find(o => norm(o.text).startsWith(w)) || opts.find(o => norm(o.text).includes(w) || (w && w.includes(norm(o.text)) && norm(o.text).length > 1));
    if (!pick) return { ok: false, code: "NO_SUCH_OPTION", options: opts.slice(0, 40).map(o => clean(o.text, 50)) };
    nativeSet(el, pick.value);
    return { ok: true, selected: clean(pick.text, 60) };
  }

  function fieldLabel(el) {
    return labelOf(el) || clean(el.placeholder || "", 80) || precedingText(el) || clean(el.getAttribute("title") || "", 80);
  }
  function isField(el) {
    if (el.tagName === "INPUT") return !/^(hidden|submit|button|reset|image|file)$/.test(el.type);
    if (el.tagName === "SELECT" || el.tagName === "TEXTAREA") return true;
    if (el.isContentEditable && (!el.parentElement || !el.parentElement.isContentEditable)) return true;
    return /^(textbox|combobox|checkbox|radio|switch|searchbox|spinbutton)$/.test(role(el));
  }
  function collectFields(scope) {
    const all = [];
    (function walk(el) {
      for (const c of children(el)) {
        if (SKIP.has(c.tagName)) continue;
        if (isField(c)) all.push(c);
        walk(c);
      }
    })(scope);
    return all;
  }
  function form(scopeId) {
    let scope = scopeId != null ? get(scopeId) : null;
    if (!scope) {
      const active = document.activeElement;
      scope = (active && active.form) || null;
      if (!scope) {
        const forms = [...document.forms].filter(visible);
        scope = forms.length === 1 ? forms[0] : document.body;
      }
    }
    const fields = [];
    const radios = {};
    for (const el of collectFields(scope)) {
      const shown = visible(el) || (el.type === "checkbox" || el.type === "radio") && el.labels && [...el.labels].some(visible);
      if (!shown) continue;
      const type = el.tagName === "SELECT" ? "select" : el.tagName === "TEXTAREA" ? "textarea" : el.isContentEditable ? "editable" : el.tagName === "INPUT" ? (el.type || "text") : role(el);
      const label = fieldLabel(el);
      const sensitive = SENSITIVE.test([el.name, el.id, el.autocomplete, el.type, label].join(" "));
      if (type === "radio") {
        const key = el.name || "radio:" + label;
        let g = radios[key];
        if (!g) {
          const fs = el.closest("fieldset");
          const legend = fs && fs.querySelector("legend");
          const group = el.closest("[role=radiogroup]");
          g = radios[key] = { ref: "e" + idOf(el), type: "radio", name: el.name || "", label: clean(legend ? legend.innerText : group ? (labelOf(group) || precedingText(group)) : precedingText(el), 80), required: el.required, options: [] };
          fields.push(g);
        }
        g.options.push({ ref: "e" + idOf(el), label: clean(labelOf(el) || el.value, 60), value: el.value, checked: el.checked });
        continue;
      }
      const f = { ref: "e" + idOf(el), type, label, name: el.name || "", id: el.id || "", required: !!el.required || el.getAttribute("aria-required") === "true" };
      if (el.placeholder) f.placeholder = clean(el.placeholder, 60);
      if (el.autocomplete && el.autocomplete !== "on" && el.autocomplete !== "off") f.autocomplete = el.autocomplete;
      if (type === "select") f.options = [...el.options].slice(0, 60).map(o => clean(o.text, 50)), f.value = el.selectedOptions[0] ? clean(el.selectedOptions[0].text, 60) : "";
      else if (type === "checkbox" || role(el) === "checkbox" || role(el) === "switch") f.checked = el.type === "checkbox" ? el.checked : el.getAttribute("aria-checked") === "true";
      else f.value = sensitive && (el.value || "") ? "•••" : clean(el.isContentEditable ? el.innerText : el.value, 120);
      if (el.min) f.min = el.min;
      if (el.max) f.max = el.max;
      if (el.maxLength > 0) f.maxlength = el.maxLength;
      if (el.pattern) f.pattern = el.pattern;
      if (el.disabled || el.readOnly) f.readonly = true;
      if (sensitive) f.sensitive = true;
      if (el.validationMessage) f.invalid = clean(el.validationMessage, 100);
      fields.push(f);
    }
    const buttons = [];
    const btnScope = scope === document.body ? document.body : scope;
    for (const b of btnScope.querySelectorAll("button, input[type=submit], input[type=button], [role=button]")) {
      if (!visible(b)) continue;
      const name = nameOf(b);
      if (!name) continue;
      buttons.push({ ref: "e" + idOf(b), text: name, submit: b.type === "submit" || (b.tagName === "BUTTON" && !b.getAttribute("type") && !!b.form), risky: RISKY.test(name) });
      if (buttons.length >= 12) break;
    }
    const f0 = scope.tagName === "FORM" ? scope : null;
    return { ok: true, url: location.href, title: document.title, form: f0 ? { ref: "e" + idOf(f0), action: f0.action || "", method: (f0.method || "get").toLowerCase(), risky: RISKY.test((f0.action || "") + " " + (f0.id || "") + " " + (f0.className || "")) } : null, fields, buttons };
  }
  function validity(idList) {
    const out = {};
    for (const id of idList) {
      const el = get(id);
      if (!el) continue;
      out["e" + id] = { valid: el.checkValidity ? el.checkValidity() : true, message: clean(el.validationMessage || "", 100), value: SENSITIVE.test([el.name, el.id, el.autocomplete, el.type].join(" ")) ? "•••" : (el.type === "checkbox" || el.type === "radio" ? el.checked : clean(el.isContentEditable ? el.innerText : el.value, 80)) };
    }
    return out;
  }
  function submitInfo(id) {
    const el = get(id);
    if (!el) return { ok: false, code: "REF_STALE" };
    const f = el.tagName === "FORM" ? el : el.form || el.closest("form");
    if (!f) return { ok: false, code: "NO_FORM" };
    const btn = f.querySelector("button[type=submit], input[type=submit], button:not([type])");
    const text = btn ? nameOf(btn) : "";
    return { ok: true, form: "e" + idOf(f), button: btn ? "e" + idOf(btn) : "", text, risky: RISKY.test(text + " " + (f.action || "") + " " + (f.id || "")) };
  }
  function requestSubmit(formId) {
    const f = get(formId);
    if (!f) return { ok: false, code: "REF_STALE" };
    if (typeof f.requestSubmit === "function") f.requestSubmit(); else f.submit();
    return { ok: true };
  }

  function findText(text, preferInteractive) {
    const want = norm(text);
    let best = null, bestScore = 0;
    for (const el of document.querySelectorAll("a,button,input,select,textarea,summary,label,[role],[onclick],[tabindex],h1,h2,h3,h4,li,span,div,p,td")) {
      if (!visible(el)) continue;
      const name = norm(nameOf(el));
      if (!name) continue;
      let score = name === want ? 3 : name.startsWith(want) ? 2 : name.includes(want) ? 1 : 0;
      if (!score) continue;
      if (isInteractive(el)) score += 2; else if (preferInteractive) score -= 0.5;
      score -= Math.min(name.length / 400, 0.9);
      if (inViewport(el)) score += 0.2;
      if (score > bestScore) { best = el; bestScore = score; }
    }
    if (!best) return { ok: false, code: "NOT_FOUND", errmsg: `No element shows "${text}".` };
    let target = best;
    while (target && !isInteractive(target) && target.parentElement && target !== document.body) {
      if (isInteractive(target.parentElement)) { target = target.parentElement; break; }
      target = target.parentElement;
      if (target === document.body) { target = best; break; }
    }
    return { ok: true, id: idOf(isInteractive(target) ? target : best), name: nameOf(best) };
  }

  function pageState() {
    return { url: location.href, title: document.title, ready: document.readyState, y: Math.round(scrollY),
      height: document.documentElement.scrollHeight, viewport: innerHeight,
      textLength: (document.body && document.body.innerText || "").length,
      active: document.activeElement && document.activeElement !== document.body ? clean(nameOf(document.activeElement), 60) : "" };
  }
  function scrollPage(spec) {
    let moved = null, containerY = null;
    if (spec.ref != null) { const el = get(spec.ref); if (!el) return { ok: false, code: "REF_STALE" }; el.scrollIntoView({ block: "center", behavior: "instant" }); }
    else {
      // Some pages scroll an inner container, not the window: scroll the biggest scrollable one.
      let target = document.scrollingElement;
      if (target.scrollHeight <= innerHeight + 2) {
        let bestArea = 0;
        for (const el of document.querySelectorAll("*")) {
          const st = getComputedStyle(el);
          if (!/(auto|scroll)/.test(st.overflowY) || el.scrollHeight <= el.clientHeight + 2) continue;
          const area = el.clientWidth * el.clientHeight;
          if (area > bestArea) { bestArea = area; target = el; }
        }
      }
      const before = target.scrollTop;
      if (spec.to === "top") target.scrollTop = 0;
      else if (spec.to === "bottom") target.scrollTop = target.scrollHeight;
      else target.scrollBy({ top: spec.dy || Math.round(innerHeight * 0.8), behavior: "instant" });
      containerY = target.scrollTop; moved = containerY !== before;
    }
    return { ok: true, y: Math.round(scrollY), containerY, moved, height: document.documentElement.scrollHeight, viewport: innerHeight };
  }

  function mdInline(el) {
    let s = "";
    for (const c of el.childNodes) {
      if (c.nodeType === 3) s += c.textContent;
      else if (c.nodeType === 1 && visible(c) && !SKIP.has(c.tagName)) {
        const t = c.tagName;
        if (t === "A" && c.href && !c.href.startsWith("javascript:")) { const inner = clean(mdInline(c)); s += inner ? `[${inner}](${c.href})` : ""; }
        else if (t === "STRONG" || t === "B") s += `**${clean(mdInline(c))}**`;
        else if (t === "EM" || t === "I") s += `*${clean(mdInline(c))}*`;
        else if (t === "CODE") s += "`" + c.textContent + "`";
        else if (t === "BR") s += "\n";
        else if (t === "IMG") s += c.alt ? `![${clean(c.alt, 60)}](${c.src})` : "";
        else s += mdInline(c);
      }
    }
    return s;
  }
  function markdown(root) {
    const out = [];
    const BLOCK = /^(P|DIV|SECTION|ARTICLE|MAIN|HEADER|FOOTER|ASIDE|NAV|UL|OL|LI|H[1-6]|TABLE|BLOCKQUOTE|PRE|FIGURE|FORM|DL|DT|DD)$/;
    function block(el) {
      if (SKIP.has(el.tagName) || !visible(el)) return;
      const t = el.tagName;
      if (/^H[1-6]$/.test(t)) { out.push("#".repeat(+t[1]) + " " + clean(mdInline(el))); return; }
      if (t === "TABLE") { out.push(tableMd(el)); return; }
      if (t === "PRE") { out.push("```\n" + el.innerText + "\n```"); return; }
      if (t === "LI") { const s = clean(mdInline(el)); if (s) out.push("- " + s); return; }
      if (t === "BLOCKQUOTE") { out.push("> " + clean(mdInline(el))); return; }
      const hasBlock = [...el.children].some(c => BLOCK.test(c.tagName));
      if (!hasBlock) { const s = clean(mdInline(el)); if (s) out.push(s); return; }
      for (const c of children(el)) block(c);
    }
    block(root);
    return out.filter((l, i) => l && l !== out[i - 1]).join("\n\n");
  }
  function tableRows(tbl) {
    return [...tbl.rows].slice(0, 200).map(r => [...r.cells].map(c => clean(c.innerText, 120)));
  }
  function tableMd(tbl) {
    const rows = tableRows(tbl);
    if (!rows.length) return "";
    const width = Math.max(...rows.map(r => r.length));
    const pad = r => r.concat(Array(width - r.length).fill(""));
    const lines = [ "| " + pad(rows[0]).join(" | ") + " |", "| " + Array(width).fill("---").join(" | ") + " |" ];
    for (const r of rows.slice(1)) lines.push("| " + pad(r).join(" | ") + " |");
    return lines.join("\n");
  }
  function mainRoot() {
    return document.querySelector("main, article, [role=main]") || document.body;
  }
  function extract(what, rootId, maxChars, offset) {
    const root = rootId != null ? get(rootId) : (what === "text" || what === "markdown") ? mainRoot() : document.body;
    if (!root) return { ok: false, code: "REF_STALE" };
    maxChars = maxChars || 20000; offset = offset || 0;
    let content;
    if (what === "links") {
      const seen = new Set(); content = [];
      for (const a of root.querySelectorAll("a[href]")) {
        if (!visible(a) || a.href.startsWith("javascript:") || seen.has(a.href)) continue;
        seen.add(a.href); content.push({ ref: "e" + idOf(a), text: nameOf(a), href: a.href });
        if (content.length >= 300) break;
      }
      return { ok: true, url: location.href, title: document.title, links: content };
    }
    if (what === "tables") {
      content = [...root.querySelectorAll("table")].filter(visible).slice(0, 20).map(t => ({ ref: "e" + idOf(t), caption: t.caption ? clean(t.caption.innerText, 100) : "", rows: tableRows(t) }));
      return { ok: true, url: location.href, title: document.title, tables: content };
    }
    const text = what === "markdown" ? markdown(root) : (root.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
    return { ok: true, url: location.href, title: document.title, chars: text.length, offset, truncated: offset + maxChars < text.length, content: text.slice(offset, offset + maxChars) };
  }
  function quote(text) {
    const want = clean(text);
    const body = clean(document.body ? document.body.innerText : "");
    const found = body.includes(want);
    const words = want.split(" ");
    const enc = s => encodeURIComponent(s).replace(/-/g, "%2D").replace(/,/g, "%2C");
    let frag;
    if (words.length > 10) frag = enc(words.slice(0, 4).join(" ")) + "," + enc(words.slice(-4).join(" "));
    else frag = enc(want);
    const base = location.href.split("#")[0];
    return { ok: true, found, text: want, url: base + "#:~:text=" + frag, page: location.href, title: document.title };
  }
  function waitCheck(spec) {
    if (spec.url_contains && !location.href.includes(spec.url_contains)) return false;
    if (spec.text && !(document.body && document.body.innerText.includes(spec.text))) return false;
    if (spec.gone && document.body && document.body.innerText.includes(spec.gone)) return false;
    if (spec.ref != null && !get(spec.ref)) return false;
    return true;
  }

  window.__pu = { v: 3, idOf, get, snapshot, box, focusAndSelect, setValue, valueOf, fireChange, selectOption,
    form, validity, submitInfo, requestSubmit, findText, pageState, scrollPage, extract, quote, waitCheck,
    risky: s => RISKY.test(s || ""), sensitive: s => SENSITIVE.test(s || "") };
})();
