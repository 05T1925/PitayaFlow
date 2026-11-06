import { readFileSync } from 'node:fs'
const protocol = JSON.parse(readFileSync(new URL('./s1-agent-protocol.json', import.meta.url), 'utf8'))
const valid = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.entries(fields).every(([key, type]) => {
    const item = value[key]
    if (type === 'string') return typeof item === 'string' && item.trim().length > 0
    if (type === 'integer') return Number.isSafeInteger(item) && item >= 0
    if (type === 'array') return Array.isArray(item)
    return item && typeof item === 'object' && !Array.isArray(item)
  })
export function validateProtocol(kind, value) {
  const schema = protocol[kind]
  if (!schema || !valid(value, schema.required)) return false
  return !schema.citation_required || valid(value.citation, schema.citation_required)
}
export const protocolVersion = protocol.version
