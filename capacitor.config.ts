import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'de.bubblebuffer.needlebub',
  appName: 'NeedleBub',
  webDir: 'dist/android',
  android: {
    allowMixedContent: false,
    backgroundColor: '#F3EFE7',
  },
  server: {
    androidScheme: 'https',
  },
}

export default config
