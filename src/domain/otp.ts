export type NeedleToolCall = {
  name: string
  arguments: unknown
}

export type OtpResult = {
  code: string
  source?: string
}

const CODE_PATTERN = /^[A-Za-z0-9]{4,8}$/
const CANDIDATE_PATTERN = /(?:^|[^A-Za-z0-9])([A-Za-z0-9]{4,8})(?=$|[^A-Za-z0-9])/g
const REJECTED_CONTEXTS = [
  /\bpromo(?:tional)?\s+code\b/i,
  /\btracking\s+(?:reference|number|code)\b/i,
]

export function formatOtpQuery(sender: string, message: string): string {
  return sender ? `Sender: ${sender}\nMessage: ${message}` : `Message: ${message}`
}

export function hasPlausibleCandidate(text: string): boolean {
  if (text.trim().length === 0) return false
  for (const match of text.matchAll(CANDIDATE_PATTERN)) {
    if (/\d/.test(match[1])) return true
  }
  return false
}

export function postprocessOtp(query: string, calls: NeedleToolCall[]): OtpResult | null {
  if (calls.length !== 1 || calls[0].name !== 'extract_otp') return null
  if (REJECTED_CONTEXTS.some((pattern) => pattern.test(query))) return null

  const args = calls[0].arguments
  if (!args || typeof args !== 'object' || Array.isArray(args)) return null

  const { code, source } = args as Record<string, unknown>
  if (typeof code !== 'string' || !CODE_PATTERN.test(code) || !query.includes(code)) return null

  const result: OtpResult = { code }
  if (typeof source === 'string' && source.length > 0 && query.includes(source)) {
    result.source = source
  }
  return result
}
