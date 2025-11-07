/* Simple WebRTC helper that uses the aggregator's SSE-based signaling endpoints.
 * - createSession() -> POST /aggregate/webrtc/create
 * - subscribeSignaling(sessionId, token, onMessage) -> GET /aggregate/webrtc/{sessionId}/stream?token=...
 * - sendSignal(sessionId, token, payload) -> POST /aggregate/webrtc/{sessionId}/signal
 * - createPeerConnection(...) -> creates RTCPeerConnection and optionally creates datachannel
 *
 * This is a lightweight scaffold for development and manual testing.
 */

const webrtcApi = (function () {
  const base = '/aggregate/webrtc';

  async function createSession() {
    const res = await fetch(base + '/create', { method: 'POST' });
    return await res.json(); // { sessionId, token, iceServers, ttlSeconds }
  }

  function subscribeSignaling(sessionId, token, onMessage) {
    const url = `${base}/${encodeURIComponent(sessionId)}/stream?token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);
    es.onmessage = (ev) => {
      if (ev.data) onMessage(ev.data);
    };
    es.addEventListener('ping', () => { /* noop keepalive */ });
    es.onerror = (e) => console.warn('SSE error', e);
    return es;
  }

  async function sendSignal(sessionId, token, payload) {
    const body = { token, payload };
    const res = await fetch(`${base}/${encodeURIComponent(sessionId)}/signal`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return res.ok;
  }

  function defaultIceServers() {
    return { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
  }

  async function createPeerConnection({ sessionId, token, isInitiator = false, onDataChannel, onIceCandidate, iceServers }) {
    const cfg = iceServers ? { iceServers: iceServers } : defaultIceServers();
    const pc = new RTCPeerConnection(cfg);

    pc.onicecandidate = (ev) => {
      if (ev.candidate) {
        sendSignal(sessionId, token, JSON.stringify({ type: 'ice', candidate: ev.candidate }));
        if (onIceCandidate) onIceCandidate(ev.candidate);
      }
    };

    if (isInitiator) {
      const dc = pc.createDataChannel('data');
      dc.onopen = () => console.log('datachannel open');
      dc.onmessage = (m) => console.log('datachannel message', m.data);
      if (onDataChannel) onDataChannel(dc);
    } else {
      pc.ondatachannel = (evt) => {
        const dc = evt.channel;
        dc.onopen = () => console.log('datachannel open');
        dc.onmessage = (m) => console.log('datachannel message', m.data);
        if (onDataChannel) onDataChannel(dc);
      };
    }

    return pc;
  }

    return { createSession, subscribeSignaling, sendSignal, createPeerConnection };
  })();

  // Expose on window for non-module usage in the demo UI
  try { window.webrtcApi = webrtcApi; } catch (e) { /* ignore in strict module env */ }

  export default webrtcApi;
