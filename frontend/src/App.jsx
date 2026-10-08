import Dashboard from './components/Dashboard'

export default function App() {
  return (
    <main className="min-h-screen bg-slate-950 p-8 text-slate-100">
      <h1 className="text-3xl font-bold">Load Balancer Sandbox</h1>
      <div className="mt-8">
        <Dashboard />
      </div>
    </main>
  )
}
