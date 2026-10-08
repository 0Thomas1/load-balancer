async function getJSON(path) {
  const res = await fetch(path)
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status}`)
  return res.json()
}

async function sendJSON(path, method, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`${method} ${path} failed: ${res.status}`)
  return res.json()
}

export const api = {
  getBackends: () => getJSON('/api/backends'),
  createBackend: (data) => sendJSON('/api/backends', 'POST', data),
  updateBackendConfig: (id, data) => sendJSON(`/api/backends/${id}/config`, 'PUT', data),
  deleteBackend: (id) => sendJSON(`/api/backends/${id}`, 'DELETE'),
  getAlgorithms: () => getJSON('/api/algorithms'),
  setAlgorithm: (name) => sendJSON('/api/algorithm', 'PUT', { name }),
  getScenarios: () => getJSON('/api/scenarios'),
  startScenario: (name, params) => sendJSON(`/api/scenarios/${name}`, 'POST', { params }),
  stopScenario: (name) => sendJSON(`/api/scenarios/${name}`, 'DELETE'),
  setTraffic: (rate, burstDurationMs) => sendJSON('/api/traffic', 'PUT', { rate, burstDurationMs }),
}
