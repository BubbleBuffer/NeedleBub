import { describe, expect, it } from 'vitest'

import { validatePackManifest } from './packManifest'

const validManifest = {
  formatVersion: 1,
  id: 'de.x0bubbuff.needlebub.otp',
  version: '1.0.0',
  name: 'OTP Extractor',
  author: 'BubbleBuffer',
  description: 'Extracts one-time authentication codes.',
  license: 'Apache-2.0',
  engine: { abi: 'needle-2.0.9-android-arm64-v1' },
  model: {
    path: 'model.cact',
    size: 23174991,
    sha256: 'a'.repeat(64),
  },
  queryTemplate: '{{input}}',
  surfaces: ['external', 'notification'],
  outputs: {
    nb_code: { type: 'string', pointer: '/code' },
    nb_source: { type: 'string', pointer: '/source', optional: true },
  },
}

describe('capability pack manifest', () => {
  it('accepts the bounded v1 contract', () => {
    expect(validatePackManifest(validManifest)).toEqual(validManifest)
  })

  it.each([
    ['non-reverse-domain id', { id: 'otp' }],
    ['non-semantic version', { version: 'one' }],
    ['multiple input slots', { queryTemplate: '{{input}} / {{input}}' }],
    ['missing input slot', { queryTemplate: 'Message only' }],
    ['unsafe model path', { model: { ...validManifest.model, path: '../model.cact' } }],
    ['unknown required field', { requiredFutureField: true }],
  ])('rejects %s', (_label, change) => {
    expect(() => validatePackManifest({ ...validManifest, ...change })).toThrow()
  })
})
