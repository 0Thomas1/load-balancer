import { CircuitStateBadge } from './CircuitStateBadge'

const STATUS_COLORS = {
  UP: 'text-green-400',
  DOWN: 'text-red-400',
  DRAINING: 'text-yellow-400',
}

export function BackendStatsGrid({ backends }) {
  if (!backends || backends.length === 0) {
    return <p className="text-sm text-slate-500">No backends configured.</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-700 text-left text-slate-400">
            <th className="pb-2 pr-4">Name</th>
            <th className="pb-2 pr-4">Status</th>
            <th className="pb-2 pr-4">Requests</th>
            <th className="pb-2 pr-4">Latency (ms)</th>
            <th className="pb-2 pr-4">Errors</th>
            <th className="pb-2">Circuit</th>
          </tr>
        </thead>
        <tbody>
          {backends.map((b) => (
            <tr key={b.id} className="border-b border-slate-800">
              <td className="py-2 pr-4 font-medium">{b.name}</td>
              <td className={`py-2 pr-4 font-medium ${STATUS_COLORS[b.status] || 'text-slate-400'}`}>
                {b.status}
              </td>
              <td className="py-2 pr-4">{b.totalRequests}</td>
              <td className="py-2 pr-4">{b.avgLatency.toFixed(1)}</td>
              <td className="py-2 pr-4">{b.errorCount}</td>
              <td className="py-2">
                <CircuitStateBadge state={b.circuitState} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
