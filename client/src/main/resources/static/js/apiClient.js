// Small pure API client: exposes methods that perform HTTP calls and return Promises.
(function (window) {
    'use strict';

    const ApiClient = {
        // POST to aggregator; payload is a plain object { patientId, delays, timeoutMs }
        callAggregator: function (baseUrl, payload) {
            const url = baseUrl.replace(/\/$/, '') + '/aggregate/journals';
            return fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).then(async resp => {
                if (!resp.ok) {
                    const bodyText = (await resp.text()).trim();
                    const detail = bodyText ? ': ' + bodyText : '';
                    throw new Error('Aggregator returned ' + resp.status + detail);
                }
                return resp.json();
            });
        }
    };

    window.ApiClient = ApiClient;
})(window);
