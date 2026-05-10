// UiController: wires DOM, uses ApiClient and SseManager. Keeps side-effects centralized.
(function (window) {
    'use strict';

    const DEFAULT_CLIENT_INBOX_URL = 'http://client:8082/inbox/callback';

    const $ = id => document.getElementById(id);
    const setText = (id, text) => { const el = $(id); if (el) el.textContent = (text == null ? '' : String(text)); };

    const UiController = (function () {
        let sse = null;
        let notes = [];
        let baseUrl = '';
        let inboxPollTimer = null;
        let inboxProcessedCount = 0;
        let inboxRespondents = 0;
        let inboxErrors = 0;

        function renderNotes() {
            const container = $('notes');
            container.innerHTML = '';
            if (!notes.length) return container.innerHTML = '<p style="color:#666">No notes received yet...</p>';
            notes.forEach(n => {
                const div = document.createElement('div');
                div.className = 'note';
                const date = new Date(n.date).toLocaleString();
                div.innerHTML = `
                    <div class="note-header"><div><span class="note-source">${n.source}</span>
                    <span class="note-meta">Date: ${date}</span></div></div>
                    <div class="note-meta"><strong>Patient:</strong> ${n.patientId} | <strong>Doctor:</strong> ${n.doctorId} | <strong>Caregiver:</strong> ${n.caregiverId}</div>
                    <div class="note-text">${n.note}</div>`;
                container.appendChild(div);
            });
        }

        function updateProgress(count) {
            const p = $('progressBar');
            if (!p) return;
            const respondents = Number(count) || 0;
            // default to 3 expected resources
            const percent = Math.min(100, Math.round((respondents / 3) * 100));
            p.style.width = percent + '%';
        }

        function appendRaw(text) {
            const raw = $('rawEvents');
            const now = new Date().toISOString();
            raw.textContent = `${now} - ${text}\n` + raw.textContent;
        }

        function clearUIForCall() {
            stopInboxPolling();
            notes = [];
            $('notes').innerHTML = '';
            const result = $('result');
            if (result) result.classList.add('hidden');
            setText('statusBadge', '');
            setText('respondents', '0');
            setText('errors', '0');
            setText('deliveryMode', '-');
            setText('resolvedInboxUrl', '-');
            updateProgress(0);
            setText('correlationId', '-');
            const callButton = $('callButton'); if (callButton) callButton.disabled = true;
            const raw = $('rawEvents'); if (raw) raw.textContent = '';
        }

        function stopInboxPolling() {
            if (inboxPollTimer) {
                window.clearInterval(inboxPollTimer);
                inboxPollTimer = null;
            }
            inboxProcessedCount = 0;
            inboxRespondents = 0;
            inboxErrors = 0;
        }

        function handleMainEvent(payload) {
            if (payload && payload.__error) {
                setText('statusBadge', 'Completed');
                const sb = $('statusBadge'); if (sb) sb.className = 'status completed';
                const cb = $('callButton'); if (cb) cb.disabled = false;
                sse.close();
                return;
            }

            if (!payload) return;
            if (payload.status === 'COMPLETE') {
                setText('statusBadge', 'Completed');
                const sb = $('statusBadge'); if (sb) sb.className = 'status completed';
                if (payload.respondents != null) { setText('respondents', payload.respondents); updateProgress(payload.respondents); }
                if (payload.errors != null) { setText('errors', payload.errors); }
                const cb = $('callButton'); if (cb) cb.disabled = false;
                sse.close();
                return;
            }

            if (Array.isArray(payload.notes) && payload.notes.length) {
                payload.notes.forEach(n => { n.source = payload.source; notes.push(n); });
                notes.sort((a, b) => new Date(b.date) - new Date(a.date));
                renderNotes();
            }
        }

        function handleViewerEvent(evt) {
            if (!evt) return;
            if (evt.type === 'open') {
                setText('viewerConnectionState', 'Open');
                appendRaw('Connection opened for ' + (sse.correlationId || '-'));
                return;
            }
            if (evt.type === 'error') {
                setText('viewerConnectionState', 'Closed');
                appendRaw('ERROR or closed');
                return;
            }
            if (evt.type === 'message') {
                appendRaw('MESSAGE: ' + evt.raw);
                try {
                    const parsed = JSON.parse(evt.raw);
                    if (parsed.status === 'COMPLETE') appendRaw('Received COMPLETE');
                } catch (e) { /* ignore */ }
            }
        }

        async function callAggregator() {
            const patientIdEl = $('patientId');
            const delayEls = [$('delay1'), $('delay2'), $('delay3')];
            const timeoutEl = $('timeout');
            const callButton = $('callButton');
            if (!patientIdEl || delayEls.some(el => !el) || !callButton) return;

            const patientId = patientIdEl.value;
            const delayValues = delayEls.map(el => {
                const raw = el.value;
                if (raw === '') return 0;
                const parsed = parseInt(raw, 10);
                return isNaN(parsed) ? 0 : parsed;
            });
            if (!patientId) return alert('Please fill in all fields');
            const delays = delayValues.join(',');

            // Parse timeout value (optional)
            let timeoutMs = null;
            if (timeoutEl && timeoutEl.value) {
                timeoutMs = parseInt(timeoutEl.value, 10);
                if (isNaN(timeoutMs) || timeoutMs <= 0) {
                    return alert('Timeout must be a positive number');
                }
            }

            clearUIForCall();

            const strategyEl = $('strategy');
            const strategy = strategyEl ? strategyEl.value : 'SSE';
            const inboxModeEl = $('inboxMode');
            const inboxMode = inboxModeEl ? inboxModeEl.value : 'AGGREGATOR';
            const inboxUrlEl = $('inboxUrl');
            const inboxUrl = inboxUrlEl ? inboxUrlEl.value.trim() : '';

            try {
                const payload = { patientId, delays, strategy };
                if (timeoutMs !== null) {
                    payload.timeoutMs = timeoutMs;
                }
                if (strategy === 'DIRECT_TO_INBOX') {
                    payload.inboxMode = inboxMode;
                    if (inboxMode === 'CLIENT') {
                        if (!inboxUrl) {
                            alert('Client inbox URL is required for DIRECT_TO_INBOX + CLIENT mode');
                            const cb = $('callButton'); if (cb) cb.disabled = false;
                            return;
                        }
                        payload.inboxUrl = inboxUrl;
                    }
                }
                
                const data = await window.ApiClient.callAggregator(baseUrl, payload);
                
                if (strategy === 'WAIT_FOR_EVERYONE') {
                    // Synchronous response: display all results immediately
                    handleSynchronousResponse(data);
                } else if (strategy === 'DIRECT_TO_INBOX') {
                    handleDirectInboxResponse(data);
                } else {
                    // SSE response: open stream for real-time updates
                    handleSseResponse(data);
                }
            } catch (err) {
                alert('Error: ' + (err && err.message ? err.message : String(err)));
                const cb = $('callButton'); if (cb) cb.disabled = false;
            }
        }
        
        function handleSynchronousResponse(data) {
            setText('respondents', data.respondents || 0);
            setText('errors', data.errors || 0);
            setText('deliveryMode', 'WAIT_FOR_EVERYONE');
            updateProgress(data.respondents || 0);
            setText('correlationId', 'N/A (synchronous)');
            const result = $('result'); if (result) result.classList.remove('hidden');
            setText('statusBadge', 'Completed');
            const sb = $('statusBadge'); if (sb) sb.className = 'status completed';
            
            // Add notes if present
            if (Array.isArray(data.notes) && data.notes.length) {
                notes = data.notes;
                notes.sort((a, b) => new Date(b.date) - new Date(a.date));
                renderNotes();
            }
            
            const cb = $('callButton'); if (cb) cb.disabled = false;
            appendRaw('Synchronous response payload: ' + JSON.stringify(data));
        }
        
        function handleSseResponse(data) {
            setText('respondents', data.respondents);
            updateProgress(data.respondents);
            setText('correlationId', data.correlationId);
            setText('deliveryMode', 'SSE');
            const result = $('result'); if (result) result.classList.remove('hidden');
            setText('statusBadge', 'Listening for events...');
            const sb = $('statusBadge'); if (sb) sb.className = 'status listening';

            // Attach SSE (server-sent events) for delivery of callbacks and viewer events
            sse.attachMain(data.correlationId, handleMainEvent);
            sse.attachViewer(data.correlationId, handleViewerEvent);
        }

        function handleDirectInboxResponse(data) {
            setText('respondents', '0');
            setText('errors', '0');
            updateProgress(0);
            setText('correlationId', data.correlationId || '-');
            setText('deliveryMode', data.deliveryMode || 'DIRECT_TO_INBOX');
            setText('resolvedInboxUrl', data.inboxUrl || '-');
            const result = $('result'); if (result) result.classList.remove('hidden');
            setText('statusBadge', 'Polling inbox...');
            const sb = $('statusBadge'); if (sb) sb.className = 'status listening';
            appendRaw('DIRECT_TO_INBOX response payload: ' + JSON.stringify(data));
            startInboxPolling(data);
        }

        function normalizeInboxReadUrl(rawUrl) {
            try {
                const url = new URL(rawUrl);
                if (url.hostname === 'aggregator' || url.hostname === 'client') {
                    url.hostname = 'localhost';
                }
                return url.toString();
            } catch (e) {
                return rawUrl;
            }
        }

        function deriveInboxReadUrl(data) {
            if (data.inboxReadUrl) {
                return normalizeInboxReadUrl(data.inboxReadUrl);
            }
            if (!data.inboxUrl) {
                return null;
            }
            let readUrl = data.inboxUrl;
            if (readUrl.endsWith('/callback')) {
                readUrl = readUrl.substring(0, readUrl.length - '/callback'.length) + '/messages';
            }
            const delimiter = readUrl.includes('?') ? '&' : '?';
            return normalizeInboxReadUrl(readUrl + delimiter + 'correlationId=' + encodeURIComponent(data.correlationId || ''));
        }

        function processInboxPayload(payload) {
            if (!payload) return;

            const status = (payload.status || '').toUpperCase();
            if (status === 'OK' || status === 'SUCCESS') {
                inboxRespondents += 1;
            } else if (status === 'TIMEOUT' || status === 'CONNECTION_CLOSED' || status === 'ERROR') {
                inboxErrors += 1;
            }

            if (Array.isArray(payload.notes) && payload.notes.length) {
                payload.notes.forEach(n => {
                    n.source = payload.source;
                    notes.push(n);
                });
                notes.sort((a, b) => new Date(b.date) - new Date(a.date));
                renderNotes();
            }

            setText('respondents', inboxRespondents);
            setText('errors', inboxErrors);
            updateProgress(inboxRespondents);
            appendRaw('INBOX callback: ' + JSON.stringify(payload));
        }

        function startInboxPolling(data) {
            stopInboxPolling();

            const expectedRespondents = Number(data.respondents) || 0;
            const readUrl = deriveInboxReadUrl(data);
            if (!readUrl) {
                setText('statusBadge', 'Inbox read URL missing');
                const sb = $('statusBadge'); if (sb) sb.className = 'status failed';
                const cb = $('callButton'); if (cb) cb.disabled = false;
                return;
            }

            const poll = async () => {
                try {
                    const resp = await fetch(readUrl, { headers: { 'Accept': 'application/json' } });
                    if (!resp.ok) {
                        throw new Error('Inbox read returned ' + resp.status);
                    }
                    const payloads = await resp.json();
                    if (Array.isArray(payloads) && payloads.length > inboxProcessedCount) {
                        for (let i = inboxProcessedCount; i < payloads.length; i++) {
                            processInboxPayload(payloads[i]);
                        }
                        inboxProcessedCount = payloads.length;
                    }

                    if (expectedRespondents > 0 && inboxRespondents >= expectedRespondents) {
                        setText('statusBadge', 'Completed');
                        const sb = $('statusBadge'); if (sb) sb.className = 'status completed';
                        const cb = $('callButton'); if (cb) cb.disabled = false;
                        stopInboxPolling();
                    }
                } catch (err) {
                    appendRaw('INBOX poll error: ' + (err && err.message ? err.message : String(err)));
                }
            };

            poll();
            inboxPollTimer = window.setInterval(poll, 1000);
        }

        function onStrategyChanged() {
            const strategyEl = $('strategy');
            const strategy = strategyEl ? strategyEl.value : 'SSE';
            const config = $('directInboxConfig');
            if (config) {
                config.style.display = strategy === 'DIRECT_TO_INBOX' ? 'block' : 'none';
            }
            ensureClientInboxDefault();
        }

        function ensureClientInboxDefault() {
            const inboxModeEl = $('inboxMode');
            const inboxUrlEl = $('inboxUrl');
            if (!inboxModeEl || !inboxUrlEl) return;

            if (inboxModeEl.value === 'CLIENT' && !inboxUrlEl.value.trim()) {
                inboxUrlEl.value = DEFAULT_CLIENT_INBOX_URL;
            }
        }

        function init(base) {
            baseUrl = base || '';
            sse = new window.SseManager(baseUrl);
            const callButton = $('callButton'); if (callButton) callButton.addEventListener('click', callAggregator);
            const strategyEl = $('strategy'); if (strategyEl) strategyEl.addEventListener('change', onStrategyChanged);
            const inboxModeEl = $('inboxMode'); if (inboxModeEl) inboxModeEl.addEventListener('change', ensureClientInboxDefault);
            onStrategyChanged();
            window.addEventListener('beforeunload', () => {
                if (sse) sse.close();
                stopInboxPolling();
            });
        }

        return { init };
    })();

    window.UiController = UiController;
})(window);
