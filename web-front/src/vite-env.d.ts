/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SIMULATOR_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
