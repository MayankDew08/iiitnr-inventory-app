const { defineConfig } = require('eslint/config');
const js = require('@eslint/js');
const globals = require('globals');
const tsEslint = require('typescript-eslint');
const security = require('eslint-plugin-security');
const prettier = require('eslint-config-prettier');

module.exports = defineConfig([
  { ignores: ['dist/**', 'coverage/**', '**/*.d.ts', 'drizzle/**'] },

  js.configs.recommended,
  ...tsEslint.configs.recommended,

  {
    files: ['**/*.ts'],
    languageOptions: {
      globals: { ...globals.node },
      parserOptions: { projectService: true, tsconfigRootDir: __dirname },
    },
    plugins: { security },
    rules: {
      'security/detect-object-injection': 'off',
      'security/detect-non-literal-fs-filename': 'off',
      '@typescript-eslint/no-floating-promises': 'warn',
      '@typescript-eslint/no-misused-promises': 'warn',
      '@typescript-eslint/require-await': 'warn',
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
  },

  prettier,
]);
