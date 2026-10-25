package com.classsche.mobile

import org.json.JSONObject

/** Reads the school's current QR image and uses its refresh/polling functions in the same session. */
internal object IdentityQrPageScript {
  fun command(action: String, displayedSource: String): String =
    "($SOURCE)(${JSONObject.quote(action)}, ${JSONObject.quote(displayedSource)})"

  private val SOURCE = """
    function(action, displayedSource) {
      const reply = data => {
        const status = window.classScheQrBridge && window.classScheQrBridge.status;
        return JSON.stringify(status ? Object.assign({ statusCode: status }, data) : data);
      };
      if (location.protocol !== 'https:' || location.hostname !== 'ids.njust.edu.cn' || location.pathname !== '/authserver/login') {
        return reply({ state: 'wrong_page' });
      }
      const jq = window.jQuery;
      if (!jq || typeof window.getQrCode !== 'function' || typeof window.isUsed !== 'function') {
        return reply({ state: document.readyState === 'complete' ? 'missing_script' : 'loading' });
      }
      let bridge = window.classScheQrBridge;
      if (!bridge) {
        bridge = window.classScheQrBridge = { active: false, pending: false, error: null, requests: new Map(),
          status: '', submitted: false, lastStatusCheck: 0 };
        // Android's foreground Handler drives status checks. Hidden WebView timers can be
        // throttled/suspended while switching to WeChat, so do not depend on countDown().
        window.countDown = function() {
          clearTimeout(window.qr_time);
        };
        clearTimeout(window.qr_time);
        // Preparing a password form must not submit a previously scanned QR code.
        jq.ajaxPrefilter(function(options, original, request) {
          let path;
          try { path = new URL(options.url, location.href).pathname; } catch (_) { return; }
          const phase = path === '/authserver/qrCode/getToken' ? 'token' :
            path === '/authserver/qrCode/getStatus.htl' ? 'status' : '';
          if (!phase) return;
          if (!bridge.active && phase === 'status') {
            request.abort();
            return;
          }
          if (phase === 'status' && Array.from(bridge.requests.values()).includes('status')) {
            request.abort();
            return;
          }
          if (!options.timeout) options.timeout = 12000;
          bridge.requests.set(request, phase);
          if (phase === 'token') { bridge.pending = true; bridge.error = null; }
          if (phase === 'status') {
            const success = options.success;
            options.success = function() {
              if (!bridge.active) return;
              const status = String(arguments[0] == null ? '' : arguments[0]).trim();
              if (!/^[0123]$/.test(status)) {
                bridge.error = { phase: 'status', kind: 'unexpected' };
                return;
              }
              bridge.status = status;
              if (status === '1') {
                // Let Android display confirmation before submitting the live QR form once.
                clearTimeout(window.qr_time);
                return;
              }
              if (typeof success === 'function') success.call(this, status, arguments[1], arguments[2]);
            };
          }
          request.done(function(data) {
            if (phase === 'token') {
              bridge.pending = false;
              if (!String(data || '').trim()) bridge.error = { phase: phase, kind: 'empty' };
            }
          });
          request.fail(function(response, reason) {
            if (phase === 'token') bridge.pending = false;
            if (reason !== 'abort') bridge.error = { phase: phase, kind: reason === 'timeout' ? 'timeout' :
              response.status ? 'http' : 'network', code: response.status || 0 };
          });
          request.always(function() { bridge.requests.delete(request); });
        });
      }
      if (action === 'prepare' || action === 'pause') {
        bridge.active = false;
        window.QR_LOGIN_ENABLED = 0;
        clearTimeout(window.qr_time);
        for (const [request, phase] of Array.from(bridge.requests)) if (phase === 'status') request.abort();
        return reply({ state: 'prepared', passwordReady: Array.from(document.querySelectorAll('input[type=password]'))
          .some(el => el.getClientRects().length > 0) });
      }
      const forms = Array.from(document.querySelectorAll('#qrLoginForm'));
      const form = forms.find(el => !el.closest('#qrLoginDiv') && el.querySelector('#qr_img'));
      if (!form) return reply({ state: 'missing_form' });
      const image = form.querySelector('#qr_img');
      const shown = selector => {
        const el = form.querySelector(selector);
        return el && getComputedStyle(el).display !== 'none';
      };
      if (action === 'refresh') {
        if (bridge.pending) return reply({ state: 'pending_token', refreshAccepted: true });
        if (typeof window.refresh !== 'function') return reply({ state: 'missing_refresh' });
        for (const request of Array.from(bridge.requests.keys())) request.abort();
        bridge.error = null;
        bridge.status = '';
        bridge.submitted = false;
        bridge.lastStatusCheck = 0;
        bridge.active = true;
        window.QR_LOGIN_ENABLED = 1;
        clearTimeout(window.qr_time);
        // Do not redisplay/save the old code while the replacement token is in flight.
        image.removeAttribute('src');
        window.refresh();
        return reply({ state: 'pending_token', refreshAccepted: true });
      }
      if (action === 'start' || action === 'resume') {
        bridge.active = true;
        window.QR_LOGIN_ENABLED = 1;
        clearTimeout(window.qr_time);
        if (action === 'resume') {
          // An AJAX timeout may itself be paused in the background. Drop its stale request.
          for (const [request, phase] of Array.from(bridge.requests)) if (phase === 'status') request.abort();
          if (bridge.error && bridge.error.phase === 'status') bridge.error = null;
          bridge.lastStatusCheck = 0;
        }
      }
      if (bridge.submitted) return reply({ state: 'submitting' });
      if (action === 'submit' && !bridge.active) return reply({ state: 'submit_error' });
      if (bridge.status === '1') {
        if (action !== 'submit') return reply({ state: 'confirmed' });
        let target;
        try { target = new URL(form.action, location.href); } catch (_) { return reply({ state: 'submit_error' }); }
        if (target.origin !== location.origin || target.pathname !== '/authserver/login' ||
          form.method.toLowerCase() !== 'post' || !form.querySelector('input[name=uuid]')?.value) {
          return reply({ state: 'submit_error' });
        }
        bridge.submitted = true;
        clearTimeout(window.qr_time);
        // Preserve all original hidden fields, action, CAS service and WebView cookies.
        try { HTMLFormElement.prototype.submit.call(form); }
        catch (_) { bridge.submitted = false; return reply({ state: 'submit_error' }); }
        return reply({ state: 'submitting' });
      }
      if (bridge.status === '3' || shown('#qr_invalid')) return reply({ state: 'expired' });
      if (bridge.active && !bridge.pending && !bridge.error &&
        !Array.from(bridge.requests.values()).includes('status') && Date.now() - bridge.lastStatusCheck >= 1000) {
        bridge.lastStatusCheck = Date.now();
        window.isUsed();
      }
      if (bridge.error) return reply({ state: 'request_error', error: bridge.error });
      if (bridge.pending) return reply({ state: 'pending_token' });
      if (bridge.status === '2' || shown('#qr_success')) return reply({ state: 'scanned' });
      if (!image.getAttribute('src')) return reply({ state: 'missing_image' });
      const source = new URL(image.src, location.href);
      if (source.origin !== location.origin || source.pathname !== '/authserver/qrCode/getCode') {
        return reply({ state: 'wrong_image' });
      }
      if (!image.complete) return reply({ state: 'pending_image' });
      if (!image.naturalWidth || !image.naturalHeight) return reply({ state: 'image_error' });
      if (source.href === displayedSource) return reply({ state: 'waiting' });
      try {
        const canvas = document.createElement('canvas');
        canvas.width = image.naturalWidth;
        canvas.height = image.naturalHeight;
        canvas.getContext('2d').drawImage(image, 0, 0);
        return reply({ state: 'waiting', imageUrl: source.href, dataUrl: canvas.toDataURL('image/png') });
      } catch (_) { return reply({ state: 'read_error' }); }
    }
  """.trimIndent()
}
