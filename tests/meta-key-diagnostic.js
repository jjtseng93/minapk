// Paste into minapk's Volume Up -> eval JS on the keyboard test page.
// Does not modify events or prevent defaults. Run again to reset the trace.
(() => {
    window.minapkMetaDiagnostic?.stop();
    const output = document.createElement('pre');
    output.style.cssText = 'position:fixed;bottom:0;left:0;right:0;max-height:40vh;overflow:auto;z-index:2147483647;background:#111;color:#fff;font:11px monospace;white-space:pre-wrap;margin:0;padding:8px;pointer-events:none';
    document.documentElement.appendChild(output);
    const trace = {userAgent: navigator.userAgent, events: []};
    const render = () => {
        output.textContent = trace.userAgent + '\n' + trace.events.slice(-12)
            .map(e => `${e.type} key=${e.key} code=${e.code} Ctrl=${e.ctrl} Alt=${e.alt} Shift=${e.shift} Meta=${e.meta} state=${e.metaState} trusted=${e.trusted}`)
            .join('\n');
    };
    const record = e => {
        trace.events.push({
            type: e.type, key: e.key, code: e.code,
            ctrl: e.ctrlKey, alt: e.altKey, shift: e.shiftKey,
            meta: e.metaKey, metaState: e.getModifierState('Meta'),
            trusted: e.isTrusted
        });
        if (trace.events.length > 200) trace.events.shift();
        render();
    };
    for (const type of ['keydown', 'keyup']) window.addEventListener(type, record, true);
    window.minapkMetaDiagnostic = {
        trace,
        stop() {
            for (const type of ['keydown', 'keyup']) window.removeEventListener(type, record, true);
            output.remove();
        }
    };
    render();
    return 'Meta diagnostic ready';
})();
