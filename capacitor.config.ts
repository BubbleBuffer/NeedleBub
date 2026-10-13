import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'de.x0bubbuff.needlebub',
  appName: 'NeedleBub',
  webDir: 'dist/android',
  android: {
    allowMixedContent: false,
    backgroundColor: '#F2EBDD',
  },
  server: {
    androidScheme: 'https',
  },
}

export default config
