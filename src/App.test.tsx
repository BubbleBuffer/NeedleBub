import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import App from './App'

describe('NeedleBub shell', () => {
  it('exposes the six private-alpha surfaces', () => {
    render(<App />)
    for (const label of ['Start', 'Status', 'Packs', 'Sources', 'Connect', 'Settings']) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument()
    }
  })

  it('uses the source pack result routing seam', () => {
    render(<App />)
    expect(screen.getByText('Source')).toBeInTheDocument()
    expect(screen.getByText('Pack')).toBeInTheDocument()
    expect(screen.getByText('Result')).toBeInTheDocument()
  })

  it('keeps operational failures direct and recoverable', async () => {
    const user = userEvent.setup()
    render(<App />)
    await user.click(screen.getByRole('button', { name: 'Packs' }))
    expect(screen.getByRole('button', { name: 'Import .nbpack' })).toBeInTheDocument()
    expect(screen.queryByText(/result history/i)).not.toBeInTheDocument()
  })
})
