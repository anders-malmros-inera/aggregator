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

            const strategyEl = $('strategy');
            const strategy = strategyEl ? strategyEl.value : 'SSE';

            try {
                const data = await window.ApiClient.callAggregator(baseUrl, { patientId, delays, strategy });
                setText('respondents', data.respondents);
                setText('correlationId', data.correlationId);
                const result = $('result'); if (result) result.classList.remove('hidden');
                setText('statusBadge', 'Listening for events...');
                const sb = $('statusBadge'); if (sb) sb.className = 'status listening';

                // Decide how to connect based on the strategy returned (or requested)
                const chosen = data.strategy || strategy || 'SSE';
                if (chosen === 'SSE') {
                    sse.attachMain(data.correlationId, handleMainEvent);
                    sse.attachViewer(data.correlationId, handleViewerEvent);
                } else if (chosen === 'WEBRTC') {
                    // WebRTC flow: create a signaling session, subscribe to signals, create RTCPeerConnection and datachannel
                    setText('statusBadge', 'Using WebRTC - connecting...');
                    const sb2 = $('statusBadge'); if (sb2) sb2.className = 'status listening';
                    appendRaw('Requested WEBRTC for ' + data.correlationId + '. Creating signaling session...');

                    try {
                        // create a signaling session on the aggregator
                        const sess = await window.webrtcApi.createSession();
                        appendRaw('Signaling session: ' + sess.sessionId + ' (ttl ' + sess.ttlSeconds + 's)');

                        // subscribe to signaling events
                        const es = window.webrtcApi.subscribeSignaling(sess.sessionId, sess.token, async (raw) => {
                            appendRaw('Signal: ' + raw);
                            let msg = null;
                            try { msg = JSON.parse(raw); } catch (e) { return; }
                            if (!pc) return;
                            if (msg.type === 'answer') {
                                appendRaw('Received answer');
                                await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
                            } else if (msg.type === 'ice') {
                                try { await pc.addIceCandidate(msg.candidate); } catch (e) { console.warn('addIce failed', e); }
                            }
                        });

                        // create peer connection as initiator and open datachannel
                        let pc = await window.webrtcApi.createPeerConnection({ sessionId: sess.sessionId, token: sess.token, isInitiator: true, onDataChannel: (dc) => {
                            appendRaw('DataChannel opened');
                            dc.onmessage = (m) => {
                                appendRaw('DataChannel message: ' + m.data);
                                try {
                                    const parsed = JSON.parse(m.data);
                                    handleMainEvent(parsed);
                                } catch (e) { /* ignore */ }
                            };
                        }, iceServers: sess.iceServers });

                        // create offer and send via signaling
                        const offer = await pc.createOffer();
                        await pc.setLocalDescription(offer);
                        await window.webrtcApi.sendSignal(sess.sessionId, sess.token, JSON.stringify({ type: 'offer', sdp: offer.sdp }));
                        appendRaw('Sent offer');

                        // wire up ICE candidates to show in UI (viewer state)
                        pc.oniceconnectionstatechange = () => {
                            appendRaw('ICE state: ' + pc.iceConnectionState);
                            setText('viewerConnectionState', pc.iceConnectionState);
                        };

                    } catch (e) {
                        appendRaw('WebRTC error: ' + (e && e.message ? e.message : String(e)));
                        const cb = $('callButton'); if (cb) cb.disabled = false;
                    }
                } else {
                    // Fallback to SSE
                    sse.attachMain(data.correlationId, handleMainEvent);
                    sse.attachViewer(data.correlationId, handleViewerEvent);
                }
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
