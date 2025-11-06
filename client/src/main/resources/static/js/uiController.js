// UiController: wires DOM, uses ApiClient and SseManager. Keeps side-effects centralized.
(function (window) {
    'use strict';

    const $ = id => document.getElementById(id);
    const setText = (id, text) => { const el = $(id); if (el) el.textContent = (text == null ? '' : String(text)); };

    const UiController = (function () {
        let sse = null;
        let notes = [];
        let baseUrl = '';

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

        function appendRaw(text) {
            const raw = $('rawEvents');
            const now = new Date().toISOString();
            raw.textContent = `${now} - ${text}\n` + raw.textContent;
        }

        function clearUIForCall() {
            notes = [];
            $('notes').innerHTML = '';
            const result = $('result');
            if (result) result.classList.add('hidden');
            setText('statusBadge', '');
            setText('respondents', '0');
            setText('correlationId', '-');
            const callButton = $('callButton'); if (callButton) callButton.disabled = true;
            const raw = $('rawEvents'); if (raw) raw.textContent = '';
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
                if (payload.respondents != null) setText('respondents', payload.respondents);
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
            const delaysEl = $('delays');
            const callButton = $('callButton');
            if (!patientIdEl || !delaysEl || !callButton) return;

            const patientId = patientIdEl.value;
            const delays = delaysEl.value;
            if (!patientId || !delays) return alert('Please fill in all fields');

            clearUIForCall();

            try {
                const data = await window.ApiClient.callAggregator(baseUrl, { patientId, delays });
                setText('respondents', data.respondents);
                setText('correlationId', data.correlationId);
                const result = $('result'); if (result) result.classList.remove('hidden');
                setText('statusBadge', 'Listening for events...');
                const sb = $('statusBadge'); if (sb) sb.className = 'status listening';

                sse.attachMain(data.correlationId, handleMainEvent);
                sse.attachViewer(data.correlationId, handleViewerEvent);
            } catch (err) {
                alert('Error: ' + (err && err.message ? err.message : String(err)));
                const cb = $('callButton'); if (cb) cb.disabled = false;
            }
        }

        function init(base) {
            baseUrl = base || '';
            sse = new window.SseManager(baseUrl);
            const callButton = $('callButton'); if (callButton) callButton.addEventListener('click', callAggregator);
            window.addEventListener('beforeunload', () => { if (sse) sse.close(); });
        }

        return { init };
    })();

    window.UiController = UiController;
})(window);
