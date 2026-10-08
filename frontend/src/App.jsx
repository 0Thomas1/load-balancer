import { useMetrics } from './hooks/useMetrics'

export default function App() {
  const { connectionState } = useMetrics()

  return (
    <main className="min-h-screen bg-slate-950 p-8 text-slate-100">
      <h1 className="text-3xl font-bold">Load Balancer Sandbox</h1>

      <div className="mt-8 grid gap-4 md:grid-cols-2">
        <section id="metrics-panel" className="rounded border border-slate-800 p-4">
          <h2 className="text-lg font-semibold">Metrics</h2>
        </section>
        <section id="backends-panel" className="rounded border border-slate-800 p-4">
          <h2 className="text-lg font-semibold">Backends</h2>
        </section>
        <section id="scenarios-panel" className="rounded border border-slate-800 p-4">
          <h2 className="text-lg font-semibold">Scenarios</h2>
        </section>
        <section id="traffic-panel" className="rounded border border-slate-800 p-4">
          <h2 className="text-lg font-semibold">Traffic</h2>
        </section>
      </div>

      <p className="mt-8 text-sm text-slate-500">
        {connectionState === 'connected' ? 'Connected' : 'Disconnected'}
      </p>
    </main>
  )
}
