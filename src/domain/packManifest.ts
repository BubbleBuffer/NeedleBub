export const PACK_FORMAT_VERSION = 1 as const

export type PackSurface = 'external' | 'notification'
export type OutputType = 'string' | 'boolean' | 'number' | 'json'

export type PackOutput = {
  type: OutputType
  pointer: string
  optional?: boolean
}

export type PackManifest = {
  formatVersion: typeof PACK_FORMAT_VERSION
  id: string
  version: string
  name: string
  author: string
  description: string
  license: string
  engine: { abi: string }
  model: { path: 'model.cact'; size: number; sha256: string }
  queryTemplate: string
  surfaces: PackSurface[]
  outputs: Record<string, PackOutput>
}

const TOP_LEVEL_KEYS = new Set([
  'formatVersion', 'id', 'version', 'name', 'author', 'description', 'license',
  'engine', 'model', 'queryTemplate', 'surfaces', 'outputs',
])
const OUTPUT_TYPES = new Set<OutputType>(['string', 'boolean', 'number', 'json'])
const SURFACES = new Set<PackSurface>(['external', 'notification'])

function object(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be an object`)
  }
  return value as Record<string, unknown>
}

function exactKeys(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  const unknown = Object.keys(value).filter((key) => !allowed.has(key))
  if (unknown.length) throw new Error(`${label} contains unknown fields: ${unknown.join(', ')}`)
}

function nonEmpty(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string' || value.trim().length === 0) throw new Error(`${label} is required`)
}

export function validatePackManifest(value: unknown): PackManifest {
  const manifest = object(value, 'manifest')
  exactKeys(manifest, TOP_LEVEL_KEYS, 'manifest')

  if (manifest.formatVersion !== PACK_FORMAT_VERSION) throw new Error('unsupported pack format')
  if (typeof manifest.id !== 'string' || !/^[a-z][a-z0-9]*(?:\.[a-z0-9][a-z0-9-]*){2,}$/.test(manifest.id)) {
    throw new Error('id must be reverse-domain notation')
  }
  if (manifest.id.length > 200) throw new Error('id is too long')
  if (typeof manifest.version !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(manifest.version)) {
    throw new Error('version must be semantic')
  }
  if (manifest.version.length > 100) throw new Error('version is too long')
  for (const field of ['name', 'author', 'description', 'license'] as const) nonEmpty(manifest[field], field)

  const engine = object(manifest.engine, 'engine')
  exactKeys(engine, new Set(['abi']), 'engine')
  nonEmpty(engine.abi, 'engine.abi')

  const model = object(manifest.model, 'model')
  exactKeys(model, new Set(['path', 'size', 'sha256']), 'model')
  if (model.path !== 'model.cact') throw new Error('model path must be model.cact')
  if (!Number.isSafeInteger(model.size) || (model.size as number) <= 0 || (model.size as number) > 128 * 1024 * 1024) throw new Error('model size is out of range')
  if (typeof model.sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(model.sha256)) throw new Error('model digest must be SHA-256')

  nonEmpty(manifest.queryTemplate, 'queryTemplate')
  if (manifest.queryTemplate.length > 8 * 1024) throw new Error('queryTemplate is too long')
  if (manifest.queryTemplate.split('{{input}}').length - 1 !== 1) throw new Error('queryTemplate needs exactly one {{input}}')

  if (!Array.isArray(manifest.surfaces) || manifest.surfaces.length === 0 || new Set(manifest.surfaces).size !== manifest.surfaces.length ||
      manifest.surfaces.some((surface) => typeof surface !== 'string' || !SURFACES.has(surface as PackSurface))) {
    throw new Error('surfaces are invalid')
  }

  const outputs = object(manifest.outputs, 'outputs')
  if (Object.keys(outputs).length > 32) throw new Error('too many declared outputs')
  for (const [name, rawOutput] of Object.entries(outputs)) {
    if (!/^nb_[a-z][a-z0-9_]*$/.test(name)) throw new Error(`invalid output name: ${name}`)
    const output = object(rawOutput, `outputs.${name}`)
    exactKeys(output, new Set(['type', 'pointer', 'optional']), `outputs.${name}`)
    if (typeof output.type !== 'string' || !OUTPUT_TYPES.has(output.type as OutputType)) throw new Error(`invalid output type: ${name}`)
    if (typeof output.pointer !== 'string' || !/^(?:\/(?:[^~/]|~[01])*)*$/.test(output.pointer)) throw new Error(`invalid JSON Pointer: ${name}`)
    if (output.optional !== undefined && typeof output.optional !== 'boolean') throw new Error(`invalid optional flag: ${name}`)
  }

  return value as PackManifest
}
