// SseManager: manages a single EventSource per correlationId and exposes attach/detach helpers.
(function (window) {
    'use strict';

    class SseManager {
        constructor(baseUrl) {
            this.baseUrl = baseUrl.replace(/\/$/, '');
            this.source = null;
            this.correlationId = null;
            this.mainHandler = null;   // function(parsed)
            this.viewerHandler = null; // function({type, raw?})
        }

        ensureSource(correlationId) {
            if (!correlationId) return;
            if (this.source && this.correlationId !== correlationId) this.close();
            if (!this.source) {
                this.correlationId = correlationId;
                const url = this.baseUrl + '/aggregate/stream?correlationId=' + encodeURIComponent(correlationId);
                this.source = new EventSource(url);
                this.source.addEventListener('message', e => this._onMessage(e));
                this.source.addEventListener('open', e => this._onOpen(e));
                this.source.addEventListener('error', e => this._onError(e));
            }
        }

        _onOpen() {
            if (this.viewerHandler) this.viewerHandler({ type: 'open' });
        }

        _onMessage(event) {
            if (!event || !event.data) return;
            if (this.viewerHandler) this.viewerHandler({ type: 'message', raw: event.data });
            let parsed = null;
            try { parsed = JSON.parse(event.data); } catch (e) { /* ignore non-JSON */ }
            if (parsed && this.mainHandler) this.mainHandler(parsed);
        }

        _onError() {
            if (this.viewerHandler) this.viewerHandler({ type: 'error' });
            if (this.mainHandler) this.mainHandler({ __error: true });
        }

        attachMain(correlationId, handler) {
            this.mainHandler = handler;
            this.ensureSource(correlationId);
        }

        attachViewer(correlationId, handler) {
            this.viewerHandler = handler;
            this.ensureSource(correlationId);
        }

        detachMain() { this.mainHandler = null; }
        detachViewer() { this.viewerHandler = null; }

        close() {
            if (this.source) {
                try { this.source.close(); } catch (e) { /* ignore */ }
            }
            this.source = null;
            this.correlationId = null;
            this.mainHandler = null;
            this.viewerHandler = null;
        }
    }

    window.SseManager = SseManager;
})(window);
