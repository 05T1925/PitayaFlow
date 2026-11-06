const safeName = value => typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value) ? value : 'Error'
const safeCode = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : undefined

/** Cross-IPC diagnostics retain bounded types, never arbitrary exception text. */
export function describeError(error) {
  const seen = new WeakSet()
  const walk = (value, depth) => {
    if (value === null || typeof value !== 'object' || seen.has(value) || depth >= 3) return undefined
    seen.add(value)
    const name = safeName(value.name)
    const code = safeCode(value.code)
    const original = String(value.message ?? '')
    const exit = /exit code:\s*(-?\d{1,6})\b/.exec(original)
    const classification = exit ? 'runtime_exit' : code ?? (name === 'TransportClosedError' ? 'transport_closed' : 'runtime_error')
    const cause = walk(value.cause, depth + 1)
    return { name, classification, ...(code ? { code } : {}),
      ...(exit ? { message: `exit code: ${exit[1]}` } : {}),
      ...(original === 'synthetic close refusal' ? { message: 'synthetic close refusal' } : {}),
      ...(cause ? { cause } : {}) }
  }
  return walk(error, 0) ?? { name: 'Error', classification: 'runtime_error' }
}
