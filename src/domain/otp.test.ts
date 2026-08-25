import { describe, expect, it } from 'vitest'

import { formatOtpQuery, postprocessOtp } from './otp'

describe('OTP contract', () => {
  it('formats the measured query exactly', () => {
    expect(formatOtpQuery('Needle Bank', 'Your code is A7B9Q')).toBe(
      'Sender: Needle Bank\nMessage: Your code is A7B9Q',
    )
  })

  it('accepts one grounded extract_otp tool call', () => {
    const query = formatOtpQuery('Needle Bank', 'Your code is A7B9Q')
    expect(
      postprocessOtp(query, [
        { name: 'extract_otp', arguments: { code: 'A7B9Q', source: 'Needle Bank' } },
      ]),
    ).toEqual({ code: 'A7B9Q', source: 'Needle Bank' })
  })

  it.each([
    ['Use promo code SAVE20', 'SAVE20'],
    ['Tracking reference ABC123', 'ABC123'],
    ['Tracking number 432109', '432109'],
  ])('rejects non-authentication contexts', (message, code) => {
    expect(postprocessOtp(`Message: ${message}`, [
      { name: 'extract_otp', arguments: { code } },
    ])).toBeNull()
  })

  it('rejects multiple calls and ungrounded values', () => {
    const query = 'Message: Your code is 123456'
    expect(postprocessOtp(query, [
      { name: 'extract_otp', arguments: { code: '123456' } },
      { name: 'extract_otp', arguments: { code: '123456' } },
    ])).toBeNull()
    expect(postprocessOtp(query, [
      { name: 'extract_otp', arguments: { code: '654321' } },
    ])).toBeNull()
  })
})
