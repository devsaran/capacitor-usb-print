export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/plugin.js',
      format: 'iife',
      name: 'capacitorUsbPrint',
      globals: { '@capacitor/core': 'capacitorExports' },
      inlineDynamicImports: true,
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      inlineDynamicImports: true,
    },
  ],
  external: ['@capacitor/core'],
};
