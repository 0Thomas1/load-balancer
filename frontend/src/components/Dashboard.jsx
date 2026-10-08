import { useState, useEffect, useRef } from 'react'
import { useMetrics } from '../hooks/useMetrics'
import { TrafficChart } from './TrafficChart'
import { BackendStatsGrid } from './BackendStatsGrid'
import { RequestFlowViz } from './RequestFlowViz'
import { ScenarioPanel } from './ScenarioPanel'
import { AlgorithmSelector } from './AlgorithmSelector'
import { BackendConfigPanel } from './BackendConfigPanel'
import { Toast } from './Toast'

const MAX_HISTORY = 60

export default function Dashboard() {
  const { snapshot, events, connectionState } = useMetrics()
  const [history, setHistory] = useState([])
  const [error, setError] = useState(null)
  const prevSnapshot = useRef(null)

  useEffect(() => {
    if (!snapshot) return
    if (!prevSnapshot.current || snapshot.timestamp !== prevSnapshot.current.timestamp) {
      setHistory((prev) => {
        const next = [...prev, { time: new Date(snapshot.timestamp).toLocaleTimeString(), rps: snapshot.requestsPerSecond }]
        return next.slice(-MAX_HISTORY)
      })
      prevSnapshot.current = snapshot
    }
  }, [snapshot])

  const backends = snapshot?.backends || []
  const healthyBackends = backends.filter((b) => b.status === 'UP')
  const allDown = backends.length > 0 && healthyBackends.length === 0
  const downCount = backends.filter((b) => b.status === 'DOWN').length

  return (
    <div className="space-y-6">
      {/* Connection status */}
      {connectionState === 'reconnecting' && (
        <div className="rounded border border-yellow-600 bg-yellow-900/20 px-4 py-2 text-sm text-yellow-400">
          Reconnecting…
        </div>
      )}

      {/* Metrics panel */}
      <section id="metrics-panel" className="rounded border border-slate-800 p-4">
        <h2 className="mb-3 text-lg font-semibold">Metrics</h2>
        {snapshot ? (
          <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
            <div>
              <div className="text-slate-400">Algorithm</div>
              <div className="font-medium">{snapshot.activeAlgorithm}</div>
            </div>
            <div>
              <div className="text-slate-400">Requests/sec</div>
              <div className="font-medium">{snapshot.requestsPerSecond}</div>
            </div>
            <div>
              <div className="text-slate-400">Total requests</div>
              <div className="font-medium">{snapshot.totalRequests}</div>
            </div>
            <div>
              <div className="text-slate-400">Errors</div>
              <div className="font-medium">{snapshot.errorCount}</div>
            </div>
          </div>
        ) : (
          <p className="text-sm text-slate-500">Waiting for snapshot…</p>
        )}
      </section>

      {/* Traffic chart */}
      <section className="rounded border border-slate-800 p-4">
        <h2 className="mb-3 text-lg font-semibold">Traffic</h2>
        <TrafficChart history={history} />
      </section>

      {/* Backends */}
      <section id="backends-panel" className="rounded border border-slate-800 p-4">
        <h2 className="mb-3 text-lg font-semibold">Backends</h2>
        {allDown ? (
          <p className="text-sm text-red-400">No healthy backends</p>
        ) : (
          <BackendStatsGrid backends={backends} />
        )}
      </section>

      {/* Request flow */}
      <section className="rounded border border-slate-800 p-4">
        <h2 className="mb-3 text-lg font-semibold">Request Flow</h2>
        <RequestFlowViz events={events} backends={healthyBackends} />
      </section>

      {/* Controls */}
      <section id="controls-panel" className="rounded border border-slate-800 p-4">
        <h2 className="mb-3 text-lg font-semibold">Controls</h2>
        <div className="grid gap-6 md:grid-cols-3">
          <div>
            <AlgorithmSelector onError={setError} />
          </div>
          <div>
            <ScenarioPanel onError={setError} downCount={downCount} />
          </div>
          <div>
            <BackendConfigPanel onError={setError} />
          </div>
        </div>
      </section>

      <Toast message={error} onClose={() => setError(null)} />
    </div>
  )
}
